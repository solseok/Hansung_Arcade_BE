package hansung.arcade.core.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 클라이언트 한 명(한 컴퓨터)과의 TCP 연결. 2P도 같은 연결을 쓴다.
 *
 * <p>스레드는 둘이다.</p>
 * <ul>
 *   <li>받는 스레드 {@code conn-N-recv}: 한 줄씩 읽어 {@link MessageDispatcher}로 넘긴다</li>
 *   <li>보내는 스레드 {@code conn-N-send}: 송신 큐에서 꺼내 순서대로 쓴다.
 *       {@link #send}는 큐에 넣기만 하므로 어느 스레드에서 불러도 줄이 섞이지 않고,
 *       방 lock 안에서 여러 연결에 보내도 느린 클라이언트 때문에 lock이 오래 잡히지 않는다</li>
 * </ul>
 *
 * <p>끊는 길은 여러 가지지만(BYE, 상대가 닫음, 15초 무응답, 오류, 서버 종료) 정리는 {@link #close}에서
 * 한 번만 하고, {@link ConnectionListener#onClose}도 한 번만 부른다.</p>
 *
 * <p>{@link #send}를 부른 스레드에서는 리스너를 부르지 않는다. 방·경기 lock을 잡은 채 보내다 큐가 넘쳐도
 * 끊김 정리(세션·방 lock)는 다른 스레드에서 돌므로 lock 순서가 엇갈리지 않는다.</p>
 */
public final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    /** 보내는 스레드에 "남은 것을 다 보냈으면 소켓을 닫아라"를 알리는 표시 */
    private static final String END_OF_STREAM = new String("END_OF_STREAM");
    /** 읽지 않는 클라이언트 때문에 메모리가 끝없이 차지 않도록 송신 큐에 두는 상한 */
    static final int MAX_QUEUED_MESSAGES = 4096;
    /** 끊기 전에 남은 메시지를 보내는 데 쓰는 최대 시간. 넘으면 강제로 닫는다 */
    private static final long FLUSH_TIMEOUT_MS = 2_000;

    private final long id;
    private final Socket socket;
    private final MessageDispatcher dispatcher;
    private final List<ConnectionListener> listeners;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(MAX_QUEUED_MESSAGES + 1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean overflowed = new AtomicBoolean();
    private final Map<Class<?>, Object> attachments = new ConcurrentHashMap<>();

    Connection(long id, Socket socket, MessageDispatcher dispatcher, List<ConnectionListener> listeners) {
        this.id = id;
        this.socket = Objects.requireNonNull(socket);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.listeners = listeners;
        // 다른 어떤 메시지보다 먼저 나가도록 만들자마자 큐에 넣는다
        send(Message.push(Protocol.HELLO, Json.object().put("protocolVersion", Protocol.VERSION)));
    }

    /** 서버가 접속 순서대로 붙이는 번호 (로그용). 로그인 후 정해지는 playerId와는 다르다 */
    public long id() {
        return id;
    }

    public SocketAddress remoteAddress() {
        return socket.getRemoteSocketAddress();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** 보내는/받는 스레드를 띄운다. 큐 맨 앞에 HELLO가 있으므로 늘 첫 메시지가 된다. */
    void start() throws IOException {
        socket.setSoTimeout(Protocol.IDLE_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        LineReader reader = new LineReader(socket.getInputStream(), Protocol.MAX_LINE_BYTES);

        Thread.ofPlatform().name("conn-" + id + "-send").daemon().start(() -> sendLoop(out));
        Thread.ofPlatform().name("conn-" + id + "-recv").daemon().start(() -> receiveLoop(reader));
    }

    /**
     * 송신 큐에 넣는다. 바로 돌아오고, 실제로 쓰는 것은 보내는 스레드다.
     *
     * 상대가 읽지 않아 큐가 {@value #MAX_QUEUED_MESSAGES}개를 넘으면 연결을 끊는다. 끊는 것(리스너 호출 포함)은
     * 다른 스레드에서 하므로, 부른 쪽이 잡고 있는 lock 안에서 끊김 정리가 돌지 않는다.
     *
     * @return 이미 닫혔거나 큐가 넘쳐 버렸으면 false
     */
    public boolean send(Message message) {
        if (closed.get() || overflowed.get()) {
            return false;
        }
        if (outbox.size() >= MAX_QUEUED_MESSAGES || !outbox.offer(message.toJson())) {
            if (overflowed.compareAndSet(false, true)) {
                Thread.ofVirtual().name("conn-" + id + "-overflow")
                        .start(() -> close("송신 큐가 가득 참 (상대가 메시지를 읽지 않음)"));
            }
            return false;
        }
        return true;
    }

    /** 바로 끊는다. 큐에 남은 메시지는 버린다. 여러 번 불러도 되고, 정리({@link ConnectionListener#onClose})는 한 번만 한다. */
    public void close(String reason) {
        try {
            if (markClosed(reason)) {
                outbox.clear();
                outbox.offer(END_OF_STREAM);
            }
        } finally {
            // closeAfterFlush 뒤 보내기가 막혀 있어도 끊을 수 있도록 플래그와 상관없이 늘 닫는다
            closeSocket();
        }
    }

    /** 큐에 남은 메시지(방금 넣은 ERROR 등)를 다 보낸 뒤 끊는다. 시간 안에 못 보내면 강제로 닫는다. */
    void closeAfterFlush(String reason) {
        if (markClosed(reason)) {
            outbox.offer(END_OF_STREAM);
            Thread.ofVirtual().name("conn-" + id + "-close").start(() -> {
                try {
                    Thread.sleep(FLUSH_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                closeSocket();
            });
        }
    }

    private boolean markClosed(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        log.info("#{} 연결 종료: {}", id, reason);
        for (ConnectionListener listener : listeners) {
            try {
                listener.onClose(this, reason);
            } catch (RuntimeException e) {
                // 한 리스너가 실패해도 나머지 정리는 계속한다
                log.error("#{} 연결 정리 중 오류", id, e);
            }
        }
        return true;
    }

    private void receiveLoop(LineReader reader) {
        try {
            while (!closed.get()) {
                String line = reader.readLine();
                if (line == null) {
                    close("상대가 연결을 닫음");
                    return;
                }
                dispatcher.dispatch(this, line);
            }
        } catch (LineReader.LineTooLongException e) {
            send(Message.error(ErrorCode.MESSAGE_TOO_LONG.name(), ErrorCode.MESSAGE_TOO_LONG.defaultMessage(), null));
            closeAfterFlush(e.getMessage());
        } catch (SocketTimeoutException e) {
            close("%d초 동안 받은 메시지가 없음".formatted(Protocol.IDLE_TIMEOUT_MS / 1000));
        } catch (IOException e) {
            close(closed.get() ? "이미 닫힘" : "받기 실패: " + e.getMessage());
        } catch (RuntimeException | Error e) {
            log.error("#{} 받는 스레드 오류", id, e);
            close("받는 스레드 오류: " + e);
            throw e;
        }
    }

    private void sendLoop(OutputStream out) {
        try {
            while (true) {
                String line = outbox.take();
                if (line == END_OF_STREAM) {
                    out.flush();
                    drainAndShutdown();
                    return;
                }
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
                // 큐가 비었을 때만 flush해서 연달아 보내는 메시지는 한 번에 내보낸다
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            close("보내기 실패: " + e.getMessage());
        } finally {
            closeSocket();
        }
    }

    /**
     * 보내기만 먼저 닫고, 상대가 이미 보낸 나머지를 잠깐 읽어 버린다.
     * 읽지 않은 바이트가 남은 채로 닫으면 RST가 나가서, 상대가 마지막 ERROR를 읽기 전에 "connection reset"을 받을 수 있다.
     */
    private void drainAndShutdown() {
        if (socket.isClosed()) {
            return;
        }
        try {
            socket.shutdownOutput();
            socket.setSoTimeout(200);
            InputStream in = socket.getInputStream();
            byte[] discard = new byte[8192];
            long deadline = System.nanoTime() + FLUSH_TIMEOUT_MS * 1_000_000;
            while (System.nanoTime() < deadline && in.read(discard) != -1) {
                // 버린다
            }
        } catch (IOException e) {
            // 상대가 먼저 닫았거나 시간이 다 됨. 어차피 닫는다
        }
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            log.debug("#{} 소켓 닫기 실패: {}", id, e.getMessage());
        }
    }

    /**
     * 이 연결에 도메인 상태(로그인한 플레이어, 들어간 방 등)를 붙여 둔다. 타입마다 하나.
     *
     * <pre>
     * connection.attach(Session.class, new Session(players));
     * Session session = connection.attachment(Session.class);   // 없으면 null
     * </pre>
     */
    public <T> void attach(Class<T> type, T value) {
        attachments.put(type, Objects.requireNonNull(value));
    }

    /** 붙여 둔 값. 없으면 null */
    public <T> T attachment(Class<T> type) {
        return type.cast(attachments.get(type));
    }

    public <T> T detach(Class<T> type) {
        return type.cast(attachments.remove(type));
    }

    @Override
    public String toString() {
        return "#" + id + " " + remoteAddress();
    }
}

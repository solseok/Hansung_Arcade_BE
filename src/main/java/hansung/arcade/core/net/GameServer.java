package hansung.arcade.core.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ServerSocket}으로 접속을 받아 연결마다 {@link Connection}을 만든다.
 *
 * <pre>
 * GameServer server = GameServer.bind(9000, dispatcher);
 * server.addListener(lobby);   // 접속·종료 알림
 * server.start();              // accept 스레드 시작
 * ...
 * server.close();              // 모든 연결을 끊고 포트를 닫는다
 * </pre>
 */
public final class GameServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameServer.class);

    private final ServerSocket serverSocket;
    private final MessageDispatcher dispatcher;
    private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private volatile Thread acceptThread;

    private GameServer(ServerSocket serverSocket, MessageDispatcher dispatcher) {
        this.serverSocket = serverSocket;
        this.dispatcher = Objects.requireNonNull(dispatcher);
        // 접속자 목록 정리는 다른 리스너보다 먼저 등록해 둔다
        listeners.add(new ConnectionListener() {
            @Override
            public void onClose(Connection connection, String reason) {
                connections.remove(connection.id());
            }
        });
    }

    /**
     * 포트를 연다. 아직 접속은 받지 않는다 ({@link #start}).
     *
     * @param port 0이면 빈 포트를 아무거나 쓴다 (테스트용)
     * @throws IOException 포트를 이미 다른 프로그램이 쓰고 있으면 그 사실을 알리는 메시지로
     */
    public static GameServer bind(int port, MessageDispatcher dispatcher) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("포트는 0~65535 사이여야 합니다: " + port);
        }
        ServerSocket serverSocket = new ServerSocket();
        try {
            // 서버를 껐다 바로 켤 때 TIME_WAIT 때문에 bind가 실패하지 않게
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
        } catch (BindException e) {
            serverSocket.close();
            throw new BindException("포트 %d을(를) 이미 다른 프로그램이 쓰고 있습니다. network.port를 바꾸거나 그 프로그램을 끄세요."
                    .formatted(port));
        } catch (IOException e) {
            serverSocket.close();
            throw e;
        }
        return new GameServer(serverSocket, dispatcher);
    }

    /** 실제로 열린 포트 (bind에 0을 줬을 때 확인용) */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** {@link #start} 전에 등록한다 */
    public void addListener(ConnectionListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public MessageDispatcher dispatcher() {
        return dispatcher;
    }

    /** 지금 접속해 있는 연결들 */
    public Collection<Connection> connections() {
        return List.copyOf(connections.values());
    }

    /** 접속자 전체에게 보낸다 */
    public void broadcast(Message message) {
        for (Connection connection : connections.values()) {
            connection.send(message);
        }
    }

    /** accept 스레드를 띄운다. 이 스레드가 살아 있는 동안 JVM이 끝나지 않는다. */
    public synchronized void start() {
        if (acceptThread != null) {
            throw new IllegalStateException("이미 시작했습니다.");
        }
        acceptThread = Thread.ofPlatform().name("accept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    log.error("접속을 받지 못했습니다.", e);
                    // 파일 디스크립터 부족처럼 계속 실패하는 경우 CPU를 태우지 않게 잠깐 쉰다
                    pause(100);
                }
                continue;
            }
            open(socket);
        }
    }

    private void open(Socket socket) {
        Connection connection = new Connection(nextId.getAndIncrement(), socket, dispatcher, listeners);
        connections.put(connection.id(), connection);
        if (serverSocket.isClosed()) {
            // accept 직후 close()가 끝나 버린 경우. 등록만 되고 남지 않게 바로 끊는다
            connection.close("서버 종료");
            return;
        }
        log.info("{} 접속 (접속자 {}명)", connection, connections.size());
        // 받기를 시작하기 전에 알려서, 리스너가 준비를 마친 뒤에 첫 메시지가 처리되게 한다
        for (ConnectionListener listener : listeners) {
            try {
                listener.onOpen(connection);
            } catch (RuntimeException e) {
                log.error("{} 접속 처리 중 오류", connection, e);
            }
        }
        try {
            connection.start();
        } catch (IOException | RuntimeException e) {
            log.warn("{} 연결 준비 실패: {}", connection, e.toString());
            connection.close("연결 준비 실패");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 새 접속을 막고 모든 연결을 끊는다. 여러 번 불러도 된다. */
    @Override
    public void close() {
        if (serverSocket.isClosed()) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.warn("서버 소켓 닫기 실패: {}", e.getMessage());
        }
        for (Connection connection : connections.values()) {
            connection.close("서버 종료");
        }
        log.info("서버를 닫았습니다.");
    }
}

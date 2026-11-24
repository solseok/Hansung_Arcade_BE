package hansung.arcade.domain.room.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.room.business.RoomSender;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.LoginHandler;
import hansung.arcade.domain.session.web.controller.SessionListener;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 끊김 정리는 연결을 닫은 스레드가 아니라 넘겨받은 executor에서 돈다. 송신 큐가 넘쳐 끊길 때 그 알림은 방 lock을 잡고
 * 푸시를 보내던 스레드에서 오므로, 그 자리에서 방을 고치면 보내던 순서가 뒤집힌다.
 */
class RoomListenerTest {

    private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean sendFails;
    private GameServer server;

    @AfterEach
    void stopServer() {
        server.close();
    }

    private void start(Executor executor) throws IOException {
        SessionService sessionService = new SessionService(new InMemoryPlayerRepository());
        GameService gameService = new GameService(event -> {
        }, () -> 0, new Random(1));
        RoomConnections roomConnections = new RoomConnections();
        // sendFails가 켜지면 보내기가 실패해, 끊김 정리 작업이 예외를 낸다
        RoomSender sender = new RoomSender() {
            @Override
            public void send(Collection<Long> connectionIds, String type, Object body) {
                if (sendFails) {
                    throw new IllegalStateException("보내기 오류");
                }
                roomConnections.send(connectionIds, type, body);
            }

            @Override
            public void sendToLobby(Collection<Long> inRoom, String type, Object body) {
                roomConnections.sendToLobby(inRoom, type, body);
            }

            @Override
            public boolean isConnected(long connectionId) {
                return roomConnections.isConnected(connectionId);
            }
        };
        RoomService roomService = new RoomService(sender, gameService, () -> 0);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(LoginHandler.TYPE, new LoginHandler(sessionService));
        new RoomHandlers(roomService, sessionService).registerTo(dispatcher);
        server = GameServer.bind(0, dispatcher);
        server.addListener(new SessionListener(sessionService));
        server.addListener(roomConnections);
        server.addListener(new RoomListener(roomService, executor));
        server.start();
    }

    private final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;

        Client(String nickname) throws IOException {
            socket = new Socket("localhost", server.port());
            socket.setSoTimeout(3000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
            receive("HELLO");
            send("{\"type\":\"LOGIN\",\"id\":1,\"body\":{\"nicknames\":[\"" + nickname + "\"]}}");
            assertTrue(receive("LOGIN").get("ok").asBoolean());
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        JsonNode receive(String type) throws IOException {
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    throw new AssertionError(type + "를 받기 전에 끊겼습니다.");
                }
                JsonNode message = Json.MAPPER.readTree(line);
                if (message.get("type").asText().equals(type)) {
                    return message;
                }
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 방장이 만든 방에 손님이 들어오고, 방장이 받은 입장 알림까지 읽은 상태 */
    private void hostAndGuest(Client host, Client guest) throws IOException {
        host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
        host.receive("ROOM_CREATE");
        guest.send("{\"type\":\"ROOM_JOIN\",\"id\":2,\"body\":{\"roomNumber\":1}}");
        guest.receive("ROOM_JOIN");
        host.receive("CHAT_MESSAGE");
    }

    private void waitForTask() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (tasks.isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("끊김 정리가 executor로 넘어오지 않았습니다.");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void 끊김_정리는_executor가_돌릴_때_한다() throws Exception {
        start(tasks::add);
        try (Client host = new Client("방장")) {
            try (Client guest = new Client("손님")) {
                hostAndGuest(host, guest);
            }
            waitForTask();

            // executor가 돌리기 전에는 방에 아무 일도 없다
            host.socket.setSoTimeout(300);
            assertThrows(SocketTimeoutException.class, host.in::readLine);
            host.socket.setSoTimeout(3000);

            List<Runnable> pending = new ArrayList<>(tasks);
            pending.forEach(Runnable::run);

            assertEquals(1, host.receive("ROOM_STATE").at("/body/players").size());
        }
    }

    @Test
    void executor가_받지_않아도_끊김_알림이_예외를_내지_않는다() throws Exception {
        start(task -> {
            throw new RejectedExecutionException("서버 종료 중");
        });
        try (Client host = new Client("방장")) {
            try (Client guest = new Client("손님")) {
                hostAndGuest(host, guest);
            }

            host.send("{\"type\":\"PING\",\"id\":9,\"body\":{}}");

            assertEquals(9, host.receive("PING").get("id").asInt());
        }
    }

    @Test
    void 끊김_정리_작업이_오류를_내도_예외를_밖으로_내지_않고_로그로_남긴다() throws Exception {
        start(tasks::add);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try (Client host = new Client("방장")) {
            try (Client guest = new Client("손님")) {
                hostAndGuest(host, guest);
            }
            waitForTask();
            sendFails = true;

            // 실제 executor(경기 타이머)는 작업의 예외를 삼키므로, 작업 안에서 잡아 로그로 남겨야 원인을 알 수 있다
            List<Runnable> pending = new ArrayList<>(tasks);
            assertDoesNotThrow(() -> pending.forEach(Runnable::run));

            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
                    && event.getThrowableProxy() != null
                    && "보내기 오류".equals(event.getThrowableProxy().getMessage())),
                    "끊김 정리 오류가 ERROR 로그로 남지 않았습니다");
        } finally {
            root.detachAppender(appender);
            sendFails = false;
        }
    }
}

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 핸들러 공통 규칙: 세션이 없는 연결, 성공 응답 뒤의 오류.
 */
class RoomHandlersTest {

    private GameServer server;

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** 로비 알림을 보낼 때 터지는 송신기 */
    private static final class BrokenLobbySender implements RoomSender {
        @Override
        public void send(Collection<Long> connectionIds, String type, Object body) {
        }

        @Override
        public void sendToLobby(Collection<Long> inRoom, String type, Object body) {
            throw new IllegalStateException("로비 알림 실패");
        }

        @Override
        public boolean isConnected(long connectionId) {
            return true;
        }
    }

    private void start(boolean withSessions) throws IOException {
        SessionService sessionService = new SessionService(new InMemoryPlayerRepository());
        GameService gameService = new GameService(event -> {
        }, () -> 0, new Random(1));
        RoomService roomService = new RoomService(new BrokenLobbySender(), gameService, () -> 0);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(LoginHandler.TYPE, new LoginHandler(sessionService));
        new RoomHandlers(roomService, sessionService).registerTo(dispatcher);
        server = GameServer.bind(0, dispatcher);
        if (withSessions) {
            server.addListener(new SessionListener(sessionService));
        }
        server.start();
    }

    private final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;

        Client() throws IOException {
            socket = new Socket("localhost", server.port());
            socket.setSoTimeout(3000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
            assertEquals("HELLO", receive().get("type").asText());
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        JsonNode receive() throws IOException {
            return Json.MAPPER.readTree(in.readLine());
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void 세션이_없는_연결은_NOT_LOGGED_IN이다() throws IOException {
        start(false);
        try (Client client = new Client()) {
            client.send("{\"type\":\"ROOM_LIST\",\"id\":1,\"body\":{}}");

            JsonNode response = client.receive();
            assertEquals("NOT_LOGGED_IN", response.at("/error/code").asText());
            assertEquals("로그인이 필요합니다.", response.at("/error/message").asText());
        }
    }

    @Test
    void 성공_응답을_보낸_뒤_오류가_나도_같은_id에_실패_응답을_또_보내지_않는다() throws IOException {
        start(true);
        try (Client client = new Client()) {
            client.send("{\"type\":\"LOGIN\",\"id\":1,\"body\":{\"nicknames\":[\"가가\"]}}");
            assertTrue(client.receive().get("ok").asBoolean());

            client.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            client.send("{\"type\":\"PING\",\"id\":3,\"body\":{}}");

            JsonNode created = client.receive();
            assertEquals("ROOM_CREATE", created.get("type").asText());
            assertTrue(created.get("ok").asBoolean());
            JsonNode next = client.receive();
            assertEquals("PING", next.get("type").asText(), next.toString());
        }
    }
}

package hansung.arcade.core.boot;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.room.web.controller.RoomConnections;
import hansung.arcade.domain.room.web.controller.RoomListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 구동 단계(ServiceStep → DispatcherStep → 서버 소켓 → AcceptStep)로 조립한 서버에서 방 API가 동작하는지 확인한다.
 * 경기 종료·GAME_EXIT·끊김이 방으로 이어지는 연결(리스너 등록과 그 순서)까지 본다.
 */
class BootWiringTest {

    private ServerContext context;
    private GameServer server;

    @BeforeEach
    void boot() throws Exception {
        context = new ServerContext(BootWiringTest.class, List.of());
        new ServiceStep().run(context);
        new DispatcherStep().run(context);
        server = GameServer.bind(0, context.get(MessageDispatcher.class));
        context.register(GameServer.class, server);
        new AcceptStep().run(context);
    }

    @AfterEach
    void shutdown() {
        server.close();
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
            List<String> skipped = new ArrayList<>();
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    throw new AssertionError(type + "를 받기 전에 끊겼습니다. 받은 것: " + skipped);
                }
                JsonNode message = Json.MAPPER.readTree(line);
                if (message.get("type").asText().equals(type)) {
                    return message;
                }
                skipped.add(message.get("type").asText());
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void 구동_단계가_방_API를_모두_등록한다() {
        MessageDispatcher dispatcher = context.get(MessageDispatcher.class);
        for (String type : List.of("ROOM_LIST", "ROOM_CREATE", "ROOM_JOIN", "ROOM_QUICK_JOIN", "ROOM_LEAVE", "READY",
                "SELECT_CHARACTER", "SELECT_TEAM", "CHANGE_MAP", "CHAT", "GAME_START")) {
            assertTrue(dispatcher.handles(type), type);
        }
        assertTrue(context.contains(RoomService.class));
        assertTrue(context.contains(RoomConnections.class));
        assertTrue(context.contains(RoomListener.class));
    }

    /** 방장(RED)이 방을 만들고 손님(BLUE)이 들어와 준비한 뒤 경기를 시작한다 */
    private void startGame(Client host, Client guest) throws IOException {
        host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
        host.receive("ROOM_CREATE");
        guest.send("{\"type\":\"ROOM_JOIN\",\"id\":2,\"body\":{\"roomNumber\":1}}");
        guest.receive("ROOM_JOIN");
        guest.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"BLUE\"}}");
        guest.send("{\"type\":\"READY\",\"body\":{\"ready\":true}}");
        guest.send("{\"type\":\"PING\",\"id\":3,\"body\":{}}");
        guest.receive("PING");
        host.send("{\"type\":\"GAME_START\",\"id\":4,\"body\":{}}");
        assertTrue(host.receive("GAME_START").get("ok").asBoolean());
        host.receive("GAME_STARTED");
        guest.receive("GAME_STARTED");
    }

    @Test
    void 조립한_서버에서_GAME_EXIT하면_방에서_빠지고_경기가_끝나면_대기실로_돌아온다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님")) {
            startGame(host, guest);

            guest.send("{\"type\":\"GAME_EXIT\",\"body\":{}}");

            assertEquals("손님님이 나갔습니다.", host.receive("CHAT_MESSAGE").at("/body/text").asText());
            host.receive("GAME_OVER");
            JsonNode state = host.receive("ROOM_STATE");
            assertEquals("WAITING", state.at("/body/room/status").asText());
            assertEquals(1, state.at("/body/players").size());
            guest.send("{\"type\":\"ROOM_LIST\",\"id\":5,\"body\":{}}");
            assertTrue(guest.receive("ROOM_LIST").get("ok").asBoolean());
        }
    }

    @Test
    void 조립한_서버에서_경기_중_끊기면_방에서_먼저_빠져_경기_뒤_대기실에_남지_않는다() throws IOException {
        try (Client host = new Client("방장")) {
            try (Client guest = new Client("손님")) {
                startGame(host, guest);
                // GAME_START 처리가 다 끝난 뒤에 끊는다 (경기를 여는 도중에 끊기면 경기가 먼저 끝난다)
                host.send("{\"type\":\"PING\",\"id\":9,\"body\":{}}");
                host.receive("PING");
            }

            assertEquals("손님님이 나갔습니다.", host.receive("CHAT_MESSAGE").at("/body/text").asText());
            host.receive("GAME_OVER");
            JsonNode state = host.receive("ROOM_STATE");
            assertEquals("WAITING", state.at("/body/room/status").asText());
            assertEquals(1, state.at("/body/players").size());
        }
    }

    @Test
    void 조립한_서버에서_로딩_시간_안에_LOADED를_보내지_않은_연결은_방에서도_빠진다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님"); Client third = new Client("셋째")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.receive("ROOM_CREATE");
            for (Client client : List.of(guest, third)) {
                client.send("{\"type\":\"ROOM_JOIN\",\"id\":2,\"body\":{\"roomNumber\":1}}");
                client.receive("ROOM_JOIN");
            }
            guest.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"BLUE\"}}");
            third.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"GREEN\"}}");
            for (Client client : List.of(guest, third)) {
                client.send("{\"type\":\"READY\",\"body\":{\"ready\":true}}");
                client.send("{\"type\":\"PING\",\"id\":3,\"body\":{}}");
                client.receive("PING");
            }
            host.send("{\"type\":\"GAME_START\",\"id\":4,\"body\":{}}");
            host.receive("GAME_STARTED");
            host.send("{\"type\":\"LOADED\",\"body\":{}}");
            guest.send("{\"type\":\"LOADED\",\"body\":{}}");

            // 셋째는 LOADED를 보내지 않는다. 로딩 제한(10초)이 지나면 경기와 방에서 함께 빠진다
            third.socket.setSoTimeout(15_000);
            third.receive("PLAYER_LEFT");
            third.receive("LOBBY_ROOMS");
            third.send("{\"type\":\"ROOM_LIST\",\"id\":5,\"body\":{}}");
            assertTrue(third.receive("ROOM_LIST").get("ok").asBoolean());
            host.socket.setSoTimeout(15_000);
            assertEquals("셋째님이 나갔습니다.", host.receive("CHAT_MESSAGE").at("/body/text").asText());
        }
    }
}

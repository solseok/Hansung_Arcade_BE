package hansung.arcade.domain.room.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.web.controller.GameConnections;
import hansung.arcade.domain.game.web.controller.GameHandlers;
import hansung.arcade.domain.game.web.controller.GameListener;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.LoginHandler;
import hansung.arcade.domain.session.web.controller.SessionListener;
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
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 소켓으로 LOGIN → 방 만들기·입장 → 대기실 → GAME_START → 경기 끝 → 대기실 흐름을 계층 전체에 걸쳐 확인한다.
 * 끊김 정리는 바로 그 스레드에서 한다.
 */
class RoomFlowTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private GameServer server;

    @BeforeEach
    void startServer() throws IOException {
        SessionService sessionService = new SessionService(new InMemoryPlayerRepository());
        GameConnections gameConnections = new GameConnections();
        GameService gameService = new GameService(gameConnections, clock::get, new Random(3));
        RoomConnections roomConnections = new RoomConnections();
        RoomService roomService = new RoomService(roomConnections, gameService, clock::get);
        gameService.onFinished(match -> roomService.finishGame(match.matchId()));
        gameService.onExit(roomService::exitGame);

        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(LoginHandler.TYPE, new LoginHandler(sessionService));
        new GameHandlers(gameService).registerTo(dispatcher);
        new RoomHandlers(roomService, sessionService).registerTo(dispatcher);

        server = GameServer.bind(0, dispatcher);
        server.addListener(new SessionListener(sessionService));
        server.addListener(gameConnections);
        server.addListener(roomConnections);
        server.addListener(new RoomListener(roomService, Runnable::run));
        server.addListener(new GameListener(gameService, Runnable::run));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
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

        /** 로그인한 클라이언트 */
        Client(String... nicknames) throws IOException {
            this();
            StringBuilder names = new StringBuilder();
            for (String nickname : nicknames) {
                names.append(names.isEmpty() ? "" : ",").append('"').append(nickname).append('"');
            }
            send("{\"type\":\"LOGIN\",\"id\":1,\"body\":{\"nicknames\":[" + names + "]}}");
            JsonNode response = receive();
            assertTrue(response.get("ok").asBoolean(), response.toString());
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        JsonNode receive() throws IOException {
            String line = in.readLine();
            return line == null ? null : Json.MAPPER.readTree(line);
        }

        /** type이 올 때까지 읽는다. 지나친 메시지는 버린다 */
        JsonNode receive(String type) throws IOException {
            List<String> skipped = new ArrayList<>();
            while (true) {
                JsonNode message = receive();
                if (message == null) {
                    throw new AssertionError(type + "를 받기 전에 끊겼습니다. 받은 것: " + skipped);
                }
                if (message.get("type").asText().equals(type)) {
                    return message;
                }
                skipped.add(message.get("type").asText());
            }
        }

        /** 다음 메시지가 이 type인지 */
        JsonNode next(String type) throws IOException {
            JsonNode message = receive();
            assertEquals(type, message.get("type").asText(), message.toString());
            return message;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void 로그인_전에는_모든_방_메시지가_NOT_LOGGED_IN이다() throws IOException {
        try (Client client = new Client()) {
            for (String type : List.of("ROOM_LIST", "ROOM_CREATE", "ROOM_JOIN", "ROOM_QUICK_JOIN", "ROOM_LEAVE",
                    "CHANGE_MAP", "GAME_START")) {
                client.send("{\"type\":\"" + type + "\",\"id\":5,\"body\":{}}");
                JsonNode response = client.next(type);
                assertFalse(response.get("ok").asBoolean());
                assertEquals("NOT_LOGGED_IN", response.at("/error/code").asText(), type);
                assertEquals(5, response.get("id").asInt());
            }
            for (String type : List.of("READY", "SELECT_CHARACTER", "SELECT_TEAM", "CHAT")) {
                client.send("{\"type\":\"" + type + "\",\"body\":{}}");
                JsonNode error = client.next("ERROR");
                assertEquals("NOT_LOGGED_IN", error.at("/body/code").asText(), type);
                assertEquals(type, error.at("/body/type").asText());
            }
        }
    }

    @Test
    void 방을_만들고_들어오면_응답이_안내보다_먼저_오고_로비는_목록을_받는다() throws IOException {
        try (Client host = new Client("방장"); Client lobby = new Client("구경꾼"); Client guest = new Client("손님")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"함께 해요\",\"capacity\":4}}");
            JsonNode created = host.next("ROOM_CREATE");
            assertTrue(created.get("ok").asBoolean());
            assertEquals(2, created.get("id").asInt());
            assertEquals(1, created.at("/body/room/number").asInt());
            assertEquals(4, created.at("/body/room/capacity").asInt());
            assertEquals(1, lobby.next("LOBBY_ROOMS").at("/body/rooms/0/players").asInt());
            guest.next("LOBBY_ROOMS");

            guest.send("{\"type\":\"ROOM_LIST\",\"id\":2,\"body\":{}}");
            JsonNode list = guest.next("ROOM_LIST");
            assertEquals("함께 해요", list.at("/body/rooms/0/title").asText());

            guest.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
            JsonNode joined = guest.next("ROOM_JOIN");
            assertEquals(2, joined.at("/body/players").size());
            assertEquals("손님님이 들어왔습니다.", guest.next("CHAT_MESSAGE").at("/body/text").asText());
            assertEquals(2, host.next("ROOM_STATE").at("/body/players").size());
            assertEquals("손님님이 들어왔습니다.", host.next("CHAT_MESSAGE").at("/body/text").asText());
            assertEquals(2, lobby.next("LOBBY_ROOMS").at("/body/rooms/0/players").asInt());

            guest.send("{\"type\":\"ROOM_LIST\",\"id\":4,\"body\":{}}");
            assertEquals("WRONG_STATE", guest.next("ROOM_LIST").at("/error/code").asText());
        }
    }

    @Test
    void 실패한_요청은_같은_id의_ok_false로_알리고_연결은_유지한다() throws IOException {
        try (Client client = new Client("가가")) {
            client.send("{\"type\":\"ROOM_JOIN\",\"id\":7,\"body\":{\"roomNumber\":42}}");
            JsonNode response = client.next("ROOM_JOIN");

            assertEquals(7, response.get("id").asInt());
            assertFalse(response.get("ok").asBoolean());
            assertEquals("ROOM_NOT_FOUND", response.at("/error/code").asText());
            assertEquals("없어진 방입니다. 목록을 다시 확인해 주세요.", response.at("/error/message").asText());
            assertFalse(response.has("body"));

            client.send("{\"type\":\"PING\",\"id\":8,\"body\":{}}");
            assertEquals(8, client.next("PING").get("id").asInt());
        }
    }

    @Test
    void 요청_body의_필드가_빠졌거나_타입이_틀리면_BAD_MESSAGE() throws IOException {
        try (Client client = new Client("가가")) {
            for (String body : List.of("{}", "{\"title\":3,\"capacity\":4}", "{\"title\":\"a\"}",
                    "{\"title\":\"a\",\"capacity\":\"4\"}", "{\"title\":\"a\",\"capacity\":2.5}",
                    "{\"title\":null,\"capacity\":4}", "{\"title\":\"a\",\"capacity\":99999999999}")) {
                client.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":" + body + "}");
                assertEquals("BAD_MESSAGE", client.next("ROOM_CREATE").at("/error/code").asText(), body);
            }
            for (String body : List.of("{}", "{\"roomNumber\":\"1\"}", "{\"roomNumber\":1.5}", "{\"roomNumber\":true}")) {
                client.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":" + body + "}");
                assertEquals("BAD_MESSAGE", client.next("ROOM_JOIN").at("/error/code").asText(), body);
            }

            client.send("{\"type\":\"ROOM_CREATE\",\"id\":4,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            client.next("ROOM_CREATE");
            for (String body : List.of("{}", "{\"mapId\":1}", "{\"mapId\":null}")) {
                client.send("{\"type\":\"CHANGE_MAP\",\"id\":5,\"body\":" + body + "}");
                assertEquals("BAD_MESSAGE", client.next("CHANGE_MAP").at("/error/code").asText(), body);
            }
            String[][] pushes = {
                    {"READY", "{}"}, {"READY", "{\"ready\":\"true\"}"}, {"READY", "{\"ready\":1}"},
                    {"SELECT_CHARACTER", "{}"}, {"SELECT_CHARACTER", "{\"character\":1}"},
                    {"SELECT_CHARACTER", "{\"character\":\"pikachu\"}"},
                    {"SELECT_TEAM", "{}"}, {"SELECT_TEAM", "{\"team\":0}"}, {"SELECT_TEAM", "{\"team\":\"blue\"}"},
                    {"CHAT", "{}"}, {"CHAT", "{\"text\":5}"}, {"CHAT", "{\"text\":\"a\",\"playerId\":\"1\"}"},
                    {"CHAT", "{\"text\":\"a\",\"playerId\":1.5}"}, {"CHAT", "{\"text\":\"a\",\"playerId\":999}"}};
            for (String[] push : pushes) {
                client.send("{\"type\":\"" + push[0] + "\",\"body\":" + push[1] + "}");
                JsonNode error = client.next("ERROR");
                assertEquals("BAD_MESSAGE", error.at("/body/code").asText(), push[0] + " " + push[1]);
                assertEquals(push[0], error.at("/body/type").asText());
            }
        }
    }

    @Test
    void 응답_없는_메시지가_상태에_맞지_않으면_ERROR_푸시로_알린다() throws IOException {
        try (Client client = new Client("가가")) {
            for (String[] push : new String[][]{{"READY", "{\"ready\":true}"},
                    {"SELECT_CHARACTER", "{\"character\":\"kkukku\"}"}, {"SELECT_TEAM", "{\"team\":\"BLUE\"}"},
                    {"CHAT", "{\"text\":\"로비\"}"}}) {
                client.send("{\"type\":\"" + push[0] + "\",\"body\":" + push[1] + "}");
                JsonNode error = client.next("ERROR");
                assertEquals("WRONG_STATE", error.at("/body/code").asText(), push[0]);
                assertEquals(push[0], error.at("/body/type").asText());
            }
        }
    }

    @Test
    void id_없이_보낸_요청은_처리하되_응답을_보내지_않는다() throws IOException {
        try (Client client = new Client("가가"); Client lobby = new Client("나나")) {
            client.send("{\"type\":\"ROOM_CREATE\",\"body\":{\"title\":\"a\",\"capacity\":4}}");
            client.send("{\"type\":\"PING\",\"id\":9,\"body\":{}}");

            assertEquals(9, client.next("PING").get("id").asInt());
            assertEquals(1, lobby.next("LOBBY_ROOMS").at("/body/rooms").size());
        }
    }

    @Test
    void 방에서_끊기면_남은_사람에게_알리고_방장을_넘긴다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님", "친구")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");
            guest.receive("LOBBY_ROOMS");
            guest.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
            guest.next("ROOM_JOIN");
            guest.next("CHAT_MESSAGE");
            guest.next("CHAT_MESSAGE");

            host.close();

            JsonNode state = guest.next("ROOM_STATE");
            assertEquals(2, state.at("/body/players").size());
            assertEquals(state.at("/body/players/0/playerId").asLong(), state.at("/body/hostId").asLong());
            assertEquals("방장님이 나갔습니다.", guest.next("CHAT_MESSAGE").at("/body/text").asText());
            assertEquals("손님님이 방장이 되었습니다.", guest.next("CHAT_MESSAGE").at("/body/text").asText());
        }
    }

    @Test
    void 방에서_나가면_응답_뒤_로비_목록을_받는다() throws IOException {
        try (Client host = new Client("방장")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");

            host.send("{\"type\":\"ROOM_LEAVE\",\"id\":3,\"body\":{}}");

            JsonNode left = host.next("ROOM_LEAVE");
            assertTrue(left.get("ok").asBoolean());
            assertEquals("{}", left.get("body").toString());
            assertEquals("{\"rooms\":[]}", host.next("LOBBY_ROOMS").get("body").toString());
        }
    }

    @Test
    void 채팅은_방_전체에_다듬어_나간다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");
            guest.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
            guest.receive("ROOM_JOIN");
            guest.next("CHAT_MESSAGE");
            host.next("ROOM_STATE");
            host.next("CHAT_MESSAGE");

            guest.send("{\"type\":\"CHAT\",\"body\":{\"text\":\"  안녕\\n하세요 \"}}");

            for (Client client : List.of(host, guest)) {
                JsonNode chat = client.next("CHAT_MESSAGE");
                assertEquals("손님", chat.at("/body/sender").asText());
                assertEquals("안녕 하세요", chat.at("/body/text").asText());
            }
        }
    }

    @Test
    void 게임을_시작하면_응답_뒤_GAME_STARTED가_오고_경기가_끝나면_대기실로_돌아온다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");
            guest.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
            guest.receive("ROOM_JOIN");
            guest.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"BLUE\"}}");
            guest.send("{\"type\":\"READY\",\"body\":{\"ready\":true}}");
            host.receive("ROOM_STATE");
            host.next("CHAT_MESSAGE");
            host.next("ROOM_STATE");
            assertTrue(host.next("ROOM_STATE").at("/body/players/1/ready").asBoolean());

            host.send("{\"type\":\"GAME_START\",\"id\":4,\"body\":{}}");

            JsonNode response = host.next("GAME_START");
            assertTrue(response.get("ok").asBoolean());
            assertEquals("{}", response.get("body").toString());
            JsonNode started = host.next("GAME_STARTED");
            assertEquals(2, started.at("/body/players").size());
            assertEquals(started, guest.receive("GAME_STARTED"));

            guest.send("{\"type\":\"GAME_EXIT\",\"body\":{}}");

            // 나간 손님은 방에서도 빠져 로비로 간다
            assertEquals("손님님이 나갔습니다.", host.receive("CHAT_MESSAGE").at("/body/text").asText());
            host.receive("GAME_OVER");
            JsonNode state = host.next("ROOM_STATE");
            assertEquals("WAITING", state.at("/body/room/status").asText());
            assertEquals(1, state.at("/body/players").size());
            assertEquals(1, guest.receive("LOBBY_ROOMS").at("/body/rooms/0/players").asInt());
            guest.send("{\"type\":\"ROOM_LIST\",\"id\":9,\"body\":{}}");
            assertTrue(guest.receive("ROOM_LIST").get("ok").asBoolean());
        }
    }

    @Test
    void 경기_뒤_준비가_풀리고_다시_시작할_수_있다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님"); Client third = new Client("셋째")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");
            for (Client client : List.of(guest, third)) {
                client.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
                client.receive("ROOM_JOIN");
            }
            guest.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"BLUE\"}}");
            for (Client client : List.of(guest, third)) {
                client.send("{\"type\":\"READY\",\"body\":{\"ready\":true}}");
                client.send("{\"type\":\"PING\",\"id\":4,\"body\":{}}");
                client.receive("PING");
            }
            host.send("{\"type\":\"GAME_START\",\"id\":5,\"body\":{}}");
            host.receive("GAME_STARTED");

            guest.send("{\"type\":\"GAME_EXIT\",\"body\":{}}");

            host.receive("GAME_OVER");
            JsonNode state = host.next("ROOM_STATE");
            assertEquals("WAITING", state.at("/body/room/status").asText());
            assertEquals(2, state.at("/body/players").size());
            assertFalse(state.at("/body/players/1/ready").asBoolean());
            assertEquals("WAITING", third.receive("ROOM_STATE").at("/body/room/status").asText());
        }
    }

    @Test
    void 채팅에_playerId가_없으면_자리_순서가_아니라_로그인_순서의_1P다() throws IOException {
        try (Client host = new Client("방장"); Client f1 = new Client("하나"); Client f2 = new Client("둘둘");
             Client f3 = new Client("셋셋"); Client f4 = new Client("넷넷"); Client pair = new Client("일피", "이피")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":8}}");
            host.next("ROOM_CREATE");
            for (Client client : List.of(f1, f2, f3, f4, pair)) {
                client.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
                client.receive("ROOM_JOIN");
            }
            f1.send("{\"type\":\"ROOM_LEAVE\",\"id\":4,\"body\":{}}");
            f1.receive("ROOM_LEAVE");
            // 정원 6으로 줄면 slot 6의 2P가 빈 slot 1로 옮겨져 1P(slot 5)보다 앞에 온다
            host.send("{\"type\":\"CHANGE_MAP\",\"id\":5,\"body\":{\"mapId\":\"village/village10\"}}");
            host.receive("CHANGE_MAP");
            JsonNode state = pair.receive("ROOM_STATE");
            while (!state.at("/body/room/mapId").asText().equals("village/village10")) {
                state = pair.receive("ROOM_STATE");
            }
            assertEquals("이피", state.at("/body/players/1/nickname").asText());

            pair.send("{\"type\":\"CHAT\",\"body\":{\"text\":\"누구\"}}");

            JsonNode chat = pair.receive("CHAT_MESSAGE");
            while (!chat.at("/body/text").asText().equals("누구")) {
                chat = pair.receive("CHAT_MESSAGE");
            }
            assertEquals("일피", chat.at("/body/sender").asText());
        }
    }

    @Test
    void 게임_중에는_대기실_메시지가_WRONG_STATE이고_채팅은_된다() throws IOException {
        try (Client host = new Client("방장"); Client guest = new Client("손님")) {
            host.send("{\"type\":\"ROOM_CREATE\",\"id\":2,\"body\":{\"title\":\"a\",\"capacity\":4}}");
            host.next("ROOM_CREATE");
            guest.send("{\"type\":\"ROOM_JOIN\",\"id\":3,\"body\":{\"roomNumber\":1}}");
            guest.receive("ROOM_JOIN");
            guest.send("{\"type\":\"SELECT_TEAM\",\"body\":{\"team\":\"BLUE\"}}");
            guest.send("{\"type\":\"READY\",\"body\":{\"ready\":true}}");
            guest.send("{\"type\":\"PING\",\"id\":4,\"body\":{}}");
            guest.receive("PING");
            host.send("{\"type\":\"GAME_START\",\"id\":5,\"body\":{}}");
            host.receive("GAME_STARTED");
            guest.receive("GAME_STARTED");

            guest.send("{\"type\":\"ROOM_LEAVE\",\"id\":6,\"body\":{}}");
            assertEquals("WRONG_STATE", guest.next("ROOM_LEAVE").at("/error/code").asText());
            host.send("{\"type\":\"CHANGE_MAP\",\"id\":7,\"body\":{\"mapId\":\"random\"}}");
            assertEquals("WRONG_STATE", host.next("CHANGE_MAP").at("/error/code").asText());

            host.send("{\"type\":\"CHAT\",\"body\":{\"text\":\"게임 중\"}}");
            assertEquals("게임 중", guest.receive("CHAT_MESSAGE").at("/body/text").asText());
        }
    }
}

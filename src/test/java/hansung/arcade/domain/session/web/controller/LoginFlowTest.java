package hansung.arcade.domain.session.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.session.business.SessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 소켓으로 접속 → LOGIN → 끊김 흐름을 계층 전체(web.controller → business → repository)에 걸쳐 확인한다.
 */
class LoginFlowTest {

    private InMemoryPlayerRepository repository;
    private GameServer server;

    @BeforeEach
    void startServer() throws IOException {
        repository = new InMemoryPlayerRepository();
        SessionService sessionService = new SessionService(repository);
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(LoginHandler.TYPE, new LoginHandler(sessionService));
        server = GameServer.bind(0, dispatcher);
        server.addListener(new SessionListener(sessionService));
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

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        JsonNode receive() throws IOException {
            String line = in.readLine();
            return line == null ? null : Json.MAPPER.readTree(line);
        }

        JsonNode login(long id, String... nicknames) throws IOException {
            send(Json.MAPPER.createObjectNode()
                    .put("type", "LOGIN").put("id", id)
                    .set("body", Json.object().set("nicknames", Json.MAPPER.valueToTree(nicknames)))
                    .toString());
            return receive();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void 닉네임_둘로_로그인하면_플레이어마다_playerId를_받는다() throws IOException {
        try (Client client = new Client()) {
            JsonNode response = client.login(1, "상상부기", "꾸꾸");

            assertEquals("LOGIN", response.get("type").asText());
            assertEquals(1, response.get("id").asInt());
            assertTrue(response.get("ok").asBoolean());
            JsonNode players = response.at("/body/players");
            assertEquals(2, players.size());
            assertEquals("상상부기", players.get(0).get("nickname").asText());
            assertEquals("꾸꾸", players.get(1).get("nickname").asText());
            assertTrue(players.get(0).get("playerId").asLong() >= 1);
            assertTrue(players.get(1).get("playerId").asLong() > players.get(0).get("playerId").asLong());
        }
    }

    @Test
    void 접속_중인_다른_사람의_닉네임이면_실패하고_연결은_유지한다() throws IOException {
        try (Client first = new Client(); Client second = new Client()) {
            first.login(1, "꾸꾸");

            JsonNode response = second.login(1, "꾸꾸");
            assertFalse(response.get("ok").asBoolean());
            assertEquals("NICKNAME_DUPLICATE", response.at("/error/code").asText());
            assertEquals("이미 쓰고 있는 닉네임입니다: 꾸꾸", response.at("/error/message").asText());

            assertTrue(second.login(2, "상상부기").get("ok").asBoolean());
        }
    }

    @Test
    void 규칙에_어긋난_닉네임과_틀린_body는_실패_응답() throws IOException {
        try (Client client = new Client()) {
            assertEquals("NICKNAME_INVALID", client.login(1, "ㅁㄴㅇㄹ").at("/error/code").asText());
            assertEquals("NICKNAME_SAME", client.login(2, "부기", "부기").at("/error/code").asText());
            assertEquals("BAD_MESSAGE", client.login(3).at("/error/code").asText());

            for (String nicknames : new String[]{"\"부기\"", "[true,false]", "[1.5,\"ab\"]", "[null,\"ab\"]", "[[\"ab\"]]"}) {
                client.send("{\"type\":\"LOGIN\",\"id\":4,\"body\":{\"nicknames\":" + nicknames + "}}");
                assertEquals("BAD_MESSAGE", client.receive().at("/error/code").asText(), nicknames);
            }
            client.send("{\"type\":\"LOGIN\",\"id\":5,\"body\":{}}");
            assertEquals("BAD_MESSAGE", client.receive().at("/error/code").asText());
            assertTrue(repository.findAll().isEmpty());
        }
    }

    @Test
    void 로그인한_연결이_다시_보내면_ALREADY_LOGGED_IN() throws IOException {
        try (Client client = new Client()) {
            client.login(1, "부기");

            assertEquals("ALREADY_LOGGED_IN", client.login(2, "꾸꾸").at("/error/code").asText());
        }
    }

    @Test
    void BYE를_보내면_닉네임이_풀려_다른_사람이_쓸_수_있다() throws Exception {
        try (Client first = new Client()) {
            first.login(1, "상상부기", "꾸꾸");
            first.send("{\"type\":\"BYE\",\"body\":{}}");
            assertNull(first.in.readLine());
        }
        waitUntil(() -> repository.findAll().isEmpty());

        try (Client second = new Client()) {
            assertTrue(second.login(1, "꾸꾸").get("ok").asBoolean());
        }
    }

    @Test
    void 갑자기_끊겨도_닉네임이_풀린다() throws Exception {
        try (Client first = new Client()) {
            first.login(1, "부기");
            assertTrue(repository.existsByNickname("부기"));
        }

        waitUntil(() -> repository.findAll().isEmpty());
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("3초 안에 조건이 맞지 않았습니다.");
            }
            Thread.sleep(10);
        }
    }
}

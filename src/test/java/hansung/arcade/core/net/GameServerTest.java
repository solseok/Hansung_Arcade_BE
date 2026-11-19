package hansung.arcade.core.net;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 소켓으로 접속해 공통 규칙(HELLO, PING, ERROR, BYE, 32 KiB 제한)을 확인한다.
 */
class GameServerTest {

    private GameServer server;
    private final List<String> closeReasons = new CopyOnWriteArrayList<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register("ECHO", (connection, request) -> request.body());
        dispatcher.register("ROOM_JOIN", (connection, request) -> {
            throw new ProtocolException("ROOM_FULL", "자리가 없습니다.");
        });
        dispatcher.register("BOOM", (connection, request) -> {
            throw new IllegalStateException("핸들러 버그");
        });
        server = GameServer.bind(0, dispatcher);
        server.addListener(new ConnectionListener() {
            @Override
            public void onClose(Connection connection, String reason) {
                closeReasons.add(reason);
                closed.countDown();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    /** 테스트용 클라이언트: 보낼 때 LF 하나, 받을 때 한 줄씩 JSON으로 */
    private final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader in;
        final OutputStream out;

        Client() throws IOException {
            socket = new Socket("localhost", server.port());
            socket.setSoTimeout(3000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = socket.getOutputStream();
        }

        void send(String line) throws IOException {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        JsonNode receive() throws IOException {
            String line = in.readLine();
            return line == null ? null : Json.MAPPER.readTree(line);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    void 접속하자마자_HELLO로_프로토콜_버전을_알린다() throws IOException {
        try (Client client = new Client()) {
            JsonNode hello = client.receive();

            assertEquals("HELLO", hello.get("type").asText());
            assertEquals(Protocol.VERSION, hello.at("/body/protocolVersion").asInt());
            assertFalse(hello.has("id"));
        }
    }

    @Test
    void PING에_같은_id로_응답한다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"PING\",\"id\":40,\"body\":{}}");

            JsonNode pong = client.receive();

            assertEquals("{\"type\":\"PING\",\"id\":40,\"ok\":true,\"body\":{}}", pong.toString());
        }
    }

    @Test
    void 핸들러가_돌려준_값이_응답_body가_된다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"ECHO\",\"id\":1,\"body\":{\"text\":\"안녕\"}}");

            assertEquals("안녕", client.receive().at("/body/text").asText());
        }
    }

    @Test
    void 처리할_수_없는_요청은_ok_false_응답으로_알리고_연결은_유지한다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"ROOM_JOIN\",\"id\":7,\"body\":{\"roomNumber\":3}}");

            JsonNode response = client.receive();
            assertEquals(7, response.get("id").asInt());
            assertFalse(response.get("ok").asBoolean());
            assertEquals("ROOM_FULL", response.at("/error/code").asText());
            assertFalse(response.has("body"));

            client.send("{\"type\":\"PING\",\"id\":8}");
            assertTrue(client.receive().get("ok").asBoolean());
        }
    }

    @Test
    void id_없는_메시지의_실패와_읽을_수_없는_줄은_ERROR_푸시로_알린다() throws IOException {
        try (Client client = new Client()) {
            client.receive();

            client.send("깨진 줄");
            assertEquals("BAD_MESSAGE", client.receive().at("/body/code").asText());

            client.send("{\"type\":\"NOPE\",\"body\":{}}");
            JsonNode unknown = client.receive();
            assertEquals("ERROR", unknown.get("type").asText());
            assertEquals("UNKNOWN_TYPE", unknown.at("/body/code").asText());
            assertEquals("NOPE", unknown.at("/body/type").asText());
        }
    }

    @Test
    void id까지_읽은_깨진_요청은_같은_id의_ok_false로_알린다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"ROOM_JOIN\",\"id\":7,\"body\":[]}");

            JsonNode response = client.receive();
            assertEquals("ROOM_JOIN", response.get("type").asText());
            assertEquals(7, response.get("id").asInt());
            assertFalse(response.get("ok").asBoolean());
            assertEquals("BAD_MESSAGE", response.at("/error/code").asText());
        }
    }

    @Test
    void 모르는_type의_요청은_ok_false로_알린다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"NOPE\",\"id\":3,\"body\":{}}");

            JsonNode response = client.receive();
            assertEquals("NOPE", response.get("type").asText());
            assertEquals("UNKNOWN_TYPE", response.at("/error/code").asText());
        }
    }

    @Test
    void 핸들러의_예상_못_한_예외는_SERVER_ERROR로_알리고_연결은_유지한다() throws IOException {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"BOOM\",\"id\":1,\"body\":{}}");

            assertEquals("SERVER_ERROR", client.receive().at("/error/code").asText());
            client.send("{\"type\":\"PING\",\"id\":2,\"body\":{}}");
            assertTrue(client.receive().get("ok").asBoolean());
        }
    }

    @Test
    void 줄이_32KiB를_넘으면_MESSAGE_TOO_LONG을_보내고_끊는다() throws Exception {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"ECHO\",\"body\":{\"text\":\"" + "a".repeat(Protocol.MAX_LINE_BYTES * 4) + "\"}}");

            assertEquals("MESSAGE_TOO_LONG", client.receive().at("/body/code").asText());
            assertNull(client.in.readLine());
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void BYE를_받으면_정리를_한_번만_하고_연결을_닫는다() throws Exception {
        try (Client client = new Client()) {
            client.receive();
            client.send("{\"type\":\"BYE\",\"body\":{}}");

            assertNull(client.in.readLine());
            assertTrue(closed.await(3, TimeUnit.SECONDS));
        }
        Thread.sleep(200);
        assertEquals(List.of("BYE"), closeReasons);
        assertTrue(server.connections().isEmpty());
    }

    @Test
    void 클라이언트가_갑자기_끊어도_정리한다() throws Exception {
        try (Client client = new Client()) {
            client.receive();
        }

        assertTrue(closed.await(3, TimeUnit.SECONDS));
        assertEquals(1, closeReasons.size());
        assertTrue(server.connections().isEmpty());
    }

    @Test
    void broadcast는_접속자_모두에게_보낸다() throws Exception {
        try (Client first = new Client(); Client second = new Client()) {
            first.receive();
            second.receive();
            waitUntil(() -> server.connections().size() == 2);

            server.broadcast(Message.push("NOTICE", Map.of("text", "점검")));

            assertEquals("점검", first.receive().at("/body/text").asText());
            assertEquals("점검", second.receive().at("/body/text").asText());
        }
    }

    @Test
    void 서버를_닫으면_모든_연결이_끊긴다() throws Exception {
        try (Client client = new Client()) {
            client.receive();
            server.close();

            assertNull(client.in.readLine());
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("서버 종료"), closeReasons);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("3초 안에 조건이 맞지 않았습니다.");
            }
            Thread.sleep(10);
        }
    }
}

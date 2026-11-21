package hansung.arcade.domain.game.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.business.GameService.Participant;
import hansung.arcade.domain.game.model.Match;
import hansung.arcade.domain.game.model.MatchPlayer;
import hansung.arcade.domain.game.model.Team;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 소켓으로 GAME_STARTED → LOADED → ROUND_BEGIN → MOVE 중계 → GAME_EXIT·끊김 → GAME_OVER 흐름을 확인한다.
 * 시계는 테스트가 돌리고, 타이머 대신 {@link GameService#tick}을 직접 부른다.
 */
class GameFlowTest {

    private final AtomicLong clock = new AtomicLong(1_000);
    private GameServer server;
    private GameService gameService;

    @BeforeEach
    void startServer() throws IOException {
        GameConnections connections = new GameConnections();
        gameService = new GameService(connections, clock::get, new Random(1));
        MessageDispatcher dispatcher = new MessageDispatcher();
        new GameHandlers(gameService).registerTo(dispatcher);
        server = GameServer.bind(0, dispatcher);
        server.addListener(connections);
        // 끊김 정리를 바로 이 스레드에서 한다
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

        void send(String type, JsonNode body) throws IOException {
            String line = Json.MAPPER.createObjectNode().put("type", type).set("body", body).toString();
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

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** 접속 순서대로 연결 번호. 접속한 뒤 서버 쪽 등록을 기다린다 */
    private List<Long> connectionIds(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (server.connections().size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        return server.connections().stream().map(Connection::id).sorted().toList();
    }

    private Match startMatch(long red, long blue) {
        return gameService.start("camp/camp08", List.of(
                new Participant(1, red, "빨강", "kkukku", Team.RED),
                new Participant(2, blue, "파랑", "random", Team.BLUE)));
    }

    private static JsonNode move(long playerId, double x, double y) {
        return Json.object().put("playerId", playerId).put("x", x).put("y", y).put("facing", "LEFT").put("moving", true);
    }

    private void beginRound(Client red, Client blue) throws IOException {
        red.send("LOADED", Json.object());
        blue.send("LOADED", Json.object());
        assertEquals(3000, red.receive("ROUND_BEGIN").get("body").get("countdownMs").asInt());
        blue.receive("ROUND_BEGIN");
        clock.addAndGet(Match.COUNTDOWN_MS);
        gameService.tick();
    }

    @Test
    void 경기를_열면_모두_GAME_STARTED를_받고_로딩이_끝나면_ROUND_BEGIN을_받는다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));

            JsonNode started = red.receive("GAME_STARTED").get("body");
            assertEquals("camp/camp08", started.get("mapId").asText());
            assertEquals(2, started.get("players").size());
            // random 캐릭터는 실제 캐릭터로 정해져 나간다
            assertTrue(!started.get("players").get(1).get("character").asText().equals("random"));
            blue.receive("GAME_STARTED");

            beginRound(red, blue);
        }
    }

    @Test
    void 경기_중이_아닐_때_LOADED를_보내면_WRONG_STATE_ERROR를_받는다() throws Exception {
        try (Client client = new Client()) {
            client.send("LOADED", Json.object());

            JsonNode error = client.receive("ERROR").get("body");
            assertEquals("WRONG_STATE", error.get("code").asText());
            assertEquals("LOADED", error.get("type").asText());
        }
    }

    @Test
    void MOVE는_다른_연결에만_PLAYER_MOVED로_중계되고_틀린_MOVE는_조용히_버린다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);

            red.send("MOVE", Json.object().put("playerId", 1).put("x", "왼쪽"));
            red.send("MOVE", move(2, 100, 100));
            red.send("MOVE", move(1, 100.5, 60));

            JsonNode moved = blue.receive("PLAYER_MOVED").get("body");
            assertEquals(1, moved.get("playerId").asLong());
            assertEquals(100.5, moved.get("x").asDouble());
            // 빨강은 자기 MOVE를 돌려받지 않고, 틀린 MOVE에 ERROR도 받지 않는다: 다음에 받는 것은 파랑의 PLAYER_MOVED다
            blue.send("MOVE", move(2, 500, 60));
            assertEquals(2, red.receive().get("body").get("playerId").asLong());
        }
    }

    @Test
    void 물풍선을_놓으면_방_전체가_BALLOON_PLACED를_받고_퓨즈가_지나면_BALLOON_EXPLODED를_받는다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            Match match = startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);
            MatchPlayer redPlayer = match.player(1);

            JsonNode place = Json.object().put("playerId", 1)
                    .put("col", redPlayer.cell().col()).put("row", redPlayer.cell().row());
            red.send("BALLOON_PLACE", place);

            JsonNode placed = blue.receive("BALLOON_PLACED").get("body");
            assertEquals(1, placed.get("balloonId").asInt());
            assertEquals(1, red.receive("BALLOON_PLACED").get("body").get("ownerId").asLong());

            clock.addAndGet(Match.FUSE_MS);
            gameService.tick();
            JsonNode exploded = red.receive("BALLOON_EXPLODED").get("body");
            assertEquals(1, exploded.get("balloons").get(0).get("balloonId").asInt());
            assertTrue(exploded.get("items").isArray());
            // 놓은 사람은 자기 칸에 서 있었으므로 갇힌다
            assertEquals(1, red.receive("PLAYER_TRAPPED").get("body").get("playerId").asLong());
        }
    }

    @Test
    void 나가기를_누르면_남은_사람이_PLAYER_LEFT와_GAME_OVER를_받는다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);

            red.send("GAME_EXIT", Json.object());

            JsonNode left = blue.receive("PLAYER_LEFT").get("body");
            assertEquals("EXIT", left.get("reason").asText());
            assertEquals(1, left.get("playerIds").get(0).asLong());
            JsonNode over = blue.receive("GAME_OVER").get("body");
            assertEquals("LAST_TEAM", over.get("reason").asText());
            assertEquals(0, gameService.activeMatches());
            // 나간 연결은 더는 경기에 없다
            red.send("GAME_EXIT", Json.object());
            assertEquals("WRONG_STATE", red.receive("ERROR").get("body").get("code").asText());
        }
    }

    @Test
    void 게임_중에_연결이_끊기면_남은_사람이_DISCONNECT로_PLAYER_LEFT를_받는다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);

            red.close();

            assertEquals("DISCONNECT", blue.receive("PLAYER_LEFT").get("body").get("reason").asText());
            assertEquals("LAST_TEAM", blue.receive("GAME_OVER").get("body").get("reason").asText());
        }
    }
}

package hansung.arcade.domain.game.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.business.GameService.Participant;
import hansung.arcade.domain.game.model.GameMap;
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
 * 실제 소켓으로 GAME_STARTED → LOADED → ROUND_BEGIN → MOVE 중계 → 물풍선·아이템 → GAME_EXIT·끊김 → GAME_OVER 흐름을 확인한다.
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

    /** (col, row) 칸 가운데로 가는 MOVE */
    private static JsonNode moveTo(long playerId, int col, int row) {
        return move(playerId, GameMap.centerX(col), GameMap.centerY(row));
    }

    /** (col, row) 칸 가운데에서 facing 쪽을 보고 멈춰 섰다는 MOVE */
    private static JsonNode stopAt(long playerId, int col, int row, String facing) {
        return Json.object().put("playerId", playerId).put("x", GameMap.centerX(col)).put("y", GameMap.centerY(row))
                .put("facing", facing).put("moving", false);
    }

    private static JsonNode boxPush(long playerId, int col, int row, String direction) {
        return Json.object().put("playerId", playerId).put("col", col).put("row", row).put("direction", direction);
    }

    /**
     * 캠프08에서 빨강이 (1,0)에 물풍선을 놓고 (1,2)로 비켜서서 터뜨린다. (0,0) 블럭이 부서져 고정 드롭 신발(itemId 1)이 나온다.
     * 파랑은 먼저 멀리 (10,10)으로 비켜 둔다. 물줄기가 사라질 때까지 시계를 돌리고 폭발 결과를 돌려준다.
     */
    private JsonNode dropShoeAtCorner(Client red, Client blue) throws IOException {
        blue.send("MOVE", moveTo(2, 10, 10));
        red.receive("PLAYER_MOVED");
        red.send("MOVE", moveTo(1, 1, 1));
        red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 1).put("row", 0));
        red.send("MOVE", moveTo(1, 1, 2));
        blue.receive("BALLOON_PLACED");
        // 비켜선 MOVE까지 서버가 처리했다
        blue.receive("PLAYER_MOVED");

        clock.addAndGet(Match.FUSE_MS);
        gameService.tick();
        JsonNode exploded = red.receive("BALLOON_EXPLODED").get("body");
        blue.receive("BALLOON_EXPLODED");
        clock.addAndGet(Match.FLAME_MS);
        gameService.tick();
        return exploded;
    }

    /** 빨강이 (0,0) 신발 칸으로 가서 줍고, 두 연결이 ITEM_TAKEN을 받을 때까지 기다린다 */
    private void pickUpShoe(Client red, Client blue) throws IOException {
        red.send("MOVE", moveTo(1, 0, 0));
        red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", 1));
        red.receive("ITEM_TAKEN");
        blue.receive("ITEM_TAKEN");
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
    void 블럭에서_나온_아이템을_ITEM_PICKUP으로_주우면_방_전체가_ITEM_TAKEN을_받고_틀리거나_늦은_요청은_조용히_버린다()
            throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);

            JsonNode exploded = dropShoeAtCorner(red, blue);
            assertEquals(1, exploded.get("items").size());
            JsonNode item = exploded.get("items").get(0);
            assertEquals(List.of(1, 0, 0), List.of(item.get("itemId").asInt(), item.get("col").asInt(),
                    item.get("row").asInt()));
            assertEquals("shoe", item.get("item").asText());
            assertEquals(0, exploded.get("removedItems").size());

            red.send("MOVE", moveTo(1, 0, 0));
            red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", "신발"));
            red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", 1));
            red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", 1));

            // 틀린 body에는 ERROR가 없으므로 빨강이 다음에 받는 것은 ITEM_TAKEN이다
            JsonNode taken = red.receive();
            assertEquals("ITEM_TAKEN", taken.get("type").asText());
            JsonNode body = taken.get("body");
            assertEquals(1, body.get("itemId").asInt());
            assertEquals(1, body.get("playerId").asLong());
            assertEquals("shoe", body.get("item").asText());
            assertEquals(1, body.get("stats").get("balloons").asInt());
            assertEquals(1, body.get("stats").get("waterRange").asInt());
            assertEquals(4, body.get("stats").get("speedLevel").asInt());
            assertTrue(body.get("heldItem").isNull());
            assertEquals(0, body.get("heldCount").asInt());
            assertEquals("ITEM_TAKEN", blue.receive("ITEM_TAKEN").get("type").asText());
            // 늦게 온 두 번째 줍기도 조용히 버렸다. 같은 연결의 메시지는 차례대로 처리되므로,
            // 그 뒤에 보낸 물풍선의 BALLOON_PLACED가 두 번째 ITEM_TAKEN 없이 바로 온다
            red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 0).put("row", 0));
            assertEquals("BALLOON_PLACED", red.receive().get("type").asText());
        }
    }

    @Test
    void 아이템을_먹은_사람이_터지면_PLAYER_POPPED_바로_뒤에_ITEM_SCATTERED를_받고_그_다음_GAME_OVER를_받는다()
            throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);
            dropShoeAtCorner(red, blue);
            pickUpShoe(red, blue);

            // 빨강이 선 자리 (0,0)에 물풍선을 놓아 갇히고, 시간이 지나 터진다
            red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 0).put("row", 0));
            red.receive("BALLOON_PLACED");
            clock.addAndGet(Match.FUSE_MS);
            gameService.tick();
            assertEquals(1, red.receive("PLAYER_TRAPPED").get("body").get("playerId").asLong());
            clock.addAndGet(Match.POP_MS);
            gameService.tick();

            for (Client client : List.of(red, blue)) {
                assertEquals(1, client.receive("PLAYER_POPPED").get("body").get("playerId").asLong());
                JsonNode scattered = client.receive();
                assertEquals("ITEM_SCATTERED", scattered.get("type").asText());
                JsonNode body = scattered.get("body");
                assertEquals(List.of(1L, 0L, 0L), List.of(body.get("playerId").asLong(), body.get("col").asLong(),
                        body.get("row").asLong()));
                assertEquals(1, body.get("items").size());
                assertEquals("shoe", body.get("items").get(0).get("item").asText());
                assertEquals(2, body.get("items").get(0).get("itemId").asInt());
                assertEquals("GAME_OVER", client.receive().get("type").asText());
            }
        }
    }

    /** 빨강이 (col, row)에 서서 그 칸에 물풍선을 놓고 (1,0)으로 비켜선 뒤 터뜨린다. 물줄기가 사라질 때까지 시계를 돌린다 */
    private JsonNode explodeFrom(Client red, Client blue, int col, int row) throws IOException {
        red.send("MOVE", moveTo(1, col, row));
        red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", col).put("row", row));
        red.send("MOVE", moveTo(1, 1, 0));
        blue.receive("BALLOON_PLACED");
        // 비켜선 MOVE까지 서버가 처리했다
        blue.receive("PLAYER_MOVED");

        clock.addAndGet(Match.FUSE_MS);
        gameService.tick();
        JsonNode exploded = red.receive("BALLOON_EXPLODED").get("body");
        blue.receive("BALLOON_EXPLODED");
        clock.addAndGet(Match.FLAME_MS);
        gameService.tick();
        return exploded;
    }

    @Test
    void 손에_든_스프링_신발을_ITEM_USE로_쓰면_방_전체가_ITEM_USED를_받고_못_쓰는_요청은_조용히_버린다() throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);
            blue.send("MOVE", moveTo(2, 10, 10));
            red.receive("PLAYER_MOVED");

            // 캠프08 (3,6)·(3,8)은 두 번 맞아야 부서지는 블럭이고 고정 드롭이 스프링 신발이다. 사이 칸 (3,7)에서 두 번 터뜨린다
            explodeFrom(red, blue, 3, 7);
            JsonNode exploded = explodeFrom(red, blue, 3, 7);
            int shoeId = -1;
            for (JsonNode item : exploded.get("items")) {
                assertEquals("spring_shoe", item.get("item").asText());
                if (item.get("col").asInt() == 3 && item.get("row").asInt() == 6) {
                    shoeId = item.get("itemId").asInt();
                }
            }
            assertEquals(2, exploded.get("items").size());

            red.send("MOVE", moveTo(1, 3, 6));
            red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", shoeId));
            JsonNode taken = red.receive("ITEM_TAKEN").get("body");
            assertEquals("spring_shoe", taken.get("heldItem").asText());
            assertEquals(1, taken.get("heldCount").asInt());
            blue.receive("ITEM_TAKEN");

            // 들지 않은 아이템, 모르는 아이템, 빠진 필드, 내려앉을 칸이 막힌 방향(왼쪽 (1,6) 블럭)은 ERROR 없이 버린다
            red.send("ITEM_USE", Json.object().put("playerId", 1).put("item", "needle"));
            red.send("ITEM_USE", Json.object().put("playerId", 1).put("item", "우산"));
            red.send("ITEM_USE", Json.object().put("playerId", 1));
            red.send("ITEM_USE", Json.object().put("playerId", 1).put("item", "spring_shoe"));
            red.send("MOVE", Json.object().put("playerId", 1).put("x", GameMap.centerX(3)).put("y", GameMap.centerY(6))
                    .put("facing", "DOWN").put("moving", false));
            red.send("ITEM_USE", Json.object().put("playerId", 1).put("item", "spring_shoe"));

            JsonNode used = red.receive();
            assertEquals("ITEM_USED", used.get("type").asText());
            for (JsonNode body : List.of(used.get("body"), blue.receive("ITEM_USED").get("body"))) {
                assertEquals(1, body.get("playerId").asLong());
                assertEquals("spring_shoe", body.get("item").asText());
                assertEquals(Match.JUMP_MS, body.get("durationMs").asLong());
                assertEquals(List.of(3, 8), List.of(body.get("col").asInt(), body.get("row").asInt()));
                assertTrue(body.get("heldItem").isNull());
                assertEquals(0, body.get("heldCount").asInt());
            }

            // 내려앉은 (3,8)에도 스프링 신발이 있어 서버가 바로 줍게 한다
            clock.addAndGet(Match.JUMP_MS);
            gameService.tick();
            for (Client client : List.of(red, blue)) {
                JsonNode landed = client.receive("ITEM_TAKEN").get("body");
                assertEquals(1, landed.get("playerId").asLong());
                assertEquals("spring_shoe", landed.get("heldItem").asText());
                assertEquals(1, landed.get("heldCount").asInt());
            }
        }
    }

    @Test
    void 상자를_밀면_방_전체가_BOX_MOVED를_받고_상자를_따라간_고정_드롭이_새_칸에서_나오며_틀린_요청은_조용히_버린다()
            throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);
            // 캠프08 (2,1) 상자에는 고정 드롭 울트라가 있다. 빨강이 위쪽 덤불 (2,0)에서 아래를 보고 멈춘다
            blue.send("MOVE", moveTo(2, 10, 10));
            red.receive("PLAYER_MOVED");
            red.send("MOVE", stopAt(1, 2, 0, "DOWN"));

            red.send("BOX_PUSH", Json.object().put("playerId", 1).put("col", 2).put("row", 1));
            red.send("BOX_PUSH", boxPush(1, 2, 1, "옆"));
            red.send("BOX_PUSH", boxPush(1, 2, 1, "RIGHT"));
            red.send("BOX_PUSH", boxPush(1, 2, 1, "DOWN"));
            // 이미 밀려 간 상자를 한 번 더 미는 늦은 요청
            red.send("BOX_PUSH", boxPush(1, 2, 1, "DOWN"));

            // 틀리거나 밀 수 없는 요청에는 ERROR가 없으므로 빨강이 다음에 받는 것은 BOX_MOVED다
            JsonNode moved = red.receive();
            assertEquals("BOX_MOVED", moved.get("type").asText());
            for (JsonNode body : List.of(moved.get("body"), blue.receive("BOX_MOVED").get("body"))) {
                assertEquals(List.of(2, 1, 2, 2), List.of(body.get("fromCol").asInt(), body.get("fromRow").asInt(),
                        body.get("toCol").asInt(), body.get("toRow").asInt()));
            }

            // 비워진 (2,1)에 물풍선을 놓고 (1,0)으로 비켜선다. 아래 물줄기가 (2,2)로 간 상자를 부수고 울트라가 나온다
            red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 2).put("row", 1));
            red.send("MOVE", moveTo(1, 1, 0));
            // 늦은 두 번째 밀기도 조용히 버렸으므로 다음은 BALLOON_PLACED다
            assertEquals("BALLOON_PLACED", red.receive().get("type").asText());
            blue.receive("BALLOON_PLACED");
            blue.receive("PLAYER_MOVED");
            clock.addAndGet(Match.FUSE_MS);
            gameService.tick();

            JsonNode exploded = red.receive("BALLOON_EXPLODED").get("body");
            List<String> tiles = new ArrayList<>();
            exploded.get("tiles").forEach(tile -> tiles.add(
                    tile.get("col").asInt() + "," + tile.get("row").asInt() + "=" + tile.get("tile").asText()));
            assertTrue(tiles.contains("2,2=."), "부서진 칸: " + tiles);
            List<String> items = new ArrayList<>();
            exploded.get("items").forEach(item -> items.add(
                    item.get("col").asInt() + "," + item.get("row").asInt() + "=" + item.get("item").asText()));
            assertTrue(items.contains("2,2=ultra"), "나온 아이템: " + items);
        }
    }

    /** BALLOON_THROW·BALLOON_KICK body: 물풍선 칸과 방향 */
    private static JsonNode cellAndDirection(long playerId, int col, int row, String direction) {
        return Json.object().put("playerId", playerId).put("col", col).put("row", row).put("direction", direction);
    }

    private static int itemIdOf(JsonNode exploded, String item) {
        for (JsonNode dropped : exploded.get("items")) {
            if (dropped.get("item").asText().equals(item)) {
                return dropped.get("itemId").asInt();
            }
        }
        throw new AssertionError(item + "이(가) 나오지 않았습니다: " + exploded);
    }

    @Test
    void 장갑으로_던지면_BALLOON_THROWN을_신발로_차면_BALLOON_KICKED와_멈춘_뒤_BALLOON_STOPPED를_방_전체가_받고_틀린_요청은_조용히_버린다()
            throws Exception {
        try (Client red = new Client(); Client blue = new Client()) {
            List<Long> ids = connectionIds(2);
            startMatch(ids.get(0), ids.get(1));
            beginRound(red, blue);
            dropShoeAtCorner(red, blue);
            pickUpShoe(red, blue);
            // 캠프08 (0,2) 블럭에는 고정 드롭 장갑이 있다. (1,2)에서 터뜨려 꺼내고 줍는다
            int glove = itemIdOf(explodeFrom(red, blue, 1, 2), "glove");
            red.send("MOVE", moveTo(1, 0, 2));
            red.send("ITEM_PICKUP", Json.object().put("playerId", 1).put("itemId", glove));
            assertEquals("glove", red.receive("ITEM_TAKEN").get("body").get("item").asText());
            blue.receive("ITEM_TAKEN");

            // (0,2)에서 아래를 보고 발밑에 놓은 물풍선을 던진다. 아래로 6칸째 (0,8)은 빈 칸이다
            red.send("MOVE", stopAt(1, 0, 2, "DOWN"));
            red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 0).put("row", 2));
            red.receive("BALLOON_PLACED");
            blue.receive("BALLOON_PLACED");
            red.send("BALLOON_THROW", Json.object().put("playerId", 1).put("col", 0).put("row", 2));
            red.send("BALLOON_THROW", cellAndDirection(1, 0, 2, "옆"));
            red.send("BALLOON_THROW", cellAndDirection(1, 0, 2, "UP"));
            red.send("BALLOON_THROW", cellAndDirection(1, 0, 2, "DOWN"));
            // 이미 날아간 물풍선을 한 번 더 던지는 늦은 요청
            red.send("BALLOON_THROW", cellAndDirection(1, 0, 2, "DOWN"));

            // 틀리거나 던질 수 없는 요청에는 ERROR가 없으므로 빨강이 다음에 받는 것은 BALLOON_THROWN이다
            JsonNode thrown = red.receive();
            assertEquals("BALLOON_THROWN", thrown.get("type").asText());
            for (JsonNode body : List.of(thrown.get("body"), blue.receive("BALLOON_THROWN").get("body"))) {
                assertEquals(1, body.get("playerId").asLong());
                assertEquals(List.of(0, 2, 0, 8), List.of(body.get("col").asInt(), body.get("row").asInt(),
                        body.get("toCol").asInt(), body.get("toRow").asInt()));
                assertEquals("DOWN", body.get("direction").asText());
                assertEquals(360, body.get("flightMs").asLong());
            }
            int thrownId = thrown.get("body").get("balloonId").asInt();

            clock.addAndGet(Match.FUSE_MS);
            gameService.tick();
            JsonNode landed = red.receive("BALLOON_EXPLODED").get("body").get("balloons").get(0);
            blue.receive("BALLOON_EXPLODED");
            assertEquals(List.of(thrownId, 0, 8), List.of(landed.get("balloonId").asInt(), landed.get("col").asInt(),
                    landed.get("row").asInt()));
            clock.addAndGet(Match.FLAME_MS);
            gameService.tick();

            // (1,0)에 놓은 물풍선을 (0,0)에서 오른쪽으로 찬다. 덤불 (2,0)을 지나 석상 (3,0) 앞에 멈춘다
            red.send("MOVE", moveTo(1, 1, 0));
            red.send("BALLOON_PLACE", Json.object().put("playerId", 1).put("col", 1).put("row", 0));
            red.send("MOVE", stopAt(1, 0, 0, "RIGHT"));
            red.receive("BALLOON_PLACED");
            blue.receive("BALLOON_PLACED");
            red.send("BALLOON_KICK", Json.object().put("playerId", 1).put("col", 1).put("row", 0));
            red.send("BALLOON_KICK", cellAndDirection(1, 1, 0, "옆"));
            red.send("BALLOON_KICK", cellAndDirection(1, 1, 0, "DOWN"));
            red.send("BALLOON_KICK", cellAndDirection(1, 1, 0, "RIGHT"));

            JsonNode kicked = red.receive();
            assertEquals("BALLOON_KICKED", kicked.get("type").asText());
            for (JsonNode body : List.of(kicked.get("body"), blue.receive("BALLOON_KICKED").get("body"))) {
                assertEquals(List.of(1, 1, 0), List.of(body.get("playerId").asInt(), body.get("col").asInt(),
                        body.get("row").asInt()));
                assertEquals("RIGHT", body.get("direction").asText());
            }
            int kickedId = kicked.get("body").get("balloonId").asInt();

            // 한 칸 미끄러지는 데 0.1초
            clock.addAndGet(100);
            gameService.tick();
            for (JsonNode body : List.of(red.receive("BALLOON_STOPPED").get("body"),
                    blue.receive("BALLOON_STOPPED").get("body"))) {
                assertEquals(List.of(kickedId, 2, 0), List.of(body.get("balloonId").asInt(), body.get("col").asInt(),
                        body.get("row").asInt()));
            }
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

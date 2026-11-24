package hansung.arcade.domain.game.model;

import hansung.arcade.core.net.Json;
import hansung.arcade.domain.game.model.MatchMessages.ItemUsed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ITEM_USE 판정과 ITEM_USED: 바늘·보호막·스프링 신발 */
class MatchItemUseTest {

    /*
     * 7×3 맵. (4,1) 블럭. 1P(0,1)는 빨강(연결 1), 2P(6,2)는 파랑(연결 2)
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".......",
            ".......",
            ".......",
            "[block]",
            ".......",
            "....B..",
            ".......",
            "[spawn]",
            ".......",
            "1......",
            "......2");

    private static final long RED = 1;
    private static final long BLUE = 2;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long PLAY = Match.COUNTDOWN_MS;

    private final GameMap map = MapLoader.parse("test/use", MAP);

    private Match playing() {
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1))),
                new Random(1));
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    private static void stand(Match match, long conn, long playerId, int col, int row, Facing facing, long now) {
        match.move(conn, playerId, GameMap.centerX(col), GameMap.centerY(row), facing, false, now);
    }

    private static List<String> types(List<MatchEvent> events) {
        return events.stream().map(MatchEvent::type).toList();
    }

    /** 2P가 (1,1)에 물풍선을 놓고 비켜선다. 물줄기 1칸이라 터지면 (0,1)~(2,1)을 덮는다. 터지는 시각을 돌려준다 */
    private static long blueBalloonAtOneOne(Match match, long placedAt) {
        stand(match, CONN_BLUE, BLUE, 1, 2, Facing.UP, placedAt);
        assertEquals(1, match.placeBalloon(CONN_BLUE, BLUE, 1, 1, placedAt).size());
        stand(match, CONN_BLUE, BLUE, 6, 2, Facing.UP, placedAt);
        return placedAt + Match.FUSE_MS;
    }

    @Test
    void 보호막을_쓰면_방_전체에_알리고_그동안_물줄기에_갇히지_않는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        long explodeAt = blueBalloonAtOneOne(match, PLAY);

        List<MatchEvent> used = match.useItem(CONN_RED, RED, "shield", explodeAt - 100);

        assertEquals(List.of(MatchMessages.ITEM_USED), types(used));
        assertEquals(List.of(CONN_RED, CONN_BLUE), used.get(0).recipients());
        assertEquals(new ItemUsed(RED, "shield", Match.SHIELD_MS, null, null, null, 0), used.get(0).body());
        assertNull(match.player(RED).heldItem());

        List<MatchEvent> exploded = match.tick(explodeAt);
        assertFalse(types(exploded).contains(MatchMessages.PLAYER_TRAPPED));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());
    }

    @Test
    void 보호막을_쓰는_중에는_또_쓰지_못하고_손에_남는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        match.player(RED).takeItem(ItemType.SHIELD);

        ItemUsed first = (ItemUsed) match.useItem(CONN_RED, RED, "shield", PLAY).get(0).body();
        assertEquals("shield", first.heldItem());
        assertEquals(1, first.heldCount());

        assertEquals(List.of(), match.useItem(CONN_RED, RED, "shield", PLAY + 100));
        assertEquals(1, match.player(RED).heldCount());
        assertEquals(1, match.useItem(CONN_RED, RED, "shield", PLAY + Match.SHIELD_MS).size());
    }

    @Test
    void 바늘은_갇혔을_때만_쓰고_save_없이_빠져나온다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.NEEDLE);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, "needle", PLAY));

        long explodeAt = blueBalloonAtOneOne(match, PLAY);
        assertTrue(types(match.tick(explodeAt)).contains(MatchMessages.PLAYER_TRAPPED));

        List<MatchEvent> used = match.useItem(CONN_RED, RED, "needle", explodeAt + 100);

        assertEquals(List.of(MatchMessages.ITEM_USED), types(used));
        assertEquals(new ItemUsed(RED, "needle", null, null, null, null, 0), used.get(0).body());
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());
        assertTrue(match.player(RED).isEscaping(explodeAt + 100));
        assertEquals(0, match.player(BLUE).saves());
        assertEquals(0, match.player(RED).saves());
    }

    @Test
    void 스프링_신발은_두_칸_앞이_막혔으면_쓰지_않고_비었으면_내려앉을_칸을_알린다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        stand(match, CONN_RED, RED, 2, 1, Facing.RIGHT, PLAY);
        // 두 칸 앞 (4,1)은 블럭
        assertEquals(List.of(), match.useItem(CONN_RED, RED, "spring_shoe", PLAY));
        assertEquals(1, match.player(RED).heldCount());

        stand(match, CONN_RED, RED, 2, 1, Facing.LEFT, PLAY);
        List<MatchEvent> used = match.useItem(CONN_RED, RED, "spring_shoe", PLAY);

        assertEquals(new ItemUsed(RED, "spring_shoe", Match.JUMP_MS, 0, 1, null, 0), used.get(0).body());
        assertTrue(match.player(RED).isAirborne(PLAY + Match.JUMP_MS - 1));
        assertFalse(match.player(RED).isAirborne(PLAY + Match.JUMP_MS));
    }

    @Test
    void 공중에서는_갇히지_않고_내려앉을_때_물줄기_칸이면_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        stand(match, CONN_RED, RED, 2, 1, Facing.LEFT, PLAY);
        long explodeAt = blueBalloonAtOneOne(match, PLAY);
        long jumpAt = explodeAt - 100;
        assertEquals(1, match.useItem(CONN_RED, RED, "spring_shoe", jumpAt).size());
        // 공중에서는 물풍선을 놓지 못한다
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED, 2, 0, jumpAt + 10));

        assertFalse(types(match.tick(explodeAt)).contains(MatchMessages.PLAYER_TRAPPED));

        // 클라이언트가 내려앉은 자리 (0,1)을 보낸 뒤 점프가 끝난다. 물줄기는 explodeAt + FLAME_MS까지 남는다
        match.player(RED).moveTo(GameMap.centerX(0), GameMap.centerY(1), Facing.LEFT, false);
        List<MatchEvent> landed = match.tick(jumpAt + Match.JUMP_MS);
        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(landed));
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED).state());
    }

    @Test
    void 손에_든_것과_다르거나_남의_플레이어면_버린다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);

        assertEquals(List.of(), match.useItem(CONN_RED, RED, "needle", PLAY));
        assertEquals(List.of(), match.useItem(CONN_BLUE, RED, "shield", PLAY));
        assertEquals(List.of(), match.useItem(CONN_BLUE, BLUE, "shield", PLAY));
        assertEquals(1, match.player(RED).heldCount());
    }

    @Test
    void 값이_없는_필드는_ITEM_USED에_넣지_않는다() throws Exception {
        String needle = Json.MAPPER.writeValueAsString(new ItemUsed(RED, "needle", null, null, null, null, 0));
        assertEquals("{\"playerId\":1,\"item\":\"needle\",\"heldCount\":0}", needle);

        String jump = Json.MAPPER.writeValueAsString(new ItemUsed(RED, "spring_shoe", 400L, 0, 1, "spring_shoe", 2));
        assertEquals("{\"playerId\":1,\"item\":\"spring_shoe\",\"durationMs\":400,\"col\":0,\"row\":1,"
                + "\"heldItem\":\"spring_shoe\",\"heldCount\":2}", jump);
    }
}

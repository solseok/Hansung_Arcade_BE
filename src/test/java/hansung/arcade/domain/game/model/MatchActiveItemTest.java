package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ItemScattered;
import hansung.arcade.domain.game.model.MatchMessages.ItemTaken;
import hansung.arcade.domain.game.model.MatchMessages.ItemUsed;
import hansung.arcade.domain.game.model.MatchMessages.PlayerTrapped;
import hansung.arcade.domain.game.model.MatchMessages.Stats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 액티브 아이템(바늘·보호막·스프링 신발): 블럭에서 나옴, 손에 듦, ITEM_USE 조건과 효과 */
class MatchActiveItemTest {

    /*
     * 9×5 맵. (0,0) 고정 드롭 바늘, (2,0) 고정 드롭 보호막, (4,0) 고정 드롭 물줄기 블럭. (5,2)·(4,4)는 부서지지 않는 동상.
     * 1P(0,2)는 빨강(연결 1), 2P(8,2)는 파랑(연결 2). 고정 드롭 말고는 블럭이 없어 난수를 쓰지 않는다
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".........",
            ".........",
            ".........",
            ".........",
            ".........",
            "[block]",
            "B.B.B....",
            ".........",
            ".....#...",
            ".........",
            "....#....",
            "[spawn]",
            ".........",
            ".........",
            "1.......2",
            ".........",
            ".........",
            "[item]",
            "n.h.f....",
            ".........",
            ".........",
            ".........",
            ".........");

    private static final long RED = 1;
    private static final long BLUE = 2;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long PLAY = Match.COUNTDOWN_MS;
    private static final RandomGenerator NO_RANDOM = () -> {
        throw new AssertionError("이 시험에서는 난수를 쓰지 않아야 합니다.");
    };

    private final GameMap map = MapLoader.parse("test/active-items", MAP);

    /** 두 명으로 경기를 열고 PLAYING까지 진행한다. 지금 시각은 {@link #PLAY} */
    private Match playing(RandomGenerator random) {
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1))),
                random);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    private Match playing() {
        return playing(NO_RANDOM);
    }

    private static double cx(int col) {
        return GameMap.centerX(col);
    }

    private static double cy(int row) {
        return GameMap.centerY(row);
    }

    private static List<String> types(List<MatchEvent> events) {
        return events.stream().map(MatchEvent::type).toList();
    }

    private static <T> T body(List<MatchEvent> events, String type, Class<T> bodyType) {
        return events.stream().filter(event -> event.type().equals(type)).findFirst()
                .map(event -> bodyType.cast(event.body()))
                .orElseThrow(() -> new AssertionError(type + "가 없습니다: " + types(events)));
    }

    private static void moveRed(Match match, int col, int row, Facing facing, long now) {
        assertFalse(match.move(CONN_RED, RED, cx(col), cy(row), facing, false, now).isEmpty());
    }

    /**
     * 빨강이 (1,1)에서 (1,0)에 물풍선을 놓고 (1,2)로 비켜서서 터뜨린다. (0,0)에 바늘(itemId 1), (2,0)에 보호막(itemId 2)이
     * 나온다. 물줄기가 사라진 시각을 돌려준다
     */
    private long dropNeedleAndShield(Match match) {
        moveRed(match, 1, 1, Facing.UP, PLAY);
        assertEquals(1, match.placeBalloon(CONN_RED, RED, 1, 0, PLAY).size());
        moveRed(match, 1, 2, Facing.DOWN, PLAY);
        BalloonExploded exploded = body(match.tick(PLAY + Match.FUSE_MS), MatchMessages.BALLOON_EXPLODED,
                BalloonExploded.class);
        assertEquals(List.of(new DroppedItem(1, 0, 0, "needle"), new DroppedItem(2, 2, 0, "shield")), exploded.items());
        long later = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        match.tick(later);
        return later;
    }

    /** 빨강이 (col, row)에 서서 발밑에 물풍선을 놓고 퓨즈가 지나 갇힌다. 갇힌 시각을 돌려준다 */
    private long trapRed(Match match, int col, int row, long now) {
        moveRed(match, col, row, Facing.DOWN, now);
        assertEquals(1, match.placeBalloon(CONN_RED, RED, col, row, now).size());
        long trappedAt = now + Match.FUSE_MS;
        match.tick(trappedAt);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED).state());
        return trappedAt;
    }

    private static ItemUsed used(List<MatchEvent> events) {
        return body(events, MatchMessages.ITEM_USED, ItemUsed.class);
    }

    // ---------------------------------------------------------------- 줍기

    @Test
    void 액티브를_주우면_ITEM_TAKEN에_heldItem과_개수가_실리고_능력치는_그대로다() {
        Match match = playing();
        long now = dropNeedleAndShield(match);
        moveRed(match, 0, 0, Facing.UP, now);

        List<MatchEvent> events = match.pickUpItem(CONN_RED, RED, 1, now);

        assertEquals(List.of(MatchMessages.ITEM_TAKEN), types(events));
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
        assertEquals(new ItemTaken(1, RED, "needle", new Stats(1, 1, 4), "needle", 1), events.get(0).body());
        // 흩뿌릴 목록에는 넣지 않는다
        assertEquals(List.of(), match.player(RED).takenItems());
    }

    @Test
    void 같은_액티브는_쌓이고_다른_액티브를_주우면_바꿔_든다() {
        Match match = playing();
        long now = dropNeedleAndShield(match);
        match.player(RED).takeItem(ItemType.NEEDLE);

        moveRed(match, 0, 0, Facing.UP, now);
        ItemTaken needle = body(match.pickUpItem(CONN_RED, RED, 1, now), MatchMessages.ITEM_TAKEN, ItemTaken.class);
        moveRed(match, 2, 0, Facing.UP, now);
        ItemTaken shield = body(match.pickUpItem(CONN_RED, RED, 2, now), MatchMessages.ITEM_TAKEN, ItemTaken.class);

        assertEquals(List.of("needle", 2), List.of(needle.heldItem(), needle.heldCount()));
        assertEquals(List.of("shield", 1), List.of(shield.heldItem(), shield.heldCount()));
    }

    @Test
    void 패시브를_주워도_ITEM_TAKEN은_들고_있던_액티브를_그대로_알려_준다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        // 빨강이 (5,1)에서 (5,0)에 물풍선을 놓고 (6,1)로 비켜선다. (4,0) 블럭에서 고정 드롭 물줄기(itemId 1)가 나온다
        moveRed(match, 5, 1, Facing.UP, PLAY);
        assertEquals(1, match.placeBalloon(CONN_RED, RED, 5, 0, PLAY).size());
        moveRed(match, 6, 1, Facing.UP, PLAY);
        BalloonExploded exploded = body(match.tick(PLAY + Match.FUSE_MS), MatchMessages.BALLOON_EXPLODED,
                BalloonExploded.class);
        assertEquals(List.of(new DroppedItem(1, 4, 0, "fluid")), exploded.items());
        long now = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        match.tick(now);
        moveRed(match, 4, 0, Facing.LEFT, now);

        ItemTaken taken = body(match.pickUpItem(CONN_RED, RED, 1, now), MatchMessages.ITEM_TAKEN, ItemTaken.class);

        assertEquals(new ItemTaken(1, RED, "fluid", new Stats(1, 2, 4), "spring_shoe", 2), taken);
    }

    // ---------------------------------------------------------------- 공통 조건

    @Test
    void 손에_든_것과_다르거나_빈손이거나_남의_플레이어면_조용히_버리고_아이템은_그대로다() {
        Match match = playing();
        moveRed(match, 3, 2, Facing.RIGHT, PLAY);

        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY));

        match.player(RED).takeItem(ItemType.NEEDLE);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY));
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.BUBBLE, PLAY));
        assertEquals(List.of(), match.useItem(CONN_RED, RED, null, PLAY));
        match.player(BLUE).takeItem(ItemType.SHIELD);
        assertEquals(List.of(), match.useItem(CONN_RED, BLUE, ItemType.SHIELD, PLAY));

        assertEquals(List.of(ItemType.NEEDLE, 1), List.of(match.player(RED).heldItem(), match.player(RED).heldCount()));
        assertEquals(List.of(ItemType.SHIELD, 1), List.of(match.player(BLUE).heldItem(), match.player(BLUE).heldCount()));
    }

    @Test
    void 경기_시작_전이나_끝난_뒤에는_쓰지_못한다() {
        Match loading = new Match(1, map, List.of(
                new MatchPlayer(RED, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1))),
                NO_RANDOM);
        loading.start(0);
        loading.player(RED).takeItem(ItemType.SHIELD);
        assertEquals(List.of(), loading.useItem(CONN_RED, RED, ItemType.SHIELD, 0));

        Match finished = playing();
        finished.player(RED).takeItem(ItemType.SHIELD);
        finished.leave(CONN_BLUE, "EXIT", false, PLAY);
        assertTrue(finished.isFinished());
        assertEquals(List.of(), finished.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY));
    }

    // ---------------------------------------------------------------- 바늘

    @Test
    void 바늘은_갇혔을_때만_쓰고_구출처럼_빠져나오지만_save는_아무도_얻지_않는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.NEEDLE);
        moveRed(match, 2, 3, Facing.DOWN, PLAY);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.NEEDLE, PLAY));
        assertEquals(1, match.player(RED).heldCount());

        long trappedAt = trapRed(match, 2, 3, PLAY);
        long usedAt = trappedAt + 100;
        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.NEEDLE, usedAt);

        assertEquals(List.of(MatchMessages.ITEM_USED), types(events));
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
        assertEquals(new ItemUsed(RED, "needle", null, null, null, null, 0), events.get(0).body());
        MatchPlayer red = match.player(RED);
        assertEquals(MatchPlayer.State.ALIVE, red.state());
        assertTrue(red.isEscaping(usedAt + Match.ESCAPE_MS - 1));
        assertFalse(red.isEscaping(usedAt + Match.ESCAPE_MS));
        assertNull(red.heldItem());
        assertEquals(0, red.saves() + match.player(BLUE).saves());
        // 갇힘 시간이 지나도 터지지 않는다
        assertEquals(List.of(), types(match.tick(trappedAt + Match.POP_MS)));
        assertEquals(MatchPlayer.State.ALIVE, red.state());
    }

    @Test
    void 갇힘_시간이_다_됐으면_타이머보다_먼저_와도_바늘을_쓰지_못한다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.NEEDLE);
        long trappedAt = trapRed(match, 2, 3, PLAY);

        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.NEEDLE, trappedAt + Match.POP_MS));

        assertEquals(MatchMessages.PLAYER_POPPED, match.tick(trappedAt + Match.POP_MS).get(0).type());
    }

    @Test
    void 갇힌_채_아이템_칸에_서_있다가_바늘을_쓰면_ITEM_USED_바로_뒤에_그_아이템을_줍는다() {
        Match match = playing();
        long now = dropNeedleAndShield(match);
        match.player(RED).takeItem(ItemType.NEEDLE);
        long trappedAt = trapRed(match, 0, 2, now);
        // 물줄기가 사라진 뒤 갇힌 채 (2,0) 보호막 칸으로 걸어간다. 그때 보낸 ITEM_PICKUP은 버려진다
        long walkedAt = trappedAt + Match.FLAME_MS;
        match.tick(walkedAt);
        match.move(CONN_RED, RED, cx(2), cy(0), Facing.UP, true, walkedAt);
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED, 2, walkedAt));

        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.NEEDLE, walkedAt + 50);

        assertEquals(List.of(MatchMessages.ITEM_USED, MatchMessages.ITEM_TAKEN), types(events));
        assertEquals(new ItemTaken(2, RED, "shield", new Stats(1, 1, 4), "shield", 1), events.get(1).body());
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(1).recipients());
    }

    // ---------------------------------------------------------------- 보호막

    @Test
    void 보호막을_쓰면_그동안_물줄기에_갇히지_않고_끝난_순간_물줄기_위면_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        moveRed(match, 2, 3, Facing.DOWN, PLAY);

        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY);

        assertEquals(List.of(MatchMessages.ITEM_USED), types(events));
        assertEquals(new ItemUsed(RED, "shield", Match.SHIELD_MS, null, null, null, 0), events.get(0).body());
        assertEquals(3_000, Match.SHIELD_MS);
        // 보호막이 끝나기 200ms 전에 발밑 물풍선이 터진다. 물줄기는 보호막이 끝난 뒤 250ms 더 남는다
        long shieldEnds = PLAY + Match.SHIELD_MS;
        long placedAt = shieldEnds - 200 - Match.FUSE_MS;
        match.placeBalloon(CONN_RED, RED, 2, 3, placedAt);
        List<MatchEvent> exploded = match.tick(shieldEnds - 200);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(exploded));
        // 보호막 중에는 같은 자리에서 움직여도 갇히지 않는다
        moveRed(match, 2, 3, Facing.UP, shieldEnds - 100);
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());

        List<MatchEvent> ended = match.tick(shieldEnds);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(ended));
        assertEquals(RED, body(ended, MatchMessages.PLAYER_TRAPPED, PlayerTrapped.class).playerId());
    }

    @Test
    void 보호막이_끝난_뒤에_터진_물줄기에는_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        moveRed(match, 2, 3, Facing.DOWN, PLAY);
        match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY);
        match.tick(PLAY + Match.SHIELD_MS);

        long placedAt = PLAY + Match.SHIELD_MS;
        match.placeBalloon(CONN_RED, RED, 2, 3, placedAt);
        List<MatchEvent> events = match.tick(placedAt + Match.FUSE_MS);

        assertTrue(types(events).contains(MatchMessages.PLAYER_TRAPPED));
    }

    @Test
    void 보호막은_갇혔거나_빠져나오는_중이거나_이미_쓰는_중이면_쓰지_못한다() {
        Match match = playing();
        MatchPlayer red = match.player(RED);
        long trappedAt = trapRed(match, 2, 3, PLAY);
        red.takeItem(ItemType.SHIELD);
        red.takeItem(ItemType.SHIELD);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SHIELD, trappedAt + 10));

        // 구해 줄 같은 편이 없으므로 직접 풀어 빠져나오는 중으로 만든다
        red.rescue(trappedAt + 10 + Match.ESCAPE_MS);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SHIELD, trappedAt + 20));

        long free = trappedAt + 10 + Match.ESCAPE_MS;
        assertEquals(1, match.useItem(CONN_RED, RED, ItemType.SHIELD, free).size());
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SHIELD, free + 100));
        assertEquals(1, red.heldCount());
        assertTrue(red.isShielded(free + Match.SHIELD_MS - 1));
        assertFalse(red.isShielded(free + Match.SHIELD_MS));
    }

    @Test
    void 보호막이_끝난_뒤_타이머가_늦어_그사이_물줄기가_사라졌어도_끝난_순간의_물줄기로_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        moveRed(match, 2, 3, Facing.DOWN, PLAY);
        match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY);
        // 보호막이 끝나기 400ms 전에 발밑 물풍선이 터진다. 물줄기는 보호막이 끝나고 50ms 뒤 사라진다
        long shieldEnds = PLAY + Match.SHIELD_MS;
        long explodedAt = shieldEnds - 400;
        match.placeBalloon(CONN_RED, RED, 2, 3, explodedAt - Match.FUSE_MS);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(match.tick(explodedAt)));

        List<MatchEvent> late = match.tick(explodedAt + Match.FLAME_MS + 10);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(late));
    }

    @Test
    void 보호막이_끝난_순간_타이머보다_먼저_다시_쓰면_먼저_갇히고_다시_켜지지_않는다() {
        Match match = playing();
        MatchPlayer red = match.player(RED);
        red.takeItem(ItemType.SHIELD);
        red.takeItem(ItemType.SHIELD);
        moveRed(match, 2, 3, Facing.DOWN, PLAY);
        match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY);
        long shieldEnds = PLAY + Match.SHIELD_MS;
        match.placeBalloon(CONN_RED, RED, 2, 3, shieldEnds - 200 - Match.FUSE_MS);
        match.tick(shieldEnds - 200);

        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.SHIELD, shieldEnds);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(1, red.heldCount());
        assertFalse(red.isShielded(shieldEnds));
    }

    @Test
    void 보호막을_쓰는_중에도_갇힌_상대에게_닿으면_터뜨린다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SHIELD);
        // 파랑이 제자리 (8,2)에 물풍선을 놓아 갇힌다
        match.placeBalloon(CONN_BLUE, BLUE, 8, 2, PLAY);
        moveRed(match, 6, 3, Facing.RIGHT, PLAY);
        match.useItem(CONN_RED, RED, ItemType.SHIELD, PLAY);
        match.tick(PLAY + Match.FUSE_MS);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(BLUE).state());

        // 물줄기가 남은 (8,2)에 들어가도 보호막이라 갇히지 않고, 갇힌 파랑에게 닿아 터뜨린다
        List<MatchEvent> events = match.move(CONN_RED, RED, cx(8), cy(2), Facing.RIGHT, true,
                PLAY + Match.FUSE_MS + 100);

        assertTrue(types(events).contains(MatchMessages.PLAYER_POPPED));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());
        assertEquals(1, match.player(RED).kills());
    }

    // ---------------------------------------------------------------- 스프링 신발

    @Test
    void 스프링_신발은_바라보는_쪽_두_칸_앞에_내려앉고_바로_앞_막힌_칸은_넘는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        // 바로 앞 (5,2)는 동상이다
        moveRed(match, 4, 2, Facing.RIGHT, PLAY);

        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY);

        assertEquals(List.of(MatchMessages.ITEM_USED), types(events));
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
        assertEquals(new ItemUsed(RED, "spring_shoe", Match.JUMP_MS, 6, 2, null, 0), events.get(0).body());
        assertEquals(400, Match.JUMP_MS);
        // 서버가 위치를 내려앉을 칸 가운데로 옮긴다
        MatchPlayer red = match.player(RED);
        assertEquals(List.of(cx(6), cy(2)), List.of(red.x(), red.y()));
        assertTrue(red.isAirborne(PLAY + Match.JUMP_MS - 1));
        assertFalse(red.isAirborne(PLAY + Match.JUMP_MS));
    }

    @Test
    void 공중에서는_MOVE_물풍선_줍기_아이템을_받지_않고_내려앉으면_다시_움직인다() {
        Match match = playing();
        MatchPlayer red = match.player(RED);
        red.takeItem(ItemType.SPRING_SHOE);
        red.takeItem(ItemType.SPRING_SHOE);
        moveRed(match, 4, 2, Facing.RIGHT, PLAY);
        // 둘 들고 하나를 쓰면 ITEM_USED가 손에 남은 것을 알려 준다
        assertEquals(new ItemUsed(RED, "spring_shoe", Match.JUMP_MS, 6, 2, "spring_shoe", 1),
                used(match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY)));
        long midAir = PLAY + Match.JUMP_MS / 2;

        assertEquals(List.of(), match.move(CONN_RED, RED, cx(7), cy(2), Facing.RIGHT, true, midAir));
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED, 6, 2, midAir));
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, midAir));
        assertEquals(List.of(cx(6), cy(2)), List.of(red.x(), red.y()));
        assertEquals(1, red.heldCount());

        match.tick(PLAY + Match.JUMP_MS);
        assertEquals(List.of(MatchMessages.PLAYER_MOVED),
                types(match.move(CONN_RED, RED, cx(7), cy(2), Facing.RIGHT, true, PLAY + Match.JUMP_MS)));
    }

    /**
     * 파랑이 (7,2)에 물풍선을 놓고 비켜선다. PLAY + FUSE_MS에 터져 (6,2)까지 덮고 FLAME_MS 동안 남는다.
     * 그 100ms 전에 빨강이 (4,2)에서 (6,2)로 뛰고, 공중이라 폭발에 갇히지 않는다. 내려앉는 시각을 돌려준다
     */
    private long jumpIntoLingeringFlame(Match match) {
        match.move(CONN_BLUE, BLUE, cx(7), cy(2), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_BLUE, BLUE, 7, 2, PLAY);
        match.move(CONN_BLUE, BLUE, cx(8), cy(4), Facing.LEFT, false, PLAY);
        long jumpedAt = PLAY + Match.FUSE_MS - 100;
        moveRed(match, 4, 2, Facing.RIGHT, jumpedAt);
        assertEquals(1, match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, jumpedAt).size());

        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(match.tick(PLAY + Match.FUSE_MS)));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());
        return jumpedAt + Match.JUMP_MS;
    }

    @Test
    void 공중에서는_물줄기에_맞지_않고_물줄기가_남은_칸에_내려앉으면_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);

        List<MatchEvent> landed = match.tick(landsAt);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(landed));
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED).state());
    }

    @Test
    void 물줄기가_남은_칸을_뛰어넘어_마른_칸에_내려앉으면_갇히지_않는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        // 파랑이 (4,2)에 물풍선을 놓아 터뜨린다. 물줄기는 (4,2)·(3,2)·(4,1)·(4,3)을 덮는다 (오른쪽 (5,2)는 동상)
        match.move(CONN_BLUE, BLUE, cx(4), cy(2), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_BLUE, BLUE, 4, 2, PLAY);
        match.move(CONN_BLUE, BLUE, cx(8), cy(4), Facing.LEFT, false, PLAY);
        match.tick(PLAY + Match.FUSE_MS);
        // 빨강은 마른 (3,3)에서 위로 뛰어 물줄기가 남은 (3,2)를 넘고 마른 (3,1)에 내려앉는다
        long jumpedAt = PLAY + Match.FUSE_MS + 50;
        moveRed(match, 3, 3, Facing.UP, jumpedAt);
        assertEquals(1, match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, jumpedAt).size());

        List<MatchEvent> landed = match.tick(jumpedAt + Match.JUMP_MS);

        assertEquals(List.of(), types(landed));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED).state());
        assertEquals(List.of(cx(3), cy(1)), List.of(match.player(RED).x(), match.player(RED).y()));
    }

    @Test
    void 타이머보다_MOVE가_먼저_오면_먼저_내려앉아_갇히고_나서_옮긴다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);

        List<MatchEvent> events = match.move(CONN_RED, RED, cx(6), cy(3), Facing.DOWN, true, landsAt);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED, MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED).state());
    }

    @Test
    void 타이머보다_물풍선이_먼저_오면_먼저_내려앉아_갇히고_물풍선은_버린다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);

        List<MatchEvent> events = match.placeBalloon(CONN_RED, RED, 6, 3, landsAt);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(events));
    }

    @Test
    void 타이머보다_줍기가_먼저_오면_먼저_내려앉아_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);

        List<MatchEvent> events = match.pickUpItem(CONN_RED, RED, 1, landsAt);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(events));
    }

    @Test
    void 타이머보다_다시_뛰기가_먼저_오면_먼저_내려앉아_갇히고_뛰지_않는다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);

        List<MatchEvent> events = match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, landsAt);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(1, match.player(RED).heldCount());
        assertEquals(List.of(cx(6), cy(2)), List.of(match.player(RED).x(), match.player(RED).y()));
    }

    @Test
    void 내려앉은_뒤_타이머가_늦어_그사이_물줄기가_사라졌어도_내려앉은_순간의_물줄기로_갇힌다() {
        Match match = playing();
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        long landsAt = jumpIntoLingeringFlame(match);
        // 물줄기는 landsAt + 350에 사라진다. 타이머가 그 뒤에야 돈다
        long flameUntil = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        assertTrue(flameUntil > landsAt);

        List<MatchEvent> late = match.tick(flameUntil + 10);

        assertEquals(List.of(MatchMessages.PLAYER_TRAPPED), types(late));
    }

    @Test
    void 같은_타이머에서_내려앉기와_폭발이_겹치면_폭발이_먼저_아이템을_지우고_가둔다() {
        // 클라이언트 한 틱의 차례(내려앉기 → 폭발·젖은 칸 아이템 지우기 → 가두기 → 줍기)와 같다
        Match match = playing();
        long now = dropNeedleAndShield(match);
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        // 파랑이 (2,1)에 물풍선을 놓는다. 터지면 (2,0) 보호막 칸까지 덮는다
        match.move(CONN_BLUE, BLUE, cx(2), cy(1), Facing.LEFT, false, now);
        match.placeBalloon(CONN_BLUE, BLUE, 2, 1, now);
        match.move(CONN_BLUE, BLUE, cx(8), cy(4), Facing.LEFT, false, now);
        // 빨강은 터지기 300ms 전에 (2,2)에서 물풍선을 넘어 (2,0)으로 뛰고, 내려앉기는 터진 100ms 뒤다
        long jumpedAt = now + Match.FUSE_MS - 300;
        moveRed(match, 2, 2, Facing.UP, jumpedAt);
        assertEquals(1, match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, jumpedAt).size());

        // 타이머가 늦어 폭발과 내려앉기를 한 번에 처리한다
        List<MatchEvent> events = match.tick(jumpedAt + Match.JUMP_MS);

        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(List.of(2), body(events, MatchMessages.BALLOON_EXPLODED, BalloonExploded.class).removedItems());
        assertNull(match.player(RED).heldItem());
    }

    @Test
    void 내려앉은_칸에_아이템이_있으면_바로_줍는다() {
        Match match = playing();
        long now = dropNeedleAndShield(match);
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        moveRed(match, 2, 2, Facing.UP, now);
        assertEquals(new ItemUsed(RED, "spring_shoe", Match.JUMP_MS, 2, 0, null, 0),
                used(match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, now)));
        // 공중에서는 줍지 못한다
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED, 2, now + 100));

        List<MatchEvent> landed = match.tick(now + Match.JUMP_MS);

        assertEquals(List.of(MatchMessages.ITEM_TAKEN), types(landed));
        assertEquals(new ItemTaken(2, RED, "shield", new Stats(1, 1, 4), "shield", 1), landed.get(0).body());
    }

    @Test
    void 타이머보다_MOVE가_먼저_오면_내려앉은_칸의_아이템을_먼저_줍고_옮긴다() {
        Match match = playing();
        long now = dropNeedleAndShield(match);
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        moveRed(match, 2, 2, Facing.UP, now);
        match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, now);

        List<MatchEvent> events = match.move(CONN_RED, RED, cx(3), cy(0), Facing.RIGHT, true, now + Match.JUMP_MS);

        assertEquals(List.of(MatchMessages.ITEM_TAKEN, MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(2, body(events, MatchMessages.ITEM_TAKEN, ItemTaken.class).itemId());
        // 타이머가 다시 내려앉히지 않는다
        assertEquals(List.of(), match.tick(now + Match.JUMP_MS));
        assertEquals(List.of(cx(3), cy(0)), List.of(match.player(RED).x(), match.player(RED).y()));
    }

    @Test
    void 내려앉을_칸이_맵_밖이거나_막혔거나_물풍선이_있으면_쓰지_않는다() {
        Match match = playing();
        MatchPlayer red = match.player(RED);
        red.takeItem(ItemType.SPRING_SHOE);

        // 맵 밖
        moveRed(match, 1, 2, Facing.LEFT, PLAY);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY));
        // (4,4)는 동상
        moveRed(match, 4, 2, Facing.DOWN, PLAY);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY));
        // (6,2)에 파랑의 물풍선
        match.move(CONN_BLUE, BLUE, cx(6), cy(2), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_BLUE, BLUE, 6, 2, PLAY);
        moveRed(match, 4, 2, Facing.RIGHT, PLAY);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY));

        assertEquals(List.of(ItemType.SPRING_SHOE, 1), List.of(red.heldItem(), red.heldCount()));
        assertEquals(List.of(cx(4), cy(2)), List.of(red.x(), red.y()));
        // (3,2)에서 위로는 (3,0) 바닥에 내려앉는다
        moveRed(match, 3, 2, Facing.UP, PLAY);
        assertEquals(new ItemUsed(RED, "spring_shoe", Match.JUMP_MS, 3, 0, null, 0),
                used(match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY)));
    }

    @Test
    void 갇혔거나_빠져나오는_중에는_스프링_신발을_쓰지_못한다() {
        Match match = playing();
        MatchPlayer red = match.player(RED);
        red.takeItem(ItemType.SPRING_SHOE);
        long trappedAt = trapRed(match, 2, 3, PLAY);
        // 내려앉을 (2,1)은 비어 있으므로 못 쓰는 까닭은 상태뿐이다
        moveRed(match, 2, 3, Facing.UP, trappedAt + 5);

        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, trappedAt + 10));
        red.rescue(trappedAt + 10 + Match.ESCAPE_MS);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, trappedAt + 20));

        assertEquals(1, match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, trappedAt + 10 + Match.ESCAPE_MS).size());
    }

    @Test
    void 공중에_있는_사람은_갇힌_사람에게_닿지_않는다() {
        Match match = playing();
        match.player(BLUE).takeItem(ItemType.SPRING_SHOE);
        long trappedAt = trapRed(match, 2, 3, PLAY);
        long flameGone = trappedAt + Match.FLAME_MS;
        match.tick(flameGone);
        // 파랑이 (4,3)에서 왼쪽으로 뛰어 빨강이 갇힌 (2,3)에 내려앉는다
        match.move(CONN_BLUE, BLUE, cx(4), cy(3), Facing.LEFT, false, flameGone);
        assertEquals(1, match.useItem(CONN_BLUE, BLUE, ItemType.SPRING_SHOE, flameGone).size());

        assertEquals(List.of(), types(match.tick(flameGone + Match.JUMP_MS - 1)));
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED).state());

        assertTrue(types(match.tick(flameGone + Match.JUMP_MS)).contains(MatchMessages.PLAYER_POPPED));
    }

    // ---------------------------------------------------------------- 터질 때

    @Test
    void 터지면_손에_든_액티브는_흩뿌리지_않고_사라진다() {
        Match match = playing(new Random(7));
        MatchPlayer red = match.player(RED);
        red.takeItem(ItemType.FLUID);
        red.takeItem(ItemType.NEEDLE);
        long trappedAt = trapRed(match, 2, 3, PLAY);

        List<MatchEvent> events = match.tick(trappedAt + Match.POP_MS);

        ItemScattered scattered = body(events, MatchMessages.ITEM_SCATTERED, ItemScattered.class);
        assertEquals(List.of("fluid"), scattered.items().stream().map(DroppedItem::item).toList());
        assertNull(red.heldItem());
        assertEquals(0, red.heldCount());
    }
}

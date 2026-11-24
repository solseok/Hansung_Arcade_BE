package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BalloonPlaced;
import hansung.arcade.domain.game.model.MatchMessages.ChangedTile;
import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ItemTaken;
import hansung.arcade.domain.game.model.MatchMessages.Stats;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 블럭에서 아이템이 나오는 것, 물줄기에 사라지는 것, ITEM_PICKUP 판정 */
class MatchItemTest {

    /*
     * 7×3 맵. 첫 줄에 블럭: (0,0) 고정 드롭 물줄기, (2,0) 고정 드롭 스프링 신발(액티브),
     * (4,0) 고정 드롭 없음, (6,0) 두 번 맞아야 부서지는 단단한 블럭.
     * 1P(0,2)·3P(6,2)는 빨강(연결 1), 2P(2,2)는 파랑(연결 2)
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".......",
            ".......",
            ".......",
            "[block]",
            "B.B.B.b",
            ".......",
            ".......",
            "[spawn]",
            ".......",
            ".......",
            "1.2...3",
            "[item]",
            "f.j....",
            ".......",
            ".......");

    private static final long RED_1 = 1;
    private static final long BLUE = 2;
    private static final long RED_3 = 3;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long PLAY = Match.COUNTDOWN_MS;
    /** 고정 드롭만 나오는 경우 난수를 쓰면 안 된다 */
    private static final RandomGenerator NO_RANDOM = () -> {
        throw new AssertionError("고정 드롭에는 난수를 쓰지 않아야 합니다.");
    };

    private final GameMap map = MapLoader.parse("test/items", MAP);

    /** 세 명으로 경기를 열고 PLAYING까지 진행한다. 지금 시각은 {@link #PLAY} */
    private Match playing(RandomGenerator random) {
        return playing(random, true);
    }

    /** @param withThird 3P까지 세 명이면 true, 1P·2P 두 명이면 false */
    private Match playing(RandomGenerator random, boolean withThird) {
        List<MatchPlayer> players = new ArrayList<>(List.of(
                new MatchPlayer(RED_1, CONN_RED, "빨강하나", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1))));
        if (withThird) {
            players.add(new MatchPlayer(RED_3, CONN_RED, "빨강셋", Mascot.of("kkukku"), Team.RED, 3, map.spawns().get(2)));
        }
        Match match = new Match(1, map, players, random);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
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

    /** 1P가 (col, 1)에서 (col, 0)에 물풍선을 놓고 (col, 2)로 비켜선 뒤, 퓨즈가 지나 터진 결과. 물줄기 1칸이라 1P는 안 맞는다 */
    private BalloonExploded explodeAt(Match match, int col, long placedAt) {
        match.move(CONN_RED, RED_1, cx(col), cy(1), Facing.UP, false, placedAt);
        assertEquals(1, match.placeBalloon(CONN_RED, RED_1, col, 0, placedAt).size());
        match.move(CONN_RED, RED_1, cx(col), cy(2), Facing.DOWN, false, placedAt);
        return body(match.tick(placedAt + Match.FUSE_MS), MatchMessages.BALLOON_EXPLODED, BalloonExploded.class);
    }

    /**
     * (1,0)을 터뜨려 (0,0)에 고정 드롭 물줄기(itemId 1)를 떨어뜨리고, 물줄기가 사라진 시각을 돌려준다.
     * (2,0)에는 고정 드롭 스프링 신발(itemId 2)도 함께 나온다
     */
    private long dropFluidAtCorner(Match match) {
        explodeAt(match, 1, PLAY);
        long later = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        match.tick(later);
        return later;
    }

    @Test
    void 블럭이_다_부서지면_패시브든_액티브든_고정_드롭이_나온다() {
        Match match = playing(NO_RANDOM);

        BalloonExploded exploded = explodeAt(match, 1, PLAY);

        assertEquals(List.of(new ChangedTile(0, 0, "."), new ChangedTile(2, 0, ".")), exploded.tiles());
        assertEquals(List.of(new DroppedItem(1, 0, 0, "fluid"), new DroppedItem(2, 2, 0, "spring_shoe")),
                exploded.items());
        assertEquals(List.of(), exploded.removedItems());
        // 한 번 떨어뜨린 고정 드롭은 비운다
        assertEquals(Optional.empty(), map.fixedDrop(0, 0));
        assertEquals(Optional.empty(), map.fixedDrop(2, 0));
    }

    @Test
    void 고정_드롭이_없는_블럭은_클라이언트와_같은_비율표로_정한다() {
        // (5,0)이 터지면 (4,0)은 부서지고 (6,0) 단단한 블럭은 한 단계 약해진다. 난수는 (4,0)에만 쓴다: 나옴, 롤러 구간
        ScriptedRandom random = new ScriptedRandom(0.1, 0.5);
        Match match = playing(random);

        BalloonExploded exploded = explodeAt(match, 5, PLAY);

        assertEquals(List.of(new ChangedTile(4, 0, "."), new ChangedTile(6, 0, "a")), exploded.tiles());
        assertEquals(List.of(new DroppedItem(1, 4, 0, "roller_shoe")), exploded.items());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 블럭에서_아이템이_나올_확률은_경기를_열_때의_인원으로_센다() {
        // 0.36은 2명(33%)이면 안 나오고, 3명(37%, 1P·3P는 한 연결)이면 나온다. 둘째 난수 0.5는 롤러 구간
        ScriptedRandom threeRandom = new ScriptedRandom(0.36, 0.5);
        BalloonExploded three = explodeAt(playing(threeRandom, true), 5, PLAY);
        assertEquals(List.of(new DroppedItem(1, 4, 0, "roller_shoe")), three.items());
        assertTrue(threeRandom.isUsedUp());

        ScriptedRandom twoRandom = new ScriptedRandom(0.36);
        BalloonExploded two = explodeAt(playing(twoRandom, false), 5, PLAY);
        assertEquals(List.of(), two.items());
        assertTrue(twoRandom.isUsedUp());
    }

    @Test
    void 경기_중에_누가_나가도_확률은_경기를_열_때의_인원으로_센다() {
        // 3P를 다른 연결에 두고 나가게 한다. 남은 사람은 두 명이지만 확률은 3명(37%) 그대로라 0.36이면 나온다
        long connThird = 103;
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED_1, CONN_RED, "빨강하나", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED_3, connThird, "빨강셋", Mascot.of("kkukku"), Team.RED, 3, map.spawns().get(2))),
                new ScriptedRandom(0.36, 0.5));
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.loaded(connThird, 0);
        match.tick(PLAY);
        match.leave(connThird, "EXIT", false, PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());

        BalloonExploded exploded = explodeAt(match, 5, PLAY);

        assertEquals(List.of(new DroppedItem(1, 4, 0, "roller_shoe")), exploded.items());
    }

    @Test
    void 단단한_블럭은_다_부서질_때만_아이템을_정한다() {
        // 첫 폭발: (4,0)은 안 나옴(0.5). (6,0)은 b→a라 정하지 않는다. 두 번째 폭발: (6,0)이 부서져 물풍선 구간(0.0, 0.0)
        Match match = playing(new ScriptedRandom(0.5, 0.0, 0.0));

        BalloonExploded first = explodeAt(match, 5, PLAY);
        BalloonExploded second = explodeAt(match, 5, PLAY + Match.FUSE_MS + Match.FLAME_MS);

        assertEquals(List.of(), first.items());
        assertEquals(List.of(new ChangedTile(6, 0, ".")), second.tiles());
        assertEquals(List.of(new DroppedItem(1, 6, 0, "bubble")), second.items());
    }

    @Test
    void 비율표에서_액티브가_나오면_그대로_놓는다() {
        // 나옴(0.0), 마지막 구간(0.99) = 스프링 신발
        Match match = playing(new ScriptedRandom(0.0, 0.99));

        BalloonExploded exploded = explodeAt(match, 5, PLAY);

        assertEquals(new ChangedTile(4, 0, "."), exploded.tiles().get(0));
        assertEquals(List.of(new DroppedItem(1, 4, 0, "spring_shoe")), exploded.items());
    }

    @Test
    void 물줄기가_덮은_칸의_아이템은_removedItems로_사라진다() {
        Match match = playing(NO_RANDOM);
        long later = dropFluidAtCorner(match);

        // 다시 (1,0)을 터뜨리면 이제 바닥인 (0,0)·(2,0)까지 물줄기가 들어간다
        BalloonExploded exploded = explodeAt(match, 1, later);

        assertEquals(List.of(1, 2), exploded.removedItems());
        assertEquals(List.of(), exploded.items());
        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, false, later + Match.FUSE_MS + Match.FLAME_MS);
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_1, 1, later + Match.FUSE_MS + Match.FLAME_MS));
    }

    @Test
    void 아이템_칸에_선_사람이_주우면_방_전체에_ITEM_TAKEN을_보내고_능력치가_오른다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        match.move(CONN_RED, RED_1, cx(0) + 15, cy(0) - 15, Facing.UP, true, now);

        List<MatchEvent> events = match.pickUpItem(CONN_RED, RED_1, 1, now);

        assertEquals(List.of(MatchMessages.ITEM_TAKEN), types(events));
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
        assertEquals(new ItemTaken(1, RED_1, "fluid", new Stats(1, 2, 4), null, 0), events.get(0).body());
        // 오른 물줄기 길이는 다음에 놓는 물풍선부터 쓴다
        BalloonPlaced placed = body(match.placeBalloon(CONN_RED, RED_1, 0, 0, now), MatchMessages.BALLOON_PLACED,
                BalloonPlaced.class);
        assertEquals(2, placed.range());
    }

    @Test
    void 마지막_MOVE_칸이_아니거나_없는_아이템이거나_남의_플레이어면_조용히_버린다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);

        match.move(CONN_RED, RED_1, cx(1), cy(0), Facing.LEFT, true, now);
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_1, 1, now));

        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.LEFT, true, now);
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_1, 99, now));
        assertEquals(List.of(), match.pickUpItem(CONN_BLUE, RED_1, 1, now));
        assertEquals(1, match.pickUpItem(CONN_RED, RED_1, 1, now).size());
    }

    @Test
    void 갇힌_사람은_아이템_칸에_있어도_줍지_못한다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        // 1P가 (0,2)에 물풍선을 놓고 그 자리에서 갇힌다. 갇힌 채로 (0,0) 아이템 칸까지 걸어간다
        match.move(CONN_RED, RED_1, cx(0), cy(2), Facing.UP, false, now);
        match.placeBalloon(CONN_RED, RED_1, 0, 2, now);
        long trappedAt = now + Match.FUSE_MS;
        match.tick(trappedAt);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());
        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, true, trappedAt + 100);

        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_1, 1, trappedAt + 100));
    }

    @Test
    void 갇힌_채_아이템_칸에_서_있다가_구출되면_PLAYER_RESCUED_바로_뒤에_그_아이템을_줍는다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        // 1P는 (0,2)에서 갇힌 채 (0,0) 아이템 칸까지 걸어간다. 그때 보낸 ITEM_PICKUP은 버려진다
        match.move(CONN_RED, RED_1, cx(0), cy(2), Facing.UP, false, now);
        match.placeBalloon(CONN_RED, RED_1, 0, 2, now);
        long trappedAt = now + Match.FUSE_MS;
        match.tick(trappedAt);
        long rescuedAt = trappedAt + Match.FLAME_MS;
        match.tick(rescuedAt);
        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, true, rescuedAt);
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_1, 1, rescuedAt));

        // 3P가 닿아 구한다. 클라이언트는 칸에 새로 들어서지 않아 다시 보내지 않으므로 서버가 바로 줍게 한다
        List<MatchEvent> events = match.move(CONN_RED, RED_3, cx(0) + 10, cy(0), Facing.LEFT, true, rescuedAt + 50);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED, MatchMessages.PLAYER_RESCUED, MatchMessages.ITEM_TAKEN),
                types(events));
        assertEquals(new ItemTaken(1, RED_1, "fluid", new Stats(1, 2, 4), null, 0), events.get(2).body());
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(2).recipients());
        assertEquals(List.of(ItemType.FLUID), match.player(RED_1).takenItems());
        // 맵에서도 치웠다
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED_3, 1, rescuedAt + 50));
    }

    @Test
    void 구출된_자리에_아이템이_없으면_ITEM_TAKEN을_보내지_않는다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        match.move(CONN_RED, RED_1, cx(0), cy(2), Facing.UP, false, now);
        match.placeBalloon(CONN_RED, RED_1, 0, 2, now);
        long trappedAt = now + Match.FUSE_MS;
        match.tick(trappedAt);
        long rescuedAt = trappedAt + Match.FLAME_MS;
        match.tick(rescuedAt);

        List<MatchEvent> events = match.move(CONN_RED, RED_3, cx(0) + 10, cy(2), Facing.LEFT, true, rescuedAt);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED, MatchMessages.PLAYER_RESCUED), types(events));
        assertEquals(List.of(), match.player(RED_1).takenItems());
    }

    @Test
    void 구출된_뒤_빠져나오는_중에도_아이템을_주울_수_있다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        match.move(CONN_RED, RED_1, cx(0), cy(2), Facing.UP, false, now);
        match.placeBalloon(CONN_RED, RED_1, 0, 2, now);
        long trappedAt = now + Match.FUSE_MS;
        match.tick(trappedAt);
        // 물줄기가 사라진 뒤 3P가 닿아 구한다. 1P는 ESCAPE_MS 동안 빠져나오는 중이다
        long rescuedAt = trappedAt + Match.FLAME_MS;
        match.tick(rescuedAt);
        match.move(CONN_RED, RED_3, cx(0) + 10, cy(2), Facing.LEFT, true, rescuedAt);
        assertTrue(match.player(RED_1).isEscaping(rescuedAt));

        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, true, rescuedAt + 100);
        List<MatchEvent> events = match.pickUpItem(CONN_RED, RED_1, 1, rescuedAt + 100);

        assertTrue(match.player(RED_1).isEscaping(rescuedAt + 100));
        assertEquals(RED_1, body(events, MatchMessages.ITEM_TAKEN, ItemTaken.class).playerId());
    }

    @Test
    void 두_사람이_같은_아이템을_보내면_먼저_온_쪽만_먹는다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        match.move(CONN_BLUE, BLUE, cx(0) - 5, cy(0), Facing.UP, true, now);
        match.move(CONN_RED, RED_1, cx(0) + 5, cy(0), Facing.UP, true, now);

        List<MatchEvent> blueFirst = match.pickUpItem(CONN_BLUE, BLUE, 1, now);
        List<MatchEvent> redLate = match.pickUpItem(CONN_RED, RED_1, 1, now);

        assertEquals(BLUE, body(blueFirst, MatchMessages.ITEM_TAKEN, ItemTaken.class).playerId());
        assertEquals(List.of(), redLate);
        assertEquals(List.of(ItemType.FLUID), match.player(BLUE).takenItems());
        assertEquals(List.of(), match.player(RED_1).takenItems());
    }

    @Test
    void 능력치가_최댓값이면_ITEM_TAKEN의_stats도_최댓값에서_멈춘다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        // kkukku 물줄기 최댓값 8
        match.player(RED_1).takeItem(ItemType.ULTRA);
        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, true, now);

        ItemTaken taken = body(match.pickUpItem(CONN_RED, RED_1, 1, now), MatchMessages.ITEM_TAKEN, ItemTaken.class);

        assertEquals(new Stats(1, 8, 4), taken.stats());
        assertEquals(List.of(ItemType.ULTRA, ItemType.FLUID), match.player(RED_1).takenItems());
    }

    @Test
    void 경기가_끝나면_아이템을_주울_수_없다() {
        Match match = playing(NO_RANDOM);
        long now = dropFluidAtCorner(match);
        match.move(CONN_RED, RED_1, cx(0), cy(0), Facing.UP, true, now);
        match.leave(CONN_BLUE, "EXIT", false, now);

        List<MatchEvent> events = match.pickUpItem(CONN_RED, RED_1, 1, now);

        assertTrue(match.isFinished());
        assertEquals(List.of(), events);
    }
}

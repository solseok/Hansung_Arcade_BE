package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.AirplaneAppeared;
import hansung.arcade.domain.game.model.MatchMessages.ItemDropped;
import hansung.arcade.domain.game.model.MatchMessages.ItemTaken;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 남은 시간에 맞춰 비행기가 나오고(AIRPLANE_APPEARED), 지나가며 빈 칸에 아이템을 떨어뜨리는지(ITEM_DROPPED) */
class MatchAirplaneTest {

    /*
     * 7×3 맵(21칸, 칸 번호 = row × 7 + col). (0,0)은 고정 드롭 물풍선이 있는 블럭, (1,0)은 덤불, 나머지는 빈 바닥.
     * 1P(0,2)는 빨강(연결 1), 2P(6,2)는 파랑(연결 2). 두 명이라 막힌 칸도 다시 뽑지 않는다.
     * 세 명 경기면 3P(6,0)가 빨강(연결 3)이다
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".......",
            ".......",
            ".......",
            "[block]",
            "BT.....",
            ".......",
            ".......",
            "[spawn]",
            "......3",
            ".......",
            "1.....2",
            "[item]",
            "b......",
            ".......",
            ".......");
    private static final int CELLS = 21;

    private static final long RED = 1;
    private static final long BLUE = 2;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long RED_2 = 3;
    private static final long CONN_RED_2 = 103;
    private static final long PLAY = Match.COUNTDOWN_MS;
    private static final long ENDS_AT = PLAY + Match.TIME_LIMIT_MS;
    /** 첫 비행기가 나오는 시각 (남은 시간 1:59) */
    private static final long FIRST = ENDS_AT - AirDrop.APPEARANCES_MS.get(0);
    /** 두 번째 비행기가 나오는 시각 (남은 시간 1:36) */
    private static final long SECOND = ENDS_AT - AirDrop.APPEARANCES_MS.get(1);

    private final GameMap map = MapLoader.parse("test/airplane", MAP);

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

    /** 빨강 둘(1P, 3P는 다른 연결)과 파랑 하나. 빨강 하나가 빠져도 경기가 이어진다 */
    private Match playingWithSecondRed(RandomGenerator random) {
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED_2, CONN_RED_2, "빨강둘", Mascot.of("kkukku"), Team.RED, 3, map.spawns().get(2))),
                random);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.loaded(CONN_RED_2, 0);
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    /** player가 (col, row)로 가서 at에 물풍선을 놓고, 물줄기가 닿지 않는 escape 칸으로 비켜선다 */
    private static void placeAndStep(Match match, long connectionId, long playerId, Cell at, Cell escape, long now) {
        match.move(connectionId, playerId, GameMap.centerX(at.col()), GameMap.centerY(at.row()), Facing.DOWN, false, now);
        assertEquals(1, match.placeBalloon(connectionId, playerId, at.col(), at.row(), now).size());
        match.move(connectionId, playerId, GameMap.centerX(escape.col()), GameMap.centerY(escape.row()), Facing.DOWN,
                false, now);
    }

    /**
     * 비행기 한 대가 고를 두 칸과 아이템을 정하는 난수. second는 first보다 뒤 칸이어야 한다
     * (남은 칸 가운데 고르므로 앞서 고른 칸을 건너뛴 번호가 된다)
     */
    private static double[] plan(Cell first, ItemType firstItem, Cell second, ItemType secondItem) {
        int a = first.row() * 7 + first.col();
        int b = second.row() * 7 + second.col();
        assertTrue(b > a, "둘째 칸이 첫째 칸보다 뒤여야 합니다");
        return new double[]{(a + 0.5) / CELLS, cargo(firstItem), (b - 1 + 0.5) / (CELLS - 1), cargo(secondItem)};
    }

    /** AirDrop.CARGO에서 item을 고르는 난수 */
    private static double cargo(ItemType item) {
        return (AirDrop.CARGO.indexOf(item) + 0.5) / AirDrop.CARGO.size();
    }

    private static double[] concat(double[]... plans) {
        List<Double> all = new ArrayList<>();
        for (double[] plan : plans) {
            for (double value : plan) {
                all.add(value);
            }
        }
        return all.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static <T> List<T> bodies(List<MatchEvent> events, String type, Class<T> bodyType) {
        return events.stream().filter(event -> event.type().equals(type)).map(event -> bodyType.cast(event.body()))
                .toList();
    }

    private static List<ItemDropped> drops(List<MatchEvent> events) {
        return bodies(events, MatchMessages.ITEM_DROPPED, ItemDropped.class);
    }

    /** ITEM_TAKEN마다 [itemId, playerId] */
    private static List<List<Long>> takers(List<MatchEvent> events) {
        return bodies(events, MatchMessages.ITEM_TAKEN, ItemTaken.class).stream()
                .map(taken -> List.of((long) taken.itemId(), taken.playerId()))
                .toList();
    }

    /** 첫 비행기(오른쪽으로 남)가 col 열을 지나는 시각 */
    private long firstPass(int col) {
        return FIRST + AirDrop.passMs(map, Facing.RIGHT, col);
    }

    /** 첫 비행기가 지나는 tick에 (col, row)로 떨어뜨린 아이템이 칸에 닿는 시각 */
    private long lands(int col, int row) {
        return firstPass(col) + AirDrop.fallMs(row);
    }

    @Test
    void 남은_시간이_출현_시각이_되면_한_대씩_나오고_방향은_번갈아_바뀐다() {
        Match match = playing(new SplittableRandom(20261124));
        List<AirplaneAppeared> appeared = new ArrayList<>();

        assertEquals(List.of(), bodies(match.tick(FIRST - 1), MatchMessages.AIRPLANE_APPEARED, AirplaneAppeared.class));
        for (long remaining : AirDrop.APPEARANCES_MS) {
            appeared.addAll(bodies(match.tick(ENDS_AT - remaining), MatchMessages.AIRPLANE_APPEARED,
                    AirplaneAppeared.class));
        }

        assertEquals(List.of(new AirplaneAppeared(1, "RIGHT"), new AirplaneAppeared(2, "LEFT"),
                new AirplaneAppeared(3, "RIGHT"), new AirplaneAppeared(4, "LEFT"),
                new AirplaneAppeared(5, "RIGHT"), new AirplaneAppeared(6, "LEFT")), appeared);
        // 마지막 출현(0:07) 뒤로는 더 나오지 않는다
        assertEquals(List.of(), bodies(match.tick(ENDS_AT - 1), MatchMessages.AIRPLANE_APPEARED,
                AirplaneAppeared.class));
    }

    @Test
    void 출현_시각보다_1초_이상_늦은_tick에서는_그_비행기를_건너뛴다() {
        Match match = playing(new SplittableRandom(20261124));

        // 첫 출현(1:59)을 0.999초 늦게 처리하면 띄운다
        assertEquals(List.of(new AirplaneAppeared(1, "RIGHT")), bodies(match.tick(FIRST + AirDrop.LATE_SKIP_MS - 1),
                MatchMessages.AIRPLANE_APPEARED, AirplaneAppeared.class));
        // 1:36과 1:14를 지나 1:14 바로 다음에야 처리하면 1:36은 건너뛰고 1:14만 띄운다
        long third = ENDS_AT - AirDrop.APPEARANCES_MS.get(2);
        assertEquals(List.of(new AirplaneAppeared(2, "LEFT")), bodies(match.tick(third),
                MatchMessages.AIRPLANE_APPEARED, AirplaneAppeared.class));
    }

    @Test
    void 출현_시각보다_딱_1초_늦으면_건너뛴다() {
        Match match = playing(new SplittableRandom(20261124));

        assertEquals(List.of(), bodies(match.tick(FIRST + AirDrop.LATE_SKIP_MS), MatchMessages.AIRPLANE_APPEARED,
                AirplaneAppeared.class));
    }

    @Test
    void 고른_칸의_열을_지나는_순간_떨어뜨리고_방_전체에_알린다() {
        ScriptedRandom random = new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID));
        Match match = playing(random);
        List<MatchEvent> appeared = match.tick(FIRST);
        assertTrue(random.isUsedUp());

        assertEquals(List.of(), drops(match.tick(firstPass(3) - 1)));
        List<MatchEvent> first = match.tick(firstPass(3));
        assertEquals(List.of(new ItemDropped(1, 1, 3, 1, "bubble", AirDrop.fallMs(1))), drops(first));
        assertEquals(List.of(new ItemDropped(1, 2, 5, 1, "fluid", AirDrop.fallMs(1))), drops(match.tick(firstPass(5))));
        assertEquals(List.of(CONN_RED, CONN_BLUE), appeared.get(0).recipients());
        assertEquals(List.of(CONN_RED, CONN_BLUE), first.get(0).recipients());
    }

    @Test
    void 늦게_띄운_비행기는_띄운_때부터_지나는_시각을_센다() {
        // 0.5초 늦게 띄우면 클라이언트도 그때부터 그리므로 지나는 시각도 0.5초 늦다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long launchedAt = FIRST + 500;
        match.tick(launchedAt);

        assertEquals(List.of(), drops(match.tick(launchedAt + AirDrop.passMs(map, Facing.RIGHT, 3) - 1)));
        assertEquals(1, drops(match.tick(launchedAt + AirDrop.passMs(map, Facing.RIGHT, 3))).size());
    }

    @Test
    void 같은_열의_두_칸은_같은_순간_고른_순서대로_떨어뜨린다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 0), ItemType.SHOE, new Cell(3, 2), ItemType.FLUID)));
        match.tick(FIRST);

        assertEquals(List.of(new ItemDropped(1, 1, 3, 0, "shoe", AirDrop.fallMs(0)),
                new ItemDropped(1, 2, 3, 2, "fluid", AirDrop.fallMs(2))), drops(match.tick(firstPass(3))));
    }

    @Test
    void 한_tick에_여러_칸을_지나치면_지나간_순서대로_떨어뜨린다() {
        // 오른쪽으로 나는 비행기는 (5,0)보다 (2,2)를 먼저 지난다. 고른 순서와 반대다
        Match match = playing(new ScriptedRandom(plan(new Cell(5, 0), ItemType.SHOE, new Cell(2, 2), ItemType.BUBBLE)));
        match.tick(FIRST);

        List<ItemDropped> dropped = drops(match.tick(firstPass(6)));

        assertEquals(List.of(new ItemDropped(1, 1, 2, 2, "bubble", AirDrop.fallMs(2)),
                new ItemDropped(1, 2, 5, 0, "shoe", AirDrop.fallMs(0))), dropped);
    }

    @Test
    void 막힌_칸에는_떨어뜨리지_않고_덤불에는_떨어뜨린다() {
        // (0,0) 블럭과 (1,0) 덤불을 고른다
        Match match = playing(new ScriptedRandom(plan(new Cell(0, 0), ItemType.BUBBLE, new Cell(1, 0), ItemType.SHOE)));
        match.tick(FIRST);

        List<ItemDropped> dropped = drops(match.tick(firstPass(6)));

        assertEquals(List.of(new ItemDropped(1, 1, 1, 0, "shoe", AirDrop.fallMs(0))), dropped);
    }

    @Test
    void 물풍선이_있는_칸에는_떨어뜨리지_않는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(1), Facing.DOWN, false, FIRST);
        assertEquals(1, match.placeBalloon(CONN_RED, RED, 3, 1, FIRST).size());
        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(2), Facing.DOWN, false, FIRST);
        match.tick(FIRST);

        List<ItemDropped> dropped = drops(match.tick(firstPass(5)));

        assertEquals(List.of(new ItemDropped(1, 1, 5, 1, "fluid", AirDrop.fallMs(1))), dropped);
    }

    @Test
    void 물줄기가_남은_칸에는_떨어뜨리지_않는다() {
        // (2,1)의 물풍선이 (3,1)을 지나기 0.1초 전에 터져 물줄기 1칸이 (3,1)을 덮고 있다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = firstPass(3) - 100;
        long placedAt = explodeAt - Match.FUSE_MS;
        match.move(CONN_RED, RED, GameMap.centerX(2), GameMap.centerY(1), Facing.DOWN, false, placedAt);
        assertEquals(1, match.placeBalloon(CONN_RED, RED, 2, 1, placedAt).size());
        match.move(CONN_RED, RED, GameMap.centerX(4), GameMap.centerY(2), Facing.DOWN, false, placedAt);
        match.tick(FIRST);
        match.tick(explodeAt);

        // 지나가는 순간의 칸을 본다. 물줄기(0.45초)는 아직 남아 있다
        assertEquals(List.of(), drops(match.tick(firstPass(3))));
        assertEquals(List.of(new ItemDropped(1, 1, 5, 1, "fluid", AirDrop.fallMs(1))), drops(match.tick(firstPass(5))));
    }

    @Test
    void 이미_아이템이_있는_칸에는_떨어뜨리지_않는다() {
        // 첫 비행기가 (3,1)에 떨어뜨린 아이템이 남아 있을 때, 둘째 비행기도 (3,1)을 고른다
        ScriptedRandom random = new ScriptedRandom(concat(
                plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID),
                plan(new Cell(3, 1), ItemType.SHOE, new Cell(4, 1), ItemType.SHOE)));
        Match match = playing(random);
        match.tick(FIRST);
        match.tick(firstPass(6));
        match.tick(SECOND);

        List<ItemDropped> dropped = drops(match.tick(SECOND + AirDrop.passMs(map, Facing.LEFT, 0)));

        assertEquals(List.of(new ItemDropped(2, 3, 4, 1, "shoe", AirDrop.fallMs(1))), dropped);
        assertTrue(random.isUsedUp());
    }

    @Test
    void 사람이_서_있는_칸에도_떨어뜨리고_닿는_순간_그_사람이_바로_먹는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(1), Facing.DOWN, false, FIRST);
        match.tick(FIRST);

        assertEquals(List.of(new ItemDropped(1, 1, 3, 1, "bubble", AirDrop.fallMs(1))), drops(match.tick(firstPass(3))));
        assertEquals(List.of(), takers(match.tick(lands(3, 1) - 1)));
        List<MatchEvent> landed = match.tick(lands(3, 1));

        assertEquals(List.of(List.of(1L, RED)), takers(landed));
        assertEquals(List.of(CONN_RED, CONN_BLUE), landed.get(0).recipients());
        assertEquals(2, match.player(RED).balloonCapacity());
        // 이미 먹었으므로 클라이언트가 늦게 보낸 줍기는 버려진다
        assertEquals(List.of(), match.pickUpItem(CONN_RED, RED, 1, lands(3, 1)));
    }

    @Test
    void 떨어지는_동안_들어온_사람도_닿는_순간_먹는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.tick(FIRST);
        assertEquals(1, drops(match.tick(firstPass(3))).size());

        match.move(CONN_BLUE, BLUE, GameMap.centerX(3), GameMap.centerY(1), Facing.LEFT, false, lands(3, 1) - 1);

        assertEquals(List.of(List.of(1L, BLUE)), takers(match.tick(lands(3, 1))));
    }

    @Test
    void 닿을_때_칸에_아무도_없으면_남았다가_들어온_사람이_ITEM_PICKUP으로_줍는다() {
        // 파랑 몸이 (3,1)에 걸치지만 중심은 오른쪽 (4,1)이라 그 칸에 선 사람이 아니다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.move(CONN_BLUE, BLUE, GameMap.centerX(4) - 4, GameMap.centerY(1), Facing.LEFT, false, FIRST);
        match.tick(FIRST);
        assertEquals(1, drops(match.tick(firstPass(3))).size());

        assertEquals(List.of(), takers(match.tick(lands(3, 1))));

        long later = lands(3, 1) + 1_000;
        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(1), Facing.UP, false, later);
        assertEquals(List.of(List.of(1L, RED)), takers(match.pickUpItem(CONN_RED, RED, 1, later)));
    }

    @Test
    void 칸에_여럿이_서_있으면_칸_가운데에_가장_가까운_사람이_먹고_거리가_같으면_playerId가_작은_사람이_먹는다() {
        Match closer = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        closer.move(CONN_RED, RED, GameMap.centerX(3) + 6, GameMap.centerY(1), Facing.LEFT, false, FIRST);
        closer.move(CONN_BLUE, BLUE, GameMap.centerX(3) - 3, GameMap.centerY(1), Facing.RIGHT, false, FIRST);
        closer.tick(FIRST);
        closer.tick(firstPass(3));
        assertEquals(List.of(List.of(1L, BLUE)), takers(closer.tick(lands(3, 1))));

        // 파랑을 먼저 넣어 경기 안 순서가 아니라 playerId로 고르는지 본다
        Match tie = new Match(1, map, List.of(
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0))),
                new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        tie.start(0);
        tie.loaded(CONN_RED, 0);
        tie.loaded(CONN_BLUE, 0);
        tie.tick(PLAY);
        tie.move(CONN_BLUE, BLUE, GameMap.centerX(3) - 5, GameMap.centerY(1), Facing.RIGHT, false, FIRST);
        tie.move(CONN_RED, RED, GameMap.centerX(3) + 5, GameMap.centerY(1), Facing.LEFT, false, FIRST);
        tie.tick(FIRST);
        tie.tick(firstPass(3));
        assertEquals(List.of(List.of(1L, RED)), takers(tie.tick(lands(3, 1))));
    }

    @Test
    void 터진_사람이나_나간_사람은_그_칸에_있어도_먹지_않는다() {
        // 3P가 (3,1)에서 갇혔다가 첫 비행기가 나올 때 시간이 다 되어 터진다
        Match popped = playingWithSecondRed(
                new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = FIRST - Match.POP_MS;
        placeAndStep(popped, CONN_RED_2, RED_2, new Cell(3, 1), new Cell(3, 1), explodeAt - Match.FUSE_MS);
        popped.tick(explodeAt);
        popped.tick(FIRST);
        assertEquals(MatchPlayer.State.POPPED, popped.player(RED_2).state());
        assertEquals(1, drops(popped.tick(firstPass(3))).size());
        assertEquals(List.of(), takers(popped.tick(lands(3, 1))));

        // 3P가 (3,1)에 선 채 나간다
        Match left = playingWithSecondRed(
                new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        left.move(CONN_RED_2, RED_2, GameMap.centerX(3), GameMap.centerY(1), Facing.DOWN, false, FIRST);
        left.leave(CONN_RED_2, "EXIT", false, FIRST);
        left.tick(FIRST);
        assertEquals(1, drops(left.tick(firstPass(3))).size());
        assertEquals(List.of(), takers(left.tick(lands(3, 1))));
    }

    @Test
    void 구출되어_빠져나오는_중인_사람도_닿을_때_먹는다() {
        // 3P가 (3,1)에서 갇혔다가, 떨어뜨리기 직전에 1P가 닿아 구해 준다. 빠져나오는 0.5초 안에 아이템이 닿는다
        Match match = playingWithSecondRed(
                new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = FIRST - 500;
        placeAndStep(match, CONN_RED_2, RED_2, new Cell(3, 1), new Cell(3, 1), explodeAt - Match.FUSE_MS);
        match.tick(explodeAt);
        match.tick(FIRST);
        long rescuedAt = firstPass(3) - 50;
        List<MatchEvent> rescued = match.move(CONN_RED, RED, GameMap.centerX(3) + 5, GameMap.centerY(1), Facing.LEFT,
                false, rescuedAt);
        assertTrue(rescued.stream().anyMatch(event -> event.type().equals(MatchMessages.PLAYER_RESCUED)));
        assertTrue(match.player(RED_2).isEscaping(lands(3, 1)));
        assertEquals(1, drops(match.tick(firstPass(3))).size());

        // 칸 가운데에 선 3P가 1P보다 가깝다
        assertEquals(List.of(List.of(1L, RED_2)), takers(match.tick(lands(3, 1))));
    }

    @Test
    void 스프링_신발로_뛰는_중인_사람은_닿을_때_먹지_않고_내려앉을_때_먹는다() {
        // 1P가 (1,1)에서 오른쪽으로 뛰어 (3,1)에 내려앉는다. 공중에 있는 동안 아이템이 (3,1)에 닿는다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.tick(FIRST);
        long jumpAt = lands(3, 1) - 200;
        match.player(RED).takeItem(ItemType.SPRING_SHOE);
        match.move(CONN_RED, RED, GameMap.centerX(1), GameMap.centerY(1), Facing.RIGHT, false, jumpAt);
        assertTrue(match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, jumpAt).stream()
                .anyMatch(event -> event.type().equals(MatchMessages.ITEM_USED)));
        assertEquals(1, drops(match.tick(firstPass(3))).size());

        assertEquals(List.of(), takers(match.tick(lands(3, 1))));
        assertEquals(List.of(List.of(1L, RED)), takers(match.tick(jumpAt + Match.JUMP_MS)));
    }

    @Test
    void 떨어지는_동안_물줄기에_사라진_아이템은_닿을_때_아무_일도_없다() {
        // (3,2)에 떨어지는 동안 (2,2)의 물풍선이 터져 물줄기 1칸이 (3,2)를 덮는다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 2), ItemType.BUBBLE, new Cell(5, 2), ItemType.FLUID)));
        long pass = firstPass(3);
        long explodeAt = pass + 50;
        placeAndStep(match, CONN_RED, RED, new Cell(2, 2), new Cell(4, 0), explodeAt - Match.FUSE_MS);
        match.tick(FIRST);
        assertEquals(1, drops(match.tick(pass)).size());
        List<MatchEvent> exploded = match.tick(explodeAt);
        assertEquals(List.of(1), bodies(exploded, MatchMessages.BALLOON_EXPLODED,
                MatchMessages.BalloonExploded.class).get(0).removedItems());

        assertEquals(List.of(), match.tick(lands(3, 2)));
    }

    @Test
    void 떨어지는_동안_이미_주운_아이템은_닿을_때_다시_먹지_않는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.tick(FIRST);
        long pass = firstPass(3);
        match.tick(pass);
        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(1), Facing.UP, false, pass);
        assertEquals(List.of(List.of(1L, RED)), takers(match.pickUpItem(CONN_RED, RED, 1, pass)));

        assertEquals(List.of(), takers(match.tick(lands(3, 1))));
        assertEquals(2, match.player(RED).balloonCapacity());
    }

    @Test
    void 경기가_끝나면_날던_비행기도_더_떨어뜨리지_않는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.tick(FIRST);
        match.leave(CONN_BLUE, "EXIT", false, FIRST);
        assertTrue(match.isFinished());

        assertEquals(List.of(), match.tick(firstPass(6)));
    }

    @Test
    void 떨어뜨린_아이템은_블럭_아이템과_같은_번호로_줍는다() {
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        match.tick(FIRST);
        long now = firstPass(3);
        ItemDropped bubble = drops(match.tick(now)).get(0);

        match.move(CONN_RED, RED, GameMap.centerX(3), GameMap.centerY(1), Facing.UP, false, now);
        List<MatchEvent> taken = match.pickUpItem(CONN_RED, RED, bubble.itemId(), now);

        assertEquals(List.of(MatchMessages.ITEM_TAKEN), taken.stream().map(MatchEvent::type).toList());
        assertEquals(bubble.itemId(), ((ItemTaken) taken.get(0).body()).itemId());
    }

    @Test
    void 갇힌_사람이_선_칸에도_떨어뜨리지만_갇힌_동안은_먹지_않고_구해질_때_먹는다() {
        // 3P가 (3,1)에서 자기 물풍선에 갇힌다. 물줄기는 지나가기 전에 사라지고, 터지기(5초)까지는 갇혀 있다
        Match match = playingWithSecondRed(
                new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = FIRST - 500;
        placeAndStep(match, CONN_RED_2, RED_2, new Cell(3, 1), new Cell(3, 1), explodeAt - Match.FUSE_MS);
        match.tick(explodeAt);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_2).state());
        match.tick(FIRST);

        assertEquals(1, drops(match.tick(firstPass(3))).size());
        assertEquals(List.of(), takers(match.tick(lands(3, 1))));

        // 같은 편 1P가 닿아 구해 주면 발밑의 아이템을 바로 먹는다
        long rescuedAt = lands(3, 1) + 100;
        List<MatchEvent> rescued = match.move(CONN_RED, RED, GameMap.centerX(3) + 5, GameMap.centerY(1), Facing.LEFT,
                false, rescuedAt);
        assertTrue(rescued.stream().anyMatch(event -> event.type().equals(MatchMessages.PLAYER_RESCUED)));
        assertEquals(List.of(List.of(1L, RED_2)), takers(rescued));
    }

    @Test
    void 블럭에서_나온_아이템_다음_번호를_이어서_쓴다() {
        // (0,1)의 물풍선이 (0,0) 블럭을 부숴 고정 드롭 물풍선이 1번으로 나온다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = FIRST - 1_000;
        placeAndStep(match, CONN_RED, RED, new Cell(0, 1), new Cell(3, 2), explodeAt - Match.FUSE_MS);
        match.tick(explodeAt);
        match.tick(FIRST);

        assertEquals(List.of(new ItemDropped(1, 2, 3, 1, "bubble", AirDrop.fallMs(1))), drops(match.tick(firstPass(3))));
    }

    @Test
    void 지나는_순간에_경기가_끝나면_떨어뜨리지_않고_GAME_OVER만_보낸다() {
        // 파랑이 갇혀 있다가 (3,1)을 지나는 그 tick에 시간이 다 되어 터지고, 빨강만 남아 경기가 끝난다
        Match match = playing(new ScriptedRandom(plan(new Cell(3, 1), ItemType.BUBBLE, new Cell(5, 1), ItemType.FLUID)));
        long explodeAt = firstPass(3) - Match.POP_MS;
        placeAndStep(match, CONN_BLUE, BLUE, new Cell(6, 1), new Cell(6, 1), explodeAt - Match.FUSE_MS);
        match.tick(explodeAt);
        match.tick(FIRST);

        List<MatchEvent> events = match.tick(firstPass(3));

        assertEquals(List.of(), drops(events));
        assertEquals(MatchMessages.GAME_OVER, events.get(events.size() - 1).type());
    }
}

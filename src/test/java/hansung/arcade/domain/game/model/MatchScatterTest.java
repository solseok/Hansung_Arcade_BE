package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ItemScattered;
import hansung.arcade.domain.game.model.MatchMessages.ItemTaken;
import hansung.arcade.domain.game.model.MatchMessages.PlayerPopped;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 터진 사람이 먹은 아이템을 맵에 흩뿌리는 규칙 (영상 관찰은 {@link Match}의 scatter 주석 참고) */
class MatchScatterTest {

    /*
     * 7×4 맵. 3열은 도로('|')와 횡단보도('='), (5,0)은 가시.
     * (0,0) 블럭(고정 드롭 물풍선), (1,1) 덤불, (6,3) 석상.
     * 1P(1,0)·3P(0,3)는 빨강(연결 1), 2P(6,2)는 파랑(연결 2)
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            "...|.!.",
            "...|...",
            "...=...",
            "...|...",
            "[block]",
            "B......",
            ".T.....",
            ".......",
            "......#",
            "[spawn]",
            ".1.....",
            ".......",
            "......2",
            "3......",
            "[item]",
            "b......",
            ".......",
            ".......",
            ".......");

    private static final long RED_1 = 1;
    private static final long BLUE = 2;
    private static final long RED_3 = 3;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long PLAY = Match.COUNTDOWN_MS;
    /** 1P가 (4,2)에 놓은 물풍선에 갇히는 시각 */
    private static final long TRAPPED_AT = PLAY + Match.FUSE_MS;
    /** 1P가 갇힌 채 시간이 다 돼 터지는 시각 */
    private static final long POPPED_AT = TRAPPED_AT + Match.POP_MS;
    private static final Cell POPPED_CELL = new Cell(4, 2);

    private final GameMap map = MapLoader.parse("test/scatter", MAP);

    private Match playing(RandomGenerator random) {
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED_1, CONN_RED, "빨강하나", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED_3, CONN_RED, "빨강셋", Mascot.of("kkukku"), Team.RED, 3, map.spawns().get(2))),
                random);
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

    /** 1P가 (4,2)에 물풍선을 놓고 그 자리에 서서 {@link #TRAPPED_AT}에 갇히게 한다. 물줄기는 블럭에 닿지 않는다 */
    private static void trapRed1(Match match) {
        match.move(CONN_RED, RED_1, cx(4), cy(2), Facing.DOWN, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 4, 2, PLAY);
        match.tick(TRAPPED_AT);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());
    }

    /** 아이템을 먹인다. 물풍선을 놓은 뒤에 불러야 물줄기 길이가 늘지 않아 다른 사람이 함께 갇히지 않는다 */
    private static void take(Match match, long playerId, List<ItemType> items) {
        items.forEach(match.player(playerId)::takeItem);
    }

    private static Set<Cell> cells(List<DroppedItem> items) {
        Set<Cell> cells = new HashSet<>();
        for (DroppedItem item : items) {
            cells.add(new Cell(item.col(), item.row()));
        }
        return cells;
    }

    private static List<String> sortedIds(List<ItemType> items) {
        return items.stream().map(ItemType::id).sorted().toList();
    }

    @Test
    void 터지면_PLAYER_POPPED_바로_뒤에_먹은_아이템을_모두_흩뿌린다() {
        Match match = playing(new Random(7));
        List<ItemType> taken = List.of(ItemType.BUBBLE, ItemType.FLUID, ItemType.FLUID, ItemType.ROLLER_SHOE,
                ItemType.SHOE);
        trapRed1(match);
        take(match, RED_1, taken);

        List<MatchEvent> events = match.tick(POPPED_AT);

        int popped = types(events).indexOf(MatchMessages.PLAYER_POPPED);
        assertEquals(MatchMessages.ITEM_SCATTERED, events.get(popped + 1).type());
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(popped + 1).recipients());
        ItemScattered scattered = body(events, MatchMessages.ITEM_SCATTERED, ItemScattered.class);
        assertEquals(RED_1, scattered.playerId());
        assertEquals(POPPED_CELL, new Cell(scattered.col(), scattered.row()));
        // 먹은 것 그대로 5개, 칸은 모두 다르고, 번호는 경기 안에서 새로 매긴다
        assertEquals(sortedIds(taken), scattered.items().stream().map(DroppedItem::item).sorted().toList());
        assertEquals(5, cells(scattered.items()).size());
        assertEquals(5, scattered.items().stream().map(DroppedItem::itemId).distinct().count());
        for (DroppedItem item : scattered.items()) {
            assertEquals(Tile.FLOOR, map.tileAt(item.col(), item.row()), item.toString());
        }
        assertEquals(List.of(), match.player(RED_1).takenItems());
    }

    @Test
    void 도로_가시_블럭_덤불_물풍선_아이템_물줄기_칸과_경기_중인_사람이_선_칸은_뺀다() {
        Match match = playing(new Random(1));
        // 1P는 20개를 먹었다. 후보(16칸)보다 많아 후보가 모두 찬다
        List<ItemType> taken = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            taken.add(ItemType.values()[i % 8]);
        }
        // 3P는 (0,1)에 물풍선을 놓고 (0,3)으로 비킨다. 1P와 함께 터져 (0,0) 블럭을 부수고 고정 드롭 물풍선을 남긴다
        match.move(CONN_RED, RED_3, cx(0), cy(1), Facing.UP, false, PLAY);
        match.placeBalloon(CONN_RED, RED_3, 0, 1, PLAY);
        match.move(CONN_RED, RED_3, cx(0), cy(3), Facing.DOWN, false, PLAY);
        trapRed1(match);
        take(match, RED_1, taken);
        // 2P는 (6,0)에 물풍선을 놓고 (5,2)로 비킨다. 1P가 터지는 바로 그 tick에 먼저 터져 (6,0)·(6,1)·(5,0)에 물줄기가 남는다
        match.move(CONN_BLUE, BLUE, cx(6), cy(1), Facing.UP, false, POPPED_AT - Match.FUSE_MS);
        match.placeBalloon(CONN_BLUE, BLUE, 6, 0, POPPED_AT - Match.FUSE_MS);
        match.move(CONN_BLUE, BLUE, cx(5), cy(2), Facing.DOWN, false, POPPED_AT - Match.FUSE_MS);
        // 3P는 (0,3)에 물풍선을 놓아 둔다. 1P가 터질 때 아직 터지지 않았다
        match.placeBalloon(CONN_RED, RED_3, 0, 3, POPPED_AT - 1_000);

        List<MatchEvent> events = match.tick(POPPED_AT);

        ItemScattered scattered = body(events, MatchMessages.ITEM_SCATTERED, ItemScattered.class);
        Set<Cell> expected = Set.of(
                new Cell(1, 0), new Cell(2, 0), new Cell(4, 0),
                new Cell(0, 1), new Cell(2, 1), new Cell(4, 1), new Cell(5, 1),
                // (4,2)는 1P가 터진 칸이라 후보다. 2P가 선 (5,2)는 뺀다 (발밑에 떨어지면 칸에 들어서지 않아 줍지 못한다)
                new Cell(0, 2), new Cell(1, 2), new Cell(2, 2), new Cell(4, 2), new Cell(6, 2),
                new Cell(1, 3), new Cell(2, 3), new Cell(4, 3), new Cell(5, 3));
        assertEquals(expected, cells(scattered.items()));
        // 후보가 모자라 16개만 놓이고 나머지 4개는 사라진다. 번호는 고정 드롭(1) 다음부터
        assertEquals(16, scattered.items().size());
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17),
                scattered.items().stream().map(DroppedItem::itemId).sorted().toList());
    }

    @Test
    void 흩뿌릴_칸은_후보_전체에서_고르게_고른다() {
        // 아이템 하나를 540판 흩뿌린다. 후보는 풀밭 20칸에서 블럭 (0,0)·덤불 (1,1)·2P (6,2)·3P (0,3)을 뺀 18칸이고,
        // 1P가 터진 (4,2)는 들어간다. 18칸이 모두 나오고 칸마다 기댓값 30번 안팎이어야 한다
        Set<Cell> expected = new HashSet<>();
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.columns(); col++) {
                expected.add(new Cell(col, row));
            }
        }
        expected.removeAll(Set.of(new Cell(3, 0), new Cell(3, 1), new Cell(3, 2), new Cell(3, 3), new Cell(5, 0),
                new Cell(0, 0), new Cell(1, 1), new Cell(6, 3), new Cell(6, 2), new Cell(0, 3)));
        assertEquals(18, expected.size());
        Map<Cell, Integer> counts = new HashMap<>();
        Random seeds = new Random(42);
        for (int trial = 0; trial < 540; trial++) {
            Match match = playing(new Random(seeds.nextLong()));
            trapRed1(match);
            take(match, RED_1, List.of(ItemType.BUBBLE));

            DroppedItem item = body(match.tick(POPPED_AT), MatchMessages.ITEM_SCATTERED, ItemScattered.class)
                    .items().get(0);
            counts.merge(new Cell(item.col(), item.row()), 1, Integer::sum);
        }
        assertEquals(expected, counts.keySet());
        for (Map.Entry<Cell, Integer> entry : counts.entrySet()) {
            assertTrue(entry.getValue() >= 12 && entry.getValue() <= 54, entry.toString());
        }
    }

    @Test
    void 같은_tick에_두_사람이_터지면_차례로_흩뿌리고_두_번째는_첫_번째가_채운_칸을_쓰지_않는다() {
        Match match = playing(new Random(5));
        // 3P를 1P 물풍선의 물줄기 칸 (4,3)에 세워 둘이 함께 갇히고, 시간이 다 돼 같은 tick에 함께 터진다
        match.move(CONN_RED, RED_3, cx(4), cy(3), Facing.UP, false, PLAY);
        trapRed1(match);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_3).state());
        List<ItemType> red1Items = List.of(ItemType.BUBBLE, ItemType.FLUID, ItemType.ROLLER_SHOE, ItemType.SHOE,
                ItemType.GLOVE);
        List<ItemType> red3Items = List.of(ItemType.BUBBLE, ItemType.BUBBLE, ItemType.FLUID, ItemType.TANK,
                ItemType.ULTRA);
        take(match, RED_1, red1Items);
        take(match, RED_3, red3Items);

        List<MatchEvent> events = match.tick(POPPED_AT);

        // 빨강이 모두 터져 파랑만 남는다: 1P 터짐·흩뿌림 → 3P 터짐·흩뿌림 → 경기 끝
        assertEquals(List.of(MatchMessages.PLAYER_POPPED, MatchMessages.ITEM_SCATTERED, MatchMessages.PLAYER_POPPED,
                MatchMessages.ITEM_SCATTERED, MatchMessages.GAME_OVER), types(events));
        ItemScattered first = (ItemScattered) events.get(1).body();
        ItemScattered second = (ItemScattered) events.get(3).body();
        assertEquals(RED_1, first.playerId());
        assertEquals(RED_3, second.playerId());
        assertEquals(sortedIds(red1Items), first.items().stream().map(DroppedItem::item).sorted().toList());
        assertEquals(sortedIds(red3Items), second.items().stream().map(DroppedItem::item).sorted().toList());
        Set<Cell> all = new HashSet<>(cells(first.items()));
        all.addAll(cells(second.items()));
        assertEquals(10, all.size());
        // 1P를 흩뿌릴 때 3P는 아직 갇혀 있어 그 칸 (4,3)에는 떨어지지 않는다. 남은 2P (6,2)에는 둘 다 떨어지지 않는다
        assertFalse(cells(first.items()).contains(new Cell(4, 3)));
        assertFalse(all.contains(new Cell(6, 2)));
    }

    @Test
    void 후보가_모자랄_때_사라지는_아이템도_먹은_순서와_관계없이_무작위다() {
        // 3×1 맵: 풀밭 두 칸과 도로 한 칸. 1P가 세 개를 먹고 터지면 둘만 놓인다
        GameMap tiny = MapLoader.parse("test/tiny", List.of("[floor]", "..|", "[block]", "...", "[spawn]", "1.2"));
        List<ItemType> taken = List.of(ItemType.BUBBLE, ItemType.FLUID, ItemType.SHOE);
        Set<ItemType> lost = new HashSet<>();
        Random seeds = new Random(42);
        for (int trial = 0; trial < 50; trial++) {
            Match match = new Match(1, tiny, List.of(
                    new MatchPlayer(RED_1, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, tiny.spawns().get(0)),
                    new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, tiny.spawns().get(1))),
                    new Random(seeds.nextLong()));
            match.start(0);
            match.loaded(CONN_RED, 0);
            match.loaded(CONN_BLUE, 0);
            match.tick(PLAY);
            match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
            match.tick(TRAPPED_AT);
            take(match, RED_1, taken);

            List<MatchEvent> events = match.tick(POPPED_AT);

            // 파랑만 남아 경기가 끝나도 흩뿌리기는 PLAYER_POPPED와 GAME_OVER 사이에 간다
            assertEquals(List.of(MatchMessages.PLAYER_POPPED, MatchMessages.ITEM_SCATTERED, MatchMessages.GAME_OVER),
                    types(events));
            ItemScattered scattered = body(events, MatchMessages.ITEM_SCATTERED, ItemScattered.class);
            assertEquals(Set.of(new Cell(0, 0), new Cell(1, 0)), cells(scattered.items()));
            List<String> placed = scattered.items().stream().map(DroppedItem::item).toList();
            taken.stream().filter(item -> !placed.contains(item.id())).forEach(lost::add);
        }
        assertEquals(Set.copyOf(taken), lost);
    }

    @Test
    void 먹은_순서와_떨어진_거리는_관계가_없다() {
        // 처음 먹은 것(물풍선)과 마지막에 먹은 것(탱크)이 거리순 몇 등에 떨어지는지 300판으로 본다
        List<ItemType> taken = List.of(ItemType.BUBBLE, ItemType.FLUID, ItemType.ROLLER_SHOE, ItemType.SHOE,
                ItemType.GLOVE, ItemType.TANK);
        int[] firstRanks = new int[taken.size()];
        int[] lastRanks = new int[taken.size()];
        double firstSum = 0;
        double lastSum = 0;
        int trials = 300;
        // GameService처럼 시드를 난수로 뽑는다 (0, 1, 2 … 처럼 이어진 시드는 java.util.Random의 첫 값들이 서로 닮는다)
        Random seeds = new Random(42);
        for (int trial = 0; trial < trials; trial++) {
            Match match = playing(new Random(seeds.nextLong()));
            trapRed1(match);
            take(match, RED_1, taken);

            ItemScattered scattered = body(match.tick(POPPED_AT), MatchMessages.ITEM_SCATTERED, ItemScattered.class);

            List<String> byDistance = scattered.items().stream()
                    .sorted(Comparator.comparingInt((DroppedItem item) -> POPPED_CELL.distanceTo(
                            new Cell(item.col(), item.row()))).thenComparingInt(DroppedItem::itemId))
                    .map(DroppedItem::item).toList();
            int first = byDistance.indexOf(ItemType.BUBBLE.id());
            int last = byDistance.indexOf(ItemType.TANK.id());
            firstRanks[first]++;
            lastRanks[last]++;
            firstSum += first;
            lastSum += last;
        }
        // 가까운 곳에 오래전 것(또는 최근 것)이 몰리지 않는다: 둘 다 1~6등에 모두 떨어지고, 평균 등수가 비슷하다
        for (int rank = 0; rank < taken.size(); rank++) {
            assertTrue(firstRanks[rank] > 0, "처음 먹은 것이 " + (rank + 1) + "등에 떨어진 적이 없다");
            assertTrue(lastRanks[rank] > 0, "마지막에 먹은 것이 " + (rank + 1) + "등에 떨어진 적이 없다");
        }
        assertTrue(Math.abs(firstSum / trials - lastSum / trials) < 0.5,
                "평균 등수: 처음 " + firstSum / trials + ", 마지막 " + lastSum / trials);
    }

    @Test
    void 다른_팀이_닿아_터뜨려도_흩뿌리고_흩뿌린_아이템은_다른_사람이_주울_수_있다() {
        Match match = playing(new Random(3));
        trapRed1(match);
        take(match, RED_1, List.of(ItemType.BUBBLE));
        long touchAt = TRAPPED_AT + Match.FLAME_MS;
        match.tick(touchAt);

        List<MatchEvent> events = match.move(CONN_BLUE, BLUE, cx(4) + 10, cy(2), Facing.LEFT, true, touchAt);

        assertEquals(new PlayerPopped(RED_1, BLUE, "TOUCH"), body(events, MatchMessages.PLAYER_POPPED, PlayerPopped.class));
        DroppedItem item = body(events, MatchMessages.ITEM_SCATTERED, ItemScattered.class).items().get(0);
        match.move(CONN_BLUE, BLUE, cx(item.col()), cy(item.row()), Facing.LEFT, true, touchAt + 50);
        ItemTaken taken = body(match.pickUpItem(CONN_BLUE, BLUE, item.itemId(), touchAt + 50), MatchMessages.ITEM_TAKEN,
                ItemTaken.class);
        assertEquals("bubble", taken.item());
        assertEquals(2, taken.stats().balloons());
    }

    @Test
    void 먹은_아이템이_없으면_흩뿌리기_메시지를_보내지_않는다() {
        Match match = playing(new Random(3));
        trapRed1(match);

        List<MatchEvent> events = match.tick(POPPED_AT);

        assertTrue(types(events).contains(MatchMessages.PLAYER_POPPED));
        assertFalse(types(events).contains(MatchMessages.ITEM_SCATTERED));
    }
}

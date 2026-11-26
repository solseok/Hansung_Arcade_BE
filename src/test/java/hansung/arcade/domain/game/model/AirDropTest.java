package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.AirDrop.Drop;
import hansung.arcade.domain.game.model.AirDrop.Plan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirDropTest {

    /**
     * 5×2 맵. 윗줄은 모두 막혀 있고 (1,0)은 부서지지 않는 집이다.
     * 아랫줄은 (0,1) 블럭, (1,1) 빈 칸, (2,1) 덤불, (3,1) 빈 칸, (4,1) 집이다
     */
    private final GameMap mixed = MapLoader.parse("t", List.of(
            "[floor]", ".....", ".....",
            "[block]", "BHBBB", "B.T.H",
            "[spawn]", ".....", ".1..."));

    /** 블럭이 없는 15×13 맵 */
    private static GameMap emptyMap() {
        List<String> lines = new ArrayList<>();
        for (String layer : List.of("[floor]", "[block]", "[spawn]")) {
            lines.add(layer);
            for (int row = 0; row < 13; row++) {
                lines.add(layer.equals("[spawn]") && row == 0 ? "1" + ".".repeat(14) : ".".repeat(15));
            }
        }
        return MapLoader.parse("t", lines);
    }

    private static Plan plan(int row, Drop... drops) {
        return new Plan(row, List.of(drops));
    }

    @Test
    void 경기_인원이_많을수록_막힌_칸을_더_많이_다시_뽑는다() {
        assertEquals(1, AirDrop.attemptsFor(1));
        assertEquals(1, AirDrop.attemptsFor(2));
        assertEquals(2, AirDrop.attemptsFor(3));
        assertEquals(3, AirDrop.attemptsFor(4));
        assertEquals(7, AirDrop.attemptsFor(8));
        // 최대 인원을 넘겨도 최대 인원으로 센다
        assertEquals(7, AirDrop.attemptsFor(9));
        assertThrows(IllegalArgumentException.class, () -> AirDrop.attemptsFor(0));
    }

    @Test
    void 줄을_먼저_고르고_그_줄에서_서로_다른_두_칸을_고른다() {
        GameMap map = emptyMap();

        // 줄 0번, 열 0번, 아이템 0번(물풍선), 남은 열 중 0번 → 이미 고른 0번을 건너뛰어 1번, 아이템 2번(물약 병)
        ScriptedRandom first = new ScriptedRandom(0.0, 0.0, 0.0, 0.0, 0.99);
        assertEquals(plan(0, new Drop(0, 0, ItemType.BUBBLE), new Drop(1, 0, ItemType.FLUID)),
                new AirDrop(map, first, 2).plan());
        assertTrue(first.isUsedUp());

        // 13줄의 6번, 15열의 7번, 같은 난수라도 둘째는 남은 14열의 7번 → 8번
        assertEquals(plan(6, new Drop(7, 6, ItemType.SHOE), new Drop(8, 6, ItemType.SHOE)),
                new AirDrop(map, new ScriptedRandom(0.5, 0.5, 0.5, 0.5, 0.5), 2).plan());

        // 가장 큰 난수로는 마지막 줄의 마지막 열들을 고른다
        assertEquals(plan(12, new Drop(14, 12, ItemType.FLUID), new Drop(13, 12, ItemType.FLUID)),
                new AirDrop(map, new ScriptedRandom(0.9999, 0.9999, 0.9999, 0.9999, 0.9999), 2).plan());
    }

    @Test
    void 두_명이면_막힌_칸도_다시_뽑지_않는다() {
        ScriptedRandom random = new ScriptedRandom(0.0, 0.0, 0.0, 0.0, 0.99);

        assertEquals(plan(0, new Drop(0, 0, ItemType.BUBBLE), new Drop(1, 0, ItemType.FLUID)),
                new AirDrop(mixed, random, 2).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 인원이_많으면_같은_줄에서_막힌_칸을_다시_뽑아_빈_칸을_고른다() {
        // 아랫줄. 첫 칸: (0,1) 블럭 → 다시 뽑아 5열의 1번 (1,1). 둘째 칸: 남은 4열의 3번 → 4번 (4,1) 집 → 다시 뽑아 2번 → 3번 (3,1)
        ScriptedRandom random = new ScriptedRandom(0.99, 0.0, 0.0, 0.3, 0.5, 0.99, 0.0, 0.5, 0.99);

        assertEquals(plan(1, new Drop(1, 1, ItemType.SHOE), new Drop(3, 1, ItemType.FLUID)),
                new AirDrop(mixed, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 줄이_다_막혀_있으면_다시_뽑아도_마지막에_뽑은_칸을_쓰고_줄은_다시_고르지_않는다() {
        // 윗줄. 3명이면 칸마다 두 번까지 뽑는다
        // 첫 칸: (0,0) 물풍선 → (2,0) 신발. 둘째 칸: 남은 4열의 0번 (0,0) 물풍선 → 4열의 3번 → 4번 (4,0) 물약 병
        ScriptedRandom random = new ScriptedRandom(0.0, 0.0, 0.0, 0.4, 0.5, 0.0, 0.0, 0.9, 0.99);

        assertEquals(plan(0, new Drop(2, 0, ItemType.SHOE), new Drop(4, 0, ItemType.FLUID)),
                new AirDrop(mixed, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 덤불은_지나갈_수_있는_칸이라_다시_뽑지_않는다() {
        // 아랫줄. 첫 칸: 5열의 2번 (2,1) 덤불이라 그대로. 둘째 칸: 4열의 0번 (0,1) 블럭 → 다시 뽑아 4열의 1번 (1,1)
        ScriptedRandom random = new ScriptedRandom(0.99, 0.5, 0.0, 0.0, 0.0, 0.3, 0.0);

        assertEquals(plan(1, new Drop(2, 1, ItemType.BUBBLE), new Drop(1, 1, ItemType.BUBBLE)),
                new AirDrop(mixed, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 부서지지_않는_집도_막힌_칸이라_다시_뽑는다() {
        // 아랫줄. 첫 칸: (4,1) 집 → 다시 뽑아 (1,1). 둘째 칸: 4열의 3번 → 4번 집 → 다시 뽑아 4열의 2번 → 3번 (3,1)
        ScriptedRandom random = new ScriptedRandom(0.99, 0.9, 0.0, 0.3, 0.0, 0.9, 0.0, 0.6, 0.0);

        assertEquals(plan(1, new Drop(1, 1, ItemType.BUBBLE), new Drop(3, 1, ItemType.BUBBLE)),
                new AirDrop(mixed, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 다_부서진_블럭_칸은_빈_칸으로_보고_고른다() {
        mixed.hitBlock(0, 1);
        // 아랫줄. 첫 칸: (0,1)은 이제 빈 칸이라 그대로. 둘째 칸: 4열의 0번 → 이미 고른 0번을 건너뛰어 (1,1)
        ScriptedRandom random = new ScriptedRandom(0.99, 0.0, 0.0, 0.0, 0.0);

        assertEquals(plan(1, new Drop(0, 1, ItemType.BUBBLE), new Drop(1, 1, ItemType.BUBBLE)),
                new AirDrop(mixed, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 한_줄이_한_칸뿐이면_한_칸만_고른다() {
        GameMap column = MapLoader.parse("t", List.of("[floor]", ".", ".", "[block]", ".", ".", "[spawn]", "1", "."));
        ScriptedRandom random = new ScriptedRandom(0.9, 0.5, 0.5);

        assertEquals(plan(1, new Drop(0, 1, ItemType.SHOE)), new AirDrop(column, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 인원이_많을수록_두_칸_모두_빈_칸일_확률이_높다() {
        // 한 줄 6칸 중 3칸이 막힌 맵: 2명 1/2 × 2/5 = 20%, 4명 (1 - 1/8) × (1 - (3/5)^3) ≈ 69%,
        // 8명 (1 - 1/128) × (1 - (3/5)^7) ≈ 96%
        GameMap row = MapLoader.parse("t", List.of("[floor]", "......", "[block]", "B.B.B.", "[spawn]", ".1...."));
        assertEquals(0.200, bothDroppable(row, 2), 0.02);
        assertEquals(0.686, bothDroppable(row, 4), 0.02);
        assertEquals(0.964, bothDroppable(row, 8), 0.02);
    }

    private static double bothDroppable(GameMap map, int players) {
        AirDrop airDrop = new AirDrop(map, new SplittableRandom(20261126), players);
        int rounds = 20_000;
        int both = 0;
        for (int i = 0; i < rounds; i++) {
            if (airDrop.plan().drops().stream().noneMatch(drop -> map.isSolid(drop.col(), drop.row()))) {
                both++;
            }
        }
        return both / (double) rounds;
    }

    @Test
    void 고른_칸은_늘_지나가는_줄의_서로_다른_칸이고_비행기_아이템이며_줄은_모든_줄에서_고른다() {
        GameMap map = emptyMap();
        AirDrop airDrop = new AirDrop(map, new SplittableRandom(20261126), 8);
        Set<Integer> rows = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            Plan plan = airDrop.plan();
            rows.add(plan.row());
            assertEquals(AirDrop.DROPS, plan.drops().size());
            assertEquals(2, plan.drops().stream().map(Drop::col).distinct().count());
            for (Drop drop : plan.drops()) {
                assertEquals(plan.row(), drop.row());
                assertTrue(map.isInside(drop.col(), drop.row()));
                assertTrue(AirDrop.CARGO.contains(drop.item()));
            }
        }
        assertEquals(13, rows.size());
    }

    @Test
    void 비행기는_오른쪽부터_번갈아_난다() {
        assertEquals(Facing.RIGHT, AirDrop.headingOf(0));
        assertEquals(Facing.LEFT, AirDrop.headingOf(1));
        assertEquals(Facing.RIGHT, AirDrop.headingOf(2));
    }

    @Test
    void 떨어뜨리는_자리가_열_가운데를_지나는_시각은_맵_바깥_80px에서_초당_220px로_계산한다() {
        GameMap map = emptyMap();
        // 오른쪽으로: 0열 가운데(20px)까지 100px → 455ms, 14열 가운데(580px)까지 660px → 3000ms
        assertEquals(455, AirDrop.passMs(map, Facing.RIGHT, 0));
        assertEquals(3000, AirDrop.passMs(map, Facing.RIGHT, 14));
        // 왼쪽으로는 맵 오른쪽 바깥(680px)에서 들어온다
        assertEquals(455, AirDrop.passMs(map, Facing.LEFT, 14));
        assertEquals(3000, AirDrop.passMs(map, Facing.LEFT, 0));
    }

    @Test
    void 비행기_배는_줄의_위쪽_끝을_지나_아이템이_칸_가운데까지_20px을_129ms에_떨어진다() {
        assertEquals(20, AirDrop.DROP_HEIGHT);
        assertEquals(129, AirDrop.FALL_MS);
    }

    @Test
    void 비행기_화물에는_탱크가_없다() {
        assertEquals(List.of(ItemType.BUBBLE, ItemType.SHOE, ItemType.FLUID), AirDrop.CARGO);
    }
}

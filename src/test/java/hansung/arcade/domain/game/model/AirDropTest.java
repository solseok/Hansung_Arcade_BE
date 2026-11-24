package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.AirDrop.Drop;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirDropTest {

    /** 5×2 맵. 윗줄은 모두 블럭(0~4번 칸), 아랫줄은 모두 빈 칸(5~9번 칸)이다 */
    private final GameMap halfBlocked = MapLoader.parse("t", List.of(
            "[floor]", ".....", ".....",
            "[block]", "BBBBB", ".....",
            "[spawn]", ".....", "1...."));

    /** 블럭이 없는 15×13 맵. 클라이언트 AirDropTest의 맵과 크기가 같다 */
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
    void 두_명이면_클라이언트와_같은_칸을_같은_난수_순서로_고른다() {
        GameMap map = emptyMap();

        // 칸 0번(0, 0), 아이템 0번(물풍선), 남은 칸 중 0번 → 이미 고른 0번을 건너뛰어 (1, 0), 아이템 2번(물약 병)
        ScriptedRandom first = new ScriptedRandom(0.0, 0.0, 0.0, 0.99);
        assertEquals(List.of(new Drop(0, 0, ItemType.BUBBLE), new Drop(1, 0, ItemType.FLUID)),
                new AirDrop(map, first, 2).plan());
        assertTrue(first.isUsedUp());

        // 같은 난수가 나와도 두 번째는 다른 칸이다: 195칸의 97번 (7, 6), 194칸의 97번 → 98번 (8, 6)
        assertEquals(List.of(new Drop(7, 6, ItemType.SHOE), new Drop(8, 6, ItemType.SHOE)),
                new AirDrop(map, new ScriptedRandom(0.5, 0.5, 0.5, 0.5), 2).plan());

        // 가장 큰 난수로는 마지막 칸들을 고른다
        assertEquals(List.of(new Drop(14, 12, ItemType.FLUID), new Drop(13, 12, ItemType.FLUID)),
                new AirDrop(map, new ScriptedRandom(0.9999, 0.9999, 0.9999, 0.9999), 2).plan());
    }

    @Test
    void 두_명이면_막힌_칸도_다시_뽑지_않는다() {
        ScriptedRandom random = new ScriptedRandom(0.0, 0.0, 0.0, 0.99);

        assertEquals(List.of(new Drop(0, 0, ItemType.BUBBLE), new Drop(1, 0, ItemType.FLUID)),
                new AirDrop(halfBlocked, random, 2).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 인원이_많으면_막힌_칸을_다시_뽑아_빈_칸을_고른다() {
        // 첫 칸: (0, 0) 블럭 → 다시 뽑아 10칸의 5번 (0, 1). 둘째 칸: 남은 9칸의 0번 (0, 0) 블럭 → 다시 뽑아 9칸의 5번 → 6번 (1, 1)
        ScriptedRandom random = new ScriptedRandom(0.0, 0.0, 0.5, 0.5, 0.0, 0.0, 0.6, 0.99);

        assertEquals(List.of(new Drop(0, 1, ItemType.SHOE), new Drop(1, 1, ItemType.FLUID)),
                new AirDrop(halfBlocked, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 다시_뽑아도_다_막혀_있으면_마지막에_뽑은_칸을_쓴다() {
        // 3명이면 칸마다 두 번까지 뽑는다. 모두 윗줄(블럭)만 뽑히게 한다
        // 첫 칸: (0, 0) 물풍선 → (2, 0) 신발. 둘째 칸: 남은 9칸의 0번 (0, 0) 물풍선 → 9칸의 3번 → 4번 (4, 0) 물약 병
        ScriptedRandom random = new ScriptedRandom(0.0, 0.0, 0.2, 0.5, 0.1, 0.0, 0.4, 0.99);

        assertEquals(List.of(new Drop(2, 0, ItemType.SHOE), new Drop(4, 0, ItemType.FLUID)),
                new AirDrop(halfBlocked, random, 3).plan());
        assertTrue(random.isUsedUp());
    }

    @Test
    void 인원이_많을수록_두_칸_모두_빈_칸일_확률이_높다() {
        // 빈 칸이 절반인 맵: 2명 1/2 × 4/9 ≈ 22%, 4명 (1 - 1/8) × (1 - (5/9)^3) ≈ 72%, 8명 (1 - 1/128) × (1 - (5/9)^7) ≈ 98%
        assertEquals(0.222, bothDroppable(2), 0.02);
        assertEquals(0.725, bothDroppable(4), 0.02);
        assertEquals(0.976, bothDroppable(8), 0.02);
    }

    @Test
    void 고른_칸은_늘_서로_다르고_맵_안이며_비행기_아이템이다() {
        AirDrop airDrop = new AirDrop(halfBlocked, new SplittableRandom(20261124), 8);
        for (int i = 0; i < 2000; i++) {
            List<Drop> drops = airDrop.plan();
            assertEquals(AirDrop.DROPS, drops.size());
            assertEquals(2, drops.stream().map(drop -> new Cell(drop.col(), drop.row())).distinct().count());
            for (Drop drop : drops) {
                assertTrue(halfBlocked.isInside(drop.col(), drop.row()));
                assertTrue(AirDrop.CARGO.contains(drop.item()));
            }
        }
    }

    private double bothDroppable(int players) {
        AirDrop airDrop = new AirDrop(halfBlocked, new SplittableRandom(20261124), players);
        int rounds = 20_000;
        int both = 0;
        for (int i = 0; i < rounds; i++) {
            if (airDrop.plan().stream().noneMatch(drop -> halfBlocked.isSolid(drop.col(), drop.row()))) {
                both++;
            }
        }
        return both / (double) rounds;
    }
}

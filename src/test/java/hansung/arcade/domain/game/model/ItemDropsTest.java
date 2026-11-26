package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDropsTest {

    @Test
    void 첫_난수가_확률_이상이면_아무것도_나오지_않는다() {
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(ItemDrops.DROP_CHANCE), 2));
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(0.99), 2));
    }

    @Test
    void 두_번째_난수를_클라이언트와_같은_순서의_비율_구간으로_나눠_고른다() {
        // 탱크를 뺀 합 97: 물풍선 0~22, 물줄기 22~44, 울트라 44~48, 롤러 48~64, 빨간 악마 64~67, 장갑 67~72,
        // 신발 72~78, 바늘 78~85, 보호막 85~91, 스프링 91~97
        assertEquals(Optional.of(ItemType.BUBBLE), roll(0.0));
        assertEquals(Optional.of(ItemType.BUBBLE), roll(21.99));
        assertEquals(Optional.of(ItemType.FLUID), roll(22.01));
        assertEquals(Optional.of(ItemType.ULTRA), roll(44.01));
        assertEquals(Optional.of(ItemType.ROLLER_SHOE), roll(48.01));
        assertEquals(Optional.of(ItemType.RED_DEVIL), roll(64.01));
        assertEquals(Optional.of(ItemType.GLOVE), roll(67.01));
        assertEquals(Optional.of(ItemType.SHOE), roll(77.99));
        // 탱크가 있던 78~81은 바늘이다
        assertEquals(Optional.of(ItemType.NEEDLE), roll(78.01));
        assertEquals(Optional.of(ItemType.NEEDLE), roll(80.5));
        assertEquals(Optional.of(ItemType.SHIELD), roll(85.01));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), roll(96.99));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), ItemDrops.roll(new ScriptedRandom(0.32, 0.9999), 2));
    }

    /** 두 번째 난수가 합 97 가운데 at 자리가 되도록 굴린다 */
    private static Optional<ItemType> roll(double at) {
        return ItemDrops.roll(new ScriptedRandom(0.0, at / ItemDrops.TOTAL), 2);
    }

    @Test
    void 비율은_탱크를_뺀_10종을_합쳐_97이고_액티브는_합쳐_19다() {
        assertEquals(97, ItemDrops.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(Arrays.stream(ItemType.values()).filter(type -> type != ItemType.TANK).toList(),
                List.copyOf(ItemDrops.WEIGHTS.keySet()));
        assertEquals(19, ItemDrops.WEIGHTS.entrySet().stream()
                .filter(entry -> entry.getKey().isActive()).mapToInt(entry -> entry.getValue()).sum());
    }

    @Test
    void 탱크는_비율표에_없어서_많이_굴려도_한_번도_나오지_않는다() {
        SplittableRandom random = new SplittableRandom(20261126);
        for (int i = 0; i < 200_000; i++) {
            assertNotEquals(Optional.of(ItemType.TANK), ItemDrops.roll(random, 8));
        }
    }

    @Test
    void 경기_인원이_많을수록_블럭에서_아이템이_자주_나온다() {
        assertEquals(0.33, ItemDrops.dropChanceFor(1));
        assertEquals(0.33, ItemDrops.dropChanceFor(2));
        assertEquals(0.37, ItemDrops.dropChanceFor(3));
        assertEquals(0.41, ItemDrops.dropChanceFor(4));
        assertEquals(0.45, ItemDrops.dropChanceFor(5));
        assertEquals(0.49, ItemDrops.dropChanceFor(6));
        assertEquals(0.53, ItemDrops.dropChanceFor(7));
        assertEquals(0.57, ItemDrops.dropChanceFor(8));
        // 최대 인원을 넘겨도 최대 인원으로 센다
        assertEquals(0.57, ItemDrops.dropChanceFor(9));
        assertThrows(IllegalArgumentException.class, () -> ItemDrops.dropChanceFor(0));
    }

    @Test
    void 첫_난수가_인원에_맞춘_확률_안이어야_나온다() {
        assertTrue(ItemDrops.roll(new ScriptedRandom(0.569, 0.0), 8).isPresent());
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(0.57), 8));
        assertTrue(ItemDrops.roll(new ScriptedRandom(0.369, 0.0), 3).isPresent());
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(0.369), 2));
    }

    @Test
    void 많이_굴리면_인원에_맞춘_확률대로_나온다() {
        for (int players : new int[]{2, 8}) {
            SplittableRandom random = new SplittableRandom(20261124);
            int rolls = 200_000;
            int dropped = 0;
            for (int i = 0; i < rolls; i++) {
                if (ItemDrops.roll(random, players).isPresent()) {
                    dropped++;
                }
            }
            assertEquals(ItemDrops.dropChanceFor(players), dropped / (double) rolls, 0.005, "players=" + players);
        }
    }
}

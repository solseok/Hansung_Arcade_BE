package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // 물풍선 22, 물줄기 22, 울트라 4, 롤러 16, 빨간 악마 3, 장갑 5, 신발 6, 탱크 3, 바늘 7, 보호막 6, 스프링 6
        assertEquals(Optional.of(ItemType.BUBBLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.0), 2));
        assertEquals(Optional.of(ItemType.BUBBLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.2199), 2));
        assertEquals(Optional.of(ItemType.FLUID), ItemDrops.roll(new ScriptedRandom(0.0, 0.22), 2));
        assertEquals(Optional.of(ItemType.ULTRA), ItemDrops.roll(new ScriptedRandom(0.0, 0.44), 2));
        assertEquals(Optional.of(ItemType.ROLLER_SHOE), ItemDrops.roll(new ScriptedRandom(0.0, 0.48), 2));
        assertEquals(Optional.of(ItemType.TANK), ItemDrops.roll(new ScriptedRandom(0.0, 0.78), 2));
        assertEquals(Optional.of(ItemType.NEEDLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.81), 2));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), ItemDrops.roll(new ScriptedRandom(0.32, 0.9999), 2));
    }

    @Test
    void 비율은_11종_모두_합쳐_100이고_액티브는_합쳐_19다() {
        assertEquals(100, ItemDrops.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(ItemType.values()), List.copyOf(ItemDrops.WEIGHTS.keySet()));
        assertEquals(19, ItemDrops.WEIGHTS.entrySet().stream()
                .filter(entry -> entry.getKey().isActive()).mapToInt(entry -> entry.getValue()).sum());
    }

    @Test
    void 경기_인원이_많을수록_블럭에서_아이템이_자주_나온다() {
        assertEquals(0.33, ItemDrops.dropChanceFor(1));
        assertEquals(0.33, ItemDrops.dropChanceFor(2));
        assertEquals(0.37, ItemDrops.dropChanceFor(3));
        assertEquals(0.41, ItemDrops.dropChanceFor(4));
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

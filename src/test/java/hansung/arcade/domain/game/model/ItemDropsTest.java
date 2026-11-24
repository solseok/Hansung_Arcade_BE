package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemDropsTest {

    @Test
    void 첫_난수가_확률_이상이면_아무것도_나오지_않는다() {
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(ItemDrops.DROP_CHANCE), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(0.99), ItemDrops.DROP_CHANCE));
    }

    @Test
    void 두_번째_난수를_클라이언트와_같은_순서의_비율_구간으로_나눠_고른다() {
        // 물풍선 22, 물줄기 22, 울트라 4, 롤러 16, 빨간 악마 3, 장갑 5, 신발 6, 탱크 3, 바늘 7, 보호막 6, 스프링 6
        assertEquals(Optional.of(ItemType.BUBBLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.0), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.BUBBLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.2199), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.FLUID), ItemDrops.roll(new ScriptedRandom(0.0, 0.22), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.ULTRA), ItemDrops.roll(new ScriptedRandom(0.0, 0.44), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.ROLLER_SHOE), ItemDrops.roll(new ScriptedRandom(0.0, 0.48), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.TANK), ItemDrops.roll(new ScriptedRandom(0.0, 0.78), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.NEEDLE), ItemDrops.roll(new ScriptedRandom(0.0, 0.81), ItemDrops.DROP_CHANCE));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), ItemDrops.roll(new ScriptedRandom(0.32, 0.9999), ItemDrops.DROP_CHANCE));
    }

    @Test
    void 비율은_11종_모두_합쳐_100이고_액티브는_합쳐_19다() {
        assertEquals(100, ItemDrops.WEIGHTS.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(ItemType.values()), List.copyOf(ItemDrops.WEIGHTS.keySet()));
        assertEquals(19, ItemDrops.WEIGHTS.entrySet().stream()
                .filter(entry -> entry.getKey().isActive()).mapToInt(entry -> entry.getValue()).sum());
    }

    @Test
    void 나올_확률은_2명까지_33퍼센트이고_한_명마다_4퍼센트포인트씩_8명이면_57퍼센트다() {
        assertEquals(0.33, ItemDrops.dropChanceFor(1));
        assertEquals(0.33, ItemDrops.dropChanceFor(2));
        assertEquals(0.37, ItemDrops.dropChanceFor(3));
        assertEquals(0.41, ItemDrops.dropChanceFor(4));
        assertEquals(0.45, ItemDrops.dropChanceFor(5));
        assertEquals(0.49, ItemDrops.dropChanceFor(6));
        assertEquals(0.53, ItemDrops.dropChanceFor(7));
        assertEquals(0.57, ItemDrops.dropChanceFor(8));
        // 8명을 넘어도 8명으로 센다
        assertEquals(0.57, ItemDrops.dropChanceFor(12));
        assertThrows(IllegalArgumentException.class, () -> ItemDrops.dropChanceFor(0));
    }

    @Test
    void 확률은_첫_난수와만_견준다() {
        assertEquals(Optional.of(ItemType.BUBBLE), ItemDrops.roll(new ScriptedRandom(0.56, 0.0), 0.57));
        assertEquals(Optional.empty(), ItemDrops.roll(new ScriptedRandom(0.57), 0.57));
    }
}

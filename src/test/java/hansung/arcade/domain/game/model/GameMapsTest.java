package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameMapsTest {

    @Test
    void 맵마다_화면_이름과_최대_인원이_있다() {
        assertEquals(List.of(
                        new GameMaps.Entry("camp/camp08", "캠프 08", 8),
                        new GameMaps.Entry("patrit/patrit14", "패트릿 14", 8),
                        new GameMaps.Entry("village/village10", "빌리지 10", 6)),
                GameMaps.ALL);
        assertEquals("빌리지 10", GameMaps.name("village/village10"));
        assertEquals("랜덤 맵", GameMaps.name("random"));
        assertThrows(IllegalArgumentException.class, () -> GameMaps.name("nowhere"));
    }

    @Test
    void 방에서_고를_수_있는_맵은_실제_맵과_random이다() {
        assertEquals("camp/camp08", GameMaps.DEFAULT_ID);
        assertTrue(GameMaps.selectable("camp/camp08"));
        assertTrue(GameMaps.selectable("random"));
        assertFalse(GameMaps.selectable("nowhere"));
        assertFalse(GameMaps.selectable(null));
        assertFalse(GameMaps.exists("random"));
    }

    @Test
    void 방_정원은_랜덤이면_가장_큰_맵에_맞춘다() {
        assertEquals(8, GameMaps.RANDOM_MAX_PLAYERS);
        assertEquals(8, GameMaps.capacityOf("random"));
        assertEquals(6, GameMaps.capacityOf("village/village10"));
        assertEquals(6, GameMaps.maxPlayers("village/village10"));
        assertThrows(IllegalArgumentException.class, () -> GameMaps.maxPlayers("random"));
        assertThrows(IllegalArgumentException.class, () -> GameMaps.capacityOf("nowhere"));
    }

    @Test
    void 탱크는_캠프_08_전용이라_다른_맵에서는_블럭에서_나오지_않는다() {
        assertEquals(Set.of("camp/camp08"), GameMaps.TANK_MAPS);
        assertEquals(Set.of(), GameMaps.excludedDrops("camp/camp08"));
        assertEquals(Set.of(ItemType.TANK), GameMaps.excludedDrops("patrit/patrit14"));
        assertEquals(Set.of(ItemType.TANK), GameMaps.excludedDrops("village/village10"));
        assertEquals(Set.of(ItemType.TANK), GameMaps.excludedDrops("test/anything"));
    }

    @Test
    void 랜덤_맵은_인원이_들어가는_맵에서만_고른다() {
        for (int seed = 0; seed < 200; seed++) {
            String seven = GameMaps.pick(7, new Random(seed));
            assertTrue(Set.of("camp/camp08", "patrit/patrit14").contains(seven), seven);
            assertTrue(GameMaps.exists(GameMaps.pick(2, new Random(seed))));
        }
        Set<String> picked = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            picked.add(GameMaps.pick(6, new Random(seed)));
        }
        assertEquals(Set.of("camp/camp08", "patrit/patrit14", "village/village10"), picked);
        assertThrows(IllegalArgumentException.class, () -> GameMaps.pick(9, new Random(1)));
    }
}

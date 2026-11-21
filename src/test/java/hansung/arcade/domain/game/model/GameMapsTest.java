package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}

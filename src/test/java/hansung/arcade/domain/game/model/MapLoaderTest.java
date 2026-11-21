package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapLoaderTest {

    @Test
    void 고를_수_있는_맵은_모두_읽히고_정원만큼_스폰_자리가_있다() {
        for (GameMaps.Entry entry : GameMaps.ALL) {
            GameMap map = MapLoader.load(entry.id());

            assertEquals(entry.id(), map.id());
            assertEquals(entry.maxPlayers(), map.spawns().size(), entry.id());
            for (Cell spawn : map.spawns()) {
                assertFalse(map.isSolid(spawn.col(), spawn.row()), entry.id() + " 스폰 " + spawn);
            }
        }
    }

    @Test
    void 블럭이_부서지면_그_칸의_바닥이_드러난다() {
        GameMap map = MapLoader.parse("t", List.of(
                "[floor]", "_.", "[block]", "c.", "[spawn]", ".1"));

        map.hitBlock(0, 0);
        assertEquals(Tile.HARD_BLOCK_2, map.tileAt(0, 0));
        map.hitBlock(0, 0);
        map.hitBlock(0, 0);
        assertEquals(Tile.ROAD, map.tileAt(0, 0));
        assertFalse(map.hitBlock(0, 0));
    }

    @Test
    void 층의_크기가_다르면_읽지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "...", "[spawn]", "1.")));
    }
}

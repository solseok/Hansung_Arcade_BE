package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void 아이템_층의_글자는_그_칸의_고정_드롭이다() {
        GameMap map = MapLoader.parse("t", List.of(
                "[floor]", "...", "[block]", "BP.", "[spawn]", "..1", "[item]", "u.."));

        assertEquals(Optional.of(ItemType.ULTRA), map.fixedDrop(0, 0));
        assertEquals(Optional.empty(), map.fixedDrop(1, 0));
        assertEquals(Optional.empty(), map.fixedDrop(2, 0));
        assertEquals(Optional.empty(), map.fixedDrop(-1, 0));
        assertEquals(Optional.empty(), map.fixedDrop(3, 0));
    }

    @Test
    void 아이템_층이_없으면_고정_드롭도_없다() {
        GameMap map = MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1"));

        assertEquals(Optional.empty(), map.fixedDrop(0, 0));
    }

    @Test
    void 고정_드롭은_부서지는_블럭_칸에만_적을_수_있다() {
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]", ".u")));
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "#.", "[spawn]", ".1", "[item]", "u.")));
    }

    @Test
    void 모르는_아이템_글자나_크기가_다른_아이템_층은_읽지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]", "?.")));
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]", "u")));
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]", "u.", "..")));
    }

    @Test
    void 비었거나_두_번_적은_아이템_층은_읽지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]")));
        assertThrows(IllegalArgumentException.class, () -> MapLoader.parse("t", List.of(
                "[floor]", "..", "[block]", "B.", "[spawn]", ".1", "[item]", "u.", "[item]", "u.")));
    }

    @Test
    void 아이템_층이_블럭_층보다_먼저_적혀_있어도_읽는다() {
        GameMap map = MapLoader.parse("t", List.of(
                "[item]", "u.", "[floor]", "..", "[block]", "B.", "[spawn]", ".1"));

        assertEquals(Optional.of(ItemType.ULTRA), map.fixedDrop(0, 0));
    }

    @Test
    void 캠프08의_단단한_블럭은_2단이면_스프링_신발을_3단이면_탱크를_떨어뜨린다() {
        GameMap map = MapLoader.load("camp/camp08");

        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.columns(); col++) {
                Tile tile = map.tileAt(col, row);
                if (tile == Tile.HARD_BLOCK_2) {
                    assertEquals(Optional.of(ItemType.SPRING_SHOE), map.fixedDrop(col, row), col + "," + row);
                } else if (tile == Tile.HARD_BLOCK_3) {
                    assertEquals(Optional.of(ItemType.TANK), map.fixedDrop(col, row), col + "," + row);
                }
            }
        }
    }

    @Test
    void 캠프08의_고정_드롭은_영상에서_확인한_29칸이다() {
        GameMap map = MapLoader.load("camp/camp08");

        Map<ItemType, Integer> counts = new EnumMap<>(ItemType.class);
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.columns(); col++) {
                map.fixedDrop(col, row).ifPresent(item -> counts.merge(item, 1, Integer::sum));
            }
        }

        assertEquals(Map.of(ItemType.SPRING_SHOE, 4, ItemType.TANK, 4, ItemType.ULTRA, 7,
                ItemType.GLOVE, 8, ItemType.SHOE, 6), counts);
        assertEquals(Optional.of(ItemType.ULTRA), map.fixedDrop(2, 1));
        assertEquals(Optional.of(ItemType.GLOVE), map.fixedDrop(0, 2));
        assertEquals(Optional.of(ItemType.SHOE), map.fixedDrop(14, 5));
        assertEquals(Optional.empty(), map.fixedDrop(2, 3));
    }

    @Test
    void 빌리지와_패트릿에는_고정_드롭이_없다() {
        for (String id : List.of("village/village10", "patrit/patrit14")) {
            GameMap map = MapLoader.load(id);
            for (int row = 0; row < map.rows(); row++) {
                for (int col = 0; col < map.columns(); col++) {
                    assertTrue(map.fixedDrop(col, row).isEmpty(), id + " " + col + "," + row);
                }
            }
        }
    }
}

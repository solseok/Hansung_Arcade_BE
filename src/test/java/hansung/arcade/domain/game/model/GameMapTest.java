package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 밀리는 상자를 옮기는 것({@link GameMap#moveBlock})과 고정 드롭 */
class GameMapTest {

    /* 4×2 맵. (1,0) 도로 위에 고정 드롭 울트라가 든 상자, (3,0)에 블럭 */
    private static final List<String> MAP = List.of(
            "[floor]",
            "._..",
            "....",
            "[block]",
            ".P.B",
            "....",
            "[spawn]",
            "....",
            "1...",
            "[item]",
            ".u..",
            "....");

    private final GameMap map = MapLoader.parse("test/move", MAP);

    @Test
    void 상자를_옮기면_원래_칸에는_바닥이_드러나고_고정_드롭도_함께_옮겨진다() {
        map.moveBlock(1, 0, 2, 0);

        assertEquals(Tile.ROAD, map.tileAt(1, 0));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 0));
        assertEquals(Optional.empty(), map.fixedDrop(1, 0));
        assertEquals(Optional.of(ItemType.ULTRA), map.takeFixedDrop(2, 0));
        assertEquals(Optional.empty(), map.fixedDrop(2, 0));
    }

    @Test
    void 밀리는_상자가_없거나_도착_칸이_빈_바닥이_아니면_옮기지_않는다() {
        // 빈 칸, 밀리지 않는 블럭
        assertThrows(IllegalArgumentException.class, () -> map.moveBlock(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> map.moveBlock(3, 0, 3, 1));
        // 맵 밖, 블럭이 있는 칸
        assertThrows(IllegalArgumentException.class, () -> map.moveBlock(1, 0, 1, -1));
        map.moveBlock(1, 0, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> map.moveBlock(2, 0, 3, 0));

        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 0));
        assertEquals(Tile.BLOCK, map.tileAt(3, 0));
        assertEquals(Optional.of(ItemType.ULTRA), map.fixedDrop(2, 0));
    }

    @Test
    void 철로_보강된_상자도_밀리고_남은_맞을_횟수와_고정_드롭이_함께_옮겨진다() {
        // 캠프08 (3,6)은 두 번 더 맞아야 부서지는 b이고 고정 드롭은 스프링 신발이다
        GameMap camp = MapLoader.load("camp/camp08");
        assertEquals(Tile.HARD_BLOCK_2, camp.tileAt(3, 6));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), camp.fixedDrop(3, 6));

        camp.moveBlock(3, 6, 3, 7);

        assertEquals(Tile.FLOOR, camp.tileAt(3, 6));
        assertEquals(Optional.empty(), camp.fixedDrop(3, 6));
        assertEquals(Tile.HARD_BLOCK_2, camp.tileAt(3, 7));
        assertEquals(Optional.of(ItemType.SPRING_SHOE), camp.fixedDrop(3, 7));
        assertTrue(camp.hitBlock(3, 7));
        assertEquals(Tile.HARD_BLOCK_1, camp.tileAt(3, 7));
    }

    @Test
    void 밀리는_것은_상자와_철로_보강된_상자뿐이다() {
        List<Tile> pushable = Arrays.stream(Tile.values()).filter(Tile::isPushable).toList();

        assertEquals(List.of(Tile.PUSH_BOX, Tile.HARD_BLOCK_3, Tile.HARD_BLOCK_2, Tile.HARD_BLOCK_1), pushable);
    }

    @Test
    void 캠프08의_울트라_상자를_밀면_고정_드롭이_상자를_따라간다() {
        GameMap camp = MapLoader.load("camp/camp08");
        assertEquals(Tile.PUSH_BOX, camp.tileAt(2, 1));
        assertEquals(Optional.of(ItemType.ULTRA), camp.fixedDrop(2, 1));

        camp.moveBlock(2, 1, 2, 2);

        assertEquals(Tile.FLOOR, camp.tileAt(2, 1));
        assertEquals(Optional.empty(), camp.fixedDrop(2, 1));
        assertEquals(Tile.PUSH_BOX, camp.tileAt(2, 2));
        assertEquals(Optional.of(ItemType.ULTRA), camp.fixedDrop(2, 2));
    }
}

package hansung.arcade.domain.game.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemTypeTest {

    @Test
    void 맵_글자와_id는_아이템마다_다르다() {
        int count = ItemType.values().length;

        assertEquals(count, Arrays.stream(ItemType.values()).map(ItemType::symbol).distinct().count());
        assertEquals(count, Arrays.stream(ItemType.values()).map(ItemType::id).distinct().count());
    }

    @Test
    void 맵_글자로_아이템을_찾고_빈_칸_글자는_아이템이_아니다() {
        for (ItemType item : ItemType.values()) {
            assertEquals(item, ItemType.fromSymbol(item.symbol()));
        }
        assertNull(ItemType.fromSymbol('.'));
    }
}

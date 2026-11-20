package hansung.arcade.domain.player.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerTest {

    @Test
    void 중복_검사_키는_영문_A부터_Z까지만_소문자로_바꾼다() {
        assertEquals("abz", Player.nicknameKey("AbZ"));
        assertEquals("부기a", Player.nicknameKey("부기A"));
        // 경계 바로 바깥('@' U+0040, '[' U+005B)과 영문 밖의 대문자는 그대로 둔다
        assertEquals("@[", Player.nicknameKey("@["));
        assertEquals("ÀΣＡ", Player.nicknameKey("ÀΣＡ"));
    }

    @Test
    void playerId는_1_이상이다() {
        assertThrows(IllegalArgumentException.class, () -> new Player(0, "부기"));
    }
}

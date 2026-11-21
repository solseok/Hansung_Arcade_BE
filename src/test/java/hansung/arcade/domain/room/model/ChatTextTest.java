package hansung.arcade.domain.room.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 클라이언트 {@code ChatMessage.clean}·{@code byteLength}와 같은 결과를 내는지 확인한다.
 */
class ChatTextTest {

    @Test
    void 영문_숫자_기호는_1_그_밖은_2로_센다() {
        assertEquals(64, ChatText.MAX_BYTES);
        assertEquals(0, ChatText.byteLength(""));
        assertEquals(5, ChatText.byteLength("ab1!?"));
        assertEquals(4, ChatText.byteLength("한글"));
        assertEquals(4, ChatText.byteLength("😀"));
        assertEquals(3, ChatText.byteLength("a가"));
    }

    @Test
    void 줄바꿈과_탭은_공백으로_바꿔_한_줄로_만든다() {
        assertEquals("가 나 다", ChatText.clean("가\n나\t다"));
        assertEquals("a b", ChatText.clean("a b"));
        assertEquals("a b", ChatText.clean("a b"));
    }

    @Test
    void 그_밖의_제어_문자도_공백으로_바꾸고_짝_없는_서로게이트는_지운다() {
        for (String control : new String[]{"\u0007", "\u000B", "\u000C", "\u001F", "\u0085", "\r"}) {
            assertEquals("a b", ChatText.clean("a" + control + "b"), Integer.toHexString(control.charAt(0)));
        }
        assertEquals("ab", ChatText.clean("a\uD800b"));
        assertEquals("ab", ChatText.clean("a\uDC00b"));
        assertEquals("a😀b", ChatText.clean("a😀b"));
    }

    @Test
    void 클라이언트와_같게_빈칸으로_보이는_글자뿐인_글은_빈_글이다() {
        assertEquals("", ChatText.clean("\u3164"));
        assertEquals("", ChatText.clean("\u200B\u200B"));
        assertEquals("", ChatText.clean("\u00A0 \u3164"));
        assertEquals("", ChatText.clean("\u00A0"));
        assertEquals("", ChatText.clean("\u2007\u202F"));
        assertEquals("", ChatText.clean(" \n\t "));
        assertEquals("가 나", ChatText.clean("\u3164가 나\u00A0"));
    }

    @Test
    void 클라이언트와_같게_줄_구분과_글자_방향을_바꾸는_글자를_남기지_않는다() {
        assertEquals("abc", ChatText.clean("a\u202Eb\u200Bc"));
        assertEquals("ab", ChatText.clean("a\uD83Db"));
        assertEquals("안녕 하세요", ChatText.clean("  안녕\n하세요\t "));
    }

    @Test
    void 다듬는_순서는_합치기_한_줄로_앞뒤_떼기_자르기_끝_떼기다() {
        // 앞 빈칸을 먼저 떼고 나서 64를 센다
        assertEquals("a".repeat(64), ChatText.clean("  " + "a".repeat(64)));
        assertEquals("a".repeat(64), ChatText.clean("\n\t" + "a".repeat(64)));
        // 풀어쓴 한글을 먼저 합치고 나서 센다 (합치기 전이면 한 글자가 6바이트)
        assertEquals("한".repeat(32), ChatText.clean("\u1112\u1161\u11AB".repeat(33)));
        // 이모지가 정확히 64에서 끝나면 넣는다
        assertEquals("a".repeat(60) + "😀", ChatText.clean("a".repeat(60) + "😀b"));
    }

    @Test
    void 앞뒤_빈칸_떼기와_빈칸_판정을_방_제목에도_쓴다() {
        assertEquals("제목", ChatText.stripBlank("\u3000\u3164제목\u00A0 "));
        assertEquals("가 나", ChatText.stripBlank(" 가 나 "));
        assertEquals("", ChatText.stripBlank("\u2800 "));
        assertTrue(ChatText.isBlank(' '));
        assertTrue(ChatText.isBlank(0x00A0));
        assertTrue(ChatText.isBlank(0x3164));
        assertTrue(ChatText.isBlank(0x2800));
        assertFalse(ChatText.isBlank('a'));
        assertFalse(ChatText.isBlank(0x200D));
    }

    @Test
    void null은_빈_글이다() {
        assertEquals("", ChatText.clean(null));
    }

    @Test
    void 서식_문자는_지운다() {
        assertEquals("ab", ChatText.clean("a​b"));
        assertEquals("ab", ChatText.clean("a‮b"));
    }

    @Test
    void 풀어쓴_한글은_한_글자로_합친다() {
        assertEquals("한", ChatText.clean("한"));
    }

    @Test
    void 앞뒤_빈칸과_채움_문자를_뗀다() {
        assertEquals("가운데 공백", ChatText.clean("  가운데 공백 　"));
        assertEquals("a", ChatText.clean("ㅤaᅟᅠﾠ⠀"));
        assertEquals("", ChatText.clean(" ㅤ ⠀ "));
        assertEquals("", ChatText.clean(""));
    }

    @Test
    void 길이_64를_넘는_뒷부분은_자른다() {
        assertEquals("a".repeat(64), ChatText.clean("a".repeat(70)));
        assertEquals("가".repeat(32), ChatText.clean("가".repeat(40)));
        assertEquals("a" + "가".repeat(31), ChatText.clean("a" + "가".repeat(32)));
    }

    @Test
    void 이모지는_반으로_자르지_않는다() {
        String text = "a".repeat(62) + "😀";

        assertEquals("a".repeat(62), ChatText.clean(text));
    }

    @Test
    void 자른_뒤_끝에_남은_공백도_뗀다() {
        assertEquals("a".repeat(63), ChatText.clean("a".repeat(63) + " b"));
    }
}

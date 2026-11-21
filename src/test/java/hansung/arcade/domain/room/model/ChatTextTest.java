package hansung.arcade.domain.room.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

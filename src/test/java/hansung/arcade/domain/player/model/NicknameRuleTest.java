package hansung.arcade.domain.player.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 클라이언트 NicknameRuleTest와 같은 경우를 서버에서도 확인하고, 서버만 막는 글자를 확인한다 */
class NicknameRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"가나", "ab", "a1", "12가", "가나다라마바사아", "Player12", "한성Bugi"})
    void 두자에서_여덟자까지_받는다(String nickname) {
        assertEquals(nickname, NicknameRule.validate(nickname));
    }

    @Test
    void 앞뒤_공백은_떼고_센다() {
        assertEquals("가나", NicknameRule.validate("  가나  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "가", "가나다라마바사아자", "Player123"})
    void 길이가_틀리면_받지_않는다(String nickname) {
        assertEquals(NicknameRule.LENGTH_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate(nickname)).getMessage());
    }

    @Test
    void null은_빈_닉네임으로_본다() {
        assertEquals(NicknameRule.LENGTH_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate(null)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㅁㄴㅇㄹ", "ㅏㅏ", "가ㅁ", "부기ㅋㅋ", "ᄀᄀ", "ﾡﾡ"})
    void 완성되지_않은_한글은_받지_않는다(String nickname) {
        assertEquals(NicknameRule.HANGUL_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate(nickname)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12", "12345678", " 2024 ", "１２３"})
    void 숫자로만_된_닉네임은_받지_않는다(String nickname) {
        assertEquals(NicknameRule.DIGITS_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate(nickname)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "꾸\u200B꾸",      // zero-width space
            "꾸\u00A0꾸",      // NBSP
            "꾸\u200D꾸",      // zero-width joiner
            "\uFEFF꾸꾸",      // BOM
            "꾸\t꾸",          // 탭
            "상상 부기",        // 가운데 공백
            "Вugi",            // 키릴 В
            "ΣΑ",              // 그리스 문자
            "Ｂｕｇｉ",          // 전각 영문
            "부기!",
            "부기_1",
            "부기😀"
    })
    void 한글_영문_숫자가_아닌_글자는_받지_않는다(String nickname) {
        assertEquals(NicknameRule.CHARSET_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate(nickname)).getMessage());
    }

    @Test
    void 허용_범위의_경계() {
        assertEquals("가힣", NicknameRule.validate("가힣"));
        assertEquals(NicknameRule.CHARSET_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate("가\uABFF")).getMessage());
        assertEquals(NicknameRule.CHARSET_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate("가\uD7A4")).getMessage());
        // 짝이 없는 서로게이트
        assertEquals(NicknameRule.CHARSET_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate("가\uD800")).getMessage());
    }

    @Test
    void NFC가_영문으로_바꾸는_글자는_바뀐_값으로_받는다() {
        // 켈빈 기호(U+212A)는 NFC에서 영문 K가 된다. 저장도 K로 되므로 따로 등록되지 않는다
        assertEquals("Kim", NicknameRule.validate("\u212Aim"));
        // 옴 기호(U+2126)는 그리스 Ω가 되어 막힌다
        assertEquals(NicknameRule.CHARSET_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate("\u2126m")).getMessage());
    }

    @Test
    void 낱자가_섞이면_글자_종류보다_낱자_문구가_먼저다() {
        assertEquals(NicknameRule.HANGUL_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> NicknameRule.validate("부ㄱ!")).getMessage());
    }

    @Test
    void 자모로_풀어_쓴_완성_글자는_합쳐서_받는다() {
        assertEquals("가나", NicknameRule.validate(Normalizer.normalize("가나", Normalizer.Form.NFD)));
    }
}

package hansung.arcade.domain.player.model;

import java.text.Normalizer;

/**
 * 닉네임 규칙: 앞뒤 공백을 뗀 뒤 2~8자, 자음·모음 낱자(ㅁㄴㅇㄹ, ㅏ 등)가 없는 완성된 글자이고 숫자로만 되어 있지 않다.
 * 여기까지는 클라이언트(hansung.arcade.outgame.NicknameRule)와 같은 규칙·같은 문구다.
 *
 * <p>서버는 하나를 더 막는다. 쓸 수 있는 글자는 완성형 한글(가~힣), 영문, 아라비아 숫자뿐이다.
 * 보이지 않는 글자(zero-width space, BOM 등)나 공백 변종(NBSP), 생김새가 같은 다른 문자(키릴 а와 영문 a)를 섞으면
 * 화면에서는 똑같아 보이는 닉네임이 따로 등록되기 때문이다. 이 실패는 {@link #CHARSET_MESSAGE}로 알리고,
 * 클라이언트는 받은 문구를 그대로 띄운다.</p>
 */
public final class NicknameRule {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 8;
    public static final String LENGTH_MESSAGE = "닉네임은 2~8자로 입력해 주세요.";
    public static final String HANGUL_MESSAGE = "낱자 없이 완성된 한글로 입력해 주세요.";
    public static final String DIGITS_MESSAGE = "숫자로만 된 닉네임은 쓸 수 없습니다.";
    public static final String CHARSET_MESSAGE = "닉네임에는 공백이나 특수문자 없이 한글, 영문, 숫자만 쓸 수 있습니다.";

    private NicknameRule() {
    }

    /**
     * 규칙에 맞으면 다듬은 닉네임을 돌려준다. macOS에서 붙여 넣은 글(NFD)은 먼저 합친다(NFC).
     *
     * @throws IllegalArgumentException 규칙에 어긋나면. 메시지를 그대로 화면에 보여 줄 수 있다
     */
    public static String validate(String raw) {
        String nickname = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFC).strip();
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException(LENGTH_MESSAGE);
        }
        if (nickname.codePoints().anyMatch(NicknameRule::isJamo)) {
            throw new IllegalArgumentException(HANGUL_MESSAGE);
        }
        // 전각 숫자(１２３)처럼 아라비아 숫자가 아닌 숫자도 숫자로 본다
        if (nickname.codePoints().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(DIGITS_MESSAGE);
        }
        if (!nickname.codePoints().allMatch(NicknameRule::isAllowed)) {
            throw new IllegalArgumentException(CHARSET_MESSAGE);
        }
        return nickname;
    }

    /** 쓸 수 있는 글자: 완성형 한글 음절, 영문 대소문자, 아라비아 숫자 */
    static boolean isAllowed(int codePoint) {
        return (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= '0' && codePoint <= '9');
    }

    /** 한글 자모 낱자: 조합용(첫가끝), 호환용(ㄱ~ㅣ), 확장 A·B, 반각 */
    static boolean isJamo(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xD7B0 && codePoint <= 0xD7FF)
                || (codePoint >= 0xFFA0 && codePoint <= 0xFFDC);
    }
}

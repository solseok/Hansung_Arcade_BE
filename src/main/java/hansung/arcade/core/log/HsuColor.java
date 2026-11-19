package hansung.arcade.core.log;

/**
 * 한성대학교 전용색상 (HSU UI 가이드). 클라이언트와 같은 값을 로그·배너에 쓴다.
 * 서버는 화면이 없으므로 AWT 색 변환은 두지 않는다.
 */
public enum HsuColor {
    BLUE(0x013B90),
    DARK_BLUE(0x001655),
    SKY_BLUE(0x227FC4),
    GRAY(0x666565),
    LIGHT_GRAY(0xC9C9CA),
    LIGHT_BLUE(0x9ED8F6),
    LIGHT_PINK(0xF7C7CD),
    LIGHT_GREEN(0xBEDEAE),
    LIGHT_YELLOW(0xFFF3C2);

    private final int rgb;

    HsuColor(int rgb) {
        this.rgb = rgb;
    }

    /** 0xRRGGBB */
    public int rgb() {
        return rgb;
    }
}

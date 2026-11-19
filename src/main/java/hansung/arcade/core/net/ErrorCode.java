package hansung.arcade.core.net;

/**
 * 모든 API에 공통인 에러 code와 기본 안내 문구. API마다 따로 있는 code(ROOM_FULL 등)는
 * {@link ProtocolException#ProtocolException(String, String)}에 문자열로 넘긴다.
 */
public enum ErrorCode {
    /** JSON이 아니거나 필수 필드가 없거나 값이 틀림 */
    BAD_MESSAGE("메시지 형식이 올바르지 않습니다."),
    /** 서버가 모르는 type */
    UNKNOWN_TYPE("알 수 없는 메시지입니다."),
    /** 한 줄이 32 KiB를 넘음. 서버가 연결을 끊는다 */
    MESSAGE_TOO_LONG("메시지가 너무 깁니다."),
    /** 로그인 전에 로그인이 필요한 메시지를 보냄 */
    NOT_LOGGED_IN("로그인이 필요합니다."),
    /** 지금 화면에서 보낼 수 없는 메시지 */
    WRONG_STATE("지금은 보낼 수 없는 메시지입니다."),
    /** 방장만 보낼 수 있는 메시지 */
    NOT_HOST("방장만 할 수 있습니다."),
    /** 처리 중 예상 못 한 예외 */
    SERVER_ERROR("서버에서 오류가 발생했습니다.");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** 화면에 그대로 띄울 수 있는 한국어 한 줄 */
    public String defaultMessage() {
        return defaultMessage;
    }
}

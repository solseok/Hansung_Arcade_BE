package hansung.arcade.domain.session.exception;

/**
 * LOGIN이 실패할 때의 code. Notion "네프 API" LOGIN 명세와 같다.
 */
public final class SessionErrorCode {

    /** 닉네임 규칙에 어긋난다. message는 NicknameRule 문구 그대로 */
    public static final String NICKNAME_INVALID = "NICKNAME_INVALID";
    /** 1P와 2P 닉네임이 같다 */
    public static final String NICKNAME_SAME = "NICKNAME_SAME";
    /** 접속 중인 다른 플레이어가 쓰고 있다 */
    public static final String NICKNAME_DUPLICATE = "NICKNAME_DUPLICATE";
    /** 이 연결은 이미 로그인했다 */
    public static final String ALREADY_LOGGED_IN = "ALREADY_LOGGED_IN";

    private SessionErrorCode() {
    }
}

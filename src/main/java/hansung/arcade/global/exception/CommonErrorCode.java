package hansung.arcade.global.exception;

/**
 * 여러 도메인이 함께 쓰는 실패 code. 비즈니스 계층이 {@code core.net}을 모르게 문자열로 둔다.
 * {@code core.net.ErrorCode}의 같은 이름과 값이 같아야 한다 (CommonErrorCodeTest가 확인한다).
 */
public final class CommonErrorCode {

    /** 요청 모양이 틀렸다 */
    public static final String BAD_MESSAGE = "BAD_MESSAGE";
    /** 로그인해야 쓸 수 있는 API */
    public static final String NOT_LOGGED_IN = "NOT_LOGGED_IN";
    /** 지금 상태에서는 할 수 없는 요청 */
    public static final String WRONG_STATE = "WRONG_STATE";

    public static final String NOT_HOST = "NOT_HOST";

    private CommonErrorCode() {
    }
}

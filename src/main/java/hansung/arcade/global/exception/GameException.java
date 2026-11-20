package hansung.arcade.global.exception;

import java.util.Objects;

/**
 * 규칙상 처리할 수 없는 요청. business 계층이 던지고, web.controller 계층이 같은 code의 실패 응답으로 바꾼다.
 * 비즈니스 계층(business)이 네트워크 계층({@code core.net})을 모르게 하려고 따로 둔다.
 */
public class GameException extends RuntimeException {

    private final String code;

    /**
     * @param code    영문 대문자와 _ (NICKNAME_DUPLICATE)
     * @param message 화면에 그대로 띄울 수 있는 한국어 한 줄
     */
    public GameException(String code, String message) {
        super(Objects.requireNonNull(message));
        this.code = Objects.requireNonNull(code);
    }

    public String code() {
        return code;
    }
}

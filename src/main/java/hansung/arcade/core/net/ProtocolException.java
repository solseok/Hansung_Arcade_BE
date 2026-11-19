package hansung.arcade.core.net;

import java.util.Objects;

/**
 * 요청을 처리할 수 없을 때 핸들러가 던진다. {@link MessageDispatcher}가 받아서
 * id가 있는 요청이면 같은 id의 {@code ok:false} 응답으로, 없으면 {@code ERROR} 푸시로 알린다.
 * 연결은 끊지 않는다.
 *
 * <pre>
 * throw new ProtocolException("ROOM_FULL", "자리가 없습니다.");
 * throw new ProtocolException(ErrorCode.NOT_HOST);
 * </pre>
 */
public class ProtocolException extends Exception {

    private final String code;

    public ProtocolException(ErrorCode code) {
        this(code.name(), code.defaultMessage());
    }

    public ProtocolException(ErrorCode code, String message) {
        this(code.name(), message);
    }

    /**
     * @param code    영문 대문자와 _ (ROOM_FULL)
     * @param message 화면에 그대로 띄울 수 있는 한국어 한 줄
     */
    public ProtocolException(String code, String message) {
        super(Objects.requireNonNull(message));
        this.code = Objects.requireNonNull(code);
    }

    public String code() {
        return code;
    }
}

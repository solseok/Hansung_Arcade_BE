package hansung.arcade.domain.session.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.ErrorCode;
import hansung.arcade.core.net.Message;
import hansung.arcade.core.net.MessageHandler;
import hansung.arcade.core.net.ProtocolException;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.dto.LoginRequest;
import hansung.arcade.domain.session.web.dto.LoginResponse;
import hansung.arcade.global.exception.GameException;

import java.util.Objects;

/**
 * LOGIN (요청 → 응답). 닉네임 1~2개로 로그인하고 플레이어마다 playerId를 받는다.
 *
 * <pre>
 * → {"type":"LOGIN","id":1,"body":{"nicknames":["상상부기","꾸꾸"]}}
 * ← {"type":"LOGIN","id":1,"ok":true,"body":{"players":[{"playerId":12,"nickname":"상상부기"}, ...]}}
 * </pre>
 */
public final class LoginHandler implements MessageHandler {

    public static final String TYPE = "LOGIN";

    private final SessionService sessionService;

    public LoginHandler(SessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService);
    }

    @Override
    public Object handle(Connection connection, Message request) throws ProtocolException {
        requireStringArray(request.body().get("nicknames"));
        LoginRequest body = request.bodyAs(LoginRequest.class);
        try {
            return LoginResponse.from(sessionService.login(Sessions.of(connection), body.nicknames()));
        } catch (GameException e) {
            throw new ProtocolException(e.code(), e.getMessage());
        }
    }

    /**
     * Jackson은 숫자·true를 문자열로 바꿔 주므로("true"라는 닉네임이 된다) 바인딩 전에 모양을 직접 본다.
     * 빠진 경우는 서비스가 개수 오류로 알린다.
     */
    private static void requireStringArray(JsonNode nicknames) throws ProtocolException {
        if (nicknames == null || nicknames.isNull()) {
            return;
        }
        if (!nicknames.isArray()) {
            throw new ProtocolException(ErrorCode.BAD_MESSAGE, "nicknames는 문자열 목록이어야 합니다.");
        }
        for (JsonNode nickname : nicknames) {
            if (!nickname.isTextual()) {
                throw new ProtocolException(ErrorCode.BAD_MESSAGE, "nicknames는 문자열 목록이어야 합니다.");
            }
        }
    }
}

package hansung.arcade.domain.session.web.controller;

import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.model.Session;

import java.util.Objects;

/**
 * TCP 연결이 생기고 끊길 때 세션을 만들고 정리한다.
 *
 * <ol>
 *   <li>접속: HELLO가 송신 큐에 들어간 뒤, 받기 시작 전에 로그인 전 세션을 연결에 붙인다</li>
 *   <li>끊김: BYE든 갑자기 끊겼든 같은 정리(닉네임 풀기)를 한 번만 한다</li>
 * </ol>
 */
public final class SessionListener implements ConnectionListener {

    private final SessionService sessionService;

    public SessionListener(SessionService sessionService) {
        this.sessionService = Objects.requireNonNull(sessionService);
    }

    @Override
    public void onOpen(Connection connection) {
        connection.attach(Session.class, sessionService.open(connection.id()));
    }

    @Override
    public void onClose(Connection connection, String reason) {
        Session session = connection.attachment(Session.class);
        if (session != null) {
            sessionService.close(session);
        }
    }
}

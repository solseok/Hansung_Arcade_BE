package hansung.arcade.domain.session.web.controller;

import hansung.arcade.core.net.Connection;
import hansung.arcade.domain.session.model.Session;

/**
 * 연결에 붙여 둔 {@link Session}을 꺼낸다. {@link SessionListener#onOpen}이 받기 시작 전에 붙이므로 핸들러에서는 늘 있다.
 */
final class Sessions {

    private Sessions() {
    }

    static Session of(Connection connection) {
        Session session = connection.attachment(Session.class);
        if (session == null) {
            // SessionListener를 등록하지 않았을 때만 생긴다. SERVER_ERROR로 알려진다
            throw new IllegalStateException(connection + "에 세션이 없습니다. SessionListener가 등록됐는지 확인하세요.");
        }
        return session;
    }
}

package hansung.arcade.domain.game.web.controller;

import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.core.net.Message;
import hansung.arcade.domain.game.business.EventSender;
import hansung.arcade.domain.game.model.MatchEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 연결 번호 → {@link Connection}. 경기 푸시({@link MatchEvent})를 받을 연결들의 송신 큐에 넣는다.
 * 이미 끊긴 연결은 건너뛴다.
 */
public final class GameConnections implements ConnectionListener, EventSender {

    private final Map<Long, Connection> connections = new ConcurrentHashMap<>();

    @Override
    public void onOpen(Connection connection) {
        connections.put(connection.id(), connection);
    }

    @Override
    public void onClose(Connection connection, String reason) {
        connections.remove(connection.id());
    }

    @Override
    public boolean isConnected(long connectionId) {
        Connection connection = connections.get(connectionId);
        return connection != null && !connection.isClosed();
    }

    @Override
    public void send(MatchEvent event) {
        Message message = Message.push(event.type(), event.body());
        for (Long connectionId : event.recipients()) {
            Connection connection = connections.get(connectionId);
            if (connection != null) {
                connection.send(message);
            }
        }
    }
}

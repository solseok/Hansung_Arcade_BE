package hansung.arcade.domain.room.web.controller;

import hansung.arcade.core.net.*;
import hansung.arcade.domain.room.business.RoomSender;
import hansung.arcade.domain.session.model.Session;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 연결 번호와 소켓을 연결하고 로그인한 로비 참가자에게만 목록을 보낸다.
 */
public final class RoomConnections implements ConnectionListener, RoomSender {

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
    public boolean isConnected(long id) {
        Connection c = connections.get(id);
        return c != null && !c.isClosed();
    }

    @Override
    public void send(Collection<Long> ids, String type, Object body) {
        Message message = Message.push(type, body);
        for (Long id : ids) {
            Connection c = connections.get(id);
            if (c != null) {
                c.send(message);
            }
        }
    }

    @Override
    public void sendToLobby(Collection<Long> inRoom, String type, Object body) {
        Message message = Message.push(type, body);
        for (Connection c : connections.values()) {
            Session session = c.attachment(Session.class);
            if (session != null && !c.isClosed() && !inRoom.contains(c.id()) && session.isLoggedIn()) {
                c.send(message);
            }
        }
    }
}

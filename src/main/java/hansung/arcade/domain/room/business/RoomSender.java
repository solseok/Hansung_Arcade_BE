package hansung.arcade.domain.room.business;

import java.util.Collection;

public interface RoomSender {
    void send(Collection<Long> connectionIds, String type, Object body);

    void sendToLobby(Collection<Long> inRoom, String type, Object body);

    boolean isConnected(long connectionId);
}

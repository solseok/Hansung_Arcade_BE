package hansung.arcade.domain.room.business;

import java.util.Collection;

/**
 * 서비스가 네트워크 구현을 모르고 방·로비 푸시를 송신 큐에 넣는 경계.
 */
public interface RoomSender {

    void send(Collection<Long> connectionIds, String type, Object body);

    void sendToLobby(Collection<Long> inRoom, String type, Object body);

    boolean isConnected(long connectionId);
}

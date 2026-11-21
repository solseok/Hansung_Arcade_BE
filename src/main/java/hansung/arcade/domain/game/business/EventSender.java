package hansung.arcade.domain.game.business;

import hansung.arcade.domain.game.model.MatchEvent;

/**
 * 경기 푸시를 실제 연결에 보낸다. web 계층이 구현한다 (business는 네트워크를 모른다).
 *
 * <p>{@link GameService}는 경기 lock을 잡은 채 이것을 부른다. 그래야 여러 스레드(받는 스레드, 타이머)에서 생긴 푸시가
 * 판정한 순서 그대로 나간다. 그러니 구현은 송신 큐에 넣기만 하고 막히지 않아야 한다.</p>
 */
@FunctionalInterface
public interface EventSender {

    void send(MatchEvent event);

    /** 연결이 아직 열려 있는지. 경기를 열 때 이미 끊긴 연결을 바로 빼는 데 쓴다 */
    default boolean isConnected(long connectionId) {
        return true;
    }
}

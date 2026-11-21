package hansung.arcade.domain.session.model;

import hansung.arcade.domain.player.model.Player;

import java.util.List;

/**
 * TCP 연결 하나의 상태. 연결이 생길 때 만들어 {@code Connection}에 붙여 두고, 끊길 때 닫는다.
 *
 * <p>같은 연결의 받는 스레드(LOGIN 처리)와 끊김을 처리하는 다른 스레드(송신 큐 넘침, 서버 종료)가
 * 함께 만지므로 모든 메서드는 이 객체로 잠근다. 여러 단계를 한 번에 해야 하면 호출하는 쪽에서
 * {@code synchronized (session)}으로 묶는다.
 */
public final class Session {

    private final long connectionId;
    private SessionState state = SessionState.CONNECTED;
    private List<Player> players = List.of();

    public Session(long connectionId) {
        this.connectionId = connectionId;
    }

    public long connectionId() {
        return connectionId;
    }

    public synchronized SessionState state() {
        return state;
    }

    /** 이 연결로 로그인한 플레이어. 1P, 2P 순서. 로그인 전이면 빈 목록 */
    public synchronized List<Player> players() {
        return players;
    }

    public synchronized boolean isLoggedIn() {
        return !players.isEmpty();
    }

    /** 로그인 전(CONNECTED)에서만 부른다. 로비로 넘어간다 */
    public synchronized void login(List<Player> loggedIn) {
        if (state != SessionState.CONNECTED) {
            throw new IllegalStateException("로그인할 수 없는 상태입니다: " + state);
        }
        if (loggedIn.isEmpty()) {
            throw new IllegalArgumentException("플레이어가 없습니다.");
        }
        players = List.copyOf(loggedIn);
        state = SessionState.LOBBY;
    }

    /** 세션을 닫고, 풀어 줘야 할 플레이어를 돌려준다. 두 번째부터는 빈 목록이라 정리가 한 번만 된다 */
    public synchronized List<Player> close() {
        if (state == SessionState.CLOSED) {
            return List.of();
        }
        state = SessionState.CLOSED;
        List<Player> released = players;
        players = List.of();
        return released;
    }

    @Override
    public synchronized String toString() {
        return "Session#" + connectionId + "(" + state + ", " + players + ")";
    }
}

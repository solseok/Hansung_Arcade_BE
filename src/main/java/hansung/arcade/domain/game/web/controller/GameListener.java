package hansung.arcade.domain.game.web.controller;

import hansung.arcade.core.net.Connection;
import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.domain.game.business.GameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 연결이 끊기면(BYE, 상대가 닫음, 15초 무응답) 경기 중이던 플레이어를 PLAYER_LEFT(DISCONNECT)로 뺀다.
 *
 * <p>정리는 다른 스레드(경기 타이머)로 넘긴다. 송신 큐가 넘쳐 끊기는 경우 이 알림은 경기 lock을 잡은 채
 * 푸시를 보내던 스레드에서 오는데, 그 자리에서 경기를 다시 고치면 보내던 목록과 상태가 엇갈리기 때문이다.</p>
 */
public final class GameListener implements ConnectionListener {

    private static final Logger log = LoggerFactory.getLogger(GameListener.class);

    private final GameService gameService;
    private final Executor executor;

    public GameListener(GameService gameService, Executor executor) {
        this.gameService = Objects.requireNonNull(gameService);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void onClose(Connection connection, String reason) {
        long connectionId = connection.id();
        try {
            executor.execute(() -> gameService.disconnect(connectionId));
        } catch (RejectedExecutionException e) {
            // 서버가 꺼지는 중이다. 경기도 함께 사라지므로 정리할 것이 없다
            log.debug("#{} 경기 정리를 건너뜀 (서버 종료 중)", connectionId);
        }
    }
}

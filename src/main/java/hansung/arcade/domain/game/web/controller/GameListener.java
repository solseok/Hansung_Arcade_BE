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
 * <p>정리는 경기 타이머 스레드로 넘긴다. 끊김 알림을 부르는 받는 스레드가 세션 정리와 경기 정리를 한 번에 붙잡지 않고,
 * 경기 정리는 tick과 같은 스레드에서 차례로 돈다.</p>
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
            executor.execute(() -> {
                try {
                    gameService.disconnect(connectionId);
                } catch (RuntimeException e) {
                    // executor가 예외를 삼키므로 여기서 남긴다
                    log.error("#{} 경기 정리 중 오류", connectionId, e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 서버가 꺼지는 중이다. 경기도 함께 사라지므로 정리할 것이 없다
            log.debug("#{} 경기 정리를 건너뜀 (서버 종료 중)", connectionId);
        }
    }
}

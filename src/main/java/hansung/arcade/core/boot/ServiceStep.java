package hansung.arcade.core.boot;

import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.web.controller.GameConnections;
import hansung.arcade.domain.game.web.controller.GameListener;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.player.repository.PlayerRepository;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 2단계: 계층을 아래에서부터 조립한다. repository → business → web.controller(연결 리스너).
 * 저장소를 H2·SQLite로 바꿀 때는 여기서 {@link PlayerRepository} 구현만 바꾼다.
 *
 * <p>경기 타이머 스레드({@code game-timer}) 하나가 {@value #TICK_MS}ms마다 모든 경기를 돌리고(물풍선 폭발, 갇힘 시간 등),
 * 끊긴 연결의 경기 정리도 맡는다.</p>
 */
public final class ServiceStep implements BootStep {

    private static final Logger log = LoggerFactory.getLogger(ServiceStep.class);

    /** 경기 타이머 간격. 물풍선이 터지는 시각의 오차가 이만큼이다 */
    static final long TICK_MS = 20;

    @Override
    public String name() {
        return "서비스 조립";
    }

    @Override
    public void run(ServerContext context) {
        PlayerRepository players = new InMemoryPlayerRepository();
        SessionService sessionService = new SessionService(players);

        context.register(PlayerRepository.class, players);
        context.register(SessionService.class, sessionService);
        context.register(ConnectionListener.class, new SessionListener(sessionService));

        ScheduledExecutorService gameTimer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("game-timer").daemon().factory());
        GameConnections gameConnections = new GameConnections();
        GameService gameService = new GameService(gameConnections, () -> System.nanoTime() / 1_000_000,
                new SecureRandom());
        gameTimer.scheduleWithFixedDelay(() -> {
            try {
                gameService.tick();
            } catch (Throwable e) {
                // 예외가 밖으로 나가면 주기 작업이 취소되어 모든 경기의 시간이 멈춘다
                log.error("경기 타이머 오류", e);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);

        context.register(GameService.class, gameService);
        context.register(GameConnections.class, gameConnections);
        context.register(GameListener.class, new GameListener(gameService, gameTimer));
    }
}

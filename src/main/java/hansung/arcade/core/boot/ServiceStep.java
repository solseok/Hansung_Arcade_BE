package hansung.arcade.core.boot;

import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.room.web.controller.RoomConnections;
import hansung.arcade.domain.room.web.controller.RoomListener;
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
 * 2단계: 세션·방·경기 서비스를 조립하고 경기 종료와 퇴장을 방 상태에 연결한다.
 * 방과 경기의 끊김 정리는 같은 단일 스레드 executor에서 등록 순서대로 처리한다.
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

        RoomConnections roomConnections = new RoomConnections();
        RoomService roomService = new RoomService(roomConnections, gameService, () -> System.nanoTime() / 1_000_000);
        gameService.onFinished(match -> roomService.finishGame(match.matchId()));
        gameService.onExit(roomService::exitGame);
        gameService.onDropped(roomService::exitGame);
        context.register(RoomConnections.class, roomConnections);
        context.register(RoomService.class, roomService);
        context.register(RoomListener.class, new RoomListener(roomService, gameTimer));
        context.register(GameService.class, gameService);
        context.register(GameConnections.class, gameConnections);
        context.register(GameListener.class, new GameListener(gameService, gameTimer));
    }
}

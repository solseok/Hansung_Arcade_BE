package hansung.arcade.core.boot;

import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.player.repository.PlayerRepository;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.SessionListener;

/**
 * 2단계: 계층을 아래에서부터 조립한다. repository → business → web.controller(연결 리스너).
 * 저장소를 H2·SQLite로 바꿀 때는 여기서 {@link PlayerRepository} 구현만 바꾼다.
 */
public final class ServiceStep implements BootStep {

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
    }
}

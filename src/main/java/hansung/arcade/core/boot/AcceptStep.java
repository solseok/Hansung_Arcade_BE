package hansung.arcade.core.boot;

import hansung.arcade.core.net.ConnectionListener;
import hansung.arcade.domain.room.web.controller.RoomConnections;
import hansung.arcade.domain.room.web.controller.RoomListener;
import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.game.web.controller.GameConnections;
import hansung.arcade.domain.game.web.controller.GameListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 5단계: 세션·송신 대상·방 정리·경기 정리 리스너를 순서대로 연결하고 접속을 받는다.
 */
public final class AcceptStep implements BootStep {

    private static final Logger log = LoggerFactory.getLogger(AcceptStep.class);

    @Override
    public String name() {
        return "접속 받기 시작";
    }

    @Override
    public void run(ServerContext context) {
        GameServer server = context.get(GameServer.class);
        // 첫 접속보다 먼저 걸어야 모든 연결에 세션이 붙는다
        server.addListener(context.get(ConnectionListener.class));
        server.addListener(context.get(GameConnections.class));
        server.addListener(context.get(RoomConnections.class));
        // 같은 단일 스레드에서 방 정리가 먼저 실행되어 퇴장 안내가 PLAYER_LEFT보다 먼저 간다.
        server.addListener(context.get(RoomListener.class));
        server.addListener(context.get(GameListener.class));
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(server::close));
        server.start();
        log.info("포트 {}에서 접속을 기다립니다.", server.port());
    }
}

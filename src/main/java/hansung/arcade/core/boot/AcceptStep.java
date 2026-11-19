package hansung.arcade.core.boot;

import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 4단계(마지막): 접속을 받기 시작한다. Ctrl+C 등으로 JVM이 끝날 때 모든 연결을 닫도록 종료 훅도 건다.
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
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("shutdown").unstarted(server::close));
        server.start();
        log.info("포트 {}에서 접속을 기다립니다.", server.port());
    }
}

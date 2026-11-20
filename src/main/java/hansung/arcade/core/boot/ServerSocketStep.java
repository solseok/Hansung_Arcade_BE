package hansung.arcade.core.boot;

import hansung.arcade.core.net.GameServer;
import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.core.server.ServerProperties;

/**
 * 4단계: 설정값 network.port로 {@link java.net.ServerSocket}을 열고 {@link GameServer}를 등록한다.
 * 포트를 이미 다른 프로그램이 쓰고 있으면 여기서 실패한다. 접속은 아직 받지 않는다.
 */
public final class ServerSocketStep implements BootStep {

    static final String PORT = "network.port";
    static final int DEFAULT_PORT = 9000;

    @Override
    public String name() {
        return "서버 소켓 열기";
    }

    @Override
    public void run(ServerContext context) throws Exception {
        int port = context.get(ServerProperties.class).getInt(PORT, DEFAULT_PORT);
        if (port < 0 || port > 65535) {
            throw new IllegalStateException("설정값 %s=%d 는 쓸 수 없습니다. 0~65535만 됩니다.".formatted(PORT, port));
        }
        context.register(GameServer.class, GameServer.bind(port, context.get(MessageDispatcher.class)));
    }
}

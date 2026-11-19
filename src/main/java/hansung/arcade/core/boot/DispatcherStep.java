package hansung.arcade.core.boot;

import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.core.server.ServerContext;

/**
 * 2단계: 메시지 type별 처리기를 모은 {@link MessageDispatcher}를 등록한다.
 * PING·BYE는 처음부터 들어 있다. 로그인·로비·대기실·인게임 API를 구현하면 여기서 등록한다.
 *
 * <pre>
 * dispatcher.register("LOGIN", loginHandler);
 * </pre>
 */
public final class DispatcherStep implements BootStep {

    @Override
    public String name() {
        return "메시지 처리기 등록";
    }

    @Override
    public void run(ServerContext context) {
        MessageDispatcher dispatcher = new MessageDispatcher();
        context.register(MessageDispatcher.class, dispatcher);
    }
}

package hansung.arcade.core.boot;

import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.web.controller.GameHandlers;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.LoginHandler;

/**
 * 3단계: 메시지 type별 처리기(각 도메인의 web.controller)를 모은 {@link MessageDispatcher}를 등록한다.
 * PING·BYE는 처음부터 들어 있다. 로비·대기실 API를 구현하면 여기서 더한다.
 */
public final class DispatcherStep implements BootStep {

    @Override
    public String name() {
        return "메시지 처리기 등록";
    }

    @Override
    public void run(ServerContext context) {
        MessageDispatcher dispatcher = new MessageDispatcher();
        dispatcher.register(LoginHandler.TYPE, new LoginHandler(context.get(SessionService.class)));
        new GameHandlers(context.get(GameService.class)).registerTo(dispatcher);
        context.register(MessageDispatcher.class, dispatcher);
    }
}

package hansung.arcade.core.boot;

import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.room.web.controller.RoomHandlers;
import hansung.arcade.core.server.ServerContext;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.web.controller.GameHandlers;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.web.controller.LoginHandler;

/**
 * 3단계: 로그인·로비·대기실·경기 메시지 처리기를 등록한다.
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
        new RoomHandlers(context.get(RoomService.class), context.get(SessionService.class)).registerTo(dispatcher);
        context.register(MessageDispatcher.class, dispatcher);
    }
}

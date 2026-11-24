package hansung.arcade.domain.game.web.controller;

import hansung.arcade.core.net.MessageDispatcher;
import hansung.arcade.core.net.ProtocolException;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.model.Facing;
import hansung.arcade.domain.game.web.dto.BalloonPlaceRequest;
import hansung.arcade.domain.game.web.dto.BoxPushRequest;
import hansung.arcade.domain.game.web.dto.ItemPickupRequest;
import hansung.arcade.domain.game.web.dto.MoveRequest;
import hansung.arcade.global.exception.GameException;

import java.util.Objects;

/**
 * 인게임에서 클라이언트가 보내는 메시지(모두 응답 없음)를 {@link GameService}로 넘긴다.
 *
 * <ul>
 *   <li>LOADED, GAME_EXIT: 경기 중이 아니면 ERROR(WRONG_STATE)</li>
 *   <li>MOVE, BALLOON_PLACE: 자주 오므로 틀려도 ERROR를 보내지 않고 조용히 버린다</li>
 *   <li>ITEM_PICKUP: 두 사람이 함께 보내면 늦은 쪽은 당연히 실패하므로, 인정되지 않아도 ERROR 없이 버린다</li>
 *   <li>BOX_PUSH: 두 사람이 같은 상자를 함께 밀면 늦은 쪽은 당연히 실패하므로, 밀 수 없어도 ERROR 없이 버린다</li>
 * </ul>
 */
public final class GameHandlers {

    public static final String LOADED = "LOADED";
    public static final String MOVE = "MOVE";
    public static final String BALLOON_PLACE = "BALLOON_PLACE";
    public static final String ITEM_PICKUP = "ITEM_PICKUP";
    public static final String BOX_PUSH = "BOX_PUSH";
    public static final String GAME_EXIT = "GAME_EXIT";

    private final GameService gameService;

    public GameHandlers(GameService gameService) {
        this.gameService = Objects.requireNonNull(gameService);
    }

    public void registerTo(MessageDispatcher dispatcher) {
        dispatcher.register(LOADED, (connection, request) -> {
            try {
                gameService.loaded(connection.id());
            } catch (GameException e) {
                throw new ProtocolException(e.code(), e.getMessage());
            }
            return null;
        });

        dispatcher.register(GAME_EXIT, (connection, request) -> {
            try {
                gameService.exit(connection.id());
            } catch (GameException e) {
                throw new ProtocolException(e.code(), e.getMessage());
            }
            return null;
        });

        dispatcher.register(MOVE, (connection, request) -> {
            MoveRequest body = bodyOrNull(() -> request.bodyAs(MoveRequest.class));
            if (body != null && body.isComplete()) {
                gameService.move(connection.id(), body.playerId(), body.x(), body.y(),
                        Facing.parse(body.facing()), body.moving());
            }
            return null;
        });

        dispatcher.register(BALLOON_PLACE, (connection, request) -> {
            BalloonPlaceRequest body = bodyOrNull(() -> request.bodyAs(BalloonPlaceRequest.class));
            if (body != null && body.isComplete()) {
                gameService.placeBalloon(connection.id(), body.playerId(), body.col(), body.row());
            }
            return null;
        });

        dispatcher.register(ITEM_PICKUP, (connection, request) -> {
            ItemPickupRequest body = bodyOrNull(() -> request.bodyAs(ItemPickupRequest.class));
            if (body != null && body.isComplete()) {
                gameService.pickUpItem(connection.id(), body.playerId(), body.itemId());
            }
            return null;
        });

        dispatcher.register(BOX_PUSH, (connection, request) -> {
            BoxPushRequest body = bodyOrNull(() -> request.bodyAs(BoxPushRequest.class));
            if (body != null && body.isComplete()) {
                gameService.pushBox(connection.id(), body.playerId(), body.col(), body.row(),
                        Facing.parse(body.direction()));
            }
            return null;
        });
    }

    @FunctionalInterface
    private interface BodyReader<T> {
        T read() throws ProtocolException;
    }

    /** 필드 타입이 틀린 body는 null로 바꿔 조용히 버리게 한다 */
    private static <T> T bodyOrNull(BodyReader<T> reader) {
        try {
            return reader.read();
        } catch (ProtocolException e) {
            return null;
        }
    }
}

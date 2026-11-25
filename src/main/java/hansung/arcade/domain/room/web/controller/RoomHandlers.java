package hansung.arcade.domain.room.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.*;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.model.Session;
import hansung.arcade.global.exception.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static hansung.arcade.global.exception.CommonErrorCode.NOT_LOGGED_IN;
import java.util.*;
import java.util.function.Consumer;

/**
 * 메시지별 입력을 검사하고 서비스 오류를 응답 또는 ERROR 푸시로 변환한다.
 */
public final class RoomHandlers {

    private static final Logger log = LoggerFactory.getLogger(RoomHandlers.class);

    private final RoomService rooms;

    private final SessionService sessions;

    public RoomHandlers(RoomService rooms, SessionService sessions) {
        this.rooms = Objects.requireNonNull(rooms);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public void registerTo(MessageDispatcher dispatcher) {
        dispatcher.register("ROOM_LIST", (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.list(id, reply)));
        dispatcher.register("ROOM_CREATE", (c, r) -> handle(c, r,
                (id, p, b, reply) -> rooms.create(id, p, string(b, "title"), integer(b, "capacity"), reply)));
        dispatcher.register("ROOM_JOIN",
                (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.join(id, p, integer(b, "roomNumber"), reply)));
        dispatcher.register("ROOM_QUICK_JOIN",
                (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.quickJoin(id, p, reply)));
        dispatcher.register("ROOM_LEAVE", (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.leave(id, reply)));
        dispatcher.register("CHANGE_MAP",
                (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.changeMap(id, string(b, "mapId"), reply)));
        dispatcher.register("CHANGE_SLOT", (c, r) -> handle(c, r, (id, p, b, reply) -> {
            int slot = integer(b, "slot");
            if (!b.path("open").isBoolean()) {
                throw bad("open은 true나 false여야 합니다.");
            }
            rooms.changeSlot(id, slot, b.get("open").booleanValue(), reply);
        }));
        dispatcher.register("GAME_START", (c, r) -> handle(c, r, (id, p, b, reply) -> rooms.startGame(id, reply)));
        dispatcher.register("SELECT_CHARACTER", (c, r) -> handle(c, r,
                (id, p, b, reply) -> rooms.selectCharacter(id, optionalPlayerId(b), string(b, "character"))));
        dispatcher.register("SELECT_TEAM", (c, r) -> handle(c, r,
                (id, p, b, reply) -> rooms.selectTeam(id, optionalPlayerId(b), string(b, "team"))));
        dispatcher.register("READY", (c, r) -> handle(c, r, (id, p, b, reply) -> {
            if (!b.path("ready").isBoolean()) {
                throw bad("ready는 true나 false여야 합니다.");
            }
            rooms.ready(id, b.get("ready").booleanValue());
        }));
        dispatcher.register("CHAT", (c, r) -> handle(c, r, (id, p, b, reply) -> {
            // 보낸 사람이 없으면 로그인 순서의 1P다
            Long sender = optionalPlayerId(b);
            rooms.chat(id, sender != null ? sender : p.getFirst().playerId(), string(b, "text"));
        }));
    }

    @FunctionalInterface
    private interface Action {

        void run(long id, List<Player> players, JsonNode body, Consumer<Object> reply) throws ProtocolException;
    }

    private Object handle(Connection connection, Message request, Action action) throws ProtocolException {
        ReplyState responded = new ReplyState();
        Consumer<Object> reply = body -> {
            if (request.expectsResponse() && !connection.isClosed()) {
                connection.send(Message.ok(request, body));
                responded.sent = true;
            }
        };
        try {
            Session session = connection.attachment(Session.class);
            if (session == null) {
                throw new GameException(NOT_LOGGED_IN, "로그인이 필요합니다.");
            }
            List<Player> players = sessions.requireLoggedIn(session);
            long id = connection.id();
            JsonNode body = request.body();
            action.run(id, players, body, reply);
        } catch (GameException e) {
            if (!responded.sent) {
                throw new ProtocolException(e.code(), e.getMessage());
            }
            connection.send(Message.error(e.code(), e.getMessage(), request.type()));
        } catch (RuntimeException e) {
            if (!responded.sent) {
                throw e;
            }
            log.error("#{} {} 처리 실패", connection.id(), request.type(), e);
            connection.send(Message.error(ErrorCode.SERVER_ERROR.name(), ErrorCode.SERVER_ERROR.defaultMessage(),
                    request.type()));
        }
        return responded.sent ? MessageHandler.RESPONDED : null;
    }

    private static final class ReplyState {

        private boolean sent;
    }

    private static String string(JsonNode body, String field) throws ProtocolException {
        if (!body.path(field).isTextual()) {
            throw bad(field + topic(field) + " 문자열이어야 합니다.");
        }
        return body.get(field).textValue();
    }

    /** body의 playerId (CHAT의 보낸 사람, SELECT_CHARACTER·SELECT_TEAM의 고른 사람). 없거나 null이면 null */
    private static Long optionalPlayerId(JsonNode body) throws ProtocolException {
        JsonNode player = body.get("playerId");
        if (player == null || player.isNull()) {
            return null;
        }
        if (!player.isIntegralNumber() || !player.canConvertToLong()) {
            throw bad("playerId는 정수여야 합니다.");
        }
        return player.longValue();
    }

    private static int integer(JsonNode body, String field) throws ProtocolException {
        JsonNode node = body.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw bad(field + topic(field) + " 정수여야 합니다.");
        }
        return node.intValue();
    }

    /**
     * 필드 이름 뒤에 붙일 조사. 받침으로 끝나게 읽히는 이름(타이틀·팀·슬롯)은 "은", 나머지는 "는"이다.
     */
    private static String topic(String field) {
        return Set.of("title", "team", "slot").contains(field) ? "은" : "는";
    }

    private static ProtocolException bad(String message) {
        return new ProtocolException(ErrorCode.BAD_MESSAGE, message);
    }
}

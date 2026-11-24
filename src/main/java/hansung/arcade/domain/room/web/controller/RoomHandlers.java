package hansung.arcade.domain.room.web.controller;

import com.fasterxml.jackson.databind.JsonNode;

import hansung.arcade.core.net.*;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.session.business.SessionService;
import hansung.arcade.domain.session.model.Session;
import hansung.arcade.global.exception.GameException;

import java.util.*;
import java.util.function.Consumer;

public final class RoomHandlers {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RoomHandlers.class);
    private final RoomService rooms;
    private final SessionService sessions;

    public RoomHandlers(RoomService rooms, SessionService sessions) {
        this.rooms = Objects.requireNonNull(rooms);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public void registerTo(MessageDispatcher dispatcher) {
        for (String type :
                List.of(
                        "ROOM_LIST",
                        "ROOM_CREATE",
                        "ROOM_JOIN",
                        "ROOM_QUICK_JOIN",
                        "ROOM_LEAVE",
                        "READY",
                        "SELECT_CHARACTER",
                        "SELECT_TEAM",
                        "CHANGE_MAP",
                        "CHAT",
                        "GAME_START")) dispatcher.register(type, this::handle);
    }

    private Object handle(Connection connection, Message request) throws ProtocolException {
        boolean[] responded = {false};
        Consumer<Object> reply =
                body -> {
                    if (request.expectsResponse() && !connection.isClosed()) {
                        connection.send(Message.ok(request, body));
                        responded[0] = true;
                    }
                };
        try {
            Session session = connection.attachment(Session.class);
            if (session == null) throw new GameException("NOT_LOGGED_IN", "로그인이 필요합니다.");
            List<Player> players = sessions.requireLoggedIn(session);
            long id = connection.id();
            JsonNode body = request.body();
            switch (request.type()) {
                case "ROOM_LIST" -> rooms.list(id, reply);
                case "ROOM_CREATE" ->
                        rooms.create(
                                id,
                                players,
                                string(body, "title"),
                                integer(body, "capacity"),
                                reply);
                case "ROOM_JOIN" -> rooms.join(id, players, integer(body, "roomNumber"), reply);
                case "ROOM_QUICK_JOIN" -> rooms.quickJoin(id, players, reply);
                case "ROOM_LEAVE" -> rooms.leave(id, reply);
                case "CHANGE_MAP" -> rooms.changeMap(id, string(body, "mapId"), reply);
                case "GAME_START" -> rooms.startGame(id, reply);
                case "READY" -> {
                    if (!body.path("ready").isBoolean()) throw bad("ready는 true나 false여야 합니다.");
                    rooms.ready(id, body.get("ready").booleanValue());
                }
                case "SELECT_CHARACTER" -> rooms.selectCharacter(id, string(body, "character"));
                case "SELECT_TEAM" -> rooms.selectTeam(id, string(body, "team"));
                case "CHAT" -> {
                    JsonNode player = body.get("playerId");
                    long playerId = players.getFirst().playerId();
                    if (player != null && !player.isNull()) {
                        if (!player.isIntegralNumber() || !player.canConvertToLong())
                            throw bad("playerId는 정수여야 합니다.");
                        playerId = player.longValue();
                    }
                    rooms.chat(id, playerId, string(body, "text"));
                }
                default -> throw new IllegalStateException(request.type());
            }
        } catch (GameException e) {
            if (!responded[0]) throw new ProtocolException(e.code(), e.getMessage());
            connection.send(Message.error(e.code(), e.getMessage(), request.type()));
        } catch (RuntimeException e) {
            log.error("#{} {} 처리 실패", connection.id(), request.type(), e);
            if (!responded[0]) throw e;
            connection.send(
                    Message.error(
                            ErrorCode.SERVER_ERROR.name(),
                            ErrorCode.SERVER_ERROR.defaultMessage(),
                            request.type()));
        }
        return MessageHandler.RESPONDED;
    }

    private static String string(JsonNode body, String field) throws ProtocolException {
        if (!body.path(field).isTextual())
            throw bad(
                    field + (Set.of("title", "team").contains(field) ? "은" : "는") + " 문자열이어야 합니다.");
        return body.get(field).textValue();
    }

    private static int integer(JsonNode body, String field) throws ProtocolException {
        JsonNode node = body.path(field);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) throw bad(field + "는 정수여야 합니다.");
        return node.intValue();
    }

    private static ProtocolException bad(String message) {
        return new ProtocolException(ErrorCode.BAD_MESSAGE, message);
    }
}

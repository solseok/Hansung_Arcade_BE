package hansung.arcade.domain.room.business;

import static hansung.arcade.domain.room.exception.RoomErrorCode.*;
import static hansung.arcade.global.exception.CommonErrorCode.*;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.model.*;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.domain.room.model.*;
import hansung.arcade.global.exception.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 전역 잠금 하나로 방 번호, 정원과 로비 목록의 일관성을 보장한다.
 * 응답과 푸시도 잠금 안에서 송신 큐에 넣어 모든 연결에 같은 변경 순서를 보장한다.
 * 경기 시작까지 잠금을 유지하여 시작 도중 퇴장한 참가자가 경기에 남지 않게 한다.
 */
public final class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    static final int MAX_ROOMS = 100;

    static final int MAX_CHATS_PER_WINDOW = 3;

    static final long CHAT_WINDOW_MS = 1000;

    private final Object lock = new Object();

    private final RoomSender sender;

    private final GameService games;

    private final LongSupplier clock;

    private final NavigableMap<Integer, Room> rooms = new TreeMap<>();

    private final Map<Long, Room> memberships = new HashMap<>();

    private final Map<Long, Room> matches = new HashMap<>();

    private final Map<Long, String> characters = new HashMap<>();

    private final Map<Long, Team> teams = new HashMap<>();

    private final Map<Long, Deque<Long>> chats = new HashMap<>();

    public RoomService(RoomSender sender, GameService games, LongSupplier clock) {
        this.sender = Objects.requireNonNull(sender);
        this.games = Objects.requireNonNull(games);
        this.clock = Objects.requireNonNull(clock);
    }

    public void list(long connection, Consumer<Object> reply) {
        synchronized (lock) {
            if (memberships.containsKey(connection)) {
                throw error(WRONG_STATE, "방 안에서는 목록을 볼 수 없습니다.");
            }
            reply.accept(RoomMessages.list(rooms.values()));
        }
    }

    public void create(long connection, List<Player> players, String title, int capacity, Consumer<Object> reply) {
        synchronized (lock) {
            requireLobby(connection);
            Room room;
            try {
                room = new Room(rooms.isEmpty() ? 1 : rooms.lastKey() + 1, title == null ? "" : title, capacity);
            } catch (IllegalArgumentException e) {
                if (TITLE_INVALID.equals(e.getMessage())) {
                    throw error(TITLE_INVALID, "방 제목은 1~30자로 입력해 주세요.");
                }
                throw error(CAPACITY_INVALID, "정원은 2~8명으로 선택해 주세요.");
            }
            if (capacity < players.size()) {
                throw error(CAPACITY_TOO_SMALL, "정원이 함께 들어가는 인원보다 적습니다.");
            }
            if (rooms.size() >= MAX_ROOMS) {
                throw error(ROOM_LIMIT, "방이 너무 많습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            seat(room, connection, players);
            rooms.put(room.number(), room);
            reply.accept(RoomMessages.state(room));
            lobby();
        }
    }

    public void join(long connection, List<Player> players, int number, Consumer<Object> reply) {
        synchronized (lock) {
            requireLobby(connection);
            Room room = rooms.get(number);
            if (room == null) {
                throw error(ROOM_NOT_FOUND, "없어진 방입니다. 목록을 다시 확인해 주세요.");
            }
            if (room.status() == RoomStatus.PLAYING) {
                throw error(ROOM_PLAYING, "게임 중인 방입니다.");
            }
            if (!room.canSeat(players.size())) {
                throw error(ROOM_FULL, "자리가 없습니다.");
            }
            enter(room, connection, players, reply);
        }
    }

    public void quickJoin(long connection, List<Player> players, Consumer<Object> reply) {
        synchronized (lock) {
            requireLobby(connection);
            Room room = rooms.values().stream().filter(r -> r.canSeat(players.size())).findFirst()
                    .orElseThrow(() -> error(NO_ROOM, "입장 가능한 방이 없습니다."));
            enter(room, connection, players, reply);
        }
    }

    public void leave(long connection, Consumer<Object> reply) {
        synchronized (lock) {
            waiting(connection, "방에 있지 않습니다.");
            remove(connection, reply);
        }
    }

    public void exitGame(long connection) {
        synchronized (lock) {
            remove(connection, null);
        }
    }

    public void disconnect(long connection) {
        synchronized (lock) {
            remove(connection, null);
            characters.remove(connection);
            teams.remove(connection);
            chats.remove(connection);
        }
    }

    public void ready(long connection, boolean value) {
        synchronized (lock) {
            Room room = waiting(connection, "대기실에서만 준비할 수 있습니다.");
            if (room.ready(connection, value)) {
                state(room);
            }
        }
    }

    public void selectCharacter(long connection, String value) {
        synchronized (lock) {
            if (!Mascot.RANDOM.equals(value) && Mascot.ALL.stream().noneMatch(m -> m.id().equals(value))) {
                throw error(BAD_MESSAGE, "알 수 없는 캐릭터입니다.");
            }
            Room room = waiting(connection, "대기실에서만 캐릭터를 고를 수 있습니다.");
            characters.put(connection, value);
            if (room.selectCharacter(connection, value)) {
                state(room);
            }
        }
    }

    public void selectTeam(long connection, String value) {
        synchronized (lock) {
            Team team;
            try {
                team = Team.valueOf(value);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw error(BAD_MESSAGE, "알 수 없는 팀입니다.");
            }
            Room room = waiting(connection, "대기실에서만 팀을 고를 수 있습니다.");
            teams.put(connection, team);
            if (room.selectTeam(connection, team)) {
                state(room);
            }
        }
    }

    public void changeMap(long connection, String mapId, Consumer<Object> reply) {
        synchronized (lock) {
            Room room = waiting(connection, "대기실에서만 맵을 고를 수 있습니다.");
            if (!room.isHostConnection(connection)) {
                throw error(NOT_HOST, "방장만 맵을 선택할 수 있습니다.");
            }
            if (!GameMaps.selectable(mapId)) {
                throw error(MAP_INVALID, "지원하지 않는 맵입니다.");
            }
            if (room.size() > GameMaps.capacityOf(mapId)) {
                throw error(MAP_TOO_SMALL, "%s은(는) 최대 %d명이라 지금 인원 %d명으로는 고를 수 없습니다.".formatted(GameMaps.name(mapId),
                        GameMaps.capacityOf(mapId), room.size()));
            }
            boolean changed = room.changeMap(mapId);
            reply.accept(null);
            if (changed) {
                state(room);
                lobby();
            }
        }
    }

    public void chat(long connection, long playerId, String text) {
        synchronized (lock) {
            Room room = memberships.get(connection);
            if (room == null) {
                throw error(WRONG_STATE, "방 안에서만 채팅할 수 있습니다.");
            }
            RoomMember member = room.membersOf(connection).stream().filter(m -> m.playerId() == playerId).findFirst()
                    .orElseThrow(() -> error(BAD_MESSAGE, "내 플레이어가 아닙니다."));
            String clean = ChatText.clean(text);
            if (clean.isEmpty()) {
                return;
            }
            long now = clock.getAsLong();
            Deque<Long> history = chats.computeIfAbsent(connection, k -> new ArrayDeque<>());
            while (!history.isEmpty() && now - history.peekFirst() >= CHAT_WINDOW_MS) {
                history.removeFirst();
            }
            if (history.size() >= MAX_CHATS_PER_WINDOW) {
                return;
            }
            history.addLast(now);
            sender.send(connections(room), RoomMessages.CHAT_MESSAGE, RoomMessages.chat(member, clean));
        }
    }

    public void startGame(long connection, Consumer<Object> reply) {
        Room room;
        List<GameService.Participant> participants;
        String map;
        synchronized (lock) {
            room = waiting(connection, "대기실에서만 게임을 시작할 수 있습니다.");
            if (!room.isHostConnection(connection)) {
                throw error(NOT_HOST, "방장만 게임을 시작할 수 있습니다.");
            }
            if (room.size() < 2) {
                throw error(NOT_ENOUGH_PLAYERS, "2명 이상 모여야 시작할 수 있습니다.");
            }
            if (!room.allReady()) {
                throw error(NOT_ALL_READY, "모두 준비해야 시작할 수 있습니다.");
            }
            if (room.teamCount() < 2) {
                throw error(ONE_TEAM, "두 팀 이상이어야 시작할 수 있습니다.");
            }
            participants = room.members().stream().map(m -> new GameService.Participant(m.playerId(), m.connectionId(),
                    m.nickname(), m.character(), m.team())).toList();
            map = room.mapId();
            room.startPlaying();
            reply.accept(null);
            // GameService의 종료 콜백은 경기 잠금 밖에서 실행되므로 방 → 경기 순서를 유지한다.
            Match match;
            try {
                match = games.start(map, participants);
            } catch (RuntimeException e) {
                log.error("방 #{} 경기 시작 실패", room.number(), e);
                room.cancelPlaying();
                state(room);
                notice(room, "게임을 시작하지 못했습니다. 다시 시도해 주세요.");
                // 시작 전체가 같은 잠금 안이므로 PLAYING 목록은 아직 로비에 전송되지 않았다.
                return;
            }
            if (rooms.get(room.number()) != room) {
                return;
            }
            if (match.isFinished()) {
                room.finishPlaying();
                state(room);
            } else {
                matches.put(match.matchId(), room);
            }
            lobby();
        }
    }

    public void finishGame(long matchId) {
        synchronized (lock) {
            Room room = matches.remove(matchId);
            if (room == null || rooms.get(room.number()) != room) {
                return;
            }
            room.finishPlaying();
            state(room);
            lobby();
        }
    }

    private void requireLobby(long connection) {
        if (memberships.containsKey(connection)) {
            throw error(ALREADY_IN_ROOM, "이미 방에 입장해 있습니다.");
        }
        if (!sender.isConnected(connection)) {
            throw error(WRONG_STATE, "이미 끊긴 연결입니다.");
        }
    }

    private Room waiting(long connection, String message) {
        Room room = memberships.get(connection);
        if (room == null) {
            throw error(WRONG_STATE, message);
        }
        if (room.status() != RoomStatus.WAITING) {
            throw error(WRONG_STATE, "게임 중에는 할 수 없습니다.");
        }
        return room;
    }

    private List<RoomMember> seat(Room room, long connection, List<Player> players) {
        List<RoomMember> added = room.seat(connection, players, characters.getOrDefault(connection, "sangsangbugi"),
                teams.getOrDefault(connection, Team.RED));
        memberships.put(connection, room);
        return added;
    }

    private void enter(Room room, long connection, List<Player> players, Consumer<Object> reply) {
        List<RoomMember> added = seat(room, connection, players);
        reply.accept(RoomMessages.state(room));
        sender.send(connections(room).stream().filter(id -> id != connection).toList(), RoomMessages.ROOM_STATE,
                RoomMessages.state(room));
        added.forEach(m -> notice(room, m.nickname() + "님이 들어왔습니다."));
        lobby();
    }

    private void remove(long connection, Consumer<Object> reply) {
        Room room = memberships.remove(connection);
        if (room == null) {
            return;
        }
        long oldHost = room.hostId();
        List<RoomMember> removed = room.unseat(connection);
        if (room.isEmpty()) {
            rooms.remove(room.number());
            matches.values().removeIf(r -> r == room);
        }
        if (reply != null) {
            reply.accept(null);
        }
        if (!room.isEmpty()) {
            if (room.status() == RoomStatus.WAITING) {
                state(room);
            }
            removed.forEach(m -> notice(room, m.nickname() + "님이 나갔습니다."));
            if (room.status() == RoomStatus.WAITING && oldHost != room.hostId()) {
                room.members().stream().filter(m -> m.playerId() == room.hostId()).findFirst()
                        .ifPresent(m -> notice(room, m.nickname() + "님이 방장이 되었습니다."));
            }
        }
        lobby();
    }

    private List<Long> connections(Room room) {
        return room.members().stream().map(RoomMember::connectionId).distinct().toList();
    }

    private void state(Room room) {
        sender.send(connections(room), RoomMessages.ROOM_STATE, RoomMessages.state(room));
    }

    private void notice(Room room, String text) {
        sender.send(connections(room), RoomMessages.CHAT_MESSAGE, RoomMessages.notice(text));
    }

    private void lobby() {
        sender.sendToLobby(Set.copyOf(memberships.keySet()), RoomMessages.LOBBY_ROOMS,
                RoomMessages.list(rooms.values()));
    }

    private static GameException error(String code, String message) {
        return new GameException(code, message);
    }
}

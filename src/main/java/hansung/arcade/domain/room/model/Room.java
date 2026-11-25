package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.GameMaps;
import hansung.arcade.domain.game.model.Team;
import hansung.arcade.domain.player.model.Player;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.text.Normalizer;

import static hansung.arcade.domain.room.exception.RoomErrorCode.CAPACITY_INVALID;
import static hansung.arcade.domain.room.exception.RoomErrorCode.TITLE_INVALID;

/**
 * 자리·방장·준비 상태와 방 제목·정원 규칙을 관리한다. 외부 동시 접근은 RoomService가 잠근다.
 */
public final class Room {

    static final int MIN_CAPACITY = 2;

    static final int MAX_CAPACITY = 8;

    static final int MAX_TITLE_LENGTH = 30;

    private final int number;

    private final int configuredCapacity;

    private final String title;

    private final TreeMap<Integer, RoomMember> seats = new TreeMap<>();

    private String mapId = GameMaps.DEFAULT_ID;

    private RoomStatus status = RoomStatus.WAITING;

    private long hostId;

    public Room(int number, String title, int capacity) {
        Objects.requireNonNull(title);
        title = normalizeTitle(title);
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException(CAPACITY_INVALID);
        }
        this.number = number;
        this.title = title;
        this.configuredCapacity = capacity;
    }

    /**
     * 화면과 서비스에서 같은 한글 정규화·빈칸·길이 규칙을 사용한다.
     */
    private static String normalizeTitle(String title) {
        String name = ChatText.stripBlank(Normalizer.normalize(title, Normalizer.Form.NFC));
        boolean invalidCharacter = name.codePoints()
                .anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || c >= 0xd800 && c <= 0xdfff);
        boolean invisible = name.codePoints()
                .allMatch(c -> ChatText.isBlank(c) || Character.getType(c) == Character.FORMAT);
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_TITLE_LENGTH || invalidCharacter
                || invisible) {
            throw new IllegalArgumentException(TITLE_INVALID);
        }
        return name;
    }

    public int number() {
        return number;
    }

    public String title() {
        return title;
    }

    public int capacity() {
        return Math.min(configuredCapacity, GameMaps.capacityOf(mapId));
    }

    public String mapId() {
        return mapId;
    }

    public RoomStatus status() {
        return status;
    }

    public long hostId() {
        return hostId;
    }

    public List<RoomMember> members() {
        return List.copyOf(seats.values());
    }

    public int size() {
        return seats.size();
    }

    public boolean isEmpty() {
        return seats.isEmpty();
    }

    public boolean canSeat(int count) {
        return count > 0 && status == RoomStatus.WAITING && size() + count <= capacity();
    }

    public List<RoomMember> membersOf(long connection) {
        return members().stream().filter(m -> m.connectionId() == connection).toList();
    }

    public boolean isHostConnection(long connection) {
        return seats.values().stream().anyMatch(m -> m.playerId() == hostId && m.connectionId() == connection);
    }

    public List<RoomMember> seat(long connection, List<Player> players, String character, Team team) {
        return seat(connection, players, player -> character, player -> team);
    }

    /** 플레이어마다 다른 캐릭터·팀으로 앉힌다 (1P·2P가 마지막으로 고른 값) */
    public List<RoomMember> seat(long connection, List<Player> players, Function<Player, String> character,
                                 Function<Player, Team> team) {
        if (players.isEmpty()) {
            throw new IllegalArgumentException();
        }
        if (!canSeat(players.size())) {
            throw new IllegalStateException();
        }
        List<RoomMember> added = new ArrayList<>();
        for (Player p : players) {
            int slot = 0;
            while (seats.containsKey(slot)) {
                slot++;
            }
            RoomMember m = new RoomMember(p.playerId(), connection, p.nickname(), slot, character.apply(p), team.apply(p),
                    false);
            seats.put(slot, m);
            added.add(m);
            if (hostId == 0) {
                hostId = p.playerId();
            }
        }
        return List.copyOf(added);
    }

    public List<RoomMember> unseat(long connection) {
        List<RoomMember> removed = membersOf(connection);
        boolean host = isHostConnection(connection);
        removed.forEach(m -> seats.remove(m.slot()));
        if (host) {
            hostId = isEmpty() ? 0 : seats.firstEntry().getValue().playerId();
            if (!isEmpty()) {
                long next = seats.firstEntry().getValue().connectionId();
                seats.replaceAll((s, m) -> m.connectionId() == next ? m.withReady(false) : m);
            }
        }
        return removed;
    }

    public boolean ready(long connection, boolean value) {
        if (isHostConnection(connection) || membersOf(connection).stream().noneMatch(m -> m.ready() != value)) {
            return false;
        }
        seats.replaceAll((s, m) -> m.connectionId() == connection ? m.withReady(value) : m);
        return true;
    }

    public boolean selectCharacter(long connection, String value) {
        return selectCharacter(connection, null, value);
    }

    /**
     * 캐릭터를 바꾼다. playerId가 null이면 그 연결의 플레이어(1P·2P) 모두, 아니면 그 플레이어만 바꾼다.
     * 바뀐 것이 있으면 준비는 그 연결 전체가 풀린다 (준비는 연결 단위다).
     *
     * @return 바뀐 것이 있는지. playerId가 이 연결의 플레이어가 아니면 아무것도 바꾸지 않고 false
     */
    public boolean selectCharacter(long connection, Long playerId, String value) {
        if (picked(connection, playerId).noneMatch(m -> !m.character().equals(value))) {
            return false;
        }
        seats.replaceAll((s, m) -> m.connectionId() != connection ? m
                : isPicked(m, playerId) ? m.withCharacter(value) : m.withReady(false));
        return true;
    }

    public boolean selectTeam(long connection, Team value) {
        return selectTeam(connection, null, value);
    }

    /** 팀을 바꾼다. playerId와 준비는 {@link #selectCharacter(long, Long, String)}와 같다 */
    public boolean selectTeam(long connection, Long playerId, Team value) {
        if (picked(connection, playerId).noneMatch(m -> m.team() != value)) {
            return false;
        }
        seats.replaceAll((s, m) -> m.connectionId() != connection ? m
                : isPicked(m, playerId) ? m.withTeam(value) : m.withReady(false));
        return true;
    }

    /** 이 연결의 플레이어 가운데 고른 사람 (playerId가 null이면 모두) */
    private Stream<RoomMember> picked(long connection, Long playerId) {
        return membersOf(connection).stream().filter(m -> isPicked(m, playerId));
    }

    private static boolean isPicked(RoomMember member, Long playerId) {
        return playerId == null || member.playerId() == playerId;
    }

    public boolean changeMap(String value) {
        if (!GameMaps.selectable(value)) {
            throw new IllegalArgumentException();
        }
        if (size() > Math.min(configuredCapacity, GameMaps.capacityOf(value))) {
            throw new IllegalStateException();
        }
        if (mapId.equals(value)) {
            return false;
        }
        mapId = value;
        for (RoomMember m : members()) {
            if (m.slot() >= capacity()) {
                seats.remove(m.slot());
                int slot = 0;
                while (seats.containsKey(slot)) {
                    slot++;
                }
                seats.put(slot, m.withSlot(slot));
            }
        }
        return true;
    }

    public void startPlaying() {
        status = RoomStatus.PLAYING;
    }

    public void cancelPlaying() {
        status = RoomStatus.WAITING;
    }

    public void finishPlaying() {
        status = RoomStatus.WAITING;
        seats.replaceAll((s, m) -> m.withReady(false));
    }

    public boolean allReady() {
        return seats.values().stream().allMatch(m -> isHostConnection(m.connectionId()) || m.ready());
    }

    public long teamCount() {
        return seats.values().stream().map(RoomMember::team).distinct().count();
    }
}

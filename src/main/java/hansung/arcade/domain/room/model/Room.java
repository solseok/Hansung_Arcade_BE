package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.GameMaps;
import hansung.arcade.domain.game.model.Team;
import hansung.arcade.domain.player.model.Player;

import java.util.*;

public final class Room {
    static final int MIN_CAPACITY = 2, MAX_CAPACITY = 8, MAX_TITLE_LENGTH = 30;
    private final int number, configuredCapacity;
    private final String title;
    private final TreeMap<Integer, RoomMember> seats = new TreeMap<>();
    private String mapId = GameMaps.DEFAULT_ID;
    private RoomStatus status = RoomStatus.WAITING;
    private long hostId;

    public Room(int number, String title, int capacity) {
        Objects.requireNonNull(title);
        if (title.isBlank()
                || title.codePointCount(0, title.length()) > MAX_TITLE_LENGTH
                || capacity < 2
                || capacity > 8) throw new IllegalArgumentException();
        this.number = number;
        this.title = title;
        this.configuredCapacity = capacity;
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
        return seats.values().stream()
                .anyMatch(m -> m.playerId() == hostId && m.connectionId() == connection);
    }

    public List<RoomMember> seat(
            long connection, List<Player> players, String character, Team team) {
        if (players.isEmpty()) throw new IllegalArgumentException();
        if (!canSeat(players.size())) throw new IllegalStateException();
        List<RoomMember> added = new ArrayList<>();
        for (Player p : players) {
            int slot = 0;
            while (seats.containsKey(slot)) slot++;
            RoomMember m =
                    new RoomMember(
                            p.playerId(), connection, p.nickname(), slot, character, team, false);
            seats.put(slot, m);
            added.add(m);
            if (hostId == 0) hostId = p.playerId();
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
        if (isHostConnection(connection)
                || membersOf(connection).stream().noneMatch(m -> m.ready() != value)) return false;
        seats.replaceAll((s, m) -> m.connectionId() == connection ? m.withReady(value) : m);
        return true;
    }

    public boolean selectCharacter(long connection, String value) {
        if (membersOf(connection).stream().noneMatch(m -> !m.character().equals(value)))
            return false;
        seats.replaceAll((s, m) -> m.connectionId() == connection ? m.withCharacter(value) : m);
        return true;
    }

    public boolean selectTeam(long connection, Team value) {
        if (membersOf(connection).stream().noneMatch(m -> m.team() != value)) return false;
        seats.replaceAll((s, m) -> m.connectionId() == connection ? m.withTeam(value) : m);
        return true;
    }

    public boolean changeMap(String value) {
        if (!GameMaps.selectable(value)) throw new IllegalArgumentException();
        if (size() > Math.min(configuredCapacity, GameMaps.capacityOf(value)))
            throw new IllegalStateException();
        if (mapId.equals(value)) return false;
        mapId = value;
        for (RoomMember m : members())
            if (m.slot() >= capacity()) {
                seats.remove(m.slot());
                int slot = 0;
                while (seats.containsKey(slot)) slot++;
                seats.put(slot, m.withSlot(slot));
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
        return seats.values().stream()
                .allMatch(m -> isHostConnection(m.connectionId()) || m.ready());
    }

    public long teamCount() {
        return seats.values().stream().map(RoomMember::team).distinct().count();
    }
}

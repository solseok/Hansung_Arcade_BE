package hansung.arcade.domain.room.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class RoomMessages {
    public static final String ROOM_STATE = "ROOM_STATE",
            LOBBY_ROOMS = "LOBBY_ROOMS",
            CHAT_MESSAGE = "CHAT_MESSAGE";

    private RoomMessages() {}

    public record RoomList(List<RoomSummary> rooms) {
        public RoomList {
            rooms = List.copyOf(rooms);
        }
    }

    public record RoomSummary(
            int number, String title, int players, int capacity, String mapId, String status) {}

    public record RoomInfo(int number, String title, int capacity, String mapId, String status) {}

    public record SeatedPlayer(
            long playerId,
            String nickname,
            int slot,
            String character,
            String team,
            boolean ready) {}

    public record RoomState(RoomInfo room, long hostId, List<SeatedPlayer> players) {
        public RoomState {
            players = List.copyOf(players);
        }
    }

    public record ChatMessage(long senderId, String sender, String text) {}

    public static RoomList list(Collection<Room> rooms) {
        return new RoomList(
                rooms.stream()
                        .sorted(Comparator.comparingInt(Room::number))
                        .map(
                                r ->
                                        new RoomSummary(
                                                r.number(),
                                                r.title(),
                                                r.size(),
                                                r.capacity(),
                                                r.mapId(),
                                                r.status().name()))
                        .toList());
    }

    public static RoomState state(Room r) {
        return new RoomState(
                new RoomInfo(r.number(), r.title(), r.capacity(), r.mapId(), r.status().name()),
                r.hostId(),
                r.members().stream()
                        .map(
                                m ->
                                        new SeatedPlayer(
                                                m.playerId(),
                                                m.nickname(),
                                                m.slot(),
                                                m.character(),
                                                m.team().name(),
                                                m.ready()))
                        .toList());
    }

    public static ChatMessage chat(RoomMember member, String text) {
        return new ChatMessage(member.playerId(), member.nickname(), text);
    }

    public static ChatMessage notice(String text) {
        return new ChatMessage(0, "", text);
    }
}

package hansung.arcade.domain.room.exception;

public final class RoomErrorCode {
    public static final String TITLE_INVALID = "TITLE_INVALID";
    public static final String CAPACITY_INVALID = "CAPACITY_INVALID";
    public static final String CAPACITY_TOO_SMALL = "CAPACITY_TOO_SMALL";
    public static final String ALREADY_IN_ROOM = "ALREADY_IN_ROOM";
    public static final String ROOM_LIMIT = "ROOM_LIMIT";
    public static final String ROOM_NOT_FOUND = "ROOM_NOT_FOUND";
    public static final String ROOM_PLAYING = "ROOM_PLAYING";
    public static final String ROOM_FULL = "ROOM_FULL";
    public static final String NO_ROOM = "NO_ROOM";
    public static final String MAP_INVALID = "MAP_INVALID";
    public static final String MAP_TOO_SMALL = "MAP_TOO_SMALL";
    public static final String NOT_ENOUGH_PLAYERS = "NOT_ENOUGH_PLAYERS";
    public static final String NOT_ALL_READY = "NOT_ALL_READY";
    public static final String ONE_TEAM = "ONE_TEAM";

    private RoomErrorCode() {}
}

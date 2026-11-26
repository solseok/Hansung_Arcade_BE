package hansung.arcade.domain.room.exception;

/**
 * 로비·대기실 규칙 위반을 클라이언트에 알리는 오류 코드.
 */
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

    public static final String SLOT_OCCUPIED = "SLOT_OCCUPIED";

    public static final String SLOT_LOCKED = "SLOT_LOCKED";

    private RoomErrorCode() {
    }
}

package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.Team;
import hansung.arcade.domain.player.model.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoomMessagesTest {

    @Test
    void 푸시_type_상수가_있다() {
        assertEquals("ROOM_STATE", RoomMessages.ROOM_STATE);
        assertEquals("LOBBY_ROOMS", RoomMessages.LOBBY_ROOMS);
        assertEquals("CHAT_MESSAGE", RoomMessages.CHAT_MESSAGE);
    }

    @Test
    void 목록은_받은_순서와_상관없이_방_번호_순서다() {
        Room two = new Room(2, "둘", 8);
        Room one = new Room(1, "하나", 4);
        one.seat(100, List.of(new Player(1, "가가"), new Player(2, "나나")), "kkukku", Team.RED);

        RoomMessages.RoomList list = RoomMessages.list(List.of(two, one));

        assertEquals(List.of(1, 2), list.rooms().stream().map(RoomMessages.RoomSummary::number).toList());
        assertEquals(new RoomMessages.RoomSummary(1, "하나", 2, 4, "camp/camp08", "WAITING"), list.rooms().getFirst());
    }

    @Test
    void 대기실_상태와_채팅은_자바_값으로도_명세의_글자를_담는다() {
        Room room = new Room(3, "제목", 8);
        room.seat(100, List.of(new Player(7, "꼬꼬")), "kkokko", Team.BLUE);

        RoomMessages.RoomState state = RoomMessages.state(room);

        assertEquals(new RoomMessages.RoomInfo(3, "제목", 8, "camp/camp08", "WAITING"), state.room());
        assertEquals(7, state.hostId());
        assertEquals(List.of(new RoomMessages.SeatedPlayer(7, "꼬꼬", 0, "kkokko", "BLUE", false)), state.players());
        assertEquals(new RoomMessages.ChatMessage(7, "꼬꼬", "안녕"),
                RoomMessages.chat(room.members().getFirst(), "안녕"));
        assertEquals(new RoomMessages.ChatMessage(0, "", "안내"), RoomMessages.notice("안내"));
    }

    @Test
    void body_record의_필드_타입은_명세의_JSON_타입과_같다() {
        Map<Class<?>, List<Class<?>>> expected = Map.of(
                RoomMessages.RoomSummary.class, List.of(int.class, String.class, int.class, int.class, String.class,
                        String.class),
                RoomMessages.RoomInfo.class, List.of(int.class, String.class, int.class, String.class, String.class),
                RoomMessages.SeatedPlayer.class, List.of(long.class, String.class, int.class, String.class,
                        String.class, boolean.class),
                RoomMessages.ChatMessage.class, List.of(long.class, String.class, String.class));
        expected.forEach((type, types) -> assertEquals(types,
                Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toList(),
                type.getSimpleName()));
    }
}

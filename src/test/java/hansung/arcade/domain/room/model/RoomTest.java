package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.Team;
import hansung.arcade.domain.player.model.Player;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomTest {

    private static List<Player> players(long... ids) {
        return Arrays.stream(ids).mapToObj(id -> new Player(id, "p" + id)).toList();
    }

    private static List<Integer> slots(Room room) {
        return room.members().stream().map(RoomMember::slot).toList();
    }

    private static List<Long> ids(Room room) {
        return room.members().stream().map(RoomMember::playerId).toList();
    }

    @Test
    void 새_방은_대기_중이고_기본_맵에_아무도_없다() {
        Room room = new Room(3, "제목", 8);

        assertEquals(3, room.number());
        assertEquals("제목", room.title());
        assertEquals(8, room.capacity());
        assertEquals("camp/camp08", room.mapId());
        assertEquals(RoomStatus.WAITING, room.status());
        assertEquals(0, room.hostId());
        assertTrue(room.isEmpty());
        assertEquals(0, room.size());
        assertEquals(List.of(), room.members());
    }

    @Test
    void 제목과_정원이_틀리면_만들_수_없다() {
        assertThrows(IllegalArgumentException.class, () -> new Room(1, "", 8));
        assertThrows(IllegalArgumentException.class, () -> new Room(1, "가".repeat(31), 8));
        assertThrows(IllegalArgumentException.class, () -> new Room(1, "제목", 1));
        assertThrows(IllegalArgumentException.class, () -> new Room(1, "제목", 9));
        assertThrows(NullPointerException.class, () -> new Room(1, null, 8));
        new Room(1, "😀".repeat(30), 2);
        assertEquals(2, Room.MIN_CAPACITY);
        assertEquals(8, Room.MAX_CAPACITY);
        assertEquals(30, Room.MAX_TITLE_LENGTH);
    }

    @Test
    void 앉히면_빈_가장_작은_자리부터_채우고_처음_앉은_사람이_방장이다() {
        Room room = new Room(1, "a", 8);

        List<RoomMember> seated = room.seat(100, players(1, 2), "kkukku", Team.BLUE);

        assertEquals(List.of(0, 1), seated.stream().map(RoomMember::slot).toList());
        assertEquals(1, room.hostId());
        RoomMember first = seated.getFirst();
        assertEquals(100, first.connectionId());
        assertEquals("p1", first.nickname());
        assertEquals("kkukku", first.character());
        assertEquals(Team.BLUE, first.team());
        assertFalse(first.ready());

        room.seat(200, players(3), "kkokko", Team.RED);
        room.seat(300, players(4), "kkokko", Team.RED);
        room.unseat(200);
        room.seat(400, players(5, 6), "kkokko", Team.RED);

        assertEquals(List.of(0, 1, 2, 3, 4), slots(room));
        assertEquals(List.of(1L, 2L, 5L, 4L, 6L), ids(room));
        assertEquals(1, room.hostId());
    }

    @Test
    void 플레이어_없이_앉힐_수_없다() {
        Room room = new Room(1, "a", 8);

        assertThrows(IllegalArgumentException.class, () -> room.seat(100, List.of(), "kkukku", Team.RED));
        assertTrue(room.isEmpty());
    }

    @Test
    void 자리가_모자라면_앉힐_수_없다() {
        Room room = new Room(1, "a", 2);
        room.seat(100, players(1), "kkukku", Team.RED);

        assertThrows(IllegalStateException.class, () -> room.seat(200, players(2, 3), "kkukku", Team.RED));
        assertEquals(1, room.size());
        assertTrue(room.canSeat(1));
        assertFalse(room.canSeat(2));
    }

    @Test
    void 게임_중이면_자리가_있어도_앉을_수_없다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);

        room.startPlaying();

        assertEquals(RoomStatus.PLAYING, room.status());
        assertFalse(room.canSeat(1));
        assertThrows(IllegalStateException.class, () -> room.seat(200, players(2), "kkukku", Team.RED));
        assertEquals(1, room.size());
    }

    @Test
    void 처음_앉은_사람이_방장이고_더_작은_자리에_누가_앉아도_바뀌지_않는다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2), "kkukku", Team.RED);
        room.seat(300, players(3), "kkukku", Team.RED);
        room.unseat(100);

        room.seat(400, players(4), "kkukku", Team.RED);

        assertEquals(0, room.members().getFirst().slot());
        assertEquals(4, room.members().getFirst().playerId());
        assertEquals(2, room.hostId());
        assertTrue(room.isHostConnection(200));
        assertFalse(room.isHostConnection(400));
        assertTrue(room.ready(400, true));
        assertFalse(room.allReady());
        room.ready(300, true);
        assertTrue(room.allReady());
    }

    @Test
    void 한_연결의_플레이어_가운데_하나라도_바뀌면_그_연결의_준비가_모두_풀린다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2), "kkukku", Team.RED);
        room.seat(200, players(3), "kkokko", Team.BLUE);
        room.ready(200, true);

        assertTrue(room.selectCharacter(200, "kkukku"));
        assertEquals(List.of(false, false), room.membersOf(200).stream().map(RoomMember::ready).toList());
        assertEquals(List.of("kkukku", "kkukku"), room.membersOf(200).stream().map(RoomMember::character).toList());

        room.ready(200, true);
        assertTrue(room.selectTeam(200, Team.BLUE));
        assertEquals(List.of(false, false), room.membersOf(200).stream().map(RoomMember::ready).toList());
        assertEquals(List.of(Team.BLUE, Team.BLUE), room.membersOf(200).stream().map(RoomMember::team).toList());

        // 모두 이미 그 값이면 아무것도 바뀌지 않는다
        room.ready(200, true);
        assertFalse(room.selectCharacter(200, "kkukku"));
        assertFalse(room.selectTeam(200, Team.BLUE));
        assertEquals(List.of(true, true), room.membersOf(200).stream().map(RoomMember::ready).toList());
    }

    @Test
    void playerId를_주면_그_플레이어만_바꾸고_준비는_그_연결_전체가_풀린다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2, 3), "kkukku", Team.RED);
        room.ready(200, true);

        assertTrue(room.selectCharacter(200, 3L, "kkokko"));
        assertEquals(List.of("kkukku", "kkokko"), room.membersOf(200).stream().map(RoomMember::character).toList());
        assertEquals(List.of(false, false), room.membersOf(200).stream().map(RoomMember::ready).toList());

        room.ready(200, true);
        assertTrue(room.selectTeam(200, 2L, Team.BLUE));
        assertEquals(List.of(Team.BLUE, Team.RED), room.membersOf(200).stream().map(RoomMember::team).toList());
        assertEquals(List.of(false, false), room.membersOf(200).stream().map(RoomMember::ready).toList());

        // 그 플레이어가 이미 그 값이면 아무것도 바뀌지 않는다 (다른 플레이어 값은 보지 않는다)
        room.ready(200, true);
        assertFalse(room.selectCharacter(200, 3L, "kkokko"));
        assertFalse(room.selectTeam(200, 2L, Team.BLUE));
        assertEquals(List.of(true, true), room.membersOf(200).stream().map(RoomMember::ready).toList());

        // 다른 연결의 플레이어는 고를 수 없다
        assertFalse(room.selectCharacter(200, 1L, "sangzzi"));
        assertEquals("kkukku", room.membersOf(100).getFirst().character());
    }

    @Test
    void 플레이어마다_다른_캐릭터와_팀으로_앉힌다() {
        Room room = new Room(1, "a", 8);

        List<RoomMember> seated = room.seat(100, players(1, 2),
                p -> p.playerId() == 1 ? "kkukku" : "kkokko", p -> p.playerId() == 1 ? Team.RED : Team.BLUE);

        assertEquals(List.of("kkukku", "kkokko"), seated.stream().map(RoomMember::character).toList());
        assertEquals(List.of(Team.RED, Team.BLUE), seated.stream().map(RoomMember::team).toList());
    }

    @Test
    void 연결의_플레이어와_방장_연결을_찾는다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1, 2), "kkukku", Team.RED);
        room.seat(200, players(3), "kkukku", Team.RED);

        assertEquals(List.of(1L, 2L), room.membersOf(100).stream().map(RoomMember::playerId).toList());
        assertEquals(List.of(), room.membersOf(999));
        assertTrue(room.isHostConnection(100));
        assertFalse(room.isHostConnection(200));
        assertFalse(room.isHostConnection(999));
    }

    @Test
    void 방장이_빠지면_자리가_가장_작은_사람이_방장이_되고_그_연결의_준비가_풀린다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2), "kkukku", Team.RED);
        room.seat(300, players(3, 4), "kkukku", Team.RED);
        room.ready(200, true);
        room.ready(300, true);
        room.unseat(200);

        List<RoomMember> removed = room.unseat(100);

        assertEquals(List.of(1L), removed.stream().map(RoomMember::playerId).toList());
        assertEquals(3, room.hostId());
        assertTrue(room.members().stream().noneMatch(RoomMember::ready));
    }

    @Test
    void 모두_빠지면_방장이_없다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1, 2), "kkukku", Team.RED);

        assertEquals(2, room.unseat(100).size());

        assertTrue(room.isEmpty());
        assertEquals(0, room.hostId());
        assertEquals(List.of(), room.unseat(100));
    }

    @Test
    void 준비는_연결_단위이고_방장_연결은_바꾸지_않는다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1, 2), "kkukku", Team.RED);
        room.seat(200, players(3, 4), "kkukku", Team.RED);

        assertFalse(room.ready(100, true));
        assertTrue(room.ready(200, true));
        assertFalse(room.ready(200, true));
        assertFalse(room.ready(999, true));

        assertEquals(List.of(false, false, true, true), room.members().stream().map(RoomMember::ready).toList());
        assertTrue(room.allReady());
        assertTrue(room.ready(200, false));
        assertFalse(room.allReady());
    }

    @Test
    void 캐릭터와_팀을_바꾸면_준비가_풀리고_같으면_바뀌지_않는다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2, 3), "kkukku", Team.RED);
        room.ready(200, true);

        assertFalse(room.selectCharacter(200, "kkukku"));
        assertTrue(room.members().get(1).ready());
        assertTrue(room.selectCharacter(200, "random"));
        assertEquals(List.of("kkukku", "random", "random"),
                room.members().stream().map(RoomMember::character).toList());
        assertFalse(room.members().get(1).ready());

        room.ready(200, true);
        assertFalse(room.selectTeam(200, Team.RED));
        assertTrue(room.selectTeam(200, Team.MINT));
        assertEquals(List.of(Team.RED, Team.MINT, Team.MINT), room.members().stream().map(RoomMember::team).toList());
        assertFalse(room.members().get(2).ready());
        assertFalse(room.selectTeam(999, Team.PINK));
    }

    @Test
    void 팀_수를_센다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2, 3), "kkukku", Team.RED);
        assertEquals(1, room.teamCount());

        room.selectTeam(200, Team.BLUE);
        assertEquals(2, room.teamCount());
    }

    @Test
    void 맵을_바꾸면_정원이_맞춰지고_닫히는_자리의_사람은_빈자리로_옮긴다() {
        Room room = new Room(1, "a", 8);
        for (long id = 1; id <= 8; id++) {
            room.seat(id * 100, players(id), "kkukku", Team.RED);
        }
        room.unseat(300);
        room.unseat(400);

        assertTrue(room.changeMap("village/village10"));

        assertEquals("village/village10", room.mapId());
        assertEquals(6, room.capacity());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), slots(room));
        assertEquals(List.of(1L, 2L, 7L, 8L, 5L, 6L), ids(room));

        assertFalse(room.changeMap("village/village10"));
        assertTrue(room.changeMap("random"));
        assertEquals(8, room.capacity());
        assertTrue(room.changeMap("patrit/patrit14"));
        assertEquals(8, room.capacity());
    }

    @Test
    void 처음_정한_정원보다_커지지_않는다() {
        Room room = new Room(1, "a", 5);
        assertEquals(5, room.capacity());

        room.changeMap("village/village10");
        assertEquals(5, room.capacity());
        room.changeMap("camp/camp08");
        assertEquals(5, room.capacity());
    }

    @Test
    void 모르는_맵이나_인원이_넘치는_맵은_고를_수_없다() {
        Room room = new Room(1, "a", 8);
        for (long id = 1; id <= 7; id++) {
            room.seat(id * 100, players(id), "kkukku", Team.RED);
        }

        assertThrows(IllegalArgumentException.class, () -> room.changeMap("nowhere"));
        assertThrows(IllegalStateException.class, () -> room.changeMap("village/village10"));
        assertEquals("camp/camp08", room.mapId());
        assertEquals(8, room.capacity());
    }

    @Test
    void 경기를_열지_못하면_대기_중으로_되돌리고_준비는_그대로다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2), "kkukku", Team.BLUE);
        room.ready(200, true);
        room.startPlaying();

        room.cancelPlaying();

        assertEquals(RoomStatus.WAITING, room.status());
        assertTrue(room.members().get(1).ready());
    }

    @Test
    void 경기가_끝나면_대기_중으로_돌아가고_모두의_준비가_풀린다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);
        room.seat(200, players(2), "kkukku", Team.BLUE);
        room.ready(200, true);
        room.startPlaying();

        room.finishPlaying();

        assertEquals(RoomStatus.WAITING, room.status());
        assertTrue(room.members().stream().noneMatch(RoomMember::ready));
    }

    @Test
    void members는_바깥에서_고칠_수_없는_사본이다() {
        Room room = new Room(1, "a", 8);
        room.seat(100, players(1), "kkukku", Team.RED);

        assertThrows(UnsupportedOperationException.class, () -> room.members().clear());
        assertEquals(1, room.size());
    }

    @Test
    void RoomMember는_빈_값을_받지_않는다() {
        assertThrows(NullPointerException.class, () -> new RoomMember(1, 1, null, 0, "kkukku", Team.RED, false));
        assertThrows(NullPointerException.class, () -> new RoomMember(1, 1, "a", 0, null, Team.RED, false));
        assertThrows(NullPointerException.class, () -> new RoomMember(1, 1, "a", 0, "kkukku", null, false));
    }
}

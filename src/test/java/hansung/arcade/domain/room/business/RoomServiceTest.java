package hansung.arcade.domain.room.business;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Json;
import hansung.arcade.domain.game.business.EventSender;
import hansung.arcade.domain.game.business.GameService;
import hansung.arcade.domain.game.business.GameService.Participant;
import hansung.arcade.domain.game.model.GameMaps;
import hansung.arcade.domain.game.model.Match;
import hansung.arcade.domain.game.model.Team;
import hansung.arcade.domain.game.model.MatchEvent;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.global.exception.GameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.LongConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 로비·대기실 규칙. 보낸 것(응답·푸시·경기 푸시)을 받는 연결마다 순서대로 모아 확인한다.
 * 응답은 type "REPLY"로 모은다. 로비는 {@link #loggedIn}에 있고 방 밖인 연결이다.
 */
class RoomServiceTest {

    private record Sent(long to, String type, JsonNode body) {
    }

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
    /** 방 쪽 푸시를 하나 넣을 때마다 (받는 연결, type)으로 불린다. lock 안에서 넣는지 확인할 때 끼어든다 */
    private volatile BiConsumer<Long, String> onSend = (to, type) -> {
    };
    private final Set<Long> loggedIn = new HashSet<>();
    /** 끊겼지만 아직 끊김 정리가 돌지 않은 연결 */
    private final Set<Long> closed = new HashSet<>();
    /** true면 경기 쪽 연결 확인이 예기치 못한 예외를 던진다 (경기를 여는 도중의 오류 흉내) */
    private boolean gameSenderBroken;
    private GameService games;
    private RoomService rooms;

    @BeforeEach
    void setUp() {
        games = new GameService(new EventSender() {
            @Override
            public void send(MatchEvent event) {
                recordEvent(event);
            }

            @Override
            public boolean isConnected(long connectionId) {
                if (gameSenderBroken) {
                    throw new IllegalStateException("경기 쪽 오류");
                }
                return !closed.contains(connectionId);
            }
        }, clock::get, new Random(7));
        rooms = new RoomService(new RoomSender() {
            @Override
            public void send(Collection<Long> connectionIds, String type, Object body) {
                for (Long id : connectionIds) {
                    onSend.accept(id, type);
                    sent.add(new Sent(id, type, Json.MAPPER.valueToTree(body)));
                }
            }

            @Override
            public boolean isConnected(long connectionId) {
                return !closed.contains(connectionId);
            }

            @Override
            public void sendToLobby(Collection<Long> inRoom, String type, Object body) {
                for (Long id : loggedIn) {
                    if (!inRoom.contains(id)) {
                        sent.add(new Sent(id, type, Json.MAPPER.valueToTree(body)));
                    }
                }
            }
        }, games, clock::get);
        games.onFinished(match -> rooms.finishGame(match.matchId()));
        games.onExit(rooms::exitGame);
        games.onDropped(rooms::exitGame);
    }

    private void recordEvent(MatchEvent event) {
        for (Long id : event.recipients()) {
            sent.add(new Sent(id, event.type(), Json.MAPPER.valueToTree(event.body())));
        }
    }

    // ---------- 도우미 ----------

    /** 연결 번호 connection의 1P (playerId = connection * 10 + 1) */
    private List<Player> one(long connection, String nickname) {
        loggedIn.add(connection);
        return List.of(new Player(connection * 10 + 1, nickname));
    }

    /** 연결 번호 connection의 1P·2P (playerId = connection * 10 + 1, + 2) */
    private List<Player> two(long connection, String first, String second) {
        loggedIn.add(connection);
        return List.of(new Player(connection * 10 + 1, first), new Player(connection * 10 + 2, second));
    }

    private Consumer<Object> reply(long connection) {
        // 응답 body가 null이면 {}로 나간다 (Message.ok와 같다)
        return body -> sent.add(new Sent(connection, "REPLY",
                body == null ? Json.object() : Json.MAPPER.valueToTree(body)));
    }

    /** ROOM_LIST 응답 body. 응답은 한 번만 온다 */
    private JsonNode listOf(long connection) {
        List<Object> replies = new ArrayList<>();
        rooms.list(connection, replies::add);
        assertEquals(1, replies.size(), "ROOM_LIST 응답은 한 번");
        return Json.MAPPER.valueToTree(replies.getFirst());
    }

    private List<JsonNode> roomStatesTo(long connection) {
        return to(connection).stream().filter(s -> s.type().equals("ROOM_STATE")).map(Sent::body).toList();
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(GameException.class, action::run).code());
    }

    private static String message(Runnable action) {
        return assertThrows(GameException.class, action::run).getMessage();
    }

    private List<Sent> to(long connection) {
        return sent.stream().filter(s -> s.to() == connection).toList();
    }

    private List<String> typesTo(long connection) {
        return to(connection).stream().map(Sent::type).toList();
    }

    private JsonNode last(long connection, String type) {
        List<Sent> matching = to(connection).stream().filter(s -> s.type().equals(type)).toList();
        if (matching.isEmpty()) {
            throw new AssertionError(connection + "번 연결이 " + type + "를 받지 않았습니다. 받은 것: " + typesTo(connection));
        }
        return matching.getLast().body();
    }

    private List<String> chatsTo(long connection) {
        return to(connection).stream().filter(s -> s.type().equals("CHAT_MESSAGE"))
                .map(s -> s.body().get("text").asText()).toList();
    }

    private void clearSent() {
        sent.clear();
    }

    /** 연결 1(방장 "방장")이 만든 방에 연결 2("손님")가 들어온 상태. 방 번호 1 */
    private void hostAndGuest() {
        rooms.create(1, one(1, "방장"), "함께 해요", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
    }

    /** {@link #hostAndGuest}에서 손님이 BLUE로 바꾸고 준비한 상태. 시작할 수 있다 */
    private void readyToStart() {
        hostAndGuest();
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
    }

    private static List<Integer> slots(JsonNode state) {
        List<Integer> slots = new ArrayList<>();
        state.get("players").forEach(p -> slots.add(p.get("slot").asInt()));
        return slots;
    }

    private static List<String> nicknames(JsonNode state) {
        List<String> names = new ArrayList<>();
        state.get("players").forEach(p -> names.add(p.get("nickname").asText()));
        return names;
    }

    private static JsonNode player(JsonNode state, String nickname) {
        for (JsonNode p : state.get("players")) {
            if (p.get("nickname").asText().equals(nickname)) {
                return p;
            }
        }
        throw new AssertionError(nickname + "가 없습니다: " + state);
    }

    // ---------- ROOM_CREATE ----------

    @Test
    void 방을_만들면_방장으로_slot_0에_기본값으로_앉고_대기실_상태로_응답한다() {
        rooms.create(1, one(1, "상상부기"), "함께 한 판 해요", 8, reply(1));

        JsonNode state = last(1, "REPLY");
        assertEquals(1, state.at("/room/number").asInt());
        assertEquals("함께 한 판 해요", state.at("/room/title").asText());
        assertEquals(8, state.at("/room/capacity").asInt());
        assertEquals("camp/camp08", state.at("/room/mapId").asText());
        assertEquals("WAITING", state.at("/room/status").asText());
        assertEquals(11, state.get("hostId").asLong());
        assertEquals(1, state.get("players").size());
        JsonNode me = state.get("players").get(0);
        assertEquals(11, me.get("playerId").asLong());
        assertEquals("상상부기", me.get("nickname").asText());
        assertEquals(0, me.get("slot").asInt());
        assertEquals("sangsangbugi", me.get("character").asText());
        assertEquals("RED", me.get("team").asText());
        assertFalse(me.get("ready").asBoolean());
    }

    @Test
    void 대기실_상태_body의_필드는_명세와_같다() {
        rooms.create(1, one(1, "상상부기"), "제목", 8, reply(1));

        JsonNode state = last(1, "REPLY");
        assertEquals(List.of("room", "hostId", "players"), fieldNames(state));
        assertEquals(List.of("number", "title", "capacity", "mapId", "status"), fieldNames(state.get("room")));
        assertEquals(List.of("playerId", "nickname", "slot", "character", "team", "ready"),
                fieldNames(state.get("players").get(0)));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void 이인용_2P로_만들면_1P는_slot_0_2P는_slot_1이고_방장은_1P다() {
        rooms.create(1, two(1, "상상부기", "꾸꾸"), "둘이 왔어요", 4, reply(1));

        JsonNode state = last(1, "REPLY");
        assertEquals(List.of(0, 1), slots(state));
        assertEquals(List.of("상상부기", "꾸꾸"), nicknames(state));
        assertEquals(11, state.get("hostId").asLong());
    }

    @Test
    void 방_번호는_지금_있는_가장_큰_번호_더하기_1이고_없으면_1이다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.create(2, one(2, "나나"), "b", 8, reply(2));
        rooms.create(3, one(3, "다다"), "c", 8, reply(3));
        rooms.leave(2, reply(2));
        rooms.create(4, one(4, "라라"), "d", 8, reply(4));
        assertEquals(4, last(4, "REPLY").at("/room/number").asInt());

        rooms.leave(4, reply(4));
        rooms.create(2, one(2, "나나"), "b", 8, reply(2));
        assertEquals(4, last(2, "REPLY").at("/room/number").asInt());

        rooms.leave(1, reply(1));
        rooms.leave(2, reply(2));
        rooms.leave(3, reply(3));
        rooms.create(5, one(5, "마마"), "e", 8, reply(5));
        assertEquals(1, last(5, "REPLY").at("/room/number").asInt());
    }

    @Test
    void 제목은_앞뒤_공백을_떼고_코드_포인트로_1에서_30자다() {
        rooms.create(1, one(1, "가가"), "  가운데 공백은 그대로  ", 8, reply(1));
        assertEquals("가운데 공백은 그대로", last(1, "REPLY").at("/room/title").asText());

        String thirtyEmoji = "😀".repeat(30);
        rooms.create(2, one(2, "나나"), thirtyEmoji, 8, reply(2));
        assertEquals(thirtyEmoji, last(2, "REPLY").at("/room/title").asText());

        assertCode("TITLE_INVALID", () -> rooms.create(3, one(3, "다다"), "가".repeat(31), 8, reply(3)));
        assertCode("TITLE_INVALID", () -> rooms.create(3, one(3, "다다"), "", 8, reply(3)));
        assertCode("TITLE_INVALID", () -> rooms.create(3, one(3, "다다"), "   ", 8, reply(3)));
        assertEquals("방 제목은 1~30자로 입력해 주세요.",
                message(() -> rooms.create(3, one(3, "다다"), "", 8, reply(3))));
    }

    @Test
    void 제목은_NFC로_합쳐_세고_보이지_않는_글자가_있으면_TITLE_INVALID() {
        rooms.create(1, one(1, "가가"), "\u1112\u1161\u11AB".repeat(30), 8, reply(1));
        assertEquals("한".repeat(30), last(1, "REPLY").at("/room/title").asText());

        rooms.create(3, one(3, "다다"), "\t앞뒤 공백 문자\n", 8, reply(3));
        assertEquals("앞뒤 공백 문자", last(3, "REPLY").at("/room/title").asText());

        for (String title : List.of("줄\n바꿈", "탭\t글", "\u200B", "제어\u0007", "줄\u2028구분", "a\uD800", "\uDC00b",
                "\u3164", "\u00A0", "\u3164\u200B\u2800")) {
            assertCode("TITLE_INVALID", () -> rooms.create(2, one(2, "나나"), title, 8, reply(2)));
        }
    }

    @Test
    void 제목의_앞뒤_빈칸은_채팅과_같은_규칙으로_떼고_이모지를_잇는_글자는_둔다() {
        rooms.create(1, one(1, "가가"), "\u3000\u3164제목\u00A0 ", 8, reply(1));
        assertEquals("제목", last(1, "REPLY").at("/room/title").asText());

        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67 가족방";
        rooms.create(2, one(2, "나나"), family, 8, reply(2));
        assertEquals(family, last(2, "REPLY").at("/room/title").asText());

        rooms.create(3, one(3, "다다"), "폭\u200B없음", 8, reply(3));
        assertEquals("폭\u200B없음", last(3, "REPLY").at("/room/title").asText());

        // 30자는 앞뒤 빈칸을 뗀 뒤에 센다
        rooms.create(4, one(4, "라라"), "  " + "가".repeat(30) + "  ", 8, reply(4));
        assertEquals("가".repeat(30), last(4, "REPLY").at("/room/title").asText());
    }

    @Test
    void 정원은_2에서_8이다() {
        assertCode("CAPACITY_INVALID", () -> rooms.create(1, one(1, "가가"), "a", 1, reply(1)));
        assertCode("CAPACITY_INVALID", () -> rooms.create(1, one(1, "가가"), "a", 9, reply(1)));
        assertCode("CAPACITY_INVALID", () -> rooms.create(1, one(1, "가가"), "a", 0, reply(1)));
        assertEquals("정원은 2~8명으로 선택해 주세요.",
                message(() -> rooms.create(1, one(1, "가가"), "a", 9, reply(1))));

        rooms.create(1, two(1, "가가", "나나"), "a", 2, reply(1));
        assertEquals(2, last(1, "REPLY").at("/room/capacity").asInt());
        rooms.create(2, one(2, "다다"), "b", 8, reply(2));
        assertEquals(8, last(2, "REPLY").at("/room/capacity").asInt());
    }

    @Test
    void 방에_있으면_ALREADY_IN_ROOM이_제목_정원_검사보다_먼저다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        assertCode("ALREADY_IN_ROOM", () -> rooms.create(1, one(1, "가가"), "", 99, reply(1)));
        assertEquals("이미 방에 입장해 있습니다.", message(() -> rooms.create(1, one(1, "가가"), "b", 8, reply(1))));
        assertCode("TITLE_INVALID", () -> rooms.create(2, one(2, "나나"), "", 99, reply(2)));
    }

    @Test
    void 방이_100개면_ROOM_LIMIT이고_하나가_없어지면_다시_만들_수_있다() {
        for (long c = 1; c <= RoomService.MAX_ROOMS; c++) {
            rooms.create(c, one(c, "p" + c), "방" + c, 8, reply(c));
        }
        assertEquals(100, RoomService.MAX_ROOMS);

        assertCode("ROOM_LIMIT", () -> rooms.create(101, one(101, "넘침"), "넘침", 8, reply(101)));
        assertEquals("방이 너무 많습니다. 잠시 뒤 다시 시도해 주세요.",
                message(() -> rooms.create(101, one(101, "넘침"), "넘침", 8, reply(101))));

        rooms.leave(50, reply(50));
        rooms.create(101, one(101, "넘침"), "넘침", 8, reply(101));
        assertEquals(101, last(101, "REPLY").at("/room/number").asInt());
    }

    @Test
    void 만들면_응답_뒤_로비에만_LOBBY_ROOMS를_보낸다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        assertEquals(List.of("REPLY"), typesTo(1));
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        JsonNode lobby = last(9, "LOBBY_ROOMS");
        assertEquals(1, lobby.get("rooms").size());
        assertEquals(1, lobby.at("/rooms/0/players").asInt());
    }

    @Test
    void 실패하면_아무것도_보내지_않는다() {
        loggedIn.add(9L);
        assertCode("TITLE_INVALID", () -> rooms.create(1, one(1, "가가"), "", 8, reply(1)));
        assertCode("ROOM_NOT_FOUND", () -> rooms.join(1, one(1, "가가"), 3, reply(1)));
        assertCode("NO_ROOM", () -> rooms.quickJoin(1, one(1, "가가"), reply(1)));

        assertTrue(sent.isEmpty());
    }

    // ---------- ROOM_LIST · LOBBY_ROOMS ----------

    @Test
    void 방_목록은_번호_순서이고_2P는_2명으로_센다() {
        rooms.create(1, two(1, "가가", "나나"), "둘", 6, reply(1));
        rooms.create(2, one(2, "다다"), "하나", 4, reply(2));
        rooms.changeMap(2, "random", reply(2));

        JsonNode list = listOf(3);

        assertEquals(2, list.get("rooms").size());
        JsonNode first = list.at("/rooms/0");
        assertEquals(List.of("number", "title", "players", "capacity", "mapId", "status"), fieldNames(first));
        assertEquals(1, first.get("number").asInt());
        assertEquals("둘", first.get("title").asText());
        assertEquals(2, first.get("players").asInt());
        assertEquals(6, first.get("capacity").asInt());
        assertEquals("camp/camp08", first.get("mapId").asText());
        assertEquals("WAITING", first.get("status").asText());
        assertEquals("random", list.at("/rooms/1/mapId").asText());
        assertEquals(4, list.at("/rooms/1/capacity").asInt());
    }

    @Test
    void 방이_없으면_빈_목록() {
        assertEquals("{\"rooms\":[]}", listOf(1).toString());
    }

    @Test
    void 방_안에서_목록을_보면_WRONG_STATE() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        assertCode("WRONG_STATE", () -> rooms.list(1, reply(1)));
        assertEquals("방 안에서는 목록을 볼 수 없습니다.", message(() -> rooms.list(1, reply(1))));
    }

    // ---------- ROOM_JOIN ----------

    @Test
    void 입장하면_응답_뒤_다른_사람에게_ROOM_STATE_방_전체에_입장_안내_로비에_LOBBY_ROOMS() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        clearSent();

        rooms.join(2, one(2, "손님"), 1, reply(2));

        assertEquals(List.of("REPLY", "CHAT_MESSAGE"), typesTo(2));
        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE"), typesTo(1));
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals(List.of("방장", "손님"), nicknames(last(2, "REPLY")));
        assertEquals(last(2, "REPLY"), last(1, "ROOM_STATE"));
        JsonNode chat = last(1, "CHAT_MESSAGE");
        assertEquals(0, chat.get("senderId").asLong());
        assertEquals("", chat.get("sender").asText());
        assertEquals("손님님이 들어왔습니다.", chat.get("text").asText());
        assertEquals(2, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
        // 들어간 사람은 이제 로비가 아니다
        assertFalse(typesTo(2).contains("LOBBY_ROOMS"));
    }

    @Test
    void 이인용_2P로_들어오면_두_자리를_차지하고_입장_안내가_한_사람씩_나간다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        clearSent();

        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));

        assertEquals(List.of(0, 1, 2), slots(last(2, "REPLY")));
        assertEquals(List.of("가가님이 들어왔습니다.", "나나님이 들어왔습니다."), chatsTo(1));
        assertEquals(List.of("가가님이 들어왔습니다.", "나나님이 들어왔습니다."), chatsTo(2));
    }

    @Test
    void 비어_있는_가장_작은_slot부터_채운다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.join(2, one(2, "나나"), 1, reply(2));
        rooms.join(3, one(3, "다다"), 1, reply(3));
        rooms.leave(2, reply(2));

        rooms.join(4, two(4, "라라", "마마"), 1, reply(4));

        JsonNode state = last(4, "REPLY");
        assertEquals(List.of(0, 1, 2, 3), slots(state));
        assertEquals(List.of("가가", "라라", "다다", "마마"), nicknames(state));
    }

    @Test
    void 없는_방_게임_중인_방_자리_없는_방에는_못_들어간다() {
        assertCode("ROOM_NOT_FOUND", () -> rooms.join(5, one(5, "가가"), 1, reply(5)));
        assertEquals("없어진 방입니다. 목록을 다시 확인해 주세요.",
                message(() -> rooms.join(5, one(5, "가가"), 1, reply(5))));

        rooms.create(1, one(1, "방장"), "정원 둘", 2, reply(1));
        assertCode("ROOM_FULL", () -> rooms.join(2, two(2, "나나", "다다"), 1, reply(2)));
        assertEquals("자리가 없습니다.", message(() -> rooms.join(2, two(2, "나나", "다다"), 1, reply(2))));
        rooms.join(3, one(3, "라라"), 1, reply(3));
        assertCode("ROOM_FULL", () -> rooms.join(4, one(4, "마마"), 1, reply(4)));

        rooms.selectTeam(3, "BLUE");
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        rooms.disconnect(3);
        assertCode("ROOM_PLAYING", () -> rooms.join(4, one(4, "마마"), 1, reply(4)));
        assertEquals("게임 중인 방입니다.", message(() -> rooms.join(4, one(4, "마마"), 1, reply(4))));
    }

    @Test
    void 게임_중이고_자리도_없으면_ROOM_PLAYING이_먼저다() {
        rooms.create(1, one(1, "방장"), "a", 2, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.startGame(1, reply(1));

        assertCode("ROOM_PLAYING", () -> rooms.join(3, one(3, "다다"), 1, reply(3)));
    }

    @Test
    void 이미_방에_있으면_ALREADY_IN_ROOM이_방_검사보다_먼저다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        assertCode("ALREADY_IN_ROOM", () -> rooms.join(1, one(1, "가가"), 1, reply(1)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.join(1, one(1, "가가"), 77, reply(1)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.quickJoin(1, one(1, "가가"), reply(1)));
    }

    @Test
    void 캐릭터와_팀은_그_연결이_마지막으로_고른_값으로_들어가고_준비는_false다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.selectCharacter(1, "kkokko");
        rooms.selectTeam(1, "PINK");
        rooms.leave(1, reply(1));

        rooms.create(2, one(2, "나나"), "b", 8, reply(2));
        rooms.join(1, one(1, "가가"), 1, reply(1));

        JsonNode me = player(last(1, "REPLY"), "가가");
        assertEquals("kkokko", me.get("character").asText());
        assertEquals("PINK", me.get("team").asText());
        assertFalse(me.get("ready").asBoolean());
    }

    @Test
    void 랜덤_캐릭터를_고른_것도_다음_방까지_기억한다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));
        rooms.selectCharacter(1, "random");
        rooms.leave(1, reply(1));

        rooms.create(1, two(1, "가가", "나나"), "b", 8, reply(1));

        JsonNode state = last(1, "REPLY");
        assertEquals("random", player(state, "가가").get("character").asText());
        assertEquals("random", player(state, "나나").get("character").asText());
        assertEquals("RED", player(state, "나나").get("team").asText());
    }

    @Test
    void 끊긴_연결이_고른_값은_잊는다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.selectCharacter(1, "kkukku");
        rooms.selectTeam(1, "MINT");
        rooms.disconnect(1);

        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        JsonNode me = player(last(1, "REPLY"), "가가");
        assertEquals("sangsangbugi", me.get("character").asText());
        assertEquals("RED", me.get("team").asText());
    }

    // ---------- ROOM_QUICK_JOIN ----------

    @Test
    void 바로_시작은_대기_중이고_자리가_남은_방_가운데_번호가_가장_작은_방에_들어간다() {
        rooms.create(1, two(1, "가가", "나나"), "꽉 참", 2, reply(1));
        rooms.create(2, one(2, "다다"), "게임 중", 8, reply(2));
        rooms.join(3, one(3, "라라"), 2, reply(3));
        rooms.selectTeam(3, "BLUE");
        rooms.ready(3, true);
        rooms.startGame(2, reply(2));
        rooms.create(4, one(4, "마마"), "여기", 8, reply(4));
        rooms.create(5, one(5, "바바"), "여기도", 8, reply(5));
        clearSent();

        rooms.quickJoin(6, one(6, "사사"), reply(6));

        assertEquals(3, last(6, "REPLY").at("/room/number").asInt());
        assertEquals(List.of("REPLY", "CHAT_MESSAGE"), typesTo(6));
        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE"), typesTo(4));
        assertEquals(List.of("사사님이 들어왔습니다."), chatsTo(4));
    }

    @Test
    void 바로_시작은_2P면_두_자리가_남은_방만_고른다() {
        rooms.create(1, one(1, "가가"), "한 자리", 2, reply(1));
        rooms.create(2, one(2, "나나"), "여러 자리", 8, reply(2));

        rooms.quickJoin(3, two(3, "다다", "라라"), reply(3));

        assertEquals(2, last(3, "REPLY").at("/room/number").asInt());
    }

    @Test
    void 들어갈_방이_없으면_NO_ROOM() {
        assertCode("NO_ROOM", () -> rooms.quickJoin(1, one(1, "가가"), reply(1)));
        assertEquals("입장 가능한 방이 없습니다.", message(() -> rooms.quickJoin(1, one(1, "가가"), reply(1))));

        rooms.create(2, one(2, "나나"), "한 자리", 2, reply(2));
        assertCode("NO_ROOM", () -> rooms.quickJoin(1, two(1, "가가", "다다"), reply(1)));
    }

    // ---------- ROOM_LEAVE ----------

    @Test
    void 나가면_응답_뒤_남은_사람에게_ROOM_STATE와_퇴장_안내_로비에_LOBBY_ROOMS() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        clearSent();

        rooms.leave(2, reply(2));

        assertEquals(List.of("REPLY", "LOBBY_ROOMS"), typesTo(2));
        assertEquals("{}", last(2, "REPLY").toString());
        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE"), typesTo(1));
        assertEquals(List.of("방장"), nicknames(last(1, "ROOM_STATE")));
        assertEquals(List.of("손님님이 나갔습니다."), chatsTo(1));
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals(1, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
    }

    @Test
    void 방장이_나가면_slot이_가장_작은_사람이_방장이_되고_안내한다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "둘째"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.leave(2, reply(2));
        rooms.join(4, one(4, "넷째"), 1, reply(4));
        clearSent();

        rooms.leave(1, reply(1));

        JsonNode state = last(3, "ROOM_STATE");
        assertEquals(41, state.get("hostId").asLong());
        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE", "CHAT_MESSAGE"), typesTo(3));
        assertEquals(List.of("방장님이 나갔습니다.", "넷째님이 방장이 되었습니다."), chatsTo(3));
    }

    @Test
    void 새_방장의_연결은_준비가_풀린다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));
        rooms.join(3, one(3, "다다"), 1, reply(3));
        rooms.ready(2, true);
        rooms.ready(3, true);

        rooms.leave(1, reply(1));

        JsonNode state = last(3, "ROOM_STATE");
        assertEquals(21, state.get("hostId").asLong());
        assertFalse(player(state, "가가").get("ready").asBoolean());
        assertFalse(player(state, "나나").get("ready").asBoolean());
        assertTrue(player(state, "다다").get("ready").asBoolean());
    }

    @Test
    void 이인용_2P는_둘이_함께_나가고_퇴장_안내도_한_사람씩_나간다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));
        rooms.join(2, one(2, "다다"), 1, reply(2));
        clearSent();

        rooms.leave(1, reply(1));

        assertEquals(List.of("다다"), nicknames(last(2, "ROOM_STATE")));
        assertEquals(List.of("가가님이 나갔습니다.", "나나님이 나갔습니다.", "다다님이 방장이 되었습니다."), chatsTo(2));
    }

    @Test
    void 마지막_사람이_나가면_방이_없어진다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "가가"), "a", 3, reply(1));
        rooms.changeMap(1, "village/village10", reply(1));
        clearSent();

        rooms.leave(1, reply(1));

        assertEquals("{\"rooms\":[]}", last(9, "LOBBY_ROOMS").toString());
        assertEquals("{\"rooms\":[]}", last(1, "LOBBY_ROOMS").toString());
        assertEquals("{\"rooms\":[]}", listOf(1).toString());

        // 처음 정한 정원도 함께 지워져 새 방은 새로 정한 정원을 따른다
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        assertEquals(8, last(1, "REPLY").at("/room/capacity").asInt());
        assertEquals("camp/camp08", last(1, "REPLY").at("/room/mapId").asText());
    }

    @Test
    void 방에_없거나_게임_중이면_나갈_수_없다() {
        assertCode("WRONG_STATE", () -> rooms.leave(1, reply(1)));
        assertEquals("방에 있지 않습니다.", message(() -> rooms.leave(1, reply(1))));

        readyToStart();
        rooms.startGame(1, reply(1));
        assertCode("WRONG_STATE", () -> rooms.leave(2, reply(2)));
    }

    // ---------- 끊김 ----------

    @Test
    void 끊기면_나가기와_같게_정리하지만_응답은_없다() {
        loggedIn.add(9L);
        hostAndGuest();
        clearSent();

        rooms.disconnect(1);

        assertTrue(typesTo(1).stream().noneMatch(type -> type.equals("REPLY")));
        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE", "CHAT_MESSAGE"), typesTo(2));
        assertEquals(List.of("방장님이 나갔습니다.", "손님님이 방장이 되었습니다."), chatsTo(2));
        assertEquals(1, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
    }

    @Test
    void 게임_중에_끊겨도_방에서_빠진다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        clearSent();

        rooms.disconnect(3);

        assertEquals(List.of("CHAT_MESSAGE"), typesTo(1));
        assertEquals(List.of("셋째님이 나갔습니다."), chatsTo(2));
        // 방장 변경과 남은 사람은 경기가 끝난 뒤의 ROOM_STATE에 반영된다
        games.exit(2);
        assertEquals(List.of("방장"), nicknames(last(1, "ROOM_STATE")));
    }

    @Test
    void 게임_중에_방장이_끊기면_방장_변경은_경기가_끝난_뒤_ROOM_STATE로만_알린다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        clearSent();

        rooms.disconnect(1);

        assertEquals(List.of("방장님이 나갔습니다."), chatsTo(2));
        assertEquals(List.of("CHAT_MESSAGE"), typesTo(2));
        games.disconnect(1);
        games.exit(3);
        JsonNode state = last(2, "ROOM_STATE");
        assertEquals("WAITING", state.at("/room/status").asText());
        assertEquals(21, state.get("hostId").asLong());
    }

    // ---------- GAME_EXIT ----------

    @Test
    void 게임에서_나가면_방에서도_빠져_로비로_간다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        clearSent();

        games.exit(3);

        assertEquals(List.of("셋째님이 나갔습니다."), chatsTo(1));
        assertTrue(typesTo(1).contains("PLAYER_LEFT"));
        assertFalse(typesTo(1).contains("ROOM_STATE"));
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(3).stream().filter(t -> !t.equals("PLAYER_LEFT")).toList());
        assertEquals(2, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
        // 로비로 왔으므로 목록을 보고 다른 방을 만들 수 있다
        assertEquals(1, listOf(3).get("rooms").size());
        rooms.create(3, one(3, "셋째"), "b", 8, reply(3));
        assertEquals(2, last(3, "REPLY").at("/room/number").asInt());
    }

    @Test
    void 게임에서_나가_경기가_끝나면_대기실_상태에_나간_사람이_없다() {
        readyToStart();
        rooms.startGame(1, reply(1));
        clearSent();

        games.exit(2);

        JsonNode state = last(1, "ROOM_STATE");
        assertEquals("WAITING", state.at("/room/status").asText());
        assertEquals(List.of("방장"), nicknames(state));
        assertEquals(1, to(1).stream().filter(s -> s.type().equals("ROOM_STATE")).count());
        assertTrue(typesTo(2).stream().noneMatch(type -> type.equals("ROOM_STATE")));
    }

    @Test
    void 경기가_끝난_뒤에_온_GAME_EXIT도_방에서_빼_로비로_보낸다() {
        // 방장 RED, 손님 BLUE, 셋째 RED. 손님이 끊기면 RED만 남아 경기가 끝난다
        readyToStart();
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        rooms.disconnect(2);
        games.disconnect(2);
        assertEquals(0, games.activeMatches());
        clearSent();

        // 방장은 GAME_OVER를 받기 전에 나가기를 눌러 이미 로비로 갔다
        assertCode("WRONG_STATE", () -> games.exit(1));

        assertEquals(List.of("셋째"), nicknames(last(3, "ROOM_STATE")));
        assertEquals(List.of("방장님이 나갔습니다.", "셋째님이 방장이 되었습니다."), chatsTo(3));
        assertEquals(1, listOf(1).get("rooms").size());
        rooms.create(1, one(1, "방장"), "새 방", 8, reply(1));
    }

    @Test
    void 나가기를_처리하는_도중_경기가_끝나도_방에서_빠진다() {
        readyToStart();
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(3, "GREEN");
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        // 나가기를 처리하는 사이 다른 스레드에서 남은 사람이 모두 끊겨 경기가 먼저 끝난다
        games.onExit(id -> {
            games.disconnect(2);
            games.disconnect(3);
            rooms.exitGame(id);
        });

        assertCode("WRONG_STATE", () -> games.exit(1));

        assertEquals(1, listOf(1).get("rooms").size());
        assertEquals(List.of("손님", "셋째"), nicknames(last(2, "ROOM_STATE")));
    }

    @Test
    void 대기실에서_온_GAME_EXIT은_나가기와_같게_방에서_뺀다() {
        loggedIn.add(9L);
        hostAndGuest();
        clearSent();

        assertCode("WRONG_STATE", () -> games.exit(2));

        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE"), typesTo(1));
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(2));
        assertEquals(1, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
    }

    @Test
    void 방_밖에서_끊기거나_두_번_끊겨도_아무것도_보내지_않는다() {
        loggedIn.add(9L);
        rooms.disconnect(5);
        hostAndGuest();
        rooms.disconnect(2);
        clearSent();

        rooms.disconnect(2);

        assertTrue(sent.isEmpty());
    }

    // ---------- READY ----------

    @Test
    void 준비하면_방_전체에_ROOM_STATE를_보내고_값이_그대로면_보내지_않는다() {
        hostAndGuest();
        clearSent();

        rooms.ready(2, true);
        assertEquals(List.of("ROOM_STATE"), typesTo(1));
        assertEquals(List.of("ROOM_STATE"), typesTo(2));
        assertTrue(player(last(1, "ROOM_STATE"), "손님").get("ready").asBoolean());

        clearSent();
        rooms.ready(2, true);
        assertTrue(sent.isEmpty());

        rooms.ready(2, false);
        assertFalse(player(last(1, "ROOM_STATE"), "손님").get("ready").asBoolean());
    }

    @Test
    void 방장의_연결이_보낸_준비는_무시한다() {
        rooms.create(1, two(1, "방장", "방장친구"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        clearSent();

        rooms.ready(1, true);

        assertTrue(sent.isEmpty());
    }

    @Test
    void 이인용_2P는_두_사람의_준비를_함께_바꾼다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));

        rooms.ready(2, true);

        JsonNode state = last(1, "ROOM_STATE");
        assertTrue(player(state, "가가").get("ready").asBoolean());
        assertTrue(player(state, "나나").get("ready").asBoolean());
    }

    @Test
    void 대기실이_아니면_준비할_수_없다() {
        assertCode("WRONG_STATE", () -> rooms.ready(1, true));

        readyToStart();
        rooms.startGame(1, reply(1));
        assertCode("WRONG_STATE", () -> rooms.ready(2, false));
    }

    // ---------- SELECT_CHARACTER · SELECT_TEAM ----------

    @Test
    void 캐릭터를_바꾸면_준비가_풀리고_ROOM_STATE를_보낸다() {
        hostAndGuest();
        rooms.ready(2, true);
        clearSent();

        rooms.selectCharacter(2, "hansungnyangi");

        JsonNode me = player(last(1, "ROOM_STATE"), "손님");
        assertEquals("hansungnyangi", me.get("character").asText());
        assertFalse(me.get("ready").asBoolean());
        assertEquals(List.of("ROOM_STATE"), typesTo(2));
    }

    @Test
    void 같은_캐릭터를_고르면_아무것도_바뀌지_않는다() {
        hostAndGuest();
        rooms.selectCharacter(2, "kkukku");
        rooms.ready(2, true);
        clearSent();

        rooms.selectCharacter(2, "kkukku");

        assertTrue(sent.isEmpty());
        rooms.selectTeam(2, "GREEN");
        assertFalse(player(last(1, "ROOM_STATE"), "손님").get("ready").asBoolean());
    }

    @Test
    void 캐릭터는_다섯_가지와_random만_고를_수_있다() {
        hostAndGuest();
        for (String id : List.of("sangsangbugi", "kkukku", "kkokko", "sangzzi", "hansungnyangi", "random")) {
            rooms.selectCharacter(2, id);
            assertEquals(id, player(last(1, "ROOM_STATE"), "손님").get("character").asText());
        }

        assertCode("BAD_MESSAGE", () -> rooms.selectCharacter(2, "pikachu"));
        assertCode("BAD_MESSAGE", () -> rooms.selectCharacter(2, "KKUKKU"));
        assertCode("BAD_MESSAGE", () -> rooms.selectCharacter(2, ""));
        assertEquals("알 수 없는 캐릭터입니다.", message(() -> rooms.selectCharacter(2, "pikachu")));
    }

    @Test
    void 모르는_캐릭터는_방_밖이어도_BAD_MESSAGE가_먼저다() {
        assertCode("BAD_MESSAGE", () -> rooms.selectCharacter(1, "pikachu"));
        assertCode("WRONG_STATE", () -> rooms.selectCharacter(1, "kkukku"));
    }

    @Test
    void 팀을_바꾸면_준비가_풀리고_ROOM_STATE를_보낸다() {
        hostAndGuest();
        rooms.ready(2, true);
        clearSent();

        rooms.selectTeam(2, "PURPLE");

        JsonNode me = player(last(1, "ROOM_STATE"), "손님");
        assertEquals("PURPLE", me.get("team").asText());
        assertFalse(me.get("ready").asBoolean());

        clearSent();
        rooms.selectTeam(2, "PURPLE");
        assertTrue(sent.isEmpty());
    }

    @Test
    void 팀은_여덟_색_이름만_받는다() {
        hostAndGuest();
        for (String team : List.of("RED", "YELLOW", "ORANGE", "GREEN", "MINT", "BLUE", "PURPLE", "PINK")) {
            rooms.selectTeam(2, team);
            assertEquals(team, player(last(1, "ROOM_STATE"), "손님").get("team").asText());
        }

        assertCode("BAD_MESSAGE", () -> rooms.selectTeam(2, "blue"));
        assertCode("BAD_MESSAGE", () -> rooms.selectTeam(2, "빨강"));
        assertCode("BAD_MESSAGE", () -> rooms.selectTeam(2, ""));
        assertEquals("알 수 없는 팀입니다.", message(() -> rooms.selectTeam(2, "blue")));
        assertCode("BAD_MESSAGE", () -> rooms.selectTeam(7, "blue"));
        assertCode("WRONG_STATE", () -> rooms.selectTeam(7, "BLUE"));
    }

    @Test
    void 이인용_2P는_캐릭터와_팀을_두_사람_모두_바꾼다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));

        rooms.selectCharacter(2, "sangzzi");
        rooms.selectTeam(2, "ORANGE");

        JsonNode state = last(1, "ROOM_STATE");
        for (String name : List.of("가가", "나나")) {
            assertEquals("sangzzi", player(state, name).get("character").asText());
            assertEquals("ORANGE", player(state, name).get("team").asText());
        }
    }

    @Test
    void 방장도_캐릭터와_팀을_고를_수_있다() {
        hostAndGuest();
        rooms.selectCharacter(1, "kkokko");
        rooms.selectTeam(1, "YELLOW");

        JsonNode host = player(last(2, "ROOM_STATE"), "방장");
        assertEquals("kkokko", host.get("character").asText());
        assertEquals("YELLOW", host.get("team").asText());
        assertFalse(host.get("ready").asBoolean());
    }

    @Test
    void 게임_중에는_캐릭터와_팀을_바꿀_수_없다() {
        readyToStart();
        rooms.startGame(1, reply(1));

        assertCode("WRONG_STATE", () -> rooms.selectCharacter(2, "kkukku"));
        assertCode("WRONG_STATE", () -> rooms.selectTeam(2, "RED"));
    }

    // ---------- CHANGE_MAP ----------

    @Test
    void 방장이_맵을_바꾸면_응답_뒤_ROOM_STATE와_LOBBY_ROOMS를_보낸다() {
        loggedIn.add(9L);
        hostAndGuest();
        clearSent();

        rooms.changeMap(1, "village/village10", reply(1));

        assertEquals(List.of("REPLY", "ROOM_STATE"), typesTo(1));
        assertEquals("{}", last(1, "REPLY").toString());
        assertEquals(List.of("ROOM_STATE"), typesTo(2));
        assertEquals("village/village10", last(2, "ROOM_STATE").at("/room/mapId").asText());
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals("village/village10", last(9, "LOBBY_ROOMS").at("/rooms/0/mapId").asText());
    }

    @Test
    void 정원은_처음_정한_정원과_맵_최대_인원_가운데_작은_값이다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.changeMap(1, "village/village10", reply(1));
        assertEquals(6, last(1, "ROOM_STATE").at("/room/capacity").asInt());
        rooms.changeMap(1, "patrit/patrit14", reply(1));
        assertEquals(8, last(1, "ROOM_STATE").at("/room/capacity").asInt());
        rooms.changeMap(1, "random", reply(1));
        assertEquals(8, last(1, "ROOM_STATE").at("/room/capacity").asInt());
        assertEquals("random", last(1, "ROOM_STATE").at("/room/mapId").asText());

        rooms.create(2, one(2, "나나"), "b", 4, reply(2));
        rooms.changeMap(2, "village/village10", reply(2));
        assertEquals(4, last(2, "ROOM_STATE").at("/room/capacity").asInt());
        rooms.changeMap(2, "camp/camp08", reply(2));
        assertEquals(4, last(2, "ROOM_STATE").at("/room/capacity").asInt());
    }

    @Test
    void 같은_맵을_고르면_응답만_보낸다() {
        hostAndGuest();
        clearSent();

        rooms.changeMap(1, "camp/camp08", reply(1));

        assertEquals(List.of("REPLY"), typesTo(1));
        assertTrue(typesTo(2).isEmpty());
    }

    @Test
    void 정원이_줄면_닫히는_자리에_앉은_사람을_열린_빈자리로_옮긴다() {
        rooms.create(1, one(1, "s0"), "a", 8, reply(1));
        for (long c = 2; c <= 8; c++) {
            rooms.join(c, one(c, "s" + (c - 1)), 1, reply(c));
        }
        rooms.leave(3, reply(3));
        rooms.leave(4, reply(4));

        rooms.changeMap(1, "village/village10", reply(1));

        JsonNode state = last(1, "ROOM_STATE");
        assertEquals(List.of(0, 1, 2, 3, 4, 5), slots(state));
        assertEquals(List.of("s0", "s1", "s6", "s7", "s4", "s5"), nicknames(state));
    }

    @Test
    void 맵을_바꿔도_준비는_그대로다() {
        hostAndGuest();
        rooms.ready(2, true);

        rooms.changeMap(1, "patrit/patrit14", reply(1));

        assertTrue(player(last(2, "ROOM_STATE"), "손님").get("ready").asBoolean());
    }

    @Test
    void 방장의_2P도_맵을_바꿀_수_있다() {
        rooms.create(1, two(1, "방장", "친구"), "a", 8, reply(1));

        rooms.changeMap(1, "village/village10", reply(1));

        assertEquals("village/village10", last(1, "ROOM_STATE").at("/room/mapId").asText());
    }

    @Test
    void 맵_변경_실패는_대기실_방장_맵_인원_순서로_검사한다() {
        assertCode("WRONG_STATE", () -> rooms.changeMap(1, "nowhere", reply(1)));

        rooms.create(1, one(1, "s0"), "a", 8, reply(1));
        for (long c = 2; c <= 7; c++) {
            rooms.join(c, one(c, "s" + (c - 1)), 1, reply(c));
        }
        assertCode("NOT_HOST", () -> rooms.changeMap(2, "nowhere", reply(2)));
        assertEquals("방장만 맵을 선택할 수 있습니다.", message(() -> rooms.changeMap(2, "camp/camp08", reply(2))));
        assertCode("MAP_INVALID", () -> rooms.changeMap(1, "nowhere", reply(1)));
        assertCode("MAP_INVALID", () -> rooms.changeMap(1, "CAMP/CAMP08", reply(1)));
        assertCode("MAP_INVALID", () -> rooms.changeMap(1, "", reply(1)));
        assertEquals("지원하지 않는 맵입니다.", message(() -> rooms.changeMap(1, "nowhere", reply(1))));
        assertCode("MAP_TOO_SMALL", () -> rooms.changeMap(1, "village/village10", reply(1)));
        assertEquals("빌리지 10은(는) 최대 6명이라 지금 인원 7명으로는 고를 수 없습니다.",
                message(() -> rooms.changeMap(1, "village/village10", reply(1))));
    }

    @Test
    void 게임_중에는_맵을_바꿀_수_없다() {
        readyToStart();
        rooms.startGame(1, reply(1));

        assertCode("WRONG_STATE", () -> rooms.changeMap(1, "village/village10", reply(1)));
    }

    // ---------- CHAT ----------

    @Test
    void 채팅은_보낸_사람을_포함한_방_전체에_나간다() {
        loggedIn.add(9L);
        hostAndGuest();
        clearSent();

        rooms.chat(2, 21, "안녕하세요");

        for (long c : new long[]{1, 2}) {
            JsonNode chat = last(c, "CHAT_MESSAGE");
            assertEquals(List.of("senderId", "sender", "text"), fieldNames(chat));
            assertEquals(21, chat.get("senderId").asLong());
            assertEquals("손님", chat.get("sender").asText());
            assertEquals("안녕하세요", chat.get("text").asText());
        }
        assertTrue(typesTo(9).isEmpty());
    }

    @Test
    void 이인용_2P는_playerId로_누가_친_글인지_고른다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));
        rooms.join(2, one(2, "다다"), 1, reply(2));

        rooms.chat(1, 12, "나나가 씀");
        assertEquals("나나", last(2, "CHAT_MESSAGE").get("sender").asText());
        rooms.chat(1, 11, "가가가 씀");
        assertEquals("가가", last(2, "CHAT_MESSAGE").get("sender").asText());
        assertCode("BAD_MESSAGE", () -> rooms.chat(1, 21, "남의 플레이어"));
        assertCode("BAD_MESSAGE", () -> rooms.chat(1, 99, "없는 플레이어"));
        assertEquals("내 플레이어가 아닙니다.", message(() -> rooms.chat(1, 21, "x")));
    }

    @Test
    void 채팅은_다듬어_보내고_다듬고_나서_비면_버린다() {
        hostAndGuest();
        clearSent();

        rooms.chat(1, 11, "  여러\n줄\t글  ");
        assertEquals("여러 줄 글", last(2, "CHAT_MESSAGE").get("text").asText());

        rooms.chat(1, 11, "가".repeat(33));
        assertEquals("가".repeat(32), last(2, "CHAT_MESSAGE").get("text").asText());

        clearSent();
        rooms.chat(1, 11, "   ");
        rooms.chat(1, 11, "​ㅤ");
        rooms.chat(1, 11, "");
        assertTrue(sent.isEmpty());
    }

    @Test
    void 로비에서는_채팅할_수_없고_게임_중에는_할_수_있다() {
        assertCode("WRONG_STATE", () -> rooms.chat(1, 11, "로비"));
        assertEquals("방 안에서만 채팅할 수 있습니다.", message(() -> rooms.chat(1, 11, "로비")));

        readyToStart();
        rooms.startGame(1, reply(1));
        clearSent();
        rooms.chat(2, 21, "게임 중");
        assertEquals("게임 중", last(1, "CHAT_MESSAGE").get("text").asText());
    }

    @Test
    void 연결마다_1초에_3개까지_보내고_넘으면_버린다() {
        assertEquals(3, RoomService.MAX_CHATS_PER_WINDOW);
        assertEquals(1_000, RoomService.CHAT_WINDOW_MS);
        hostAndGuest();
        clearSent();

        rooms.chat(1, 11, "1");
        rooms.chat(1, 11, "2");
        clock.addAndGet(500);
        rooms.chat(1, 11, "3");
        rooms.chat(1, 11, "4");
        rooms.chat(2, 21, "다른 연결");
        clock.addAndGet(499);
        rooms.chat(1, 11, "5");
        clock.addAndGet(1);
        // 처음 두 개가 1초를 채워 빠졌다. 1.5초에 보낸 "3"은 아직 남아 있다
        rooms.chat(1, 11, "6");
        rooms.chat(1, 11, "7");
        rooms.chat(1, 11, "8");
        clock.addAndGet(500);
        rooms.chat(1, 11, "9");

        assertEquals(List.of("1", "2", "3", "다른 연결", "6", "7", "9"), chatsTo(2));
    }

    @Test
    void 버린_빈_글은_도배_횟수에_세지_않는다() {
        hostAndGuest();
        clearSent();

        rooms.chat(1, 11, " ");
        rooms.chat(1, 11, " ");
        rooms.chat(1, 11, " ");
        rooms.chat(1, 11, "1");
        rooms.chat(1, 11, "2");
        rooms.chat(1, 11, "3");

        assertEquals(List.of("1", "2", "3"), chatsTo(2));
    }

    @Test
    void 내_플레이어가_아니면_비어_있는_글이어도_BAD_MESSAGE다() {
        hostAndGuest();

        assertCode("BAD_MESSAGE", () -> rooms.chat(1, 21, " "));
    }

    // ---------- GAME_START ----------

    @Test
    void 게임을_시작하면_응답_뒤_GAME_STARTED를_보내고_로비에_게임_중으로_알린다() {
        loggedIn.add(9L);
        readyToStart();
        clearSent();

        rooms.startGame(1, reply(1));

        assertEquals(List.of("REPLY", "GAME_STARTED"), typesTo(1));
        assertEquals("{}", last(1, "REPLY").toString());
        assertEquals(List.of("GAME_STARTED"), typesTo(2));
        JsonNode started = last(2, "GAME_STARTED");
        assertEquals("camp/camp08", started.get("mapId").asText());
        assertEquals(2, started.get("players").size());
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals("PLAYING", last(9, "LOBBY_ROOMS").at("/rooms/0/status").asText());
        assertEquals("PLAYING", listOf(9).at("/rooms/0/status").asText());
    }

    @Test
    void 랜덤_맵과_랜덤_캐릭터는_실제_값으로_정해_알린다() {
        hostAndGuest();
        rooms.selectTeam(2, "BLUE");
        rooms.selectCharacter(2, "random");
        rooms.ready(2, true);
        rooms.changeMap(1, "random", reply(1));
        clearSent();

        rooms.startGame(1, reply(1));

        JsonNode started = last(2, "GAME_STARTED");
        assertFalse(started.get("mapId").asText().equals("random"));
        started.get("players").forEach(p -> assertFalse(p.get("character").asText().equals("random")));
    }

    @Test
    void 방장만_시작할_수_있다() {
        readyToStart();

        assertCode("NOT_HOST", () -> rooms.startGame(2, reply(2)));
        assertEquals("방장만 게임을 시작할 수 있습니다.", message(() -> rooms.startGame(2, reply(2))));
    }

    @Test
    void 혼자면_시작할_수_없다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));

        assertCode("NOT_ENOUGH_PLAYERS", () -> rooms.startGame(1, reply(1)));
        assertEquals("2명 이상 모여야 시작할 수 있습니다.", message(() -> rooms.startGame(1, reply(1))));
    }

    @Test
    void 방장이_아닌_사람이_모두_준비해야_시작할_수_있다() {
        hostAndGuest();
        rooms.join(3, two(3, "가가", "나나"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);

        assertCode("NOT_ALL_READY", () -> rooms.startGame(1, reply(1)));
        assertEquals("모두 준비해야 시작할 수 있습니다.", message(() -> rooms.startGame(1, reply(1))));

        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        assertEquals("{}", last(1, "REPLY").toString());
    }

    @Test
    void 방장의_2P는_준비하지_않아도_된다() {
        rooms.create(1, two(1, "방장", "친구"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        clearSent();

        rooms.startGame(1, reply(1));

        assertEquals(3, last(2, "GAME_STARTED").get("players").size());
    }

    @Test
    void 모두_같은_팀이면_시작할_수_없다() {
        hostAndGuest();
        rooms.ready(2, true);

        assertCode("ONE_TEAM", () -> rooms.startGame(1, reply(1)));
        assertEquals("두 팀 이상이어야 시작할 수 있습니다.", message(() -> rooms.startGame(1, reply(1))));
    }

    @Test
    void 이인용_2P_둘만_있으면_팀이_같아_ONE_TEAM이다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));

        assertCode("ONE_TEAM", () -> rooms.startGame(1, reply(1)));
    }

    @Test
    void 시작_실패는_대기실_방장_인원_준비_팀_순서로_검사한다() {
        assertCode("WRONG_STATE", () -> rooms.startGame(1, reply(1)));

        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        assertCode("NOT_HOST", () -> rooms.startGame(2, reply(2)));
        assertCode("NOT_ALL_READY", () -> rooms.startGame(1, reply(1)));
        rooms.ready(2, true);
        assertCode("ONE_TEAM", () -> rooms.startGame(1, reply(1)));
        rooms.leave(2, reply(2));
        assertCode("NOT_ENOUGH_PLAYERS", () -> rooms.startGame(1, reply(1)));
    }

    @Test
    void 시작에_실패하면_아무것도_보내지_않고_대기실에_남는다() {
        hostAndGuest();
        clearSent();

        assertCode("NOT_ALL_READY", () -> rooms.startGame(1, reply(1)));

        assertTrue(sent.isEmpty());
        assertEquals(0, games.activeMatches());
        rooms.ready(2, true);
        assertEquals("WAITING", last(1, "ROOM_STATE").at("/room/status").asText());
    }

    @Test
    void 게임_중에는_다시_시작할_수_없다() {
        readyToStart();
        rooms.startGame(1, reply(1));

        assertCode("WRONG_STATE", () -> rooms.startGame(1, reply(1)));
        assertEquals(1, games.activeMatches());
    }

    // ---------- 경기 끝 ----------

    @Test
    void 경기가_끝나면_방을_대기실로_돌리고_모두의_준비를_푼다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, one(2, "손님"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        clearSent();

        games.exit(2);

        assertEquals(0, games.activeMatches());
        JsonNode state = last(1, "ROOM_STATE");
        assertEquals("WAITING", state.at("/room/status").asText());
        // GAME_EXIT한 손님은 방에서도 빠졌다
        assertEquals(List.of("방장", "셋째"), nicknames(state));
        state.get("players").forEach(p -> assertFalse(p.get("ready").asBoolean()));
        List<String> types = typesTo(1);
        assertTrue(types.indexOf("GAME_OVER") < types.indexOf("ROOM_STATE"));
        assertEquals("WAITING", last(9, "LOBBY_ROOMS").at("/rooms/0/status").asText());

        // 다시 준비하면 또 시작할 수 있다
        rooms.selectTeam(3, "GREEN");
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        assertEquals(1, games.activeMatches());
    }

    @Test
    void 경기_중에_모두_끊겨_방이_없어지면_경기가_끝나도_아무것도_보내지_않는다() {
        loggedIn.add(9L);
        readyToStart();
        rooms.startGame(1, reply(1));
        rooms.disconnect(1);
        rooms.disconnect(2);
        clearSent();

        games.disconnect(1);
        games.disconnect(2);

        assertEquals(0, games.activeMatches());
        assertTrue(sent.stream().noneMatch(s -> s.type().equals("ROOM_STATE") || s.type().equals("LOBBY_ROOMS")),
                sent.toString());
        assertEquals("{\"rooms\":[]}", listOf(9).toString());
    }

    @Test
    void 시작하자마자_모두_끊겨_끝난_경기도_방을_대기실로_돌린다() {
        readyToStart();
        closed.add(1L);
        closed.add(2L);
        clearSent();

        loggedIn.add(9L);
        rooms.startGame(1, reply(1));

        assertEquals(0, games.activeMatches());
        assertEquals("WAITING", last(1, "ROOM_STATE").at("/room/status").asText());
        // 끝난 경기를 로비에 게임 중으로 알리지 않는다
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals("WAITING", last(9, "LOBBY_ROOMS").at("/rooms/0/status").asText());
        rooms.disconnect(1);
        rooms.disconnect(2);
        assertEquals("{\"rooms\":[]}", listOf(9).toString());
    }

    // ---------- 끊김과 겹친 요청 ----------

    @Test
    void 이미_끊긴_연결은_방을_만들거나_들어갈_수_없다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        closed.add(2L);
        clearSent();

        assertCode("WRONG_STATE", () -> rooms.create(2, one(2, "손님"), "b", 8, reply(2)));
        assertCode("WRONG_STATE", () -> rooms.join(2, one(2, "손님"), 1, reply(2)));
        assertCode("WRONG_STATE", () -> rooms.quickJoin(2, one(2, "손님"), reply(2)));
        assertEquals("이미 끊긴 연결입니다.", message(() -> rooms.join(2, one(2, "손님"), 1, reply(2))));
        assertTrue(sent.isEmpty());
        assertEquals(1, listOf(9).at("/rooms/0/players").asInt());
    }

    @Test
    void 모르는_경기의_끝은_무시한다() {
        hostAndGuest();
        clearSent();

        rooms.finishGame(12345);

        assertTrue(sent.isEmpty());
    }

    // ---------- 방장 규칙 ----------

    @Test
    void 방장보다_작은_빈자리에_누가_앉아도_방장은_바뀌지_않는다() {
        rooms.create(1, one(1, "가가"), "a", 4, reply(1));
        rooms.join(2, one(2, "나나"), 1, reply(2));
        rooms.join(3, one(3, "다다"), 1, reply(3));
        rooms.leave(1, reply(1));
        clearSent();

        rooms.join(4, one(4, "라라"), 1, reply(4));

        JsonNode state = last(4, "REPLY");
        assertEquals(0, player(state, "라라").get("slot").asInt());
        assertEquals(21, state.get("hostId").asLong());
        assertEquals(List.of("라라님이 들어왔습니다."), chatsTo(2));
        assertCode("NOT_HOST", () -> rooms.startGame(4, reply(4)));
        assertCode("NOT_HOST", () -> rooms.changeMap(4, "random", reply(4)));
        rooms.changeMap(2, "random", reply(2));
        // 새로 온 사람의 준비는 무시되지 않는다
        rooms.ready(4, true);
        assertTrue(player(last(2, "ROOM_STATE"), "라라").get("ready").asBoolean());
    }

    @Test
    void 맵_변경으로_자리를_옮겨도_방장은_그대로다() {
        rooms.create(1, one(1, "s0"), "a", 8, reply(1));
        for (long c = 2; c <= 7; c++) {
            rooms.join(c, one(c, "s" + (c - 1)), 1, reply(c));
        }
        rooms.leave(1, reply(1));

        rooms.changeMap(2, "village/village10", reply(2));

        JsonNode state = last(2, "ROOM_STATE");
        assertEquals(0, player(state, "s6").get("slot").asInt());
        assertEquals(21, state.get("hostId").asLong());
        assertCode("NOT_HOST", () -> rooms.changeMap(7, "random", reply(7)));
    }

    @Test
    void 방장이_아닌데_준비하지_않은_사람만_시작을_막는다() {
        rooms.create(1, one(1, "가가"), "a", 4, reply(1));
        rooms.join(2, one(2, "나나"), 1, reply(2));
        rooms.join(3, one(3, "다다"), 1, reply(3));
        rooms.leave(1, reply(1));
        rooms.join(4, one(4, "라라"), 1, reply(4));
        rooms.selectTeam(3, "BLUE");
        rooms.ready(3, true);
        assertCode("NOT_ALL_READY", () -> rooms.startGame(2, reply(2)));

        rooms.ready(4, true);
        rooms.startGame(2, reply(2));

        assertEquals("PLAYING", listOf(9).at("/rooms/0/status").asText());
    }

    // ---------- 경기를 열지 못할 때 ----------

    /** 손님(연결 2)을 다른 경기에 넣어 두어 GameService.start가 실패하게 한다 */
    private void occupyGuestElsewhere() {
        games.start("camp/camp08", List.of(new Participant(99, 2, "딴판", "kkukku", Team.RED),
                new Participant(98, 50, "딴판둘", "kkukku", Team.BLUE)));
    }

    @Test
    void 경기를_열지_못하면_응답_뒤_대기실_상태와_안내를_보내고_준비는_그대로다() {
        loggedIn.add(9L);
        readyToStart();
        occupyGuestElsewhere();
        clearSent();

        rooms.startGame(1, reply(1));

        assertEquals(List.of("REPLY", "ROOM_STATE", "CHAT_MESSAGE"), typesTo(1));
        JsonNode state = last(1, "ROOM_STATE");
        assertEquals("WAITING", state.at("/room/status").asText());
        assertTrue(player(state, "손님").get("ready").asBoolean());
        JsonNode notice = last(1, "CHAT_MESSAGE");
        assertEquals(0, notice.get("senderId").asLong());
        assertEquals("게임을 시작하지 못했습니다. 다시 시도해 주세요.", notice.get("text").asText());
        assertTrue(typesTo(2).containsAll(List.of("ROOM_STATE", "CHAT_MESSAGE")));
        // 로비에는 게임 중이라고 알린 적이 없다
        assertTrue(typesTo(9).isEmpty());
        assertEquals("WAITING", listOf(9).at("/rooms/0/status").asText());
    }

    @Test
    void 시작하자마자_한_팀만_남아_끝나도_로비에는_대기_중으로만_알린다() {
        loggedIn.add(9L);
        readyToStart();
        clearSent();
        closed.add(2L);

        rooms.startGame(1, reply(1));

        assertEquals(0, games.activeMatches());
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals("WAITING", last(9, "LOBBY_ROOMS").at("/rooms/0/status").asText());
        assertEquals("WAITING", last(1, "ROOM_STATE").at("/room/status").asText());
    }

    @Test
    void 경기를_여는_도중_어떤_오류가_나도_대기실로_되돌린다() {
        readyToStart();
        clearSent();
        gameSenderBroken = true;

        rooms.startGame(1, reply(1));
        gameSenderBroken = false;

        assertEquals(1, to(1).stream().filter(s -> s.type().equals("REPLY")).count());
        assertEquals("WAITING", last(1, "ROOM_STATE").at("/room/status").asText());
        assertEquals("게임을 시작하지 못했습니다. 다시 시도해 주세요.", last(2, "CHAT_MESSAGE").get("text").asText());
        // 경기가 남지 않고, 아무에게도 GAME_STARTED가 가지 않았다
        assertEquals(0, games.activeMatches());
        assertTrue(sent.stream().noneMatch(s -> s.type().equals("GAME_STARTED")));

        // 준비는 그대로라 바로 다시 시작할 수 있다
        clearSent();
        rooms.startGame(1, reply(1));
        assertTrue(typesTo(2).contains("GAME_STARTED"));
        assertEquals(1, games.activeMatches());
    }

    @Test
    void 방장은_playerId로_정해져_2P가_더_작은_자리로_옮겨도_그대로다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        for (long c = 2; c <= 5; c++) {
            rooms.join(c, one(c, "p" + c), 1, reply(c));
        }
        rooms.join(7, two(7, "일피", "이피"), 1, reply(7));
        for (long c = 2; c <= 5; c++) {
            rooms.leave(c, reply(c));
        }
        rooms.leave(1, reply(1));
        assertEquals(71, last(7, "ROOM_STATE").get("hostId").asLong());

        rooms.changeMap(7, "village/village10", reply(7));

        JsonNode state = last(7, "ROOM_STATE");
        assertEquals(0, player(state, "이피").get("slot").asInt());
        assertEquals(71, state.get("hostId").asLong());
    }

    @Test
    void 로비의_게임_중_알림은_GAME_STARTED_뒤에_나간다() {
        loggedIn.add(9L);
        readyToStart();
        clearSent();

        rooms.startGame(1, reply(1));

        List<String> order = sent.stream().map(Sent::type).filter(t -> !t.equals("REPLY")).distinct().toList();
        assertEquals(List.of("GAME_STARTED", "LOBBY_ROOMS"), order);
    }

    // ---------- 검사 순서와 문구 ----------

    @Test
    void 방에_있는_연결은_끊겼어도_ALREADY_IN_ROOM이_먼저다() {
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        closed.add(1L);

        assertCode("ALREADY_IN_ROOM", () -> rooms.create(1, one(1, "가가"), "b", 8, reply(1)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.join(1, one(1, "가가"), 1, reply(1)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.quickJoin(1, one(1, "가가"), reply(1)));
    }

    @Test
    void 정원이_함께_들어가는_인원보다_적으면_CAPACITY_TOO_SMALL() {
        loggedIn.add(1L);
        List<Player> three = List.of(new Player(11, "가가"), new Player(12, "나나"), new Player(13, "다다"));

        assertCode("CAPACITY_TOO_SMALL", () -> rooms.create(1, three, "a", 2, reply(1)));
        assertEquals("정원이 함께 들어가는 인원보다 적습니다.", message(() -> rooms.create(1, three, "a", 2, reply(1))));
        assertCode("CAPACITY_INVALID", () -> rooms.create(1, three, "a", 1, reply(1)));
    }

    @Test
    void 대기실이_아닐_때의_안내_문구() {
        assertEquals("방에 있지 않습니다.", message(() -> rooms.leave(5, reply(5))));
        assertEquals("대기실에서만 준비할 수 있습니다.", message(() -> rooms.ready(5, true)));
        assertEquals("대기실에서만 캐릭터를 고를 수 있습니다.", message(() -> rooms.selectCharacter(5, "kkukku")));
        assertEquals("대기실에서만 팀을 고를 수 있습니다.", message(() -> rooms.selectTeam(5, "RED")));
        assertEquals("대기실에서만 맵을 고를 수 있습니다.", message(() -> rooms.changeMap(5, "random", reply(5))));
        assertEquals("대기실에서만 게임을 시작할 수 있습니다.", message(() -> rooms.startGame(5, reply(5))));

        readyToStart();
        rooms.startGame(1, reply(1));
        for (Runnable action : List.<Runnable>of(() -> rooms.leave(2, reply(2)), () -> rooms.ready(2, false),
                () -> rooms.selectCharacter(2, "kkukku"), () -> rooms.selectTeam(2, "RED"),
                () -> rooms.changeMap(1, "random", reply(1)), () -> rooms.startGame(1, reply(1)))) {
            assertEquals("게임 중에는 할 수 없습니다.", message(action));
        }
    }

    // ---------- null ----------

    @Test
    void 제목이_null이면_TITLE_INVALID() {
        assertCode("TITLE_INVALID", () -> rooms.create(1, one(1, "가가"), null, 8, reply(1)));
    }

    @Test
    void 채팅_내용이_null이면_방과_플레이어를_검사한_뒤_버린다() {
        assertCode("WRONG_STATE", () -> rooms.chat(1, 11, null));
        hostAndGuest();
        assertCode("BAD_MESSAGE", () -> rooms.chat(1, 21, null));
        clearSent();

        rooms.chat(1, 11, null);

        assertTrue(sent.isEmpty());
    }

    @Test
    void 만들_때_빈_값을_받지_않는다() {
        RoomSender sender = new RoomSender() {
            @Override
            public void send(Collection<Long> connectionIds, String type, Object body) {
            }

            @Override
            public void sendToLobby(Collection<Long> inRoom, String type, Object body) {
            }

            @Override
            public boolean isConnected(long connectionId) {
                return true;
            }
        };
        assertThrows(NullPointerException.class, () -> new RoomService(null, games, clock::get));
        assertThrows(NullPointerException.class, () -> new RoomService(sender, null, clock::get));
        assertThrows(NullPointerException.class, () -> new RoomService(sender, games, null));
    }

    // ---------- lock ----------

    @Test
    void 바깥에서_서비스_객체를_잠가도_방_처리가_막히지_않는다() throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (rooms) {
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        assertTrue(locked.await(1, TimeUnit.SECONDS));
        try {
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                rooms.create(1, one(1, "가가"), "a", 8, reply(1));
                done.countDown();
            });
            worker.start();
            assertTrue(done.await(1, TimeUnit.SECONDS), "RoomService가 자기 자신(this)으로 잠그면 안 됩니다.");
        } finally {
            release.countDown();
            holder.join();
        }
        assertNotNull(last(1, "REPLY"));
    }

    // ---------- 명세 대조 리뷰에서 나온 빈틈 ----------

    /** action을 다른 스레드에서 시작해 300ms 기다린다. 그 안에 끝났으면 true (lock에 막히지 않았다) */
    private static boolean finishesWhileHeld(Runnable action, AtomicReference<Thread> started) {
        Thread thread = new Thread(action);
        started.set(thread);
        thread.start();
        try {
            thread.join(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    @Test
    void 대기실_상태는_lock_안에서_넣어_모두가_같은_순서로_받는다() throws InterruptedException {
        hostAndGuest();
        List<Player> third = one(3, "셋째");
        rooms.join(3, third, 1, reply(3));
        clearSent();
        AtomicBoolean interleaved = new AtomicBoolean();
        AtomicReference<Thread> other = new AtomicReference<>();
        onSend = (to, type) -> {
            if (type.equals("ROOM_STATE") && other.get() == null) {
                interleaved.set(finishesWhileHeld(() -> rooms.ready(3, true), other));
            }
        };

        rooms.ready(2, true);
        other.get().join();
        onSend = (to, type) -> {
        };

        assertFalse(interleaved.get(), "보내는 도중에 다른 변경이 끼어들었습니다 (lock 밖에서 보냄)");
        assertEquals(2, roomStatesTo(1).size());
        assertEquals(roomStatesTo(1), roomStatesTo(2));
        assertEquals(roomStatesTo(1), roomStatesTo(3));
    }

    @Test
    void 입장_응답은_lock_안에서_넣어_다음_변경보다_먼저_간다() throws InterruptedException {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        List<Player> guest = one(2, "손님");
        List<Player> third = one(3, "셋째");
        clearSent();
        AtomicBoolean interleaved = new AtomicBoolean();
        AtomicReference<Thread> other = new AtomicReference<>();

        rooms.join(2, guest, 1, body -> {
            sent.add(new Sent(2, "REPLY", Json.MAPPER.valueToTree(body)));
            interleaved.set(finishesWhileHeld(() -> rooms.join(3, third, 1, reply(3)), other));
        });
        other.get().join();

        assertFalse(interleaved.get(), "응답을 넣는 도중에 다른 입장이 끼어들었습니다 (lock 밖에서 응답)");
        assertEquals(List.of("REPLY", "CHAT_MESSAGE", "ROOM_STATE", "CHAT_MESSAGE"), typesTo(2));
        assertEquals(2, last(2, "REPLY").get("players").size());
    }

    @Test
    void 방_목록_응답은_lock_안에서_넣어_로비_알림보다_늦게_오지_않는다() throws InterruptedException {
        loggedIn.add(1L);
        List<Player> maker = one(2, "만듦");
        AtomicBoolean interleaved = new AtomicBoolean();
        AtomicReference<Thread> other = new AtomicReference<>();

        rooms.list(1, body -> {
            sent.add(new Sent(1, "REPLY", Json.MAPPER.valueToTree(body)));
            interleaved.set(finishesWhileHeld(() -> rooms.create(2, maker, "새 방", 8, reply(2)), other));
        });
        other.get().join();

        assertFalse(interleaved.get(), "목록 응답을 넣는 도중에 방이 생겼습니다 (lock 밖에서 응답)");
        assertEquals(List.of("REPLY", "LOBBY_ROOMS"), typesTo(1));
        assertEquals("{\"rooms\":[]}", last(1, "REPLY").toString());
        assertEquals(1, last(1, "LOBBY_ROOMS").get("rooms").size());
    }

    /** 연결 connections가 한꺼번에 action을 하고, 성공은 "OK", 실패는 code를 돌려준다 */
    private static List<String> concurrently(List<Long> connections, LongConsumer action)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (long connection : connections) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    action.accept(connection);
                    results.add("OK");
                } catch (GameException e) {
                    results.add(e.code());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        return results;
    }

    private static long count(List<String> results, String result) {
        return results.stream().filter(result::equals).count();
    }

    @RepeatedTest(20)
    void 마지막_자리에_여럿이_동시에_들어와도_한_명만_들어간다() throws InterruptedException {
        rooms.create(1, one(1, "방장"), "a", 2, reply(1));
        List<Long> connections = new ArrayList<>();
        Map<Long, List<Player>> players = new HashMap<>();
        for (long c = 2; c <= 17; c++) {
            connections.add(c);
            players.put(c, one(c, "p" + c));
        }

        List<String> results = concurrently(connections, c -> rooms.join(c, players.get(c), 1, body -> {
        }));

        assertEquals(1, count(results, "OK"), results.toString());
        assertEquals(15, count(results, "ROOM_FULL"), results.toString());
        assertEquals(2, listOf(99).at("/rooms/0/players").asInt());
    }

    @RepeatedTest(20)
    void 바로_시작이_동시에_몰려도_자리만큼만_들어간다() throws InterruptedException {
        rooms.create(1, one(1, "가가"), "a", 2, reply(1));
        rooms.create(2, one(2, "나나"), "b", 2, reply(2));
        List<Long> connections = new ArrayList<>();
        Map<Long, List<Player>> players = new HashMap<>();
        for (long c = 3; c <= 10; c++) {
            connections.add(c);
            players.put(c, one(c, "p" + c));
        }

        List<String> results = concurrently(connections, c -> rooms.quickJoin(c, players.get(c), body -> {
        }));

        assertEquals(2, count(results, "OK"), results.toString());
        assertEquals(6, count(results, "NO_ROOM"), results.toString());
        JsonNode list = listOf(99);
        assertEquals(2, list.at("/rooms/0/players").asInt());
        assertEquals(2, list.at("/rooms/1/players").asInt());
    }

    @RepeatedTest(10)
    void 방이_99개일_때_여럿이_동시에_만들어도_100개를_넘지_않고_번호가_겹치지_않는다() throws InterruptedException {
        for (long c = 1; c <= 99; c++) {
            rooms.create(c, one(c, "p" + c), "방" + c, 8, reply(c));
        }
        List<Long> connections = new ArrayList<>();
        Map<Long, List<Player>> players = new HashMap<>();
        for (long c = 100; c < 110; c++) {
            connections.add(c);
            players.put(c, one(c, "p" + c));
        }

        List<String> results = concurrently(connections, c -> rooms.create(c, players.get(c), "늦은 방", 8, body -> {
        }));

        assertEquals(1, count(results, "OK"), results.toString());
        assertEquals(9, count(results, "ROOM_LIMIT"), results.toString());
        JsonNode list = listOf(999);
        assertEquals(100, list.get("rooms").size());
        Set<Integer> numbers = new HashSet<>();
        list.get("rooms").forEach(room -> numbers.add(room.get("number").asInt()));
        assertEquals(100, numbers.size());
    }

    @Test
    void 다른_방에_있는_사람은_대기실이든_게임_중이든_LOBBY_ROOMS를_받지_않는다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "대기"), "a", 8, reply(1));
        rooms.create(2, one(2, "방장"), "b", 8, reply(2));
        rooms.join(3, one(3, "손님"), 2, reply(3));
        rooms.selectTeam(3, "BLUE");
        rooms.ready(3, true);
        rooms.startGame(2, reply(2));
        clearSent();

        rooms.create(4, one(4, "새방"), "c", 8, reply(4));

        for (long c : new long[]{1, 2, 3, 4}) {
            assertFalse(typesTo(c).contains("LOBBY_ROOMS"), c + "번 연결");
        }
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals(3, last(9, "LOBBY_ROOMS").get("rooms").size());
    }

    @Test
    void 바로_시작은_사람_수와_상관없이_번호가_가장_작은_방을_고르고_로비에_알린다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        rooms.create(2, one(2, "나나"), "b", 8, reply(2));
        rooms.join(3, one(3, "다다"), 2, reply(3));
        rooms.join(4, one(4, "라라"), 2, reply(4));
        clearSent();

        // 1번 방 1명, 2번 방 3명
        rooms.quickJoin(5, one(5, "마마"), reply(5));
        assertEquals(1, last(5, "REPLY").at("/room/number").asInt());
        assertEquals(List.of("LOBBY_ROOMS"), typesTo(9));
        assertEquals(2, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());

        // 1번 방 4명, 2번 방 3명
        rooms.join(6, one(6, "바바"), 1, reply(6));
        rooms.join(7, one(7, "사사"), 1, reply(7));
        rooms.quickJoin(8, one(8, "아아"), reply(8));
        assertEquals(1, last(8, "REPLY").at("/room/number").asInt());
    }

    @Test
    void 도배_방지는_2P_두_사람을_합쳐_연결마다_센다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));
        rooms.join(2, one(2, "다다"), 1, reply(2));
        clearSent();

        rooms.chat(1, 11, "1");
        rooms.chat(1, 12, "2");
        rooms.chat(1, 11, "3");
        rooms.chat(1, 12, "4");

        assertEquals(List.of("1", "2", "3"), chatsTo(2));
    }

    @Test
    void 채팅_playerId가_0이나_음수면_서버_안내를_흉내_낼_수_없게_BAD_MESSAGE() {
        hostAndGuest();
        clearSent();

        assertCode("BAD_MESSAGE", () -> rooms.chat(1, 0, "공지"));
        assertCode("BAD_MESSAGE", () -> rooms.chat(1, -11, "공지"));

        assertTrue(sent.isEmpty());
    }

    @Test
    void 실패한_캐릭터_팀_선택은_다음_방에_쓰지_않는다() {
        assertCode("WRONG_STATE", () -> rooms.selectCharacter(1, "kkokko"));
        assertCode("WRONG_STATE", () -> rooms.selectTeam(1, "PINK"));
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        JsonNode me = player(last(1, "REPLY"), "가가");
        assertEquals("sangsangbugi", me.get("character").asText());
        assertEquals("RED", me.get("team").asText());

        rooms.join(2, one(2, "나나"), 1, reply(2));
        rooms.selectCharacter(2, "kkukku");
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.startGame(1, reply(1));
        assertCode("WRONG_STATE", () -> rooms.selectCharacter(2, "sangzzi"));
        assertCode("WRONG_STATE", () -> rooms.selectTeam(2, "PINK"));
        games.exit(2);
        rooms.create(2, one(2, "나나"), "b", 8, reply(2));

        JsonNode guest = player(last(2, "REPLY"), "나나");
        assertEquals("kkukku", guest.get("character").asText());
        assertEquals("BLUE", guest.get("team").asText());
    }

    @Test
    void 대기실에서_2P_연결이_끊기면_두_사람이_함께_빠진다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));
        clearSent();

        rooms.disconnect(2);

        assertEquals(List.of("ROOM_STATE", "CHAT_MESSAGE", "CHAT_MESSAGE"), typesTo(1));
        assertEquals(List.of("가가님이 나갔습니다.", "나나님이 나갔습니다."), chatsTo(1));
        assertEquals(List.of("방장"), nicknames(last(1, "ROOM_STATE")));
    }

    @Test
    void 방장인_2P_연결이_끊기면_두_사람의_퇴장_안내_뒤에_새_방장을_알린다() {
        rooms.create(1, two(1, "가가", "나나"), "a", 8, reply(1));
        rooms.join(2, one(2, "다다"), 1, reply(2));
        clearSent();

        rooms.disconnect(1);

        assertEquals(List.of("가가님이 나갔습니다.", "나나님이 나갔습니다.", "다다님이 방장이 되었습니다."), chatsTo(2));
        assertEquals(21, last(2, "ROOM_STATE").get("hostId").asLong());
    }

    @Test
    void 게임_중에_2P_연결이_나가면_안내만_두_줄이고_경기_뒤_대기실에_없다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(2, "BLUE");
        rooms.selectTeam(3, "GREEN");
        rooms.ready(2, true);
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        clearSent();

        games.exit(2);

        assertEquals(List.of("가가님이 나갔습니다.", "나나님이 나갔습니다."), chatsTo(1));
        assertFalse(typesTo(1).contains("ROOM_STATE"));
        assertFalse(typesTo(1).contains("GAME_OVER"));
        List<Long> left = new ArrayList<>();
        last(1, "PLAYER_LEFT").get("playerIds").forEach(id -> left.add(id.asLong()));
        assertEquals(List.of(21L, 22L), left);
        assertEquals(2, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());

        games.exit(3);
        assertEquals(List.of("방장"), nicknames(last(1, "ROOM_STATE")));
    }

    @Test
    void 맵_변경이_실패하면_아무것도_보내지_않고_맵과_정원이_그대로다() {
        loggedIn.add(9L);
        rooms.create(1, one(1, "s0"), "a", 8, reply(1));
        for (long c = 2; c <= 7; c++) {
            rooms.join(c, one(c, "s" + (c - 1)), 1, reply(c));
        }
        clearSent();

        assertCode("MAP_TOO_SMALL", () -> rooms.changeMap(1, "village/village10", reply(1)));
        assertCode("MAP_INVALID", () -> rooms.changeMap(1, "nowhere", reply(1)));
        assertCode("NOT_HOST", () -> rooms.changeMap(2, "random", reply(2)));

        assertTrue(sent.isEmpty());
        rooms.ready(2, true);
        JsonNode state = last(1, "ROOM_STATE");
        assertEquals("camp/camp08", state.at("/room/mapId").asText());
        assertEquals(8, state.at("/room/capacity").asInt());
    }

    @Test
    void 준비_캐릭터_팀_같은_맵_채팅은_로비에_알리지_않는다() {
        loggedIn.add(9L);
        hostAndGuest();
        clearSent();

        rooms.ready(2, true);
        rooms.selectCharacter(2, "kkukku");
        rooms.selectTeam(2, "BLUE");
        rooms.changeMap(1, "camp/camp08", reply(1));
        rooms.chat(1, 11, "안녕");

        assertTrue(typesTo(9).isEmpty(), typesTo(9).toString());
    }

    @Test
    void 방장이_나가면_자리가_가장_작은_2P의_둘째도_방장이_될_수_있다() {
        rooms.create(1, one(1, "s0"), "a", 8, reply(1));
        for (long c = 2; c <= 5; c++) {
            rooms.join(c, one(c, "s" + (c - 1)), 1, reply(c));
        }
        rooms.join(7, two(7, "일피", "이피"), 1, reply(7));
        rooms.leave(2, reply(2));
        // 정원이 6으로 줄어 slot 6의 이피가 빈 slot 1로 옮겨진다
        rooms.changeMap(1, "village/village10", reply(1));
        assertEquals(1, player(last(7, "ROOM_STATE"), "이피").get("slot").asInt());
        clearSent();

        rooms.leave(1, reply(1));

        assertEquals(72, last(3, "ROOM_STATE").get("hostId").asLong());
        assertEquals(List.of("s0님이 나갔습니다.", "이피님이 방장이 되었습니다."), chatsTo(3));
    }

    @Test
    void GAME_STARTED에는_플레이어마다_고른_캐릭터와_팀과_서로_다른_스폰이_들어간다() {
        rooms.create(1, one(1, "방장"), "a", 8, reply(1));
        rooms.join(2, two(2, "가가", "나나"), 1, reply(2));
        rooms.selectCharacter(1, "kkokko");
        rooms.selectCharacter(2, "hansungnyangi");
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        clearSent();

        rooms.startGame(1, reply(1));

        JsonNode started = last(1, "GAME_STARTED");
        assertEquals(List.of("mapId", "players"), fieldNames(started));
        assertTrue(GameMaps.exists(started.get("mapId").asText()));
        Map<Long, String> seen = new HashMap<>();
        Set<Integer> spawns = new HashSet<>();
        for (JsonNode p : started.get("players")) {
            assertEquals(List.of("playerId", "nickname", "character", "team", "spawn"), fieldNames(p));
            seen.put(p.get("playerId").asLong(),
                    p.get("nickname").asText() + "/" + p.get("character").asText() + "/" + p.get("team").asText());
            int spawn = p.get("spawn").asInt();
            assertTrue(spawn >= 1 && spawn <= 8, "spawn " + spawn);
            spawns.add(spawn);
        }
        assertEquals(Map.of(11L, "방장/kkokko/RED", 21L, "가가/hansungnyangi/BLUE",
                22L, "나나/hansungnyangi/BLUE"), seen);
        assertEquals(3, spawns.size());
        assertEquals(started, last(2, "GAME_STARTED"));
    }

    @Test
    void 로딩_시간_안에_LOADED를_보내지_않은_연결은_방에서도_빠져_로비로_간다() {
        loggedIn.add(9L);
        readyToStart();
        rooms.join(3, one(3, "셋째"), 1, reply(3));
        rooms.selectTeam(3, "GREEN");
        rooms.ready(3, true);
        rooms.startGame(1, reply(1));
        games.loaded(1);
        games.loaded(2);
        clearSent();

        clock.addAndGet(Match.LOAD_TIMEOUT_MS);
        games.tick();

        assertTrue(typesTo(3).contains("PLAYER_LEFT"));
        assertEquals(List.of("셋째님이 나갔습니다."), chatsTo(1));
        assertFalse(typesTo(1).contains("ROOM_STATE"));
        assertTrue(typesTo(3).contains("LOBBY_ROOMS"));
        assertEquals(2, last(9, "LOBBY_ROOMS").at("/rooms/0/players").asInt());
        assertEquals(1, listOf(3).get("rooms").size());

        games.exit(2);
        assertEquals(List.of("방장"), nicknames(last(1, "ROOM_STATE")));
    }

    @Test
    void 아무도_LOADED를_보내지_않으면_모두_로비로_가고_방이_없어진다() {
        loggedIn.add(9L);
        readyToStart();
        rooms.startGame(1, reply(1));
        clearSent();

        clock.addAndGet(Match.LOAD_TIMEOUT_MS);
        games.tick();

        assertEquals(0, games.activeMatches());
        assertEquals("{\"rooms\":[]}", listOf(9).toString());
        assertEquals("{\"rooms\":[]}", last(1, "LOBBY_ROOMS").toString());
        assertTrue(sent.stream().noneMatch(s -> s.type().equals("ROOM_STATE")));
    }

    @Test
    void 경기가_로딩_시간_초과로_끝나도_대기실에_빠진_사람이_남지_않는다() {
        readyToStart();
        rooms.startGame(1, reply(1));
        games.loaded(1);
        clearSent();

        clock.addAndGet(Match.LOAD_TIMEOUT_MS);
        games.tick();

        // 손님이 빠져 RED만 남아 경기가 끝난다. 그 뒤 대기실 상태는 처음부터 방장 혼자다
        assertEquals(0, games.activeMatches());
        List<JsonNode> states = roomStatesTo(1);
        assertFalse(states.isEmpty());
        states.forEach(state -> assertEquals(List.of("방장"), nicknames(state)));
        assertEquals("WAITING", states.getLast().at("/room/status").asText());
    }

    @Test
    void 없어진_방의_경기가_늦게_끝나도_같은_번호의_새_방을_건드리지_않는다() {
        readyToStart();
        rooms.startGame(1, reply(1));
        rooms.disconnect(1);
        rooms.disconnect(2);
        rooms.create(3, one(3, "새방장"), "새 방", 8, reply(3));
        assertEquals(1, last(3, "REPLY").at("/room/number").asInt());
        rooms.join(4, one(4, "새손님"), 1, reply(4));
        rooms.selectTeam(4, "BLUE");
        rooms.ready(4, true);
        rooms.startGame(3, reply(3));
        clearSent();

        games.disconnect(1);
        games.disconnect(2);

        assertEquals(1, games.activeMatches());
        assertTrue(sent.stream().noneMatch(s -> s.type().equals("ROOM_STATE") || s.type().equals("LOBBY_ROOMS")));
        assertCode("ROOM_PLAYING", () -> rooms.join(5, one(5, "늦게"), 1, reply(5)));
    }

    @Test
    void 경기가_끝나도_방의_랜덤_맵과_랜덤_캐릭터는_그대로다() {
        hostAndGuest();
        rooms.selectCharacter(2, "random");
        rooms.selectTeam(2, "BLUE");
        rooms.ready(2, true);
        rooms.changeMap(1, "random", reply(1));
        rooms.startGame(1, reply(1));
        games.loaded(1);
        games.loaded(2);
        clock.addAndGet(Match.COUNTDOWN_MS);
        games.tick();
        clock.addAndGet(Match.TIME_LIMIT_MS);
        games.tick();

        assertEquals(0, games.activeMatches());
        JsonNode state = last(1, "ROOM_STATE");
        assertEquals("WAITING", state.at("/room/status").asText());
        assertEquals("random", state.at("/room/mapId").asText());
        assertEquals("random", player(state, "손님").get("character").asText());
    }

    @Test
    void 게임_중에는_방장이_아닌_사람의_시작과_맵_변경도_WRONG_STATE가_먼저다() {
        readyToStart();
        rooms.startGame(1, reply(1));

        assertCode("WRONG_STATE", () -> rooms.startGame(2, reply(2)));
        assertCode("WRONG_STATE", () -> rooms.changeMap(2, "random", reply(2)));
    }

    @Test
    void 게임_중인_연결의_로비_요청은_방_안에_있을_때의_오류다() {
        readyToStart();
        rooms.startGame(1, reply(1));

        assertCode("WRONG_STATE", () -> rooms.list(2, reply(2)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.create(2, one(2, "손님"), "b", 8, reply(2)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.join(2, one(2, "손님"), 1, reply(2)));
        assertCode("ALREADY_IN_ROOM", () -> rooms.quickJoin(2, one(2, "손님"), reply(2)));
    }

    @Test
    void 숫자_경계와_맵_때문에_줄어든_정원() {
        assertCode("CAPACITY_INVALID", () -> rooms.create(1, one(1, "가가"), "a", -1, reply(1)));
        rooms.create(1, one(1, "가가"), "a", 8, reply(1));
        assertCode("ROOM_NOT_FOUND", () -> rooms.join(2, one(2, "나나"), 0, reply(2)));
        assertCode("ROOM_NOT_FOUND", () -> rooms.join(2, one(2, "나나"), -1, reply(2)));

        rooms.changeMap(1, "village/village10", reply(1));
        for (long c = 2; c <= 6; c++) {
            rooms.join(c, one(c, "p" + c), 1, reply(c));
        }
        assertCode("ROOM_FULL", () -> rooms.join(7, one(7, "p7"), 1, reply(7)));
        assertCode("NO_ROOM", () -> rooms.quickJoin(7, one(7, "p7"), reply(7)));
    }
}

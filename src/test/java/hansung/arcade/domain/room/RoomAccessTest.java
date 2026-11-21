package hansung.arcade.domain.room;

import hansung.arcade.domain.game.model.GameMaps;
import hansung.arcade.domain.room.business.RoomSender;
import hansung.arcade.domain.room.business.RoomService;
import hansung.arcade.domain.room.exception.RoomErrorCode;
import hansung.arcade.domain.room.model.ChatText;
import hansung.arcade.domain.room.model.Room;
import hansung.arcade.domain.room.model.RoomMember;
import hansung.arcade.domain.room.model.RoomMessages;
import hansung.arcade.domain.room.model.RoomStatus;
import hansung.arcade.domain.room.web.controller.RoomConnections;
import hansung.arcade.domain.room.web.controller.RoomHandlers;
import hansung.arcade.domain.room.web.controller.RoomListener;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 공개해야 하는 것만 public이다. 클래스는 final(또는 enum·record·interface), 인스턴스 필드는 모두 private,
 * static 필드는 모두 final이고 public이 아니어도 되는 상수(테스트만 쓰는 상한 등)는 public이 아니다. 상수만 모은 클래스는 생성자가 private이다.
 * public 메서드는 아래 목록과 정확히 같아야 한다 (equals·hashCode·toString은 따지지 않는다). 그 밖의 도우미는 private이나 package-private로 둔다.
 */
class RoomAccessTest {

    private static final Map<Class<?>, Set<String>> PUBLIC_METHODS = Map.ofEntries(
            Map.entry(Room.class, Set.of("number", "title", "capacity", "mapId", "status", "hostId", "members",
                    "size", "isEmpty", "canSeat", "membersOf", "isHostConnection", "seat", "unseat", "ready",
                    "selectCharacter", "selectTeam", "changeMap", "startPlaying", "cancelPlaying", "finishPlaying", "allReady",
                    "teamCount")),
            Map.entry(RoomMember.class, Set.of("playerId", "connectionId", "nickname", "slot", "character", "team",
                    "ready")),
            Map.entry(ChatText.class, Set.of("byteLength", "clean")),
            Map.entry(RoomMessages.class, Set.of("list", "state", "chat", "notice")),
            Map.entry(RoomService.class, Set.of("list", "create", "join", "quickJoin", "leave", "exitGame", "disconnect", "ready",
                    "selectCharacter", "selectTeam", "changeMap", "chat", "startGame", "finishGame")),
            Map.entry(RoomSender.class, Set.of("send", "sendToLobby", "isConnected")),
            Map.entry(RoomConnections.class, Set.of("onOpen", "onClose", "send", "sendToLobby", "isConnected")),
            Map.entry(RoomListener.class, Set.of("onClose")),
            Map.entry(RoomHandlers.class, Set.of("registerTo")),
            Map.entry(RoomErrorCode.class, Set.of()),
            Map.entry(GameMaps.class, Set.of("exists", "selectable", "maxPlayers", "capacityOf", "name", "pick")));

    private static final Set<Class<?>> CONSTANT_HOLDERS = Set.of(RoomErrorCode.class, ChatText.class,
            RoomMessages.class, GameMaps.class);

    @Test
    void public_메서드는_공개해야_하는_것뿐이다() {
        PUBLIC_METHODS.forEach((type, expected) -> {
            Set<String> actual = Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .map(Method::getName)
                    .filter(name -> !Set.of("equals", "hashCode", "toString").contains(name))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(expected), actual, type.getSimpleName() + "의 public 메서드");
        });
    }

    @Test
    void 클래스는_상속할_수_없다() {
        for (Class<?> type : PUBLIC_METHODS.keySet()) {
            if (type.isInterface() || type.isEnum() || type.isRecord()) {
                continue;
            }
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getSimpleName() + "는 final이어야 합니다.");
        }
        assertTrue(RoomStatus.class.isEnum());
        assertTrue(RoomMember.class.isRecord());
        assertTrue(RoomSender.class.isInterface());
    }

    @Test
    void 필드는_private이고_공개_필드는_상수뿐이다() {
        for (Class<?> type : PUBLIC_METHODS.keySet()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (field.isSynthetic() || field.isEnumConstant()) {
                    continue;
                }
                String name = type.getSimpleName() + "." + field.getName();
                if (Modifier.isStatic(modifiers)) {
                    assertTrue(Modifier.isFinal(modifiers), name + "는 final이어야 합니다.");
                } else {
                    assertTrue(Modifier.isPrivate(modifiers), name + "는 private이어야 합니다.");
                }
            }
        }
    }

    @Test
    void 상수와_정적_도우미만_있는_클래스는_만들_수_없다() {
        for (Class<?> type : CONSTANT_HOLDERS) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()),
                        type.getSimpleName() + "의 생성자는 private이어야 합니다.");
            }
        }
    }

    @Test
    void 테스트만_쓰는_상한은_public이_아니다() throws NoSuchFieldException {
        for (String name : new String[]{"MAX_ROOMS", "MAX_CHATS_PER_WINDOW", "CHAT_WINDOW_MS"}) {
            int modifiers = RoomService.class.getDeclaredField(name).getModifiers();
            assertTrue(!Modifier.isPublic(modifiers) && !Modifier.isPrivate(modifiers),
                    "RoomService." + name + "는 package-private이어야 합니다.");
        }
        int modifiers = GameMaps.class.getDeclaredField("RANDOM_MAX_PLAYERS").getModifiers();
        assertTrue(!Modifier.isPublic(modifiers) && !Modifier.isPrivate(modifiers));
    }

    @Test
    void RoomMember를_바꾸는_도우미는_밖에서_쓸_수_없다() {
        for (Method method : RoomMember.class.getDeclaredMethods()) {
            if (method.getName().startsWith("with")) {
                assertTrue(!Modifier.isPublic(method.getModifiers()), method.getName() + "는 public이면 안 됩니다.");
            }
        }
    }
}

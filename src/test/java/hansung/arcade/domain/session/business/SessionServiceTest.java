package hansung.arcade.domain.session.business;

import hansung.arcade.domain.player.model.NicknameRule;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.domain.player.repository.InMemoryPlayerRepository;
import hansung.arcade.domain.session.model.Session;
import hansung.arcade.domain.session.model.SessionState;
import hansung.arcade.global.exception.CommonErrorCode;
import hansung.arcade.global.exception.GameException;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceTest {

    private final InMemoryPlayerRepository repository = new InMemoryPlayerRepository();
    private final SessionService service = new SessionService(repository);

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(GameException.class, action::run).code());
    }

    @Test
    void 접속하면_로그인_전_세션이_생긴다() {
        Session session = service.open(7);

        assertEquals(7, session.connectionId());
        assertEquals(SessionState.CONNECTED, session.state());
        assertFalse(session.isLoggedIn());
    }

    @Test
    void 닉네임_둘로_로그인하면_로비로_가고_playerId를_받는다() {
        Session session = service.open(1);

        List<Player> players = service.login(session, List.of(" 상상부기 ", "꾸꾸"));

        assertEquals(List.of("상상부기", "꾸꾸"), players.stream().map(Player::nickname).toList());
        assertTrue(players.get(0).playerId() >= 1);
        assertEquals(SessionState.LOBBY, session.state());
        assertEquals(players, session.players());
        assertEquals(players, service.requireLoggedIn(session));
    }

    @Test
    void 닉네임_개수가_틀리면_BAD_MESSAGE() {
        Session session = service.open(1);

        assertCode("BAD_MESSAGE", () -> service.login(session, null));
        assertCode("BAD_MESSAGE", () -> service.login(session, List.of()));
        assertCode("BAD_MESSAGE", () -> service.login(session, List.of("가나", "다라", "마바")));
    }

    @Test
    void 규칙에_어긋나면_NICKNAME_INVALID와_규칙_문구() {
        Session session = service.open(1);

        GameException e = assertThrows(GameException.class, () -> service.login(session, List.of("부기", "ㅋㅋ")));

        assertEquals("NICKNAME_INVALID", e.code());
        assertEquals(NicknameRule.HANGUL_MESSAGE, e.getMessage());
        assertCode("BAD_MESSAGE", () -> service.login(session, Arrays.asList("부기", null)));
        assertTrue(repository.findAll().isEmpty());
    }

    @Test
    void 보이지_않는_글자를_섞으면_NICKNAME_INVALID() {
        service.login(service.open(1), List.of("꾸꾸"));

        GameException e = assertThrows(GameException.class, () -> service.login(service.open(2), List.of("꾸\u200B꾸")));

        assertEquals("NICKNAME_INVALID", e.code());
        assertEquals(NicknameRule.CHARSET_MESSAGE, e.getMessage());
    }

    @Test
    void 일P와_이P가_같으면_NICKNAME_SAME() {
        assertCode("NICKNAME_SAME", () -> service.login(service.open(1), List.of("Bugi", "bugi")));
    }

    @Test
    void 다른_연결이_쓰는_닉네임이면_NICKNAME_DUPLICATE이고_세션은_그대로() {
        service.login(service.open(1), List.of("꾸꾸"));
        Session other = service.open(2);

        GameException e = assertThrows(GameException.class, () -> service.login(other, List.of("상상부기", "꾸꾸")));

        assertEquals("NICKNAME_DUPLICATE", e.code());
        assertEquals("이미 쓰고 있는 닉네임입니다: 꾸꾸", e.getMessage());
        assertEquals(SessionState.CONNECTED, other.state());
        assertFalse(repository.existsByNickname("상상부기"));
    }

    @Test
    void 로그인한_연결이_다시_보내면_ALREADY_LOGGED_IN() {
        Session session = service.open(1);
        service.login(session, List.of("부기"));

        assertCode("ALREADY_LOGGED_IN", () -> service.login(session, List.of("꾸꾸")));
        // 형식이 틀려도 이미 로그인했다는 것을 먼저 알린다
        assertCode("ALREADY_LOGGED_IN", () -> service.login(session, List.of("a")));
        assertCode("ALREADY_LOGGED_IN", () -> service.login(session, List.of()));
        assertFalse(repository.existsByNickname("꾸꾸"));
    }

    @Test
    void 영문은_대소문자를_구분하지_않는다() {
        service.login(service.open(1), List.of("Bugi"));

        assertCode("NICKNAME_DUPLICATE", () -> service.login(service.open(2), List.of("BUGI")));
        // 한글이 섞여 있어도 영문 부분만 접어서 비교한다
        service.login(service.open(3), List.of("부기A"));
        assertCode("NICKNAME_DUPLICATE", () -> service.login(service.open(4), List.of("부기a")));
    }

    @RepeatedTest(200)
    void 로그인과_끊김이_동시에_일어나도_닉네임이_남지_않는다() throws Exception {
        Session session = service.open(1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread login = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                service.login(session, List.of("상상부기", "꾸꾸"));
            } catch (GameException e) {
                // 끊김이 먼저면 WRONG_STATE
                if (!CommonErrorCode.WRONG_STATE.equals(e.code())) {
                    unexpected.set(e);
                }
            } catch (Throwable e) {
                unexpected.set(e);
            }
        });
        Thread close = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                service.close(session);
            } catch (Throwable e) {
                unexpected.set(e);
            }
        });
        start.countDown();
        login.join(3000);
        close.join(3000);

        assertFalse(login.isAlive() || close.isAlive(), "3초 안에 끝나지 않았습니다");
        assertNull(unexpected.get());

        assertEquals(SessionState.CLOSED, session.state());
        assertTrue(repository.findAll().isEmpty(), repository.findAll().toString());
    }

    @Test
    void 로그인_전이면_NOT_LOGGED_IN() {
        assertCode("NOT_LOGGED_IN", () -> service.requireLoggedIn(service.open(1)));
    }

    @Test
    void 끊기면_닉네임을_풀고_정리는_한_번만_한다() {
        Session session = service.open(1);
        service.login(session, List.of("상상부기", "꾸꾸"));

        service.close(session);
        service.close(session);

        assertEquals(SessionState.CLOSED, session.state());
        assertTrue(repository.findAll().isEmpty());
        service.login(service.open(2), List.of("꾸꾸"));
    }

    @Test
    void 닫힌_세션으로는_로그인하지_않는다() {
        Session session = service.open(1);
        service.close(session);

        assertCode("WRONG_STATE", () -> service.login(session, List.of("부기")));
        assertTrue(repository.findAll().isEmpty());
    }
}

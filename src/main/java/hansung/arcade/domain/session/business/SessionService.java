package hansung.arcade.domain.session.business;

import hansung.arcade.domain.player.exception.DuplicateNicknameException;
import hansung.arcade.domain.player.model.NicknameRule;
import hansung.arcade.domain.player.model.Player;
import hansung.arcade.domain.player.repository.PlayerRepository;
import hansung.arcade.domain.session.model.Session;
import hansung.arcade.global.exception.GameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static hansung.arcade.domain.session.exception.SessionErrorCode.ALREADY_LOGGED_IN;
import static hansung.arcade.domain.session.exception.SessionErrorCode.NICKNAME_DUPLICATE;
import static hansung.arcade.domain.session.exception.SessionErrorCode.NICKNAME_INVALID;
import static hansung.arcade.domain.session.exception.SessionErrorCode.NICKNAME_SAME;
import static hansung.arcade.global.exception.CommonErrorCode.BAD_MESSAGE;
import static hansung.arcade.global.exception.CommonErrorCode.NOT_LOGGED_IN;
import static hansung.arcade.global.exception.CommonErrorCode.WRONG_STATE;

/**
 * 연결 하나의 일생: 접속(세션 만들기) → LOGIN(닉네임 검사·등록) → 끊김(닉네임 풀기).
 * 네트워크 계층을 모르고, 실패는 {@link GameException}으로 알린다.
 *
 * <p>lock 순서는 세션 → 저장소 하나뿐이다. 방·로비 API를 더할 때 세션이나 방을 잠근 채 다른 연결에 보내지 않는다
 * (받는 쪽 송신 큐가 넘치면 그 자리에서 그 연결의 정리가 돌아 lock이 엇갈릴 수 있다). 보낼 대상만 모아 lock 밖에서 보낸다.
 */
public final class SessionService {

    /** 한 연결의 플레이어 수: 1P 또는 2P */
    public static final int MAX_LOCAL_PLAYERS = 2;

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final PlayerRepository players;

    public SessionService(PlayerRepository players) {
        this.players = Objects.requireNonNull(players);
    }

    /** 접속 직후 부른다. 로그인 전 상태의 세션을 만든다 */
    public Session open(long connectionId) {
        return new Session(connectionId);
    }

    /**
     * 닉네임 1~2개로 로그인한다. 2P의 두 닉네임은 한꺼번에 등록되거나 한꺼번에 실패한다.
     *
     * @param rawNicknames 클라이언트가 보낸 그대로. 1P, 2P 순서
     * @return 등록한 플레이어. 같은 순서
     * @throws GameException BAD_MESSAGE, NICKNAME_INVALID, NICKNAME_SAME, NICKNAME_DUPLICATE, ALREADY_LOGGED_IN
     */
    public List<Player> login(Session session, List<String> rawNicknames) {
        // 로그인한 연결이 무엇을 보내든 ALREADY_LOGGED_IN이 먼저다 (아래 lock 안에서 한 번 더 본다)
        requireBeforeLogin(session);
        if (rawNicknames == null || rawNicknames.isEmpty() || rawNicknames.size() > MAX_LOCAL_PLAYERS) {
            throw new GameException(BAD_MESSAGE, "닉네임은 1~2개를 보내야 합니다.");
        }
        List<String> nicknames = new ArrayList<>(rawNicknames.size());
        for (String raw : rawNicknames) {
            if (raw == null) {
                throw new GameException(BAD_MESSAGE, "닉네임이 비어 있습니다.");
            }
            try {
                nicknames.add(NicknameRule.validate(raw));
            } catch (IllegalArgumentException e) {
                throw new GameException(NICKNAME_INVALID, e.getMessage());
            }
        }
        if (nicknames.size() == 2
                && Player.nicknameKey(nicknames.get(0)).equals(Player.nicknameKey(nicknames.get(1)))) {
            throw new GameException(NICKNAME_SAME, "1P와 2P 닉네임이 같습니다.");
        }

        // 등록과 세션 변경 사이에 끊김 정리(close)가 끼어들면 닉네임이 영영 안 풀린다. 세션을 잠근 채 둘 다 한다.
        synchronized (session) {
            requireBeforeLogin(session);
            List<Player> loggedIn;
            try {
                loggedIn = players.saveAll(nicknames);
            } catch (DuplicateNicknameException e) {
                throw new GameException(NICKNAME_DUPLICATE, "이미 쓰고 있는 닉네임입니다: " + e.nickname());
            }
            session.login(loggedIn);
            log.info("#{} 로그인: {}", session.connectionId(), loggedIn);
            return loggedIn;
        }
    }

    /**
     * 아직 로그인하지 않은 연결인지 확인한다. LOGIN 핸들러는 body 모양을 보기 전에 이걸 먼저 부른다.
     *
     * @throws GameException 로그인했으면 ALREADY_LOGGED_IN, 끊겼으면 WRONG_STATE
     */
    public void requireBeforeLogin(Session session) {
        switch (session.state()) {
            case CONNECTED -> {
            }
            case CLOSED -> throw new GameException(WRONG_STATE, "이미 끊긴 연결입니다.");
            default -> throw new GameException(ALREADY_LOGGED_IN, "이미 로그인했습니다.");
        }
    }

    /**
     * 로그인했는지 확인한다. 로비·방 API는 처리 전에 이걸 부른다.
     *
     * @throws GameException 로그인 전이면 NOT_LOGGED_IN
     */
    public List<Player> requireLoggedIn(Session session) {
        List<Player> loggedIn = session.players();
        if (loggedIn.isEmpty()) {
            throw new GameException(NOT_LOGGED_IN, "로그인이 필요합니다.");
        }
        return loggedIn;
    }

    /**
     * 연결이 끝났을 때 부른다 (BYE, 상대가 닫음, 15초 무응답, 서버 종료 모두). 여러 번 불려도 정리는 한 번만 한다.
     * 방 API를 만들면 여기서 방 나가기·방장 넘기기·게임 중 탈락도 한다.
     */
    public void close(Session session) {
        List<Player> released;
        synchronized (session) {
            // 두 번째부터는 빈 목록이 돌아온다
            released = session.close();
            players.deleteAll(released);
        }
        if (!released.isEmpty()) {
            log.info("#{} 로그아웃: {}", session.connectionId(), released);
        }
    }

    /** 접속해 있는 모든 플레이어 */
    public List<Player> onlinePlayers() {
        return players.findAll();
    }
}

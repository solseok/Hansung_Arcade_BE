package hansung.arcade.domain.game.business;

import hansung.arcade.domain.game.business.GameService.Participant;
import hansung.arcade.domain.game.model.Match;
import hansung.arcade.domain.game.model.MatchEvent;
import hansung.arcade.domain.game.model.MatchMessages;
import hansung.arcade.domain.game.model.Team;
import hansung.arcade.global.exception.GameException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static hansung.arcade.global.exception.CommonErrorCode.WRONG_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServiceTest {

    private static final String MAP = "village/village10";

    /** 보낸 푸시를 모아 두고, 끊긴 것으로 칠 연결을 정할 수 있는 가짜 송신기 */
    private static final class RecordingSender implements EventSender {
        final List<MatchEvent> sent = new ArrayList<>();
        final Set<Long> disconnected = new HashSet<>();

        @Override
        public void send(MatchEvent event) {
            sent.add(event);
        }

        @Override
        public boolean isConnected(long connectionId) {
            return !disconnected.contains(connectionId);
        }

        List<String> types() {
            return sent.stream().map(MatchEvent::type).toList();
        }
    }

    private final RecordingSender sender = new RecordingSender();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final GameService service = new GameService(sender, clock::get, new Random(1));
    private final List<Match> finished = new ArrayList<>();

    {
        service.onFinished(finished::add);
    }

    private static Participant player(long playerId, long connectionId, Team team) {
        return new Participant(playerId, connectionId, "플레이어" + playerId, "kkukku", team);
    }

    @Test
    void 한_팀뿐이면_경기를_열지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> service.start(MAP,
                List.of(player(1, 1, Team.RED), player(2, 2, Team.RED))));
        assertEquals(0, service.activeMatches());
    }

    @Test
    void 맵_정원을_넘으면_경기를_열지_않는다() {
        List<Participant> seven = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            seven.add(player(i, i, i % 2 == 0 ? Team.RED : Team.BLUE));
        }
        assertThrows(IllegalArgumentException.class, () -> service.start(MAP, seven));
    }

    @Test
    void 이미_경기_중인_연결로_다시_열면_거절하고_먼저_연_경기는_그대로다() {
        Match first = service.start(MAP, List.of(player(1, 1, Team.RED), player(2, 2, Team.BLUE)));

        assertThrows(IllegalArgumentException.class, () -> service.start(MAP,
                List.of(player(3, 3, Team.RED), player(4, 1, Team.BLUE))));

        assertSame(first, service.matchOf(1));
        assertNull(service.matchOf(3));
        assertEquals(1, service.activeMatches());
    }

    @Test
    void 열_때_이미_끊긴_연결은_바로_빼고_한_팀만_남으면_끝낸다() {
        sender.disconnected.add(2L);

        Match match = service.start(MAP, List.of(player(1, 1, Team.RED), player(2, 2, Team.BLUE)));

        assertTrue(match.isFinished());
        assertEquals(List.of(MatchMessages.GAME_STARTED, MatchMessages.PLAYER_LEFT, MatchMessages.GAME_OVER),
                sender.types());
        assertEquals(List.of(match), finished);
        assertNull(service.matchOf(1));
        assertEquals(0, service.activeMatches());
    }

    @Test
    void 경기가_끝나면_종료_알림은_한_번만_오고_그_뒤의_나가기와_끊김은_아무_일도_없다() {
        Match match = service.start(MAP, List.of(player(1, 1, Team.RED), player(2, 2, Team.BLUE)));

        service.exit(1);
        service.disconnect(2);
        service.tick();

        assertEquals(List.of(match), finished);
        GameException e = assertThrows(GameException.class, () -> service.exit(2));
        assertEquals(WRONG_STATE, e.code());
    }

    @Test
    void 경기_중이_아닌_연결의_LOADED와_GAME_EXIT는_WRONG_STATE이고_MOVE는_조용히_버린다() {
        assertEquals(WRONG_STATE, assertThrows(GameException.class, () -> service.loaded(9)).code());
        assertEquals(WRONG_STATE, assertThrows(GameException.class, () -> service.exit(9)).code());

        service.placeBalloon(9, 9, 0, 0);
        service.disconnect(9);

        assertEquals(List.of(), sender.sent);
    }

    @Test
    void 한_경기의_송신이_실패해도_다른_경기는_그대로_진행된다() {
        GameService failing = new GameService(event -> {
            if (event.recipients().contains(1L)) {
                throw new IllegalStateException("보내기 실패");
            }
            sender.send(event);
        }, clock::get, new Random(1));
        failing.start(MAP, List.of(player(1, 1, Team.RED), player(2, 2, Team.BLUE)));
        failing.start(MAP, List.of(player(3, 3, Team.RED), player(4, 4, Team.BLUE)));
        failing.loaded(3);
        failing.loaded(4);

        assertEquals(List.of(MatchMessages.GAME_STARTED, MatchMessages.ROUND_BEGIN), sender.types());
        assertEquals(Match.Phase.COUNTDOWN, failing.matchOf(3).phase());
        assertEquals(2, failing.activeMatches());
    }

    @Test
    void 캠프_08에서_두_팀이_붙으면_팀마다_한_진영에서_시작한다() {
        // 판마다 연결 번호가 달라 경기를 열어 둔 채 다음 판을 연다
        for (int round = 0; round < 20; round++) {
            List<Participant> eight = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                long id = 100L * round + i + 1;
                eight.add(player(id, id, i % 2 == 0 ? Team.RED : Team.BLUE));
            }
            Match match = service.start("camp/camp08", eight);

            Set<Integer> red = new HashSet<>();
            for (Participant p : eight) {
                if (p.team() == Team.RED) {
                    red.add(match.player(p.playerId()).spawn());
                }
            }
            assertTrue(red.equals(Set.of(1, 2, 3, 4)) || red.equals(Set.of(5, 6, 7, 8)), "round " + round + ": " + red);
        }
    }

}

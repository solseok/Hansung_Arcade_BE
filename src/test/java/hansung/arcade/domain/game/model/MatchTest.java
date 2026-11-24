package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BalloonPlaced;
import hansung.arcade.domain.game.model.MatchMessages.ChangedTile;
import hansung.arcade.domain.game.model.MatchMessages.ExplodedBalloon;
import hansung.arcade.domain.game.model.MatchMessages.GameOver;
import hansung.arcade.domain.game.model.MatchMessages.GameStarted;
import hansung.arcade.domain.game.model.MatchMessages.PlayerLeft;
import hansung.arcade.domain.game.model.MatchMessages.PlayerMoved;
import hansung.arcade.domain.game.model.MatchMessages.PlayerPopped;
import hansung.arcade.domain.game.model.MatchMessages.PlayerRescued;
import hansung.arcade.domain.game.model.MatchMessages.PlayerTrapped;
import hansung.arcade.domain.game.model.MatchMessages.Standing;
import hansung.arcade.global.exception.GameException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchTest {

    /*
     * 7×3 맵. (3,0)은 부서지는 블럭, (2,2)는 부서지지 않는 석상.
     * 1P·3P(빨강, 연결 1), 2P(파랑, 연결 2)
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".......",
            ".......",
            ".......",
            "[block]",
            "...B...",
            ".......",
            "..#....",
            "[spawn]",
            "1.....2",
            ".......",
            "3.....4");

    private static final long RED_1 = 1;
    private static final long BLUE = 2;
    private static final long RED_3 = 3;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;

    private final GameMap map = MapLoader.parse("test/map", MAP);

    private Match match(Mascot redMascot, boolean withSecondRed) {
        List<MatchPlayer> players = new java.util.ArrayList<>();
        players.add(new MatchPlayer(RED_1, CONN_RED, "빨강하나", redMascot, Team.RED, 1, map.spawns().get(0)));
        players.add(new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)));
        if (withSecondRed) {
            players.add(new MatchPlayer(RED_3, CONN_RED, "빨강셋", Mascot.of("kkukku"), Team.RED, 3, map.spawns().get(2)));
        }
        return new Match(1, map, players);
    }

    /** 경기를 열고 모두 로딩을 마치고 카운트다운까지 끝내 PLAYING으로 만든다. 지금 시각은 1000 */
    private Match playing(Mascot redMascot, boolean withSecondRed) {
        Match match = match(redMascot, withSecondRed);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.tick(Match.COUNTDOWN_MS);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    private static final long PLAY = Match.COUNTDOWN_MS;

    private static double cx(int col) {
        return GameMap.centerX(col);
    }

    private static double cy(int row) {
        return GameMap.centerY(row);
    }

    private static List<String> types(List<MatchEvent> events) {
        return events.stream().map(MatchEvent::type).toList();
    }

    private static <T> T body(List<MatchEvent> events, String type, Class<T> bodyType) {
        return events.stream().filter(event -> event.type().equals(type)).findFirst()
                .map(event -> bodyType.cast(event.body()))
                .orElseThrow(() -> new AssertionError(type + "가 없습니다: " + types(events)));
    }

    private static Map<Long, String> outcomes(GameOver over) {
        return over.standings().stream().collect(Collectors.toMap(Standing::playerId, Standing::outcome));
    }

    @Test
    void 경기를_열면_맵과_플레이어를_방_전체에_알린다() {
        List<MatchEvent> events = match(Mascot.of("kkukku"), true).start(0);

        GameStarted started = body(events, MatchMessages.GAME_STARTED, GameStarted.class);
        assertEquals("test/map", started.mapId());
        assertEquals(3, started.players().size());
        assertEquals("RED", started.players().get(0).team());
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
    }

    @Test
    void 모든_연결이_LOADED를_보내면_ROUND_BEGIN을_보낸다() {
        Match match = match(Mascot.of("kkukku"), true);
        match.start(0);

        assertEquals(List.of(), match.loaded(CONN_RED, 10));
        List<MatchEvent> events = match.loaded(CONN_BLUE, 20);

        assertEquals(List.of(MatchMessages.ROUND_BEGIN), types(events));
        assertEquals(Match.Phase.COUNTDOWN, match.phase());
    }

    @Test
    void 같은_연결이_LOADED를_두_번_보내면_WRONG_STATE다() {
        Match match = match(Mascot.of("kkukku"), false);
        match.start(0);
        match.loaded(CONN_RED, 0);

        GameException e = assertThrows(GameException.class, () -> match.loaded(CONN_RED, 0));
        assertEquals("WRONG_STATE", e.code());
    }

    @Test
    void 카운트다운이_끝나기_전의_MOVE와_물풍선은_버린다() {
        Match match = match(Mascot.of("kkukku"), false);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);

        assertEquals(List.of(), match.move(CONN_RED, RED_1, cx(1), cy(0), Facing.RIGHT, true, 100));
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 0, 0, 100));
    }

    @Test
    void MOVE는_보낸_연결을_빼고_중계한다() {
        Match match = playing(Mascot.of("kkukku"), true);

        List<MatchEvent> events = match.move(CONN_RED, RED_1, 60.5, 20, Facing.RIGHT, true, PLAY);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(List.of(CONN_BLUE), events.get(0).recipients());
        assertEquals(new PlayerMoved(RED_1, 60.5, 20, "RIGHT", true), events.get(0).body());
    }

    @Test
    void 다른_연결의_플레이어나_맵_밖_위치로_보낸_MOVE는_버린다() {
        Match match = playing(Mascot.of("kkukku"), false);

        assertEquals(List.of(), match.move(CONN_BLUE, RED_1, cx(1), cy(0), Facing.UP, true, PLAY));
        assertEquals(List.of(), match.move(CONN_RED, RED_1, -1, cy(0), Facing.UP, true, PLAY));
        assertEquals(List.of(), match.move(CONN_RED, RED_1, Double.NaN, cy(0), Facing.UP, true, PLAY));
        assertEquals(List.of(), match.move(CONN_RED, RED_1, cx(1), cy(0), null, true, PLAY));
    }

    @Test
    void 물풍선은_능력치만큼만_놓을_수_있다() {
        Match match = playing(Mascot.of("kkukku"), false);

        List<MatchEvent> first = match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        List<MatchEvent> second = match.placeBalloon(CONN_RED, RED_1, 1, 0, PLAY);

        BalloonPlaced placed = body(first, MatchMessages.BALLOON_PLACED, BalloonPlaced.class);
        assertEquals(new BalloonPlaced(1, RED_1, 0, 0, 1, Match.FUSE_MS), placed);
        assertEquals(List.of(), second);
    }

    @Test
    void 서_있는_칸에서_두_칸_떨어졌거나_대각선이거나_막힌_칸에는_놓을_수_없다() {
        Match match = playing(Mascot.of("hansungnyangi"), false);
        match.move(CONN_RED, RED_1, cx(2), cy(1), Facing.DOWN, false, PLAY);

        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 4, 1, PLAY));
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 1, 0, PLAY));
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 2, 2, PLAY));
        assertEquals(1, match.placeBalloon(CONN_RED, RED_1, 2, 1, PLAY).size());
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 2, 1, PLAY));
    }

    @Test
    void 퓨즈가_지나면_터져서_블럭을_부수고_물줄기는_막힌_곳에서_멈추고_그_칸의_플레이어를_가둔다() {
        // sangzzi: 물줄기 2칸
        Match match = playing(Mascot.of("sangzzi"), false);
        match.move(CONN_RED, RED_1, cx(3), cy(1), Facing.DOWN, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 3, 1, PLAY);

        assertEquals(List.of(), match.tick(PLAY + Match.FUSE_MS - 1));
        List<MatchEvent> events = match.tick(PLAY + Match.FUSE_MS);

        BalloonExploded exploded = body(events, MatchMessages.BALLOON_EXPLODED, BalloonExploded.class);
        assertEquals(List.of(new ExplodedBalloon(1, 3, 1, 0, 1, 2, 2)), exploded.balloons());
        assertEquals(List.of(new ChangedTile(3, 0, ".")), exploded.tiles());
        assertEquals(Tile.FLOOR, map.tileAt(3, 0));
        assertEquals(new PlayerTrapped(RED_1, 1, Match.POP_MS),
                body(events, MatchMessages.PLAYER_TRAPPED, PlayerTrapped.class));
        // 터진 물풍선은 주인에게 돌아와 다시 놓을 수 있다
        match.tick(PLAY + Match.FUSE_MS + Match.FLAME_MS);
        assertEquals(0, match.player(RED_1).placedBalloons());
    }

    @Test
    void 물줄기가_다른_물풍선에_닿으면_함께_터져_한_메시지로_알린다() {
        Match match = playing(Mascot.of("hansungnyangi"), false);
        match.move(CONN_RED, RED_1, cx(5), cy(1), Facing.DOWN, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 5, 1, PLAY);
        match.move(CONN_RED, RED_1, cx(6), cy(1), Facing.DOWN, false, PLAY + 1000);
        match.placeBalloon(CONN_RED, RED_1, 6, 1, PLAY + 1000);

        List<MatchEvent> events = match.tick(PLAY + Match.FUSE_MS);

        BalloonExploded exploded = body(events, MatchMessages.BALLOON_EXPLODED, BalloonExploded.class);
        assertEquals(List.of(1, 2), exploded.balloons().stream().map(ExplodedBalloon::balloonId).toList());
        assertEquals(1, events.stream().filter(event -> event.type().equals(MatchMessages.BALLOON_EXPLODED)).count());
    }

    @Test
    void 같은_순간_터지는_이웃_물풍선은_놓은_순서와_상관없이_서로의_칸_앞에서_멈춘다() {
        for (boolean leftFirst : new boolean[]{true, false}) {
            // hansungnyangi: 물풍선 2개, 물줄기 1칸. (4,1)과 (5,1)에 같은 시각에 놓는다
            Match match = playing(Mascot.of("hansungnyangi"), false);
            int[] order = leftFirst ? new int[]{4, 5} : new int[]{5, 4};
            for (int col : order) {
                match.move(CONN_RED, RED_1, cx(col), cy(1), Facing.DOWN, false, PLAY);
                match.placeBalloon(CONN_RED, RED_1, col, 1, PLAY);
            }

            BalloonExploded exploded = body(match.tick(PLAY + Match.FUSE_MS), MatchMessages.BALLOON_EXPLODED,
                    BalloonExploded.class);

            Map<Integer, ExplodedBalloon> byCol = exploded.balloons().stream()
                    .collect(Collectors.toMap(ExplodedBalloon::col, balloon -> balloon));
            ExplodedBalloon left = byCol.get(4);
            ExplodedBalloon right = byCol.get(5);
            assertEquals(List.of(1, 1, 1, 0), List.of(left.up(), left.down(), left.left(), left.right()), "leftFirst=" + leftFirst);
            assertEquals(List.of(1, 1, 0, 1), List.of(right.up(), right.down(), right.left(), right.right()), "leftFirst=" + leftFirst);
        }
    }

    @Test
    void 물줄기가_남은_칸에_놓은_물풍선은_바로_터진다() {
        Match match = playing(Mascot.of("hansungnyangi"), false);
        match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        match.move(CONN_RED, RED_1, cx(2), cy(0), Facing.LEFT, false, PLAY);
        match.tick(PLAY + Match.FUSE_MS);
        // (0,0)이 터져 (1,0)까지 물줄기가 남아 있다

        List<MatchEvent> events = match.placeBalloon(CONN_RED, RED_1, 1, 0, PLAY + Match.FUSE_MS + 100);

        assertEquals(List.of(MatchMessages.BALLOON_PLACED, MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED),
                types(events));
        assertEquals(0, body(events, MatchMessages.BALLOON_PLACED, BalloonPlaced.class).fuseMs());
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());
    }

    @Test
    void 남아_있는_물줄기에_걸어_들어가도_갇힌다() {
        Match match = playing(Mascot.of("sangzzi"), false);
        match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        match.move(CONN_RED, RED_1, cx(0), cy(2), Facing.DOWN, false, PLAY);
        // (0,0)에서 아래로 2칸 → (0,2)까지 덮는다. 1P는 (0,2)에 있으므로 터질 때 갇힌다. 2P가 대신 걸어 들어가 본다
        match.move(CONN_RED, RED_1, cx(1), cy(2), Facing.DOWN, false, PLAY);
        match.tick(PLAY + Match.FUSE_MS);

        List<MatchEvent> events = match.move(CONN_BLUE, BLUE, cx(2), cy(0), Facing.LEFT, true, PLAY + Match.FUSE_MS + 100);

        assertEquals(new PlayerTrapped(BLUE, 1, Match.POP_MS),
                body(events, MatchMessages.PLAYER_TRAPPED, PlayerTrapped.class));
    }

    @Test
    void 같은_팀이_갇힌_사람에게_닿으면_구해_주고_save가_오른다() {
        Match match = playing(Mascot.of("kkukku"), true);
        match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        match.tick(PLAY + Match.FUSE_MS);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());

        long later = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        match.tick(later);
        List<MatchEvent> events = match.move(CONN_RED, RED_3, cx(0) + 5, cy(0) + 5, Facing.UP, true, later);

        assertEquals(new PlayerRescued(RED_1, RED_3), body(events, MatchMessages.PLAYER_RESCUED, PlayerRescued.class));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED_1).state());
        assertEquals(1, match.player(RED_3).saves());
    }

    @Test
    void 다른_팀이_갇힌_사람에게_닿으면_터지고_남은_팀이_하나면_경기가_끝난다() {
        Match match = playing(Mascot.of("kkukku"), false);
        match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        match.tick(PLAY + Match.FUSE_MS);

        long later = PLAY + Match.FUSE_MS + Match.FLAME_MS;
        match.tick(later);
        List<MatchEvent> events = match.move(CONN_BLUE, BLUE, cx(0) + 10, cy(0), Facing.LEFT, true, later);

        assertEquals(new PlayerPopped(RED_1, BLUE, "TOUCH"), body(events, MatchMessages.PLAYER_POPPED, PlayerPopped.class));
        GameOver over = body(events, MatchMessages.GAME_OVER, GameOver.class);
        assertEquals("LAST_TEAM", over.reason());
        assertEquals(Map.of(RED_1, "LOSE", BLUE, "WIN"), outcomes(over));
        assertEquals(1, over.standings().stream().filter(s -> s.playerId() == BLUE).findFirst().orElseThrow().kills());
        assertTrue(match.isFinished());
    }

    @Test
    void 갇힐_때_닿아_있던_같은_팀은_움직이지_않아도_다음_tick에_구해_준다() {
        Match match = playing(Mascot.of("kkukku"), true);
        // 1P는 (1,1) 오른쪽 끝, 3P는 바로 옆 (2,1) 왼쪽 끝. (0,1) 물풍선의 물줄기는 (1,1)까지만 닿는다
        match.move(CONN_RED, RED_3, cx(2) - 18, cy(1), Facing.LEFT, false, PLAY);
        match.move(CONN_RED, RED_1, cx(1) + 18, cy(1), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 0, 1, PLAY);

        List<MatchEvent> trapped = match.tick(PLAY + Match.FUSE_MS);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());
        assertTrue(!types(trapped).contains(MatchMessages.PLAYER_RESCUED), "갇힌 모습이 한 번은 보여야 한다");

        List<MatchEvent> events = match.tick(PLAY + Match.FUSE_MS + 20);

        assertEquals(new PlayerRescued(RED_1, RED_3), body(events, MatchMessages.PLAYER_RESCUED, PlayerRescued.class));
    }

    @Test
    void 막_갇힌_사람은_곧바로_들어온_MOVE로는_풀리지_않는다() {
        Match match = playing(Mascot.of("kkukku"), true);
        match.move(CONN_RED, RED_3, cx(2) - 18, cy(1), Facing.LEFT, false, PLAY);
        match.move(CONN_RED, RED_1, cx(1) + 18, cy(1), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 0, 1, PLAY);
        match.tick(PLAY + Match.FUSE_MS);

        List<MatchEvent> events = match.move(CONN_RED, RED_3, cx(2) - 18, cy(1), Facing.LEFT, false,
                PLAY + Match.FUSE_MS + 5);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(MatchPlayer.State.TRAPPED, match.player(RED_1).state());
    }

    @Test
    void 구해진_뒤_빠져나오는_동안은_남은_물줄기에_다시_갇히지_않는다() {
        Match match = playing(Mascot.of("kkukku"), true);
        match.move(CONN_RED, RED_3, cx(2) - 18, cy(1), Facing.LEFT, false, PLAY);
        match.move(CONN_RED, RED_1, cx(1) + 18, cy(1), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 0, 1, PLAY);
        long trappedAt = PLAY + Match.FUSE_MS;
        match.tick(trappedAt);
        match.tick(trappedAt + 20);
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED_1).state());

        // 물줄기(FLAME_MS)는 아직 (1,1)에 남아 있다
        List<MatchEvent> events = match.move(CONN_RED, RED_1, cx(1) + 18, cy(1), Facing.LEFT, false, trappedAt + 50);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(MatchPlayer.State.ALIVE, match.player(RED_1).state());
        // 빠져나오는 동안은 물풍선도 놓지 못한다
        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 1, 1, trappedAt + 60));
        assertEquals(1, match.placeBalloon(CONN_RED, RED_1, 1, 1, trappedAt + 20 + Match.ESCAPE_MS).size());
    }

    @Test
    void 같은_팀과_상대가_함께_닿으면_상대가_터뜨린다() {
        Match match = playing(Mascot.of("kkukku"), true);
        match.move(CONN_RED, RED_3, cx(2) - 18, cy(1), Facing.LEFT, false, PLAY);
        match.move(CONN_BLUE, BLUE, cx(2) - 16, cy(1) + 2, Facing.LEFT, false, PLAY);
        match.move(CONN_RED, RED_1, cx(1) + 18, cy(1), Facing.LEFT, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 0, 1, PLAY);
        match.tick(PLAY + Match.FUSE_MS);

        List<MatchEvent> events = match.tick(PLAY + Match.FUSE_MS + 20);

        assertEquals(new PlayerPopped(RED_1, BLUE, "TOUCH"), body(events, MatchMessages.PLAYER_POPPED, PlayerPopped.class));
        assertTrue(!types(events).contains(MatchMessages.PLAYER_RESCUED));
    }

    @Test
    void 카운트다운이_끝난_직후의_입력은_tick을_기다리지_않고_받는다() {
        Match match = match(Mascot.of("kkukku"), false);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);

        List<MatchEvent> events = match.move(CONN_RED, RED_1, cx(1), cy(0), Facing.RIGHT, true, Match.COUNTDOWN_MS);

        assertEquals(List.of(MatchMessages.PLAYER_MOVED), types(events));
        assertEquals(Match.Phase.PLAYING, match.phase());
    }

    @Test
    void 갇힌_뒤_popMs가_지나면_TIMEOUT으로_터진다() {
        Match match = playing(Mascot.of("kkukku"), true);
        match.placeBalloon(CONN_RED, RED_1, 0, 0, PLAY);
        long trappedAt = PLAY + Match.FUSE_MS;
        match.tick(trappedAt);

        assertEquals(List.of(), match.tick(trappedAt + Match.POP_MS - 1));
        List<MatchEvent> events = match.tick(trappedAt + Match.POP_MS);

        PlayerPopped popped = body(events, MatchMessages.PLAYER_POPPED, PlayerPopped.class);
        assertEquals(new PlayerPopped(RED_1, null, "TIMEOUT"), popped);
        // 3P가 남아 있으므로 빨강 팀은 아직 살아 있다
        assertEquals(Match.Phase.PLAYING, match.phase());
    }

    @Test
    void 마지막_사람들이_한꺼번에_터지면_ALL_DOWN으로_모두_비긴다() {
        Match match = playing(Mascot.of("sangzzi"), false);
        // 1P는 (1,1)에 물풍선을 놓고 그 자리에 선다. 물줄기 2칸이 오른쪽 (3,1)까지 덮는다
        match.move(CONN_RED, RED_1, cx(1), cy(1), Facing.DOWN, false, PLAY);
        match.move(CONN_BLUE, BLUE, cx(4), cy(1), Facing.DOWN, false, PLAY);
        match.placeBalloon(CONN_RED, RED_1, 1, 1, PLAY);
        long trappedAt = PLAY + Match.FUSE_MS;
        List<MatchEvent> explosion = match.tick(trappedAt);
        assertEquals(1, explosion.stream().filter(e -> e.type().equals(MatchMessages.PLAYER_TRAPPED)).count());
        // 2P는 (4,1)이라 안 맞았다. 남은 물줄기 칸 (2,1)로 걸어 들어가 갇힌다 (서로 닿지 않는 거리)
        match.move(CONN_BLUE, BLUE, cx(2) + 15, cy(1), Facing.LEFT, true, trappedAt);
        assertEquals(MatchPlayer.State.TRAPPED, match.player(BLUE).state());

        List<MatchEvent> events = match.tick(trappedAt + Match.POP_MS);

        GameOver over = body(events, MatchMessages.GAME_OVER, GameOver.class);
        assertEquals("ALL_DOWN", over.reason());
        assertEquals(Map.of(RED_1, "DRAW", BLUE, "DRAW"), outcomes(over));
    }

    @Test
    void 나가면_남은_사람에게_PLAYER_LEFT를_보내고_한_팀만_남으면_경기가_끝난다() {
        Match match = playing(Mascot.of("kkukku"), true);

        List<MatchEvent> events = match.leave(CONN_RED, "EXIT", false, PLAY);

        MatchEvent left = events.get(0);
        assertEquals(MatchMessages.PLAYER_LEFT, left.type());
        assertEquals(new PlayerLeft(List.of(RED_1, RED_3), "EXIT"), left.body());
        assertEquals(List.of(CONN_BLUE), left.recipients());
        GameOver over = body(events, MatchMessages.GAME_OVER, GameOver.class);
        assertEquals("LAST_TEAM", over.reason());
        assertEquals(Map.of(RED_1, "LOSE", RED_3, "LOSE", BLUE, "WIN"), outcomes(over));
        assertEquals(List.of(CONN_BLUE), match.connectionIds());
    }

    @Test
    void 로딩_시간이_지나면_LOADED를_안_보낸_연결을_빼고_시작한다() {
        Match match = match(Mascot.of("kkukku"), false);
        match.start(0);
        match.loaded(CONN_RED, 0);

        assertEquals(List.of(), match.tick(Match.LOAD_TIMEOUT_MS - 1));
        List<MatchEvent> events = match.tick(Match.LOAD_TIMEOUT_MS);

        // 파랑이 빠져 빨강만 남았으므로 ROUND_BEGIN 없이 바로 끝난다. 빠진 연결도 PLAYER_LEFT를 받는다
        assertEquals(List.of(MatchMessages.PLAYER_LEFT, MatchMessages.GAME_OVER), types(events));
        assertEquals(List.of(CONN_RED, CONN_BLUE), events.get(0).recipients());
        assertEquals("LAST_TEAM", body(events, MatchMessages.GAME_OVER, GameOver.class).reason());
    }

    @Test
    void 로딩_시간이_지났는데_아무도_로딩하지_않았으면_이긴_사람_없이_끝난다() {
        Match match = match(Mascot.of("kkukku"), false);
        match.start(0);

        List<MatchEvent> events = match.tick(Match.LOAD_TIMEOUT_MS);

        // 먼저 뺀 쪽 때문에 나중 쪽이 이기면 안 된다
        assertEquals(List.of(MatchMessages.PLAYER_LEFT, MatchMessages.PLAYER_LEFT), types(events));
        assertTrue(match.isFinished());
    }

    @Test
    void 로딩_시간이_지나도_여러_팀이_남아_있으면_남은_사람끼리_시작한다() {
        // 빨강·파랑·초록 세 연결 가운데 파랑만 로딩을 못 마쳤다
        Match other = new Match(2, map, List.of(
                new MatchPlayer(RED_1, CONN_RED, "빨강", Mascot.of("kkukku"), Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", Mascot.of("kkukku"), Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED_3, 103, "초록", Mascot.of("kkukku"), Team.GREEN, 3, map.spawns().get(2))));
        other.start(0);
        other.loaded(CONN_RED, 0);
        other.loaded(103, 0);

        List<MatchEvent> events = other.tick(Match.LOAD_TIMEOUT_MS);

        assertEquals(List.of(MatchMessages.PLAYER_LEFT, MatchMessages.ROUND_BEGIN), types(events));
        assertEquals(List.of(CONN_RED, 103L), other.connectionIds());
    }

    @Test
    void 제한_시간이_끝나면_TIME_UP이고_여러_팀이_살아_있으면_비긴다() {
        Match match = playing(Mascot.of("kkukku"), false);

        assertEquals(List.of(), match.tick(PLAY + Match.TIME_LIMIT_MS - 1));
        GameOver over = body(match.tick(PLAY + Match.TIME_LIMIT_MS), MatchMessages.GAME_OVER, GameOver.class);

        assertEquals("TIME_UP", over.reason());
        assertEquals(Map.of(RED_1, "DRAW", BLUE, "DRAW"), outcomes(over));
    }

    @Test
void 끝난_경기에는_어떤_연결도_남아_있지_않은_것으로_본다() {
        Match match = playing(Mascot.of("kkukku"), false);
        assertTrue(match.includes(CONN_RED));

        match.leave(CONN_BLUE, "EXIT", false, PLAY);

        assertTrue(match.isFinished());
        assertTrue(!match.includes(CONN_RED));
        assertTrue(!match.includes(CONN_BLUE));
    }

    @Test
        void 경기가_끝난_뒤의_나가기는_아무_일도_없다() {
        Match match = playing(Mascot.of("kkukku"), false);
        match.leave(CONN_RED, "DISCONNECT", false, PLAY);

        assertTrue(match.isFinished());
        assertEquals(List.of(), match.leave(CONN_BLUE, "DISCONNECT", false, PLAY));
        assertEquals(List.of(), match.tick(PLAY + Match.TIME_LIMIT_MS));
    }
}

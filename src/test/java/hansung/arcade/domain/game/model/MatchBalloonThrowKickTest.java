package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BalloonKicked;
import hansung.arcade.domain.game.model.MatchMessages.BalloonStopped;
import hansung.arcade.domain.game.model.MatchMessages.BalloonThrown;
import hansung.arcade.domain.game.model.MatchMessages.BoxMoved;
import hansung.arcade.domain.game.model.MatchMessages.ChangedTile;
import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ExplodedBalloon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BALLOON_THROW(장갑으로 던지기)·BALLOON_KICK(신발·빨간 악마로 차기)과 나는·미끄러지는 물풍선 판정 */
class MatchBalloonThrowKickTest {

    /*
     * 13×12 맵. 줄마다 한 가지를 시험한다.
     *   0줄: 아무것도 없다
     *   2줄: (7,2) 부서지지 않는 석상
     *   4줄: (4,4) 덤불, (10,4) 석상
     *   6줄: (8,6) 왼쪽 면에 가시가 난 블럭 (오른쪽으로 미끄러져 온 물풍선을 터뜨린다). 고정 드롭 물풍선
     *   8줄: (6,8) 바닥 가시
     *   9줄: (9,9) 상자. 고정 드롭 물풍선. 아래로 밀면 10줄을 막는다
     * 1P(0,11)은 빨강(연결 1), 2P(12,11)은 파랑(연결 2). 둘 다 꾸꾸(물줄기 1칸·물풍선 1개)
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            "......!......",
            ".............",
            ".............",
            ".............",
            "[block]",
            ".............",
            ".............",
            ".......#.....",
            ".............",
            "....T.....#..",
            ".............",
            "........<....",
            ".............",
            ".............",
            ".........P...",
            ".............",
            ".............",
            "[spawn]",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            "1...........2",
            "[item]",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            ".............",
            "........b....",
            ".............",
            ".............",
            ".........b...",
            ".............",
            ".............");

    private static final long RED = 1;
    private static final long BLUE = 2;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final List<Long> EVERYONE = List.of(CONN_RED, CONN_BLUE);
    private static final long PLAY = Match.COUNTDOWN_MS;
    /** 이 맵에서 부서지는 블럭은 모두 고정 드롭이라 난수를 쓰면 안 된다 */
    private static final RandomGenerator NO_RANDOM = () -> {
        throw new AssertionError("고정 드롭만 나와야 합니다.");
    };

    private final GameMap map = MapLoader.parse("test/throw-kick", MAP);

    /** 두 명으로 경기를 열고 PLAYING까지 진행한다. 지금 시각은 {@link #PLAY} */
    private Match playing() {
        Mascot kkukku = Mascot.of("kkukku");
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED, CONN_RED, "빨강", kkukku, Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE, CONN_BLUE, "파랑", kkukku, Team.BLUE, 2, map.spawns().get(1))),
                NO_RANDOM);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    private static void give(Match match, long playerId, ItemType... items) {
        for (ItemType item : items) {
            match.player(playerId).takeItem(item);
        }
    }

    private static long connectionOf(long playerId) {
        return playerId == RED ? CONN_RED : CONN_BLUE;
    }

    /** playerId가 (col, row) 칸 가운데에서 facing 쪽을 보고 멈춰 섰다는 MOVE */
    private static void stand(Match match, long playerId, int col, int row, Facing facing, long now) {
        match.move(connectionOf(playerId), playerId, GameMap.centerX(col), GameMap.centerY(row), facing, false, now);
    }

    /** playerId가 (col, row)에 서서 발밑에 물풍선을 놓는다 */
    private static void placeAt(Match match, long playerId, int col, int row, long now) {
        stand(match, playerId, col, row, Facing.DOWN, now);
        assertEquals(List.of(MatchMessages.BALLOON_PLACED),
                types(match.placeBalloon(connectionOf(playerId), playerId, col, row, now)));
    }

    private static List<MatchEvent> throwBalloon(Match match, long playerId, int col, int row, Facing direction,
                                                 long now) {
        return match.throwBalloon(connectionOf(playerId), playerId, col, row, direction, now);
    }

    private static List<MatchEvent> kick(Match match, long playerId, int col, int row, Facing direction, long now) {
        return match.kickBalloon(connectionOf(playerId), playerId, col, row, direction, now);
    }

    private static List<String> types(List<MatchEvent> events) {
        return events.stream().map(MatchEvent::type).toList();
    }

    private static List<MatchEvent> thrown(int balloonId, Long playerId, int col, int row, int toCol, int toRow,
                                           Facing direction, long flightMs) {
        return List.of(new MatchEvent(EVERYONE, MatchMessages.BALLOON_THROWN,
                new BalloonThrown(balloonId, playerId, col, row, toCol, toRow, direction.name(), flightMs)));
    }

    private static List<MatchEvent> kicked(int balloonId, long playerId, int col, int row, Facing direction) {
        return List.of(new MatchEvent(EVERYONE, MatchMessages.BALLOON_KICKED,
                new BalloonKicked(balloonId, playerId, col, row, direction.name())));
    }

    private static List<MatchEvent> stopped(int balloonId, int col, int row) {
        return List.of(new MatchEvent(EVERYONE, MatchMessages.BALLOON_STOPPED, new BalloonStopped(balloonId, col, row)));
    }

    private static BalloonExploded exploded(List<MatchEvent> events) {
        return events.stream().filter(event -> event.type().equals(MatchMessages.BALLOON_EXPLODED)).findFirst()
                .map(event -> (BalloonExploded) event.body())
                .orElseThrow(() -> new AssertionError("BALLOON_EXPLODED가 없습니다: " + types(events)));
    }

    // ---------------------------------------------------------------- 던지기

    @Test
    void 장갑이_있으면_선_칸의_물풍선을_바라보는_쪽_6칸째의_사람이_선_칸에도_던지고_방_전체에_BALLOON_THROWN을_보낸다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        stand(match, BLUE, 7, 0, Facing.DOWN, PLAY);
        placeAt(match, RED, 1, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);

        assertEquals(thrown(1, RED, 1, 0, 7, 0, Facing.RIGHT, 6 * Match.FLIGHT_MS_PER_TILE),
                throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY));

        assertEquals(List.of(), match.tick(PLAY + 360), "내려앉는 것은 따로 알리지 않는다");
        assertEquals(List.of(new ExplodedBalloon(1, 7, 0, 0, 1, 1, 1)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
    }

    @Test
    void 걷는_중에도_바라보는_앞_칸의_남의_물풍선을_던진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, BLUE, 1, 0, PLAY);
        match.move(CONN_RED, RED, GameMap.centerX(0), GameMap.centerY(0), Facing.RIGHT, true, PLAY);

        assertEquals(thrown(1, RED, 1, 0, 7, 0, Facing.RIGHT, 360), throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY));
    }

    @Test
    void 장갑이_없거나_방향이_다르거나_선_칸도_앞_칸도_아니거나_남의_연결이면_던지지_않는다() {
        Match match = playing();
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(List.of(), throwBalloon(match, RED, 2, 0, Facing.RIGHT, PLAY), "장갑이 없다");

        give(match, RED, ItemType.GLOVE);
        stand(match, RED, 1, 0, Facing.UP, PLAY);
        assertEquals(List.of(), throwBalloon(match, RED, 2, 0, Facing.RIGHT, PLAY), "마지막 MOVE가 위를 본다");
        stand(match, RED, 0, 0, Facing.RIGHT, PLAY);
        assertEquals(List.of(), throwBalloon(match, RED, 2, 0, Facing.RIGHT, PLAY), "두 칸 앞이다");
        assertEquals(List.of(), throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY), "앞 칸에 물풍선이 없다");
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(List.of(), match.throwBalloon(CONN_BLUE, RED, 2, 0, Facing.RIGHT, PLAY), "남의 플레이어다");
        assertEquals(List.of(), throwBalloon(match, RED, 2, 0, null, PLAY), "방향을 모른다");

        assertEquals(thrown(1, RED, 2, 0, 8, 0, Facing.RIGHT, 360), throwBalloon(match, RED, 2, 0, Facing.RIGHT, PLAY));
    }

    @Test
    void 여섯째_칸이_막혔거나_물풍선이_차지했으면_같은_쪽으로_다음_빈_칸까지_더_간다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE, ItemType.BUBBLE);
        placeAt(match, RED, 1, 2, PLAY);
        stand(match, RED, 1, 2, Facing.RIGHT, PLAY);
        assertEquals(thrown(1, RED, 1, 2, 8, 2, Facing.RIGHT, 7 * Match.FLIGHT_MS_PER_TILE),
                throwBalloon(match, RED, 1, 2, Facing.RIGHT, PLAY), "(7,2)는 석상이다");

        placeAt(match, BLUE, 7, 0, PLAY);
        placeAt(match, RED, 1, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(thrown(3, RED, 1, 0, 8, 0, Facing.RIGHT, 420), throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY));
    }

    @Test
    void 맵_끝까지_빈_칸이_없으면_맵_끝에서_거꾸로_돌아와_처음_빈_칸에_내려앉고_가까워도_0점2초는_난다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, BLUE, 12, 0, PLAY);
        placeAt(match, RED, 8, 0, PLAY);
        stand(match, RED, 8, 0, Facing.RIGHT, PLAY);

        assertEquals(thrown(2, RED, 8, 0, 11, 0, Facing.RIGHT, Match.MIN_FLIGHT_MS),
                throwBalloon(match, RED, 8, 0, Facing.RIGHT, PLAY));
    }

    @Test
    void 내려앉을_곳이_제자리뿐이면_던지지_않고_물풍선은_그대로_있다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, RED, 12, 3, PLAY);
        stand(match, RED, 12, 3, Facing.RIGHT, PLAY);

        assertEquals(List.of(), throwBalloon(match, RED, 12, 3, Facing.RIGHT, PLAY));
        stand(match, RED, 11, 1, Facing.DOWN, PLAY);
        assertEquals(List.of(new ExplodedBalloon(1, 12, 3, 1, 1, 1, 0)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
    }

    @Test
    void 나는_물풍선은_물줄기에_맞지_않고_물줄기가_남은_칸에_내려앉으면_바로_터진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, BLUE, 7, 1, PLAY);
        stand(match, BLUE, 12, 11, Facing.DOWN, PLAY);
        long throwAt = PLAY + 1700;
        placeAt(match, RED, 1, 0, throwAt);
        stand(match, RED, 1, 0, Facing.RIGHT, throwAt);
        assertEquals(thrown(2, RED, 1, 0, 7, 0, Facing.RIGHT, 360),
                throwBalloon(match, RED, 1, 0, Facing.RIGHT, throwAt));

        // 파랑 물풍선의 위쪽 물줄기는 던진 물풍선이 내려앉을 (7,0)까지 뻗고, 던진 물풍선은 함께 터지지 않는다 (아래는 석상)
        assertEquals(List.of(new ExplodedBalloon(1, 7, 1, 1, 0, 1, 1)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
        assertEquals(List.of(), match.tick(throwAt + 359));
        assertEquals(List.of(new ExplodedBalloon(2, 7, 0, 0, 1, 1, 1)), exploded(match.tick(throwAt + 360)).balloons());
    }

    @Test
    void 나는_사이_퓨즈가_다_되면_기다렸다가_내려앉자마자_그_칸에서_터진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, RED, 1, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY + 1800);
        assertEquals(1, throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY + 1800).size());

        assertEquals(List.of(), match.tick(PLAY + Match.FUSE_MS));
        assertEquals(List.of(), match.tick(PLAY + 2159));
        assertEquals(List.of(new ExplodedBalloon(1, 7, 0, 0, 1, 1, 1)), exploded(match.tick(PLAY + 2160)).balloons());
    }

    @Test
    void 바닥_가시_칸에_내려앉으면_바로_터진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, RED, 0, 8, PLAY);
        stand(match, RED, 0, 8, Facing.RIGHT, PLAY);
        assertEquals(thrown(1, RED, 0, 8, 6, 8, Facing.RIGHT, 360), throwBalloon(match, RED, 0, 8, Facing.RIGHT, PLAY));

        assertEquals(List.of(), match.tick(PLAY + 359));
        assertEquals(List.of(new ExplodedBalloon(1, 6, 8, 1, 1, 1, 1)), exploded(match.tick(PLAY + 360)).balloons());
    }

    @Test
    void 날아가는_물풍선이_내려앉을_칸에는_물풍선을_놓거나_상자를_밀거나_스프링_신발로_내려앉지_못하고_떠난_칸에는_놓을_수_있다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE, ItemType.BUBBLE, ItemType.SPRING_SHOE);
        placeAt(match, RED, 3, 10, PLAY);
        stand(match, RED, 3, 10, Facing.RIGHT, PLAY);
        assertEquals(thrown(1, RED, 3, 10, 9, 10, Facing.RIGHT, 360),
                throwBalloon(match, RED, 3, 10, Facing.RIGHT, PLAY));

        assertEquals(List.of(MatchMessages.BALLOON_PLACED),
                types(match.placeBalloon(CONN_RED, RED, 3, 10, PLAY)), "떠난 칸은 비었다");
        stand(match, BLUE, 9, 8, Facing.DOWN, PLAY);
        assertEquals(List.of(), match.pushBox(CONN_BLUE, BLUE, 9, 9, Facing.DOWN, PLAY));
        stand(match, BLUE, 8, 10, Facing.DOWN, PLAY);
        assertEquals(List.of(), match.placeBalloon(CONN_BLUE, BLUE, 9, 10, PLAY));
        stand(match, RED, 7, 10, Facing.RIGHT, PLAY);
        assertEquals(List.of(), match.useItem(CONN_RED, RED, ItemType.SPRING_SHOE, PLAY));
        assertEquals(1, match.player(RED).heldCount());

        match.tick(PLAY + 360);
        stand(match, BLUE, 9, 8, Facing.DOWN, PLAY + 360);
        assertEquals(List.of(), match.pushBox(CONN_BLUE, BLUE, 9, 9, Facing.DOWN, PLAY + 360), "내려앉은 뒤에도 막는다");
    }

    @Test
    void 내려앉는_순간_그_칸을_미끄러지는_물풍선이_지나가면_같은_쪽_다음_빈_칸으로_더_날아간다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        give(match, BLUE, ItemType.SHOE);
        placeAt(match, RED, 1, 0, PLAY);
        placeAt(match, BLUE, 9, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        stand(match, BLUE, 10, 0, Facing.LEFT, PLAY);
        assertEquals(1, throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY).size());
        // 파랑이 찬 물풍선은 (7,0)을 0.3초부터 0.5초까지 지나간다
        assertEquals(kicked(2, BLUE, 9, 0, Facing.LEFT), kick(match, BLUE, 9, 0, Facing.LEFT, PLAY));

        assertEquals(thrown(1, null, 7, 0, 8, 0, Facing.RIGHT, Match.MIN_FLIGHT_MS), match.tick(PLAY + 360));
        assertEquals(stopped(2, 0, 0), match.tick(PLAY + 1800), "떠난 (1,0)을 지나 맵 끝 앞에 멈춘다");
    }

    @Test
    void 퓨즈가_다_된_물풍선은_타이머보다_먼저_던져도_던지지_않고_그_자리에서_터진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, RED, 1, 0, PLAY);
        stand(match, RED, 0, 0, Facing.RIGHT, PLAY);

        List<MatchEvent> events = throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY + Match.FUSE_MS + 5);

        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(List.of(new ExplodedBalloon(1, 1, 0, 0, 1, 1, 1)), exploded(events).balloons());
    }

    @Test
    void 퓨즈가_다_된_물풍선은_타이머보다_먼저_차도_차지_않고_그_자리에서_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);

        List<MatchEvent> events = kick(match, RED, 2, 0, Facing.RIGHT, PLAY + Match.FUSE_MS + 5);

        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(List.of(new ExplodedBalloon(1, 2, 0, 0, 1, 1, 1)), exploded(events).balloons());
    }

    @Test
    void 내려앉을_때가_된_물풍선은_타이머보다_먼저_와도_내려앉혀_다시_던진다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE);
        placeAt(match, RED, 1, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(1, throwBalloon(match, RED, 1, 0, Facing.RIGHT, PLAY).size());
        stand(match, RED, 6, 0, Facing.RIGHT, PLAY + 365);

        assertEquals(thrown(1, RED, 7, 0, 12, 0, Facing.RIGHT, 300), throwBalloon(match, RED, 7, 0, Facing.RIGHT, PLAY + 365));
    }

    @Test
    void 가시_면으로_차서_바로_터질_때도_타이머가_아직_옮기지_않은_미끄러지는_물풍선을_지금_칸에서_맞힌다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        give(match, BLUE, ItemType.SHOE);
        placeAt(match, RED, 7, 6, PLAY);
        stand(match, RED, 6, 6, Facing.RIGHT, PLAY);
        placeAt(match, BLUE, 2, 5, PLAY);
        stand(match, BLUE, 1, 5, Facing.RIGHT, PLAY);
        // 파랑 물풍선은 0.9초에 (7,5)로 넘어간다. 그 뒤 타이머가 돌기 전에 빨강이 가시 면 앞 (7,6) 물풍선을 찬다
        assertEquals(1, kick(match, BLUE, 2, 5, Facing.RIGHT, PLAY).size());

        List<MatchEvent> events = kick(match, RED, 7, 6, Facing.RIGHT, PLAY + 910);

        assertEquals(List.of(new ExplodedBalloon(1, 7, 6, 0, 1, 1, 0), new ExplodedBalloon(2, 7, 5, 1, 0, 1, 1)),
                exploded(events).balloons());
    }

    @Test
    void 미끄러지거나_나는_물풍선은_던지거나_차지_못한다() {
        Match match = playing();
        give(match, RED, ItemType.GLOVE, ItemType.SHOE, ItemType.BUBBLE);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY).size());
        assertEquals(List.of(), throwBalloon(match, RED, 2, 0, Facing.RIGHT, PLAY));
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY));

        placeAt(match, RED, 1, 2, PLAY);
        stand(match, RED, 1, 2, Facing.RIGHT, PLAY);
        assertEquals(1, throwBalloon(match, RED, 1, 2, Facing.RIGHT, PLAY).size());
        assertEquals(List.of(), throwBalloon(match, RED, 1, 2, Facing.RIGHT, PLAY));
    }

    // ---------------------------------------------------------------- 차기

    @Test
    void 신발이_있으면_막힌_앞_칸의_물풍선을_차서_한_칸에_0점2초씩_맵_끝_앞까지_미끄러뜨리고_멈추면_BALLOON_STOPPED를_보낸다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 5, 0, PLAY);
        stand(match, RED, 4, 0, Facing.RIGHT, PLAY);

        assertEquals(kicked(1, RED, 5, 0, Facing.RIGHT), kick(match, RED, 5, 0, Facing.RIGHT, PLAY));
        assertEquals(200, Match.BALLOON_SLIDE_MS_PER_TILE);
        assertEquals(List.of(), match.tick(PLAY + 1399));
        assertEquals(stopped(1, 12, 0), match.tick(PLAY + 7 * Match.BALLOON_SLIDE_MS_PER_TILE));
        assertEquals(List.of(new ExplodedBalloon(1, 12, 0, 0, 1, 1, 0)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
    }

    @Test
    void 블럭_바로_앞_칸에_멈추고_사람과_덤불은_지나간다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 4, PLAY);
        stand(match, RED, 1, 4, Facing.RIGHT, PLAY);
        stand(match, BLUE, 6, 4, Facing.DOWN, PLAY);

        assertEquals(1, kick(match, RED, 2, 4, Facing.RIGHT, PLAY).size());
        assertEquals(List.of(), match.tick(PLAY + 1399));
        assertEquals(stopped(1, 9, 4), match.tick(PLAY + 1400), "(10,4)는 석상이다");
    }

    @Test
    void 다른_물풍선_위는_지나가고_멈출_칸에_물풍선이_있으면_그_앞_빈_칸에_멈춘다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        give(match, BLUE, ItemType.BUBBLE);
        placeAt(match, BLUE, 6, 0, PLAY);
        placeAt(match, BLUE, 12, 0, PLAY);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        stand(match, BLUE, 12, 5, Facing.DOWN, PLAY);

        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY).size());
        assertEquals(List.of(), match.tick(PLAY + 800), "(6,0) 물풍선 위를 지나는 중");
        assertEquals(stopped(3, 11, 0), match.tick(PLAY + 1800));
    }

    @Test
    void 빨간_악마로도_차고_신발도_빨간_악마도_없으면_차지_못한다() {
        Match match = playing();
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY));

        give(match, RED, ItemType.RED_DEVIL);
        assertEquals(kicked(1, RED, 2, 0, Facing.RIGHT), kick(match, RED, 2, 0, Facing.RIGHT, PLAY));
    }

    @Test
    void 걷는_중이거나_다른_쪽을_보거나_바로_앞_칸이_아니거나_몸이_물풍선_칸에_걸쳐_있으면_차지_않는다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 0, PLAY);

        match.move(CONN_RED, RED, GameMap.centerX(1), GameMap.centerY(0), Facing.RIGHT, true, PLAY);
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY), "걷는 중");
        stand(match, RED, 1, 0, Facing.UP, PLAY);
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY), "위를 본다");
        stand(match, RED, 0, 0, Facing.RIGHT, PLAY);
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY), "두 칸 앞");
        assertEquals(List.of(), kick(match, RED, 1, 0, Facing.RIGHT, PLAY), "앞 칸에 물풍선이 없다");
        // 몸 가운데는 (1,0)이지만 충돌 박스 오른쪽이 (2,0)에 걸쳤다. 아직 그 물풍선을 지나갈 수 있어 막히지 않는다
        match.move(CONN_RED, RED, GameMap.centerX(1) + 5, GameMap.centerY(0), Facing.RIGHT, false, PLAY);
        assertEquals(List.of(), kick(match, RED, 2, 0, Facing.RIGHT, PLAY), "몸이 물풍선 칸에 걸쳤다");

        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY).size());
    }

    @Test
    void 앞에_미끄러질_빈_칸이_없으면_차지_않는다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, BLUE, 12, 0, PLAY);
        placeAt(match, RED, 11, 0, PLAY);
        stand(match, RED, 10, 0, Facing.RIGHT, PLAY);

        assertEquals(List.of(), kick(match, RED, 11, 0, Facing.RIGHT, PLAY));
        stand(match, RED, 11, 1, Facing.UP, PLAY);
        assertEquals(List.of(), kick(match, RED, 11, 0, Facing.UP, PLAY), "맵 끝 바로 앞");
    }

    @Test
    void 가시_난_면_앞에_멈추면_멈추지_않고_그_자리에서_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 6, PLAY);
        stand(match, RED, 1, 6, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 6, Facing.RIGHT, PLAY).size());

        assertEquals(List.of(), match.tick(PLAY + 999));
        List<MatchEvent> events = match.tick(PLAY + 1000);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(events));
        BalloonExploded exploded = exploded(events);
        assertEquals(List.of(new ExplodedBalloon(1, 7, 6, 1, 1, 1, 0)), exploded.balloons());
        assertEquals(List.of(new ChangedTile(8, 6, ".")), exploded.tiles());
        assertEquals(List.of(new DroppedItem(1, 8, 6, "bubble")), exploded.items());
    }

    @Test
    void 가시_난_면_바로_앞의_물풍선을_차면_그_자리에서_바로_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 7, 6, PLAY);
        stand(match, RED, 6, 6, Facing.RIGHT, PLAY);

        List<MatchEvent> events = kick(match, RED, 7, 6, Facing.RIGHT, PLAY);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED), types(events));
        assertEquals(List.of(new ExplodedBalloon(1, 7, 6, 1, 1, 1, 0)), exploded(events).balloons());
    }

    @Test
    void 가시가_없는_블럭_바로_앞의_물풍선은_차도_그대로_있다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 6, 2, PLAY);
        stand(match, RED, 5, 2, Facing.RIGHT, PLAY);

        assertEquals(List.of(), kick(match, RED, 6, 2, Facing.RIGHT, PLAY));
        assertEquals(List.of(), match.tick(PLAY + 100));
    }

    @Test
    void 미끄러지다_퓨즈가_다_되면_그_순간_있던_칸에서_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        // 1.51초에 차면 (3,0)을 지나 1.81초에 (4,0)에, 2.01초에 (5,0)으로 넘어간다. 퓨즈는 2초에 다 된다
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY + 1510).size());

        List<MatchEvent> events = match.tick(PLAY + 2030);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(events), "멈추지 않았으므로 BALLOON_STOPPED가 없다");
        assertEquals(List.of(new ExplodedBalloon(1, 4, 0, 0, 1, 1, 1)), exploded(events).balloons());
    }

    @Test
    void 미끄러져_들어간_칸에_물줄기가_남았으면_그_칸에서_바로_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, BLUE, 6, 1, PLAY);
        stand(match, BLUE, 12, 11, Facing.DOWN, PLAY);
        placeAt(match, RED, 2, 0, PLAY + 1000);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY + 1000);
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY + 1400).size());

        // 2초에 파랑 물풍선이 터질 때 찬 물풍선은 (5,0) 가운데라 물줄기에 닿지 않는다. 2.1초에 (6,0)으로 넘어간다
        assertEquals(List.of(new ExplodedBalloon(1, 6, 1, 1, 1, 1, 1)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
        assertEquals(List.of(), match.tick(PLAY + 2099));
        assertEquals(List.of(new ExplodedBalloon(2, 6, 0, 0, 1, 1, 1)), exploded(match.tick(PLAY + 2100)).balloons());
    }

    @Test
    void 미끄러져_들어간_칸이_바닥_가시면_그_칸에서_바로_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 8, PLAY);
        stand(match, RED, 1, 8, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 8, Facing.RIGHT, PLAY).size());

        assertEquals(List.of(), match.tick(PLAY + 699));
        assertEquals(List.of(new ExplodedBalloon(1, 6, 8, 1, 1, 1, 1)), exploded(match.tick(PLAY + 700)).balloons());
    }

    @Test
    void 미끄러지는_물풍선이_다른_물줄기에_닿으면_가운데가_가장_가까운_칸에서_함께_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, BLUE, 6, 1, PLAY);
        stand(match, BLUE, 12, 11, Facing.DOWN, PLAY);
        placeAt(match, RED, 2, 0, PLAY + 1000);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY + 1000);
        // 1.29초에 차면 1.99초에 (6,0)으로 넘어간다
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY + 1290).size());

        assertEquals(List.of(new ExplodedBalloon(1, 6, 1, 0, 1, 1, 1), new ExplodedBalloon(2, 6, 0, 0, 0, 1, 1)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
    }

    @Test
    void 다른_물풍선_위를_지나다_퓨즈가_다_되면_겹친_물풍선도_함께_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        placeAt(match, BLUE, 6, 0, PLAY + 500);
        stand(match, BLUE, 12, 11, Facing.DOWN, PLAY + 500);
        // 1.2초에 차면 1.9초부터 2.1초까지 (6,0) 파랑 물풍선 위를 지난다. 빨강 물풍선의 퓨즈는 2초에 다 된다
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY + 1200).size());

        assertEquals(List.of(new ExplodedBalloon(1, 6, 0, 0, 1, 1, 1), new ExplodedBalloon(2, 6, 0, 0, 1, 1, 1)),
                exploded(match.tick(PLAY + Match.FUSE_MS)).balloons());
    }

    @Test
    void 미끄러지는_길에_상자가_밀려_들어오면_그_앞_칸에_멈춘다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 10, PLAY);
        stand(match, RED, 1, 10, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 10, Facing.RIGHT, PLAY).size());

        match.tick(PLAY + 300);
        stand(match, BLUE, 9, 8, Facing.DOWN, PLAY + 300);
        assertEquals(List.of(new MatchEvent(EVERYONE, MatchMessages.BOX_MOVED, new BoxMoved(9, 9, 9, 10))),
                match.pushBox(CONN_BLUE, BLUE, 9, 9, Facing.DOWN, PLAY + 300));
        assertEquals(stopped(1, 8, 10), match.tick(PLAY + 1200));
    }

    @Test
    void 미끄러지는_물풍선이_막_들어가려는_칸으로는_상자를_밀지_않는다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 5, 10, PLAY);
        stand(match, RED, 4, 10, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 5, 10, Facing.RIGHT, PLAY).size());

        // 0.6초에 (8,10) 가운데를 떠나 (9,10)으로 가는 중
        assertEquals(List.of(), match.tick(PLAY + 610));
        stand(match, BLUE, 9, 8, Facing.DOWN, PLAY + 610);
        assertEquals(List.of(), match.pushBox(CONN_BLUE, BLUE, 9, 9, Facing.DOWN, PLAY + 610));
        assertEquals(stopped(1, 12, 10), match.tick(PLAY + 1400));
    }

    @Test
    void 미끄러지는_물풍선이_막_들어가려는_칸에는_물풍선을_놓지_못하고_떠난_칸에는_놓을_수_있다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY).size());

        match.tick(PLAY + 10);
        stand(match, BLUE, 3, 1, Facing.UP, PLAY + 10);
        assertEquals(List.of(), match.placeBalloon(CONN_BLUE, BLUE, 3, 0, PLAY + 10));
        match.tick(PLAY + 110);
        stand(match, BLUE, 2, 1, Facing.UP, PLAY + 110);
        assertEquals(List.of(MatchMessages.BALLOON_PLACED), types(match.placeBalloon(CONN_BLUE, BLUE, 2, 0, PLAY + 110)));
    }

    @Test
    void 다른_물풍선이_있는_칸에서_더_갈_곳이_없어지면_멈추지_않고_겹친_물풍선과_함께_터진다() {
        Match match = playing();
        give(match, RED, ItemType.SHOE);
        give(match, BLUE, ItemType.BUBBLE);
        placeAt(match, BLUE, 11, 0, PLAY);
        placeAt(match, RED, 2, 0, PLAY);
        stand(match, RED, 1, 0, Facing.RIGHT, PLAY);
        assertEquals(1, kick(match, RED, 2, 0, Facing.RIGHT, PLAY).size());

        // 1.7초에 (11,0)으로 넘어가 1.8초에 가운데에 온다. 그 사이 마지막 빈 칸 (12,0)에 파랑이 물풍선을 놓는다
        assertEquals(List.of(), match.tick(PLAY + 1720));
        stand(match, BLUE, 12, 1, Facing.UP, PLAY + 1720);
        assertEquals(List.of(MatchMessages.BALLOON_PLACED), types(match.placeBalloon(CONN_BLUE, BLUE, 12, 0, PLAY + 1720)));
        stand(match, BLUE, 12, 3, Facing.UP, PLAY + 1720);

        List<MatchEvent> events = match.tick(PLAY + 1800);
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED), types(events));
        assertEquals(List.of(1, 2, 3), exploded(events).balloons().stream().map(ExplodedBalloon::balloonId).sorted()
                .toList());
        assertTrue(exploded(events).balloons().contains(new ExplodedBalloon(2, 11, 0, 0, 1, 1, 0)));
    }
}

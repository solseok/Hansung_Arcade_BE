package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BoxMoved;
import hansung.arcade.domain.game.model.MatchMessages.ChangedTile;
import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ExplodedBalloon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BOX_PUSH 판정과 BOX_MOVED, 밀린 상자의 고정 드롭·물줄기·물풍선 판정 */
class MatchBoxTest {

    /*
     * 10×8 맵.
     *   (2,1) 상자, 고정 드롭 울트라. (8,1) 밀리지 않는 블럭
     *   (0,3) 맵 가장자리 상자, (3,3) 상자 오른쪽 (4,3) 블럭, (6,3) 상자 오른쪽 (7,3) 덤불
     *   (1,4) 도로 위 상자, 그 오른쪽 (2,4)는 가시 바닥
     *   (3,5)·(4,5) 나란한 상자 둘
     *   (6,6) 상자 오른쪽 (7,6) 블럭, 고정 드롭 물줄기
     *   (1,7) 철로 보강된 상자 b (두 번 더 맞아야 부서짐)
     * 1P(2,0)·3P(0,7)은 빨강(연결 1), 2P(9,0)는 파랑(연결 2), 4P(9,7)은 초록(연결 3). 모두 물줄기 2칸·물풍선 1개
     */
    private static final List<String> MAP = List.of(
            "[floor]",
            "..........",
            "..........",
            "..........",
            "..........",
            "._!.......",
            "..........",
            "..........",
            "..........",
            "[block]",
            "..........",
            "..P.....B.",
            "..........",
            "P..PB.PT..",
            ".P........",
            "...PP.....",
            "......PB..",
            ".b........",
            "[spawn]",
            "..1......2",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "3........4",
            "[item]",
            "..........",
            "..u.......",
            "..........",
            "..........",
            "..........",
            "..........",
            ".......f..",
            "..........");

    private static final long RED_1 = 1;
    private static final long BLUE_2 = 2;
    private static final long RED_3 = 3;
    private static final long GREEN_4 = 4;
    private static final long CONN_RED = 101;
    private static final long CONN_BLUE = 102;
    private static final long CONN_GREEN = 103;
    private static final long PLAY = Match.COUNTDOWN_MS;
    /** 이 맵에서 부서지는 블럭은 모두 고정 드롭이라 난수를 쓰면 안 된다 */
    private static final RandomGenerator NO_RANDOM = () -> {
        throw new AssertionError("고정 드롭만 나와야 합니다.");
    };

    private final GameMap map = MapLoader.parse("test/boxes", MAP);

    /** 네 명으로 경기를 열고 모두 로딩을 마친 카운트다운 상태. PLAYING은 {@link #PLAY}부터 */
    private Match counting() {
        Mascot sangzzi = Mascot.of("sangzzi");
        Match match = new Match(1, map, List.of(
                new MatchPlayer(RED_1, CONN_RED, "빨강하나", sangzzi, Team.RED, 1, map.spawns().get(0)),
                new MatchPlayer(BLUE_2, CONN_BLUE, "파랑", sangzzi, Team.BLUE, 2, map.spawns().get(1)),
                new MatchPlayer(RED_3, CONN_RED, "빨강셋", sangzzi, Team.RED, 3, map.spawns().get(2)),
                new MatchPlayer(GREEN_4, CONN_GREEN, "초록", sangzzi, Team.GREEN, 4, map.spawns().get(3))),
                NO_RANDOM);
        match.start(0);
        match.loaded(CONN_RED, 0);
        match.loaded(CONN_BLUE, 0);
        match.loaded(CONN_GREEN, 0);
        assertEquals(Match.Phase.COUNTDOWN, match.phase());
        return match;
    }

    /** PLAYING까지 진행한다. 지금 시각은 {@link #PLAY} */
    private Match playing() {
        Match match = counting();
        match.tick(PLAY);
        assertEquals(Match.Phase.PLAYING, match.phase());
        return match;
    }

    private static long connectionOf(long playerId) {
        if (playerId == BLUE_2) {
            return CONN_BLUE;
        }
        return playerId == GREEN_4 ? CONN_GREEN : CONN_RED;
    }

    private static double cx(int col) {
        return GameMap.centerX(col);
    }

    private static double cy(int row) {
        return GameMap.centerY(row);
    }

    /** playerId가 (col, row) 칸 가운데에서 facing 쪽을 보고 멈춰 섰다는 MOVE */
    private static void stopAt(Match match, long playerId, int col, int row, Facing facing, long now) {
        match.move(connectionOf(playerId), playerId, cx(col), cy(row), facing, false, now);
    }

    private static List<MatchEvent> push(Match match, long playerId, int col, int row, Facing direction, long now) {
        return match.pushBox(connectionOf(playerId), playerId, col, row, direction, now);
    }

    /** 네 연결이 모두 남은 경기에서 BOX_MOVED 하나만 방 전체로 나갔는지 */
    private static void assertMoved(List<MatchEvent> events, int fromCol, int fromRow, int toCol, int toRow) {
        assertEquals(List.of(new MatchEvent(List.of(CONN_RED, CONN_BLUE, CONN_GREEN), MatchMessages.BOX_MOVED,
                new BoxMoved(fromCol, fromRow, toCol, toRow))), events);
    }

    private static List<String> types(List<MatchEvent> events) {
        return events.stream().map(MatchEvent::type).toList();
    }

    private static BalloonExploded exploded(List<MatchEvent> events) {
        return events.stream().filter(event -> event.type().equals(MatchMessages.BALLOON_EXPLODED)).findFirst()
                .map(event -> (BalloonExploded) event.body())
                .orElseThrow(() -> new AssertionError("BALLOON_EXPLODED가 없습니다: " + types(events)));
    }

    /**
     * playerId를 (9,4)에서 자기 물풍선으로 가둔다. 물줄기 2칸이 닿는 (9,2)~(9,6)·(7,4)~(8,4)에는 상자도 다른 사람도 없다.
     *
     * @return 갇힌 시각
     */
    private static long trapAtCorner(Match match, long playerId, long at) {
        stopAt(match, playerId, 9, 4, Facing.DOWN, at);
        assertEquals(1, match.placeBalloon(connectionOf(playerId), playerId, 9, 4, at).size());
        long trappedAt = at + Match.FUSE_MS;
        assertEquals(List.of(MatchMessages.BALLOON_EXPLODED, MatchMessages.PLAYER_TRAPPED),
                types(match.tick(trappedAt)));
        return trappedAt;
    }

    @Test
    void 상자에_막혀_그쪽을_보고_멈춘_사람이_밀면_상자가_한_칸_옮겨지고_방_전체에_BOX_MOVED를_보낸다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);

        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);
        assertEquals(Tile.FLOOR, map.tileAt(2, 1));
        assertEquals(Tile.PUSH_BOX, map.tileAt(3, 1));
    }

    @Test
    void 네_방향_어느_쪽으로도_밀_수_있다() {
        Match match = playing();
        long now = PLAY;
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, now);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, now), 2, 1, 3, 1);

        now += Match.SLIDE_MS;
        stopAt(match, RED_1, 3, 0, Facing.DOWN, now);
        assertMoved(push(match, RED_1, 3, 1, Facing.DOWN, now), 3, 1, 3, 2);

        now += Match.SLIDE_MS;
        stopAt(match, RED_1, 4, 2, Facing.LEFT, now);
        assertMoved(push(match, RED_1, 3, 2, Facing.LEFT, now), 3, 2, 2, 2);

        now += Match.SLIDE_MS;
        stopAt(match, RED_1, 2, 3, Facing.UP, now);
        assertMoved(push(match, RED_1, 2, 2, Facing.UP, now), 2, 2, 2, 1);
    }

    @Test
    void 바닥이면_도로나_가시_칸으로도_밀리고_원래_칸에는_그_바닥이_드러난다() {
        Match match = playing();
        stopAt(match, RED_1, 0, 4, Facing.RIGHT, PLAY);

        assertMoved(push(match, RED_1, 1, 4, Facing.RIGHT, PLAY), 1, 4, 2, 4);
        assertEquals(Tile.ROAD, map.tileAt(1, 4));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 4));
    }

    @Test
    void 철로_보강된_상자도_밀리고_새_칸에서_맞으면_한_단계_약해진다() {
        Match match = playing();
        stopAt(match, RED_1, 0, 7, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 1, 7, Facing.RIGHT, PLAY), 1, 7, 2, 7);
        assertEquals(Tile.FLOOR, map.tileAt(1, 7));
        assertEquals(Tile.HARD_BLOCK_2, map.tileAt(2, 7));

        // (0,7)에 물풍선을 놓고 빨강 둘이 비켜선다. 오른쪽 물줄기가 비워진 (1,7)을 지나 (2,7)을 맞힌다
        assertEquals(1, match.placeBalloon(CONN_RED, RED_1, 0, 7, PLAY).size());
        stopAt(match, RED_1, 1, 6, Facing.UP, PLAY);
        stopAt(match, RED_3, 1, 5, Facing.UP, PLAY);
        BalloonExploded exploded = exploded(match.tick(PLAY + Match.FUSE_MS));

        assertEquals(List.of(new ChangedTile(2, 7, "a")), exploded.tiles());
        assertEquals(List.of(), exploded.items());
        assertEquals(Tile.HARD_BLOCK_1, map.tileAt(2, 7));
    }

    @Test
    void 상자_바로_옆에서_상자_쪽을_보고_멈춰_있지_않으면_밀지_않는다() {
        Match match = playing();
        // 한 칸 떨어져 있다
        stopAt(match, RED_1, 0, 1, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        // 바로 옆이지만 다른 쪽을 본다
        stopAt(match, RED_1, 1, 1, Facing.DOWN, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        // 바로 옆에서 상자 쪽을 보지만 걷는 중이다 (막혀 멈추지 않았다)
        match.move(CONN_RED, RED_1, cx(1), cy(1), Facing.RIGHT, true, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        // 보는 쪽으로 미는 요청이지만 그쪽 바로 앞 칸은 상자가 아니다 (상자는 오른쪽)
        stopAt(match, RED_1, 1, 1, Facing.UP, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.UP, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 1));

        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);
    }

    @Test
    void 밀리는_상자가_아닌_블럭이나_빈_칸은_밀지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 7, 1, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 8, 1, Facing.RIGHT, PLAY));
        assertEquals(Tile.BLOCK, map.tileAt(8, 1));

        stopAt(match, RED_1, 6, 1, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 7, 1, Facing.RIGHT, PLAY));
    }

    @Test
    void 밀려_갈_칸이_블럭_덤불_다른_상자이거나_맵_밖이면_밀지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 2, 3, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 3, 3, Facing.RIGHT, PLAY));
        stopAt(match, RED_1, 5, 3, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 6, 3, Facing.RIGHT, PLAY));
        stopAt(match, RED_1, 2, 5, Facing.RIGHT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 3, 5, Facing.RIGHT, PLAY));
        stopAt(match, RED_1, 1, 3, Facing.LEFT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 0, 3, Facing.LEFT, PLAY));

        assertEquals(List.of(Tile.PUSH_BOX, Tile.BLOCK, Tile.PUSH_BOX, Tile.BUSH, Tile.PUSH_BOX, Tile.PUSH_BOX,
                        Tile.PUSH_BOX),
                List.of(map.tileAt(3, 3), map.tileAt(4, 3), map.tileAt(6, 3), map.tileAt(7, 3), map.tileAt(3, 5),
                        map.tileAt(4, 5), map.tileAt(0, 3)));
    }

    @Test
    void 물풍선이_놓인_칸으로는_밀지_않는다() {
        Match match = playing();
        stopAt(match, BLUE_2, 3, 2, Facing.UP, PLAY);
        assertEquals(1, match.placeBalloon(CONN_BLUE, BLUE_2, 3, 1, PLAY).size());
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);

        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 1));
    }

    @Test
    void 아이템이_있는_칸으로는_밀지_않고_아이템이_치워지면_민다() {
        Match match = playing();
        // 파랑이 (7,5)에서 터뜨려 (7,6) 블럭의 고정 드롭 물줄기(itemId 1)를 꺼낸다. 파랑은 (8,4)로 비켜선다
        stopAt(match, BLUE_2, 7, 5, Facing.DOWN, PLAY);
        assertEquals(1, match.placeBalloon(CONN_BLUE, BLUE_2, 7, 5, PLAY).size());
        stopAt(match, BLUE_2, 8, 4, Facing.DOWN, PLAY);
        long explodedAt = PLAY + Match.FUSE_MS;
        assertEquals(List.of(new DroppedItem(1, 7, 6, "fluid")), exploded(match.tick(explodedAt)).items());
        long dry = explodedAt + Match.FLAME_MS;
        match.tick(dry);
        stopAt(match, RED_1, 5, 6, Facing.RIGHT, dry);

        assertEquals(List.of(), push(match, RED_1, 6, 6, Facing.RIGHT, dry));

        // 초록이 주워 가고 비켜서면 민다
        stopAt(match, GREEN_4, 7, 6, Facing.UP, dry);
        assertEquals(List.of(MatchMessages.ITEM_TAKEN), types(match.pickUpItem(CONN_GREEN, GREEN_4, 1, dry)));
        stopAt(match, GREEN_4, 9, 7, Facing.UP, dry);
        assertMoved(push(match, RED_1, 6, 6, Facing.RIGHT, dry), 6, 6, 7, 6);
    }

    @Test
    void 몸이_조금이라도_걸친_사람이_있는_칸으로는_밀지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        // 밀려 갈 칸은 (3,1). 중심은 오른쪽 (4,1)이지만 충돌 박스 왼쪽이 걸친다
        match.move(CONN_BLUE, BLUE_2, cx(4) - 10, cy(1), Facing.LEFT, false, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        // 중심은 아래 (3,2)이지만 충돌 박스 위쪽이 걸친다
        match.move(CONN_BLUE, BLUE_2, cx(3), cy(2) - 10, Facing.UP, false, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        // 중심은 위 (3,0)이지만 충돌 박스 아래쪽이 걸친다
        match.move(CONN_BLUE, BLUE_2, cx(3), cy(0) + 10, Facing.DOWN, false, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 1));

        // (3,5) 상자를 아래 (3,6)으로: 중심은 왼쪽 (2,6)이지만 충돌 박스 오른쪽이 걸친다
        stopAt(match, RED_3, 3, 4, Facing.DOWN, PLAY);
        match.move(CONN_GREEN, GREEN_4, cx(2) + 10, cy(6), Facing.RIGHT, false, PLAY);
        assertEquals(List.of(), push(match, RED_3, 3, 5, Facing.DOWN, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(3, 5));
    }

    @Test
    void 충돌_박스가_칸_경계에_딱_맞닿기만_한_사람은_막지_않는다() {
        Match match = playing();
        double edge = GameMap.TILE_SIZE;
        // (3,1)로 밀 때 파랑 충돌 박스 아래 끝이 (3,0)과 (3,1)의 경계에 딱 맞닿는다
        match.move(CONN_BLUE, BLUE_2, cx(3), edge - MatchPlayer.HALF_HITBOX, Facing.DOWN, false, PLAY);
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);

        // (3,6)으로 밀 때 초록 충돌 박스 오른쪽 끝이 (2,6)과 (3,6)의 경계에 딱 맞닿는다
        match.move(CONN_GREEN, GREEN_4, 3 * edge - MatchPlayer.HALF_HITBOX, cy(6), Facing.RIGHT, false, PLAY);
        stopAt(match, RED_3, 3, 4, Facing.DOWN, PLAY);
        assertMoved(push(match, RED_3, 3, 5, Facing.DOWN, PLAY), 3, 5, 3, 6);
    }

    @Test
    void 터져_쓰러지는_중인_사람은_칸을_막고_다_쓰러져_사라지면_막지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        long trappedAt = trapAtCorner(match, RED_3, PLAY);
        // 3P가 갇힌 채 (3,1)로 걸어가 시간이 다 되어 터진다. 빨강 1P가 남아 경기는 이어진다
        match.move(CONN_RED, RED_3, cx(3), cy(1), Facing.LEFT, false, trappedAt);
        long poppedAt = trappedAt + Match.POP_MS;
        assertEquals(List.of(MatchMessages.PLAYER_POPPED), types(match.tick(poppedAt)));

        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, poppedAt));
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, poppedAt + Match.DYING_MS - 1));
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, poppedAt + Match.DYING_MS), 2, 1, 3, 1);
    }

    @Test
    void 나간_사람은_칸을_막지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        stopAt(match, GREEN_4, 3, 1, Facing.LEFT, PLAY);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));

        match.leave(CONN_GREEN, "EXIT", false, PLAY);

        assertEquals(List.of(new MatchEvent(List.of(CONN_RED, CONN_BLUE), MatchMessages.BOX_MOVED,
                new BoxMoved(2, 1, 3, 1))), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
    }

    @Test
    void 물줄기가_남은_칸으로는_밀지_않고_물줄기가_사라지면_민다() {
        Match match = playing();
        // 파랑이 (5,1)에 놓은 물풍선의 왼쪽 물줄기 2칸이 (4,1)·(3,1)을 덮는다. (2,1) 상자에는 닿지 않는다
        stopAt(match, BLUE_2, 5, 1, Facing.LEFT, PLAY);
        assertEquals(1, match.placeBalloon(CONN_BLUE, BLUE_2, 5, 1, PLAY).size());
        stopAt(match, BLUE_2, 6, 0, Facing.LEFT, PLAY);
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        long explodedAt = PLAY + Match.FUSE_MS;
        assertEquals(List.of(), exploded(match.tick(explodedAt)).tiles());

        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, explodedAt));
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, explodedAt + Match.FLAME_MS - 1));
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, explodedAt + Match.FLAME_MS), 2, 1, 3, 1);
    }

    @Test
    void 미끄러지는_동안에는_같은_상자를_다시_밀지_않는다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);

        // 비워진 칸으로 따라 들어가 다시 민다
        stopAt(match, RED_1, 2, 1, Facing.RIGHT, PLAY + 50);
        assertEquals(List.of(), push(match, RED_1, 3, 1, Facing.RIGHT, PLAY + Match.SLIDE_MS - 1));
        assertMoved(push(match, RED_1, 3, 1, Facing.RIGHT, PLAY + Match.SLIDE_MS), 3, 1, 4, 1);
    }

    @Test
    void 두_사람이_같은_상자를_함께_밀면_먼저_온_쪽만_민다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        stopAt(match, BLUE_2, 2, 0, Facing.DOWN, PLAY);

        assertMoved(push(match, BLUE_2, 2, 1, Facing.DOWN, PLAY), 2, 1, 2, 2);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 2));
        assertEquals(Tile.FLOOR, map.tileAt(3, 1));
    }

    @Test
    void 밀린_상자는_고정_드롭을_데려가고_물줄기는_비워진_칸을_지나_새_칸의_상자를_맞힌다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);
        assertEquals(Optional.empty(), map.fixedDrop(2, 1));
        assertEquals(Optional.of(ItemType.ULTRA), map.fixedDrop(3, 1));

        // 미는 자리 (1,1)에 물풍선을 놓고 (0,2)로 비켜선다. 오른쪽 물줄기는 비워진 (2,1)을 지나 (3,1) 상자에서 멈춘다
        assertEquals(1, match.placeBalloon(CONN_RED, RED_1, 1, 1, PLAY).size());
        stopAt(match, RED_1, 0, 2, Facing.DOWN, PLAY);
        BalloonExploded exploded = exploded(match.tick(PLAY + Match.FUSE_MS));

        assertEquals(List.of(new ExplodedBalloon(1, 1, 1, 1, 2, 1, 1)), exploded.balloons());
        assertEquals(List.of(new ChangedTile(3, 1, ".")), exploded.tiles());
        assertEquals(List.of(new DroppedItem(1, 3, 1, "ultra")), exploded.items());
        assertEquals(Optional.empty(), map.fixedDrop(3, 1));
    }

    @Test
    void 미끄러지는_중에_터진_물줄기도_새_칸에_있는_상자를_맞힌다() {
        Match match = playing();
        // 파랑이 (5,1)에 물풍선을 놓고 (6,0)으로 비켜선다. 왼쪽 물줄기 2칸은 원래 자리 (2,1)의 상자에 닿지 않는다
        stopAt(match, BLUE_2, 5, 1, Facing.LEFT, PLAY);
        assertEquals(1, match.placeBalloon(CONN_BLUE, BLUE_2, 5, 1, PLAY).size());
        stopAt(match, BLUE_2, 6, 0, Facing.LEFT, PLAY);
        long explodesAt = PLAY + Match.FUSE_MS;
        // 터지기 직전에 민다. 터지는 순간 상자는 아직 미끄러지는 중이다
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, explodesAt - Match.SLIDE_MS / 2), 2, 1, 3, 1);

        BalloonExploded exploded = exploded(match.tick(explodesAt));

        assertEquals(List.of(new ExplodedBalloon(1, 5, 1, 1, 2, 1, 2)), exploded.balloons());
        assertEquals(List.of(new ChangedTile(3, 1, ".")), exploded.tiles());
        assertEquals(List.of(new DroppedItem(1, 3, 1, "ultra")), exploded.items());
    }

    @Test
    void 상자를_밀어_비운_칸에는_물풍선을_놓을_수_있고_상자가_간_칸에는_놓을_수_없다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, PLAY), 2, 1, 3, 1);
        stopAt(match, RED_1, 2, 1, Facing.RIGHT, PLAY);

        assertEquals(List.of(), match.placeBalloon(CONN_RED, RED_1, 3, 1, PLAY));
        assertEquals(List.of(MatchMessages.BALLOON_PLACED), types(match.placeBalloon(CONN_RED, RED_1, 2, 1, PLAY)));
    }

    @Test
    void 갇혔거나_구출되어_빠져나오는_중에는_밀지_않는다() {
        Match match = playing();
        long trappedAt = trapAtCorner(match, RED_1, PLAY);
        // 갇힌 채 상자 옆으로 걸어와 멈춘다
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, trappedAt);
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, trappedAt));

        // 같은 편 3P가 닿아 구해 준다
        long rescuedAt = trappedAt + Match.MIN_TRAPPED_MS;
        List<MatchEvent> touched = match.move(CONN_RED, RED_3, cx(1) + 5, cy(1), Facing.UP, false, rescuedAt);
        assertTrue(types(touched).contains(MatchMessages.PLAYER_RESCUED));
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.RIGHT, rescuedAt + Match.ESCAPE_MS - 1));
        assertMoved(push(match, RED_1, 2, 1, Facing.RIGHT, rescuedAt + Match.ESCAPE_MS), 2, 1, 3, 1);
    }

    @Test
    void 카운트다운이_끝나기_전에는_밀지_않는다() {
        Match match = counting();
        // 1P는 스폰 (2,0)에서 아래를 보고 서 있고 바로 아래가 상자다. 카운트다운 중에는 MOVE도 받지 않는다
        assertEquals(List.of(), push(match, RED_1, 2, 1, Facing.DOWN, PLAY - 1));

        assertMoved(push(match, RED_1, 2, 1, Facing.DOWN, PLAY), 2, 1, 2, 2);
    }

    @Test
    void 다른_연결의_플레이어이거나_방향이_없는_요청은_버린다() {
        Match match = playing();
        stopAt(match, RED_1, 1, 1, Facing.RIGHT, PLAY);

        assertEquals(List.of(), match.pushBox(CONN_BLUE, RED_1, 2, 1, Facing.RIGHT, PLAY));
        assertEquals(List.of(), match.pushBox(CONN_RED, RED_1, 2, 1, null, PLAY));
        assertEquals(Tile.PUSH_BOX, map.tileAt(2, 1));
    }
}

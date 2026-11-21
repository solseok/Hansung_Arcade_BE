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
import hansung.arcade.domain.game.model.MatchMessages.RoundBegin;
import hansung.arcade.domain.game.model.MatchMessages.Standing;
import hansung.arcade.domain.game.model.MatchMessages.StartedPlayer;
import hansung.arcade.global.exception.GameException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static hansung.arcade.global.exception.CommonErrorCode.WRONG_STATE;

/**
 * 경기 한 판. 맵 사본, 플레이어 상태, 물풍선·물줄기를 들고 인게임 규칙을 판정한다.
 *
 * <p>네트워크를 모른다. 메서드마다 보내야 할 푸시를 {@link MatchEvent} 목록으로 돌려주고, 보내는 것은 부른 쪽이
 * 한다. 시간은 밖에서 받은 ms 값({@code now})만 쓰므로 테스트에서 시계를 마음대로 돌릴 수 있다.</p>
 *
 * <pre>
 * GAME_STARTED → (모든 연결 LOADED 또는 {@link #LOAD_TIMEOUT_MS}) → ROUND_BEGIN
 * → {@link #COUNTDOWN_MS} 뒤 PLAYING → (남은 팀 하나 · 모두 터짐 · {@link #TIME_LIMIT_MS}) → GAME_OVER
 * </pre>
 *
 * <p>모든 public 메서드는 이 객체로 잠근다. 받는 스레드(MOVE 등)와 타이머 스레드(tick)가 함께 부른다.</p>
 */
public final class Match {

    public enum Phase { LOADING, COUNTDOWN, PLAYING, FINISHED }

    /** GAME_STARTED 뒤 이 시간 안에 LOADED를 보내지 않은 연결은 나간 것으로 본다 */
    public static final long LOAD_TIMEOUT_MS = 10_000;
    /** ROUND_BEGIN부터 입력을 받기까지 */
    public static final long COUNTDOWN_MS = 3_000;
    /** 경기 제한 시간 (카운트다운이 끝난 때부터). 값은 검토 중 */
    public static final long TIME_LIMIT_MS = 180_000;
    /** 물풍선이 놓인 뒤 터지기까지 (클라이언트 WaterBalloon.FUSE_SECONDS) */
    public static final long FUSE_MS = 2_000;
    /** 물줄기가 남아 있는 시간. 이 동안 그 칸에 들어온 플레이어도 갇힌다 (클라이언트 Explosion.SECONDS) */
    public static final long FLAME_MS = 450;
    /** 갇힌 뒤 구해지지 않으면 터지기까지 */
    public static final long POP_MS = 5_000;
    /** 구해진 뒤 빠져나오는 시간. 이 동안은 다시 갇히지 않는다 (클라이언트 Player.ESCAPE_SECONDS) */
    public static final long ESCAPE_MS = 500;
    /** 갇힌 뒤 적어도 이만큼(타이머 한 번)은 구해지거나 터지지 않는다. 갇힌 모습이 한 번은 보이도록 한다 */
    static final long MIN_TRAPPED_MS = 20;
    /** 두 캐릭터 중심이 가로·세로 모두 이보다 가까우면 닿은 것 (클라이언트 Player.TOUCH_DISTANCE) */
    static final double TOUCH_DISTANCE = GameMap.TILE_SIZE / 2.0;

    private final long matchId;
    private final GameMap map;
    private final Map<Long, MatchPlayer> players = new LinkedHashMap<>();
    /** 아직 경기에 남아 있는 연결. 들어온 순서 */
    private final Set<Long> connections = new LinkedHashSet<>();
    private final Set<Long> loaded = new LinkedHashSet<>();
    private final List<Balloon> balloons = new ArrayList<>();
    private final List<Flame> flames = new ArrayList<>();

    private Phase phase = Phase.LOADING;
    private long loadDeadline;
    private long playStartsAt;
    private long endsAt;
    private int lastBalloonId;

    public Match(long matchId, GameMap map, List<MatchPlayer> participants) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("플레이어가 없습니다.");
        }
        this.matchId = matchId;
        this.map = map;
        for (MatchPlayer player : participants) {
            if (players.putIfAbsent(player.playerId(), player) != null) {
                throw new IllegalArgumentException("playerId가 겹칩니다: " + player.playerId());
            }
            connections.add(player.connectionId());
        }
    }

    public long matchId() {
        return matchId;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    /** 아직 경기에 남아 있는 연결 번호들 */
    public synchronized List<Long> connectionIds() {
        return List.copyOf(connections);
    }

    /** 이 연결이 아직 끝나지 않은 경기에 남아 있는지 */
    public synchronized boolean includes(long connectionId) {
        return phase != Phase.FINISHED && connections.contains(connectionId);
    }

    /** 플레이어 상태. 경기 lock 밖에서 읽으면 값이 어긋날 수 있으니 확인·로그용으로만 쓴다 */
    public synchronized MatchPlayer player(long playerId) {
        return players.get(playerId);
    }

    /** 경기를 연다. 방 전체에 GAME_STARTED를 보내고 LOADED를 기다린다 */
    public synchronized List<MatchEvent> start(long now) {
        loadDeadline = now + LOAD_TIMEOUT_MS;
        List<StartedPlayer> started = players.values().stream()
                .map(p -> new StartedPlayer(p.playerId(), p.nickname(), p.mascot().id(), p.team().name(), p.spawn()))
                .toList();
        return List.of(toAll(MatchMessages.GAME_STARTED, new GameStarted(map.id(), started)));
    }

    /**
     * LOADED. 연결마다 한 번. 남은 연결이 모두 보내면 ROUND_BEGIN.
     *
     * @throws GameException 로딩 중이 아니거나 이미 보냈으면 WRONG_STATE
     */
    public synchronized List<MatchEvent> loaded(long connectionId, long now) {
        if (phase != Phase.LOADING || !connections.contains(connectionId) || !loaded.add(connectionId)) {
            throw new GameException(WRONG_STATE, "지금은 로딩 완료를 보낼 수 없습니다.");
        }
        List<MatchEvent> events = new ArrayList<>();
        beginRoundIfAllLoaded(now, events);
        return events;
    }

    /**
     * MOVE. 맞지 않으면 조용히 버린다 (초당 20번이라 ERROR를 보내지 않는다).
     * 위치를 저장하고 다른 연결에 중계한 뒤, 그 위치로 물줄기·구출·터뜨리기를 판정한다.
     */
    public synchronized List<MatchEvent> move(long connectionId, long playerId, double x, double y, Facing facing,
                                              boolean moving, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING || facing == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0 || x >= map.pixelWidth() || y >= map.pixelHeight()) {
            return List.of();
        }
        MatchPlayer mover = ownPlayer(connectionId, playerId);
        if (mover == null || !mover.isInPlay()) {
            return List.of();
        }
        mover.moveTo(x, y, facing, moving);

        List<MatchEvent> events = new ArrayList<>();
        events.add(new MatchEvent(othersThan(connectionId), MatchMessages.PLAYER_MOVED,
                new PlayerMoved(playerId, x, y, facing.name(), moving)));
        trapIfWet(mover, now, events);
        settleTouches(now, events);
        finishIfDecided(events);
        return events;
    }

    /**
     * BALLOON_PLACE. 놓을 수 없으면 조용히 버린다.
     * 살아 있고 갇히지 않음, 마지막 MOVE 칸이나 그 상하좌우 칸(통신 지연 여유), 지나갈 수 있는 빈 칸,
     * 놓아 둔 수가 능력치보다 적음. 물줄기가 남은 칸에 놓으면 바로 터진다 (클라이언트 BalloonField.place와 같다).
     */
    public synchronized List<MatchEvent> placeBalloon(long connectionId, long playerId, int col, int row, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING) {
            return List.of();
        }
        MatchPlayer owner = ownPlayer(connectionId, playerId);
        Cell cell = new Cell(col, row);
        if (owner == null || !owner.isFree(now)
                || owner.cell().distanceTo(cell) > 1
                || map.isSolid(col, row) || balloonAt(cell) != null
                || owner.placedBalloons() >= owner.balloonCapacity()) {
            return List.of();
        }
        boolean wet = flameAt(cell, now) != null;
        long fuseMs = wet ? 0 : FUSE_MS;
        Balloon balloon = new Balloon(++lastBalloonId, owner.playerId(), cell, owner.waterRange(), now + fuseMs);
        balloons.add(balloon);
        owner.balloonPlaced();
        List<MatchEvent> events = new ArrayList<>();
        events.add(toAll(MatchMessages.BALLOON_PLACED, new BalloonPlaced(balloon.balloonId(), owner.playerId(),
                col, row, balloon.range(), fuseMs)));
        if (wet) {
            // 갇힌 사람의 닿음 판정은 MIN_TRAPPED_MS 뒤에 한다
            explodeDueBalloons(now, events);
            finishIfDecided(events);
        }
        return events;
    }

    /**
     * 연결 하나의 플레이어(1P·2P)를 경기에서 뺀다. GAME_EXIT, 끊김, 로딩 시간 초과 모두 여기로 온다.
     * 그 플레이어의 물풍선은 그대로 터지게 둔다. 경기에 없는 연결이면 아무 일도 없다.
     *
     * @param reason       EXIT 또는 DISCONNECT
     * @param notifyLeaver 나간 연결에도 PLAYER_LEFT를 보낼지 (로딩 시간 초과처럼 아직 화면에 있는 경우)
     */
    public synchronized List<MatchEvent> leave(long connectionId, String reason, boolean notifyLeaver, long now) {
        if (phase == Phase.FINISHED || !connections.contains(connectionId)) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        removeConnection(connectionId, reason, notifyLeaver, events);
        afterLeaving(now, events);
        return events;
    }

    /**
     * 로딩 시간 초과: LOADED를 안 보낸 연결을 모두 뺀 뒤에 한 번만 판정한다.
     * 하나씩 빼며 판정하면 빼는 순서에 따라 로딩하지 않은 쪽이 이길 수 있다.
     */
    private void dropUnloaded(long now, List<MatchEvent> events) {
        for (Long connectionId : List.copyOf(connections)) {
            if (!loaded.contains(connectionId)) {
                removeConnection(connectionId, "DISCONNECT", true, events);
            }
        }
        afterLeaving(now, events);
    }

    private void removeConnection(long connectionId, String reason, boolean notifyLeaver, List<MatchEvent> events) {
        List<Long> recipients = List.copyOf(connections);
        connections.remove(connectionId);
        loaded.remove(connectionId);

        List<Long> leftIds = new ArrayList<>();
        for (MatchPlayer player : players.values()) {
            if (player.connectionId() == connectionId && player.state() != MatchPlayer.State.LEFT) {
                player.leave();
                leftIds.add(player.playerId());
            }
        }
        if (!notifyLeaver) {
            recipients = List.copyOf(connections);
        }
        events.add(new MatchEvent(recipients, MatchMessages.PLAYER_LEFT, new PlayerLeft(leftIds, reason)));
    }

    private void afterLeaving(long now, List<MatchEvent> events) {
        if (connections.isEmpty()) {
            // 남은 사람이 없으니 GAME_OVER를 받을 사람도 없다
            phase = Phase.FINISHED;
            balloons.clear();
            flames.clear();
            return;
        }
        // 한 팀만 남았으면 로딩 중이어도 ROUND_BEGIN 없이 바로 끝낸다
        finishIfDecided(events);
        beginRoundIfAllLoaded(now, events);
    }

    /** 타이머가 자주(수십 ms마다) 부른다. 로딩 시간 초과, 카운트다운 끝, 물풍선 폭발, 갇힘 시간 초과, 제한 시간을 처리한다 */
    public synchronized List<MatchEvent> tick(long now) {
        List<MatchEvent> events = new ArrayList<>();
        switch (phase) {
            case LOADING -> {
                if (now >= loadDeadline) {
                    dropUnloaded(now, events);
                }
            }
            case COUNTDOWN -> startPlayIfDue(now);
            case PLAYING -> {
                explodeDueBalloons(now, events);
                flames.removeIf(flame -> flame.until() <= now);
                settleTouches(now, events);
                for (MatchPlayer player : players.values()) {
                    if (player.state() == MatchPlayer.State.TRAPPED && player.trappedUntil() <= now) {
                        pop(player, null, events);
                    }
                }
                finishIfDecided(events);
                if (phase == Phase.PLAYING && now >= endsAt) {
                    finish("TIME_UP", events);
                }
            }
            case FINISHED -> {
            }
        }
        return events;
    }

    /**
     * 카운트다운이 끝났으면 PLAYING으로 넘긴다. tick만 기다리면 카운트다운 직후 첫 입력이 최대 한 tick 동안 버려지므로
     * 입력을 받을 때도 부른다.
     */
    private void startPlayIfDue(long now) {
        if (phase == Phase.COUNTDOWN && now >= playStartsAt) {
            phase = Phase.PLAYING;
        }
    }

    private void beginRoundIfAllLoaded(long now, List<MatchEvent> events) {
        if (phase != Phase.LOADING || !loaded.containsAll(connections)) {
            return;
        }
        phase = Phase.COUNTDOWN;
        playStartsAt = now + COUNTDOWN_MS;
        endsAt = playStartsAt + TIME_LIMIT_MS;
        events.add(toAll(MatchMessages.ROUND_BEGIN, new RoundBegin(COUNTDOWN_MS, TIME_LIMIT_MS)));
    }

    /**
     * 터질 때가 된 물풍선을 연쇄까지 한꺼번에 터뜨려 BALLOON_EXPLODED 하나로 알리고, 물줄기에 있는 플레이어를 가둔다.
     *
     * <p>한 번에 터지는 물풍선은 모두 같은 순간 터진 것으로 본다. 그래서 줄기를 다 정할 때까지 물풍선을 맵에서 빼지 않고,
     * 어느 줄기든 물풍선이 있는 칸(이번에 함께 터지는 것 포함) 앞에서 멈춘다. 그러면 놓은 순서와 상관없이 결과가 같다.</p>
     *
     */
    private void explodeDueBalloons(long now, List<MatchEvent> events) {
        Deque<Balloon> queue = new ArrayDeque<>();
        Set<Balloon> exploding = new LinkedHashSet<>();
        for (Balloon balloon : balloons) {
            if (balloon.fuseAt() <= now && exploding.add(balloon)) {
                queue.add(balloon);
            }
        }
        if (queue.isEmpty()) {
            return;
        }

        List<ExplodedBalloon> exploded = new ArrayList<>();
        Set<Cell> hitBlocks = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            Balloon balloon = queue.poll();
            Set<Cell> covered = new LinkedHashSet<>();
            covered.add(balloon.cell());
            int[] reach = new int[Facing.values().length];
            for (Facing direction : Facing.values()) {
                for (int step = 1; step <= balloon.range(); step++) {
                    int col = balloon.cell().col() + direction.dx() * step;
                    int row = balloon.cell().row() + direction.dy() * step;
                    if (map.isSolid(col, row)) {
                        if (map.isInside(col, row)) {
                            hitBlocks.add(new Cell(col, row));
                        }
                        break;
                    }
                    Balloon other = balloonAt(new Cell(col, row));
                    if (other != null) {
                        // 닿은 물풍선도 같은 순간 터져 그 칸을 덮는다
                        if (exploding.add(other)) {
                            queue.add(other);
                        }
                        break;
                    }
                    covered.add(new Cell(col, row));
                    reach[direction.ordinal()] = step;
                }
            }
            flames.add(new Flame(balloon.balloonId(), balloon.cell(), covered, now + FLAME_MS));
            MatchPlayer owner = players.get(balloon.ownerId());
            if (owner != null) {
                owner.balloonGone();
            }
            exploded.add(new ExplodedBalloon(balloon.balloonId(), balloon.cell().col(), balloon.cell().row(),
                    reach[Facing.UP.ordinal()], reach[Facing.DOWN.ordinal()],
                    reach[Facing.LEFT.ordinal()], reach[Facing.RIGHT.ordinal()]));
        }
        balloons.removeAll(exploding);

        // 블럭은 모든 물줄기를 정한 뒤 한 번씩만 맞힌다 (두 물줄기가 같은 블럭에 닿아도 한 번)
        List<ChangedTile> tiles = new ArrayList<>();
        for (Cell cell : hitBlocks) {
            if (map.hitBlock(cell.col(), cell.row())) {
                tiles.add(new ChangedTile(cell.col(), cell.row(),
                        String.valueOf(map.tileAt(cell.col(), cell.row()).symbol())));
            }
        }
        // 아이템 API를 만들기 전까지 items·removedItems는 늘 비어 있다
        events.add(toAll(MatchMessages.BALLOON_EXPLODED, new BalloonExploded(exploded, tiles, List.of(), List.of())));

        for (MatchPlayer player : players.values()) {
            trapIfWet(player, now, events);
        }
    }

    /** 살아 있고 빠져나오는 중이 아닌 사람이 물줄기 칸에 있으면 가둔다 */
    private void trapIfWet(MatchPlayer player, long now, List<MatchEvent> events) {
        if (!player.isFree(now)) {
            return;
        }
        Flame flame = flameAt(player.cell(), now);
        if (flame == null) {
            return;
        }
        player.trap(now, now + POP_MS);
        events.add(toAll(MatchMessages.PLAYER_TRAPPED, new PlayerTrapped(player.playerId(), flame.balloonId(), POP_MS)));
    }

    /**
     * 갇힌 사람마다 닿은 살아 있는 사람을 본다 (클라이언트 BattleRules.settleTouches와 같다).
     * 상대가 하나라도 닿았으면 같은 편이 함께 닿았어도 터뜨리고, 상대가 없고 같은 편이 닿았으면 구한다.
     * 닿은 사람 가운데 중심이 가장 가까운 사람(거리가 같으면 playerId가 작은 사람)이 kill·save를 얻는다.
     * 막 갇힌 사람({@link #MIN_TRAPPED_MS} 안)과 갇힘 시간이 다 된 사람(tick이 TIMEOUT으로 터뜨린다)은 판정하지 않는다.
     * 빠져나오는 중인 사람은 남에게 닿지 않는다.
     */
    private void settleTouches(long now, List<MatchEvent> events) {
        for (MatchPlayer trapped : players.values()) {
            if (trapped.state() != MatchPlayer.State.TRAPPED || now - trapped.trappedAt() < MIN_TRAPPED_MS
                    || trapped.trappedUntil() <= now) {
                continue;
            }
            List<MatchPlayer> touching = players.values().stream()
                    .filter(other -> other != trapped && other.isFree(now) && touches(other, trapped))
                    .toList();
            MatchPlayer killer = nearest(trapped,
                    touching.stream().filter(other -> other.team() != trapped.team()).toList());
            if (killer != null) {
                pop(trapped, killer, events);
                continue;
            }
            MatchPlayer saver = nearest(trapped, touching);
            if (saver != null) {
                trapped.rescue(now + ESCAPE_MS);
                saver.addSave();
                events.add(toAll(MatchMessages.PLAYER_RESCUED, new PlayerRescued(trapped.playerId(), saver.playerId())));
            }
        }
    }

    private static MatchPlayer nearest(MatchPlayer trapped, List<MatchPlayer> candidates) {
        return candidates.stream()
                .min(Comparator.comparingDouble(
                                (MatchPlayer other) -> Math.hypot(other.x() - trapped.x(), other.y() - trapped.y()))
                        .thenComparingLong(MatchPlayer::playerId))
                .orElse(null);
    }

    private static boolean touches(MatchPlayer a, MatchPlayer b) {
        return Math.abs(a.x() - b.x()) < TOUCH_DISTANCE && Math.abs(a.y() - b.y()) < TOUCH_DISTANCE;
    }

    /** @param killer 닿아서 터뜨린 사람. 시간이 지나 터졌으면 null */
    private void pop(MatchPlayer player, MatchPlayer killer, List<MatchEvent> events) {
        player.pop();
        if (killer != null) {
            killer.addKill();
        }
        events.add(toAll(MatchMessages.PLAYER_POPPED, new PlayerPopped(player.playerId(),
                killer == null ? null : killer.playerId(), killer == null ? "TIMEOUT" : "TOUCH")));
    }

    /** 남은 팀이 하나 이하면 경기를 끝낸다. 한 번에 여럿이 터진 뒤 부르므로, 마지막 사람들이 함께 터지면 ALL_DOWN이다 */
    private void finishIfDecided(List<MatchEvent> events) {
        if (phase == Phase.FINISHED) {
            return;
        }
        Set<Team> teams = teamsInPlay();
        if (teams.size() == 1) {
            finish("LAST_TEAM", events);
        } else if (teams.isEmpty()) {
            finish("ALL_DOWN", events);
        }
    }

    private Set<Team> teamsInPlay() {
        Set<Team> teams = new LinkedHashSet<>();
        for (MatchPlayer player : players.values()) {
            if (player.isInPlay()) {
                teams.add(player.team());
            }
        }
        return teams;
    }

    /**
     * GAME_OVER. 남은 팀이 하나면 그 팀이 이기고, 아니면(모두 터짐, 시간 끝에 여러 팀 생존) 모두 비긴다.
     * 나간 플레이어도 standings에 LOSE로 남긴다 (검토 중).
     */
    private void finish(String reason, List<MatchEvent> events) {
        Set<Team> teams = teamsInPlay();
        Team winner = teams.size() == 1 ? teams.iterator().next() : null;
        List<Standing> standings = new ArrayList<>();
        for (MatchPlayer player : players.values()) {
            String outcome;
            if (player.state() == MatchPlayer.State.LEFT) {
                outcome = "LOSE";
            } else if (winner == null) {
                outcome = "DRAW";
            } else {
                outcome = player.team() == winner ? "WIN" : "LOSE";
            }
            standings.add(new Standing(player.playerId(), player.nickname(), outcome, player.kills(), player.saves()));
        }
        phase = Phase.FINISHED;
        balloons.clear();
        flames.clear();
        events.add(toAll(MatchMessages.GAME_OVER, new GameOver(reason, standings)));
    }

    /** 이 연결의 플레이어. 다른 연결의 playerId면 null */
    private MatchPlayer ownPlayer(long connectionId, long playerId) {
        MatchPlayer player = players.get(playerId);
        return player != null && player.connectionId() == connectionId ? player : null;
    }

    private Balloon balloonAt(Cell cell) {
        for (Balloon balloon : balloons) {
            if (balloon.cell().equals(cell)) {
                return balloon;
            }
        }
        return null;
    }

    /** 그 칸을 덮은 물줄기. 여럿이면 터진 칸이 가까운 것, 거리가 같으면 늦게 터진 것 (클라이언트 wetOwnersAt과 같은 순서) */
    private Flame flameAt(Cell cell, long now) {
        Flame best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Flame flame : flames) {
            if (flame.until() <= now || !flame.cells().contains(cell)) {
                continue;
            }
            int distance = Math.abs(flame.center().col() - cell.col()) + Math.abs(flame.center().row() - cell.row());
            if (distance <= bestDistance) {
                best = flame;
                bestDistance = distance;
            }
        }
        return best;
    }

    private List<Long> othersThan(long connectionId) {
        return connections.stream().filter(id -> id != connectionId).toList();
    }

    private MatchEvent toAll(String type, Object body) {
        return new MatchEvent(List.copyOf(connections), type, body);
    }

    /** 놓인 물풍선. range는 놓을 때의 주인 물줄기 길이이고 나중에 바뀌지 않는다 */
    private record Balloon(int balloonId, long ownerId, Cell cell, int range, long fuseAt) {
    }

    /** 터진 물풍선이 남긴 물줄기 */
    private record Flame(int balloonId, Cell center, Set<Cell> cells, long until) {
    }
}

package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.AirplaneAppeared;
import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BalloonPlaced;
import hansung.arcade.domain.game.model.MatchMessages.ChangedTile;
import hansung.arcade.domain.game.model.MatchMessages.DroppedItem;
import hansung.arcade.domain.game.model.MatchMessages.ExplodedBalloon;
import hansung.arcade.domain.game.model.MatchMessages.GameOver;
import hansung.arcade.domain.game.model.MatchMessages.GameStarted;
import hansung.arcade.domain.game.model.MatchMessages.ItemDropped;
import hansung.arcade.domain.game.model.MatchMessages.ItemScattered;
import hansung.arcade.domain.game.model.MatchMessages.ItemTaken;
import hansung.arcade.domain.game.model.MatchMessages.ItemUsed;
import hansung.arcade.domain.game.model.MatchMessages.PlayerLeft;
import hansung.arcade.domain.game.model.MatchMessages.PlayerMoved;
import hansung.arcade.domain.game.model.MatchMessages.PlayerPopped;
import hansung.arcade.domain.game.model.MatchMessages.PlayerRescued;
import hansung.arcade.domain.game.model.MatchMessages.PlayerTrapped;
import hansung.arcade.domain.game.model.MatchMessages.RoundBegin;
import hansung.arcade.domain.game.model.MatchMessages.Standing;
import hansung.arcade.domain.game.model.MatchMessages.StartedPlayer;
import hansung.arcade.domain.game.model.MatchMessages.Stats;
import hansung.arcade.global.exception.GameException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

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
 * <p>PLAYING 동안 남은 시간이 {@link AirDrop#APPEARANCES_MS}가 되면 비행기가 나와(AIRPLANE_APPEARED) 맵을 가로지르며
 * 고른 칸에 아이템을 떨어뜨린다(ITEM_DROPPED).</p>
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
    /** 보호막이 물줄기를 막아 주는 시간 (클라이언트 PlayerGear.SHIELD_SECONDS) */
    public static final long SHIELD_MS = 3_000;
    /** 스프링 신발로 뛰어 내려앉기까지. 이 동안 공중에 있다 (클라이언트 PlayerGear.JUMP_SECONDS) */
    public static final long JUMP_MS = 400;
    /** 스프링 신발로 뛰는 거리(칸). 바로 앞 칸을 넘어 그 너머 칸에 내려앉는다 (클라이언트 ItemActions.JUMP_TILES) */
    static final int JUMP_TILES = 2;
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
    /** 맵 바닥에 놓인 아이템. 한 칸에 하나뿐이다. itemId 순서 */
    private final Map<Integer, FieldItem> items = new LinkedHashMap<>();
    /** 아이템이 나올지·무엇인지, 흩뿌릴 칸을 정하는 난수 */
    private final RandomGenerator random;
    /** 경기를 열 때의 인원 (한 연결의 1P·2P는 두 명). 블럭에서 아이템이 나올 확률을 정한다 */
    private final int playerCount;
    /** 비행기가 떨어뜨릴 칸을 고른다 */
    private final AirDrop airDrop;
    /** 아직 떨어뜨릴 칸이 남은 비행기들. 나온 순서 */
    private final List<Airplane> airplanes = new ArrayList<>();
    /** 다음에 나올 비행기의 {@link AirDrop#APPEARANCES_MS} 차례 */
    private int nextAppearance;
    private int lastAirplaneId;

    private Phase phase = Phase.LOADING;
    private long loadDeadline;
    private long playStartsAt;
    private long endsAt;
    private int lastBalloonId;
    private int lastItemId;

    /**
     * @param random 아이템 생성·흩뿌리기에 쓴다. 이 경기만 쓰도록 따로 만들어 넘긴다 (테스트는 시드를 고정한다)
     */
    public Match(long matchId, GameMap map, List<MatchPlayer> participants, RandomGenerator random) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("플레이어가 없습니다.");
        }
        this.matchId = matchId;
        this.map = map;
        this.random = Objects.requireNonNull(random);
        this.playerCount = participants.size();
        this.airDrop = new AirDrop(map, this.random, participants.size());
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
        if (owner == null || !owner.isFree(now) || owner.isAirborne(now)
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
     * ITEM_PICKUP. 인정되면 방 전체에 ITEM_TAKEN을 보낸다. 인정되지 않으면 조용히 버린다.
     * 아이템이 아직 있음, 마지막 MOVE 칸이 그 아이템 칸, 살아 있고 갇히지 않음 (Notion 명세).
     * 구출된 뒤 빠져나오는 중이어도 받는다. 클라이언트는 칸에 들어설 때 한 번만 보내므로, 여기서 버리면 아이템이 맵에 남는다.
     * lock 안에서 처리하므로 두 사람이 같은 아이템을 보내면 먼저 온 쪽만 먹고, 늦은 쪽은 아이템이 없어 버려진다.
     *
     * <p>칸은 마지막 MOVE로만 본다 (물풍선 놓기와 달리 이웃 칸 여유가 없다). 그래서 클라이언트는 아이템 칸에 들어선 위치를
     * MOVE로 먼저 보내고 ITEM_PICKUP을 보내야 한다. 같은 연결의 메시지는 보낸 차례대로 처리된다.</p>
     */
    public synchronized List<MatchEvent> pickUpItem(long connectionId, long playerId, int itemId, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING) {
            return List.of();
        }
        MatchPlayer taker = ownPlayer(connectionId, playerId);
        FieldItem item = items.get(itemId);
        if (taker == null || item == null || taker.state() != MatchPlayer.State.ALIVE
                || !taker.cell().equals(item.cell())) {
            return List.of();
        }
        return List.of(take(taker, item));
    }

    /** 아이템을 맵에서 치우고 먹인 뒤, 방 전체에 보낼 ITEM_TAKEN을 만든다 */
    private MatchEvent take(MatchPlayer taker, FieldItem item) {
        items.remove(item.itemId());
        taker.takeItem(item.type());
        Stats stats = new Stats(taker.balloonCapacity(), taker.waterRange(), taker.speedLevel());
        return toAll(MatchMessages.ITEM_TAKEN, new ItemTaken(item.itemId(), taker.playerId(), item.type().id(), stats,
                heldId(taker), taker.heldCount()));
    }

    private static String heldId(MatchPlayer player) {
        return player.heldItem() == null ? null : player.heldItem().id();
    }

    /**
     * ITEM_USE. 손에 든 쓰는 아이템을 하나 쓰고 방 전체에 ITEM_USED를 보낸다. 쓸 수 없으면 조용히 버린다 (손에 든 것도 그대로).
     * 클라이언트 {@code ItemActions.useItem}과 같은 때에만 쓸 수 있다.
     * <ul>
     *   <li>바늘: 갇혀 있을 때만 (갇힘 시간이 다 돼 터질 참이면 안 된다). 구출처럼 빠져나오기 시작하지만 save는 아무도 얻지 않는다.
     *       발밑에 아이템이 있으면 구출 때처럼 바로 줍는다.</li>
     *   <li>보호막: 살아 있고 빠져나오는 중·공중이 아니고, 이미 보호막을 쓰는 중이 아닐 때. {@link #SHIELD_MS} 동안 갇히지 않는다.</li>
     *   <li>스프링 신발: 살아 있고 빠져나오는 중·공중이 아닐 때. 마지막 MOVE 칸에서 바라보는 쪽으로 {@link #JUMP_TILES}칸 앞이
     *       맵 안의 지나갈 수 있는 칸이고 물풍선이 없어야 한다. {@link #JUMP_MS} 동안 공중에 있어 갇히지 않는다.
     *       위치는 서버가 옮기지 않고 클라이언트가 보내는 MOVE를 따른다.</li>
     * </ul>
     * 보호막·점프가 끝날 때 물줄기 칸에 서 있으면 그때 갇힌다 ({@link #tick}).
     *
     * @param item 쓰려는 아이템 id. 손에 든 것과 달라도 버린다 (클라이언트와 서버가 어긋난 것)
     */
    public synchronized List<MatchEvent> useItem(long connectionId, long playerId, String item, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING) {
            return List.of();
        }
        MatchPlayer user = ownPlayer(connectionId, playerId);
        if (user == null || user.heldItem() == null || !user.heldItem().id().equals(item)) {
            return List.of();
        }
        ItemType held = user.heldItem();
        Long durationMs = null;
        Cell landing = null;
        switch (held) {
            case NEEDLE -> {
                if (user.state() != MatchPlayer.State.TRAPPED || user.trappedUntil() <= now) {
                    return List.of();
                }
                user.rescue(now + ESCAPE_MS);
            }
            case SHIELD -> {
                if (!user.isFree(now) || user.isAirborne(now) || user.isShielded(now)) {
                    return List.of();
                }
                user.raiseShield(now + SHIELD_MS);
                durationMs = SHIELD_MS;
            }
            case SPRING_SHOE -> {
                if (!user.isFree(now) || user.isAirborne(now)) {
                    return List.of();
                }
                Facing facing = user.facing();
                Cell target = new Cell(user.cell().col() + facing.dx() * JUMP_TILES,
                        user.cell().row() + facing.dy() * JUMP_TILES);
                if (map.isSolid(target.col(), target.row()) || balloonAt(target) != null) {
                    return List.of();
                }
                user.jump(now + JUMP_MS);
                durationMs = JUMP_MS;
                landing = target;
            }
            default -> {
                return List.of();
            }
        }
        user.useHeld();
        List<MatchEvent> events = new ArrayList<>();
        events.add(toAll(MatchMessages.ITEM_USED, new ItemUsed(user.playerId(), held.id(), durationMs,
                landing == null ? null : landing.col(), landing == null ? null : landing.row(),
                heldId(user), user.heldCount())));
        if (held == ItemType.NEEDLE) {
            // 구출과 같다: 갇힌 동안은 ITEM_PICKUP을 버렸으므로, 갇힌 채 들어선 아이템 칸이면 지금 줍는다
            FieldItem underfoot = itemAt(user.cell());
            if (underfoot != null) {
                events.add(take(user, underfoot));
            }
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

    /**
     * 나가거나 끊긴 사람이 먹은 아이템은 흩뿌리지 않고 함께 사라진다. 명세(PLAYER_POPPED·ITEM_SCATTERED)는 터질 때만 다룬다.
     */
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
            items.clear();
            airplanes.clear();
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
                for (MatchPlayer player : players.values()) {
                    // 보호막·점프가 끝났을 때 물줄기 칸에 서 있으면 움직이지 않아도 갇힌다
                    if (player.protectionJustEnded(now)) {
                        trapIfWet(player, now, events);
                    }
                }
                settleTouches(now, events);
                for (MatchPlayer player : players.values()) {
                    if (player.state() == MatchPlayer.State.TRAPPED && player.trappedUntil() <= now) {
                        pop(player, null, now, events);
                    }
                }
                finishIfDecided(events);
                if (phase == Phase.PLAYING && now >= endsAt) {
                    finish("TIME_UP", events);
                }
                if (phase == Phase.PLAYING) {
                    flyAirplanes(now, events);
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
     * <p>아이템은 클라이언트 GameWorld와 같은 순서로 다룬다. 블럭을 맞히고, 다 부서진 블럭 자리에 아이템을 놓고,
     * 물줄기가 덮은 칸의 아이템을 없앤다. 블럭 자리는 물줄기가 들어가지 않은 칸이라 방금 나온 아이템은 남는다.</p>
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
        Set<Cell> wetCells = new LinkedHashSet<>();
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
            wetCells.addAll(covered);
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
        List<DroppedItem> dropped = new ArrayList<>();
        for (Cell cell : hitBlocks) {
            if (map.hitBlock(cell.col(), cell.row())) {
                Tile after = map.tileAt(cell.col(), cell.row());
                tiles.add(new ChangedTile(cell.col(), cell.row(), String.valueOf(after.symbol())));
                if (after.isGround()) {
                    dropFromBrokenBlock(cell).ifPresent(dropped::add);
                }
            }
        }
        List<Integer> removed = new ArrayList<>();
        items.values().removeIf(item -> {
            boolean wet = wetCells.contains(item.cell());
            if (wet) {
                removed.add(item.itemId());
            }
            return wet;
        });
        events.add(toAll(MatchMessages.BALLOON_EXPLODED, new BalloonExploded(exploded, tiles, dropped, removed)));

        for (MatchPlayer player : players.values()) {
            trapIfWet(player, now, events);
        }
    }

    /** 살아 있고 빠져나오는 중이 아닌 사람이 물줄기 칸에 있으면 가둔다. 보호막을 쓰는 중이거나 공중에 있으면 갇히지 않는다 */
    private void trapIfWet(MatchPlayer player, long now, List<MatchEvent> events) {
        if (!player.isFree(now) || player.ignoresWater(now)) {
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
                pop(trapped, killer, now, events);
                continue;
            }
            MatchPlayer saver = nearest(trapped, touching);
            if (saver != null) {
                trapped.rescue(now + ESCAPE_MS);
                saver.addSave();
                events.add(toAll(MatchMessages.PLAYER_RESCUED, new PlayerRescued(trapped.playerId(), saver.playerId())));
                // 갇힌 채 아이템 칸에 들어가 서 있었다면 지금 줍는다. 갇힌 동안은 ITEM_PICKUP을 버리고,
                // 클라이언트는 칸에 들어설 때 한 번만 보내므로 구출된 자리에서는 다시 보내지 않는다
                FieldItem underfoot = itemAt(trapped.cell());
                if (underfoot != null) {
                    events.add(take(trapped, underfoot));
                }
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

    /**
     * 터뜨린다. PLAYER_POPPED 바로 뒤에 먹은 아이템을 흩뿌리고, 하나라도 놓였으면 ITEM_SCATTERED를 보낸다
     * (놓을 칸이 하나도 없으면 보내지 않는다).
     *
     * @param killer 닿아서 터뜨린 사람. 시간이 지나 터졌으면 null
     */
    private void pop(MatchPlayer player, MatchPlayer killer, long now, List<MatchEvent> events) {
        Cell poppedAt = player.cell();
        player.pop();
        if (killer != null) {
            killer.addKill();
        }
        events.add(toAll(MatchMessages.PLAYER_POPPED, new PlayerPopped(player.playerId(),
                killer == null ? null : killer.playerId(), killer == null ? "TIMEOUT" : "TOUCH")));
        List<DroppedItem> scattered = scatter(player.handOverTakenItems(), now);
        if (!scattered.isEmpty()) {
            events.add(toAll(MatchMessages.ITEM_SCATTERED,
                    new ItemScattered(player.playerId(), poppedAt.col(), poppedAt.row(), scattered)));
        }
    }

    /**
     * 터진 사람이 먹은 아이템을 맵 전체의 빈 풀밭에 무작위로 흩뿌린다.
     *
     * <p>규칙은 실제 크레이지 아케이드(빌리지 개인전) 영상에서 사망 두 건, 아이템 15개를 프레임 단위로 보고 정했다.</p>
     * <ul>
     *   <li>떨어진 칸은 죽은 칸에서 맨해튼 거리 1~14칸으로 맵 전체에 가까운 칸·먼 칸이 섞여 있었다
     *       → 맵 전체 후보에서 중복 없이 균등하게 고른다. 죽은 칸 주변을 비우는 반경도 없다.</li>
     *   <li>15개 모두 풀밭에 떨어졌고 도로·횡단보도에는 하나도 없었다 (균등 랜덤이었다면 약 0.5%)
     *       → 후보는 일반 바닥({@link Tile#FLOOR})만이다. 집·나무·블럭·덤불, 물풍선·아이템이 있는 칸은 뺀다.</li>
     *   <li>집·나무를 넘어 도로 건너편까지도, 바로 옆 칸에도 떨어졌다. 사방이 막힌 곳에서 죽은 장면은 찾지 못했다
     *       → 막힌 곳 안에만(또는 밖에만) 뿌릴 근거가 없으므로, 갇혔는지·길이 이어져 있는지는 보지 않는다.</li>
     *   <li>처음 먹은 롤러 셋이 거리순 1·5·6등이었다 (오래전 것이 가까이도, 멀리도 아님)
     *       → 먹은 순서와 떨어지는 거리는 관계없다. 섞은 후보 칸과 짝지으므로 이미 그렇고, 아이템 목록도 섞어
     *       후보가 모자랄 때 사라지는 아이템까지 먹은 순서와 관계없게 한다.</li>
     * </ul>
     * <p>물줄기가 남은 칸도 뺀다. 클라이언트는 물줄기 칸의 아이템을 바로 지우므로(GameWorld) 놓으면 화면과 어긋난다.
     * 경기 중인 사람(살아 있거나 갇힌 사람)이 서 있는 칸도 뺀다. 클라이언트는 아이템 칸에 들어설 때 한 번만 ITEM_PICKUP을
     * 보내므로, 발밑에 떨어진 아이템은 그 사람이 칸을 떠났다 돌아오기 전까지 아무도 줍지 못하고 남는다.
     * 후보가 모자라면 놓을 수 있는 만큼만 놓고 나머지는 사라진다.</p>
     *
     * @return 놓인 아이템. 흩뿌릴 아이템이나 후보 칸이 없으면 비어 있다
     */
    private List<DroppedItem> scatter(List<ItemType> taken, long now) {
        if (taken.isEmpty()) {
            return List.of();
        }
        Set<Cell> occupied = new HashSet<>();
        for (MatchPlayer player : players.values()) {
            if (player.state() == MatchPlayer.State.ALIVE || player.state() == MatchPlayer.State.TRAPPED) {
                occupied.add(player.cell());
            }
        }
        List<Cell> candidates = new ArrayList<>();
        for (int row = 0; row < map.rows(); row++) {
            for (int col = 0; col < map.columns(); col++) {
                Cell cell = new Cell(col, row);
                if (map.tileAt(col, row) == Tile.FLOOR && balloonAt(cell) == null && itemAt(cell) == null
                        && flameAt(cell, now) == null && !occupied.contains(cell)) {
                    candidates.add(cell);
                }
            }
        }
        List<ItemType> shuffled = new ArrayList<>(taken);
        Collections.shuffle(shuffled, random);
        Collections.shuffle(candidates, random);
        int count = Math.min(shuffled.size(), candidates.size());
        List<DroppedItem> placed = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            placed.add(placeItem(candidates.get(i), shuffled.get(i)));
        }
        return placed;
    }

    /**
     * 다 부서진 블럭 자리에 아이템을 놓는다. 맵의 고정 드롭이 있으면 그것을, 없으면 클라이언트와 같은 비율표로 정한다.
     * 나올 확률은 경기 인원에 맞춘다 ({@link ItemDrops#dropChanceFor}). 고정 드롭에는 난수를 쓰지 않는다.
     * 쓰는 아이템(바늘·보호막·스프링 신발)도 놓는다.
     */
    private Optional<DroppedItem> dropFromBrokenBlock(Cell cell) {
        return map.takeFixedDrop(cell.col(), cell.row())
                .or(() -> ItemDrops.roll(random, playerCount))
                .map(item -> placeItem(cell, item));
    }

    private DroppedItem placeItem(Cell cell, ItemType type) {
        FieldItem item = new FieldItem(++lastItemId, type, cell);
        items.put(item.itemId(), item);
        return new DroppedItem(item.itemId(), cell.col(), cell.row(), type.id());
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
        items.clear();
        airplanes.clear();
        events.add(toAll(MatchMessages.GAME_OVER, new GameOver(reason, standings)));
    }

    /**
     * 남은 시간이 출현 시각이 된 비행기를 띄우고, 날고 있는 비행기가 지나친 칸에 아이템을 떨어뜨린다.
     * PLAYING일 때만 부른다. 경기가 끝나면 새로 나오지도, 떨어뜨리지도 않는다 (클라이언트 {@code AirDrop}과 같다).
     * 출현 시각보다 {@link AirDrop#LATE_SKIP_MS} 이상 늦은 tick이면 그 비행기는 건너뛴다.
     */
    private void flyAirplanes(long now, List<MatchEvent> events) {
        List<Long> appearances = AirDrop.APPEARANCES_MS;
        while (nextAppearance < appearances.size() && endsAt - now <= appearances.get(nextAppearance)) {
            long appearAt = endsAt - appearances.get(nextAppearance);
            nextAppearance++;
            if (now - appearAt < AirDrop.LATE_SKIP_MS) {
                launchAirplane(now, events);
            }
        }
        for (Iterator<Airplane> it = airplanes.iterator(); it.hasNext(); ) {
            Airplane airplane = it.next();
            while (!airplane.ahead.isEmpty() && airplane.ahead.peek().passAt() <= now) {
                release(airplane.airplaneId, airplane.ahead.poll().drop(), now, events);
            }
            if (airplane.ahead.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 비행기를 띄운다. 떨어뜨릴 칸은 지금 맵으로 고르고({@link AirDrop#plan}), 지나가는 순서로 줄 세운다 */
    private void launchAirplane(long now, List<MatchEvent> events) {
        int airplaneId = ++lastAirplaneId;
        Facing heading = AirDrop.headingOf(airplaneId - 1);
        List<PassingDrop> drops = new ArrayList<>();
        for (AirDrop.Drop drop : airDrop.plan()) {
            drops.add(new PassingDrop(drop, now + AirDrop.passMs(map, heading, drop.col())));
        }
        drops.sort(Comparator.comparingLong(PassingDrop::passAt));
        airplanes.add(new Airplane(airplaneId, new ArrayDeque<>(drops)));
        events.add(toAll(MatchMessages.AIRPLANE_APPEARED, new AirplaneAppeared(airplaneId, heading.name())));
    }

    /**
     * 비행기가 drop 칸의 열을 지나는 순간. 칸이 비어 있으면 아이템을 놓고 ITEM_DROPPED로 알린다.
     * 서버는 이때부터 아이템이 맵에 있다고 본다. 클라이언트는 fallMs 동안 떨어지는 모습을 보여 준 뒤 놓는다.
     */
    private void release(int airplaneId, AirDrop.Drop drop, long now, List<MatchEvent> events) {
        Cell cell = new Cell(drop.col(), drop.row());
        if (!canDropAt(cell, now)) {
            return;
        }
        DroppedItem placed = placeItem(cell, drop.item());
        events.add(toAll(MatchMessages.ITEM_DROPPED, new ItemDropped(airplaneId, placed.itemId(), placed.col(),
                placed.row(), placed.item(), AirDrop.fallMs(cell.row()))));
    }

    /**
     * 비행기가 지나가는 순간 이 칸에 떨어뜨릴 수 있는지. 지나간 시각이 아니라 그 뒤 처음 처리하는 tick 시각(now)의 칸을 본다
     * (tick이 수십 ms마다라 많아야 한 tick 차이다). 아래 중 하나라도 있으면 떨어뜨리지 않는다 (덤불 칸은 떨어뜨린다).
     * <ul>
     *   <li>막힌 칸 (블럭·집 등 지나갈 수 없는 칸, 맵 바깥)</li>
     *   <li>물풍선, 이미 놓인 아이템</li>
     *   <li>물줄기가 남은 칸. 클라이언트는 물줄기 칸의 아이템을 바로 지운다</li>
     *   <li>경기 중인 사람(살아 있거나 갇힌 사람)의 몸이 조금이라도 걸친 칸</li>
     * </ul>
     */
    private boolean canDropAt(Cell cell, long now) {
        if (map.isSolid(cell.col(), cell.row()) || balloonAt(cell) != null || itemAt(cell) != null
                || flameAt(cell, now) != null) {
            return false;
        }
        for (MatchPlayer player : players.values()) {
            if (player.isInPlay() && player.overlaps(cell)) {
                return false;
            }
        }
        return true;
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

    private FieldItem itemAt(Cell cell) {
        for (FieldItem item : items.values()) {
            if (item.cell().equals(cell)) {
                return item;
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

    /** 맵 바닥에 놓인 아이템 */
    private record FieldItem(int itemId, ItemType type, Cell cell) {
    }

    /** 날고 있는 비행기 */
    private static final class Airplane {

        private final int airplaneId;
        /** 아직 지나가지 않은 칸. 지나가는 순서다 */
        private final Deque<PassingDrop> ahead;

        Airplane(int airplaneId, Deque<PassingDrop> ahead) {
            this.airplaneId = airplaneId;
            this.ahead = ahead;
        }
    }

    /** 비행기가 떨어뜨리려는 칸과, 떨어뜨리는 자리가 그 열을 지나는 시각 */
    private record PassingDrop(AirDrop.Drop drop, long passAt) {
    }
}

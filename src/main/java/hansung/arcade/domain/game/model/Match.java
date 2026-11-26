package hansung.arcade.domain.game.model;

import hansung.arcade.domain.game.model.MatchMessages.AirplaneAppeared;
import hansung.arcade.domain.game.model.MatchMessages.BalloonExploded;
import hansung.arcade.domain.game.model.MatchMessages.BalloonKicked;
import hansung.arcade.domain.game.model.MatchMessages.BalloonPlaced;
import hansung.arcade.domain.game.model.MatchMessages.BalloonStopped;
import hansung.arcade.domain.game.model.MatchMessages.BalloonThrown;
import hansung.arcade.domain.game.model.MatchMessages.BoxMoved;
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
import java.util.HashMap;
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
 * <p>PLAYING 동안 남은 시간이 {@link AirDrop#APPEARANCES_MS}가 되면 비행기가 나와(AIRPLANE_APPEARED) 맵의 한 줄을 따라
 * 가로지르며 그 줄의 고른 칸에 아이템을 떨어뜨린다(ITEM_DROPPED). 아이템이 칸에 닿는 순간 그 칸에 선 사람이 있으면 바로 먹인다(ITEM_TAKEN).</p>
 *
 * <p>장갑을 먹은 사람은 물풍선을 던지고(BALLOON_THROW → BALLOON_THROWN), 신발·빨간 악마를 먹은 사람은 막힌 앞 칸의 물풍선을
 * 찬다(BALLOON_KICK → BALLOON_KICKED → BALLOON_STOPPED). 나는·미끄러지는 물풍선은 타이머가 옮긴다.</p>
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
    /** 터진 뒤 쓰러지는 연출 시간. 이 동안은 몸이 칸을 막는다 (클라이언트 Player.DYING_JUMP_SECONDS + DYING_BLINK_SECONDS) */
    static final long DYING_MS = 2_000;
    /** 밀린 상자가 한 칸 미끄러지는 시간. 이 동안은 그 상자를 다시 밀 수 없다 (클라이언트 BoxPusher.SLIDE_SECONDS) */
    static final long SLIDE_MS = 200;
    /** 갇힌 뒤 적어도 이만큼(타이머 한 번)은 구해지거나 터지지 않는다. 갇힌 모습이 한 번은 보이도록 한다 */
    static final long MIN_TRAPPED_MS = 20;
    /** 두 캐릭터 중심이 가로·세로 모두 이보다 가까우면 닿은 것 (클라이언트 Player.TOUCH_DISTANCE) */
    static final double TOUCH_DISTANCE = GameMap.TILE_SIZE / 2.0;
    /**
     * 물줄기에 맞았다고 보는 겹침 비율 (클라이언트 BattleRules.SOAK_SHARE). 충돌 박스가 걸친 가로 줄 하나나 세로 열 하나에서
     * 물줄기 칸과 겹친 넓이가 박스 넓이의 66% 이상이어야 갇힌다. 그래서 칸 경계에 걸쳐 서면 맞지 않을 수 있다(걸치기)
     */
    static final double SOAK_SHARE = 0.66;
    /** 나눠 잰 비율이 경계에서 아주 조금 모자랄 수 있어 그만큼은 넘은 것으로 친다 (클라이언트와 같다) */
    private static final double SHARE_SLACK = 1e-9;
    /** 보호막이 물줄기를 막아 주는 시간 (클라이언트 PlayerGear.SHIELD_SECONDS) */
    public static final long SHIELD_MS = 3_000;
    /** 스프링 신발로 뛰어 내려앉기까지 (클라이언트 PlayerGear.JUMP_SECONDS) */
    public static final long JUMP_MS = 400;
    /** 스프링 신발로 뛰는 거리(칸). 바로 앞 칸을 넘어 그 너머 칸에 내려앉는다 (클라이언트 ItemActions.JUMP_TILES) */
    static final int JUMP_TILES = 2;
    /** 장갑으로 던진 물풍선이 내려앉는 거리. 원래 칸을 0번째로 세어 이만큼째 칸이다 (클라이언트 BalloonField.THROW_DISTANCE) */
    static final int THROW_TILES = 6;
    /** 던진 물풍선이 한 칸을 나는 시간 (클라이언트 BalloonField.FLIGHT_SECONDS_PER_TILE) */
    static final long FLIGHT_MS_PER_TILE = 60;
    /** 아주 가까이 내려앉아도 이만큼은 난다 (클라이언트 BalloonField.MIN_FLIGHT_SECONDS) */
    static final long MIN_FLIGHT_MS = 200;
    /** 찬 물풍선이 한 칸을 미끄러지는 시간. 초당 5칸이다 (클라이언트 BalloonField.SLIDE_TILES_PER_SECOND) */
    static final long BALLOON_SLIDE_MS_PER_TILE = 200;
    /** 미끄러지는 물풍선이 칸 가운데에서 칸 경계까지, 또는 칸 경계에서 다음 칸 가운데까지 가는 시간 */
    private static final long HALF_SLIDE_MS = BALLOON_SLIDE_MS_PER_TILE / 2;

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
    /** 밀려서 미끄러지는 중인 상자: 도착 칸 → 다 미끄러지는 시각 */
    private final Map<Cell, Long> slidingUntil = new HashMap<>();
    /** 아이템이 나올지·무엇인지, 흩뿌릴 칸을 정하는 난수 */
    private final RandomGenerator random;
    /** 경기를 열 때의 인원 (한 연결의 1P·2P는 두 명). 블럭에서 아이템이 나올 확률을 정한다 */
    private final int playerCount;
    /** 비행기가 떨어뜨릴 칸을 고른다 */
    private final AirDrop airDrop;
    /** 아직 떨어뜨릴 칸이 남은 비행기들. 나온 순서 */
    private final List<Airplane> airplanes = new ArrayList<>();
    /** 비행기에서 떨어져 아직 칸에 닿지 않은 아이템. 떨어뜨린 순서 */
    private final List<FallingDrop> falling = new ArrayList<>();
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
     * 스프링 신발로 공중에 있는 동안은 서버가 위치를 정하므로 버린다.
     */
    public synchronized List<MatchEvent> move(long connectionId, long playerId, double x, double y, Facing facing,
                                              boolean moving, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING || facing == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 0 || y < 0 || x >= map.pixelWidth() || y >= map.pixelHeight()) {
            return List.of();
        }
        MatchPlayer mover = ownPlayer(connectionId, playerId);
        if (mover == null || !mover.isInPlay() || mover.isAirborne(now)) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        // 타이머보다 먼저 왔으면 내려앉기·보호막 끝을 먼저 처리한다. 내려앉은 칸의 아이템은 옮기기 전에 줍는다
        endItemEffectsIfDue(mover, now, events);
        mover.moveTo(x, y, facing, moving);

        events.add(new MatchEvent(othersThan(connectionId), MatchMessages.PLAYER_MOVED,
                new PlayerMoved(playerId, x, y, facing.name(), moving)));
        trapIfWet(mover, now, events);
        settleTouches(now, events);
        finishIfDecided(events);
        return events;
    }

    /**
     * BALLOON_PLACE. 놓을 수 없으면 조용히 버린다.
     * 살아 있고 갇히지 않음, 마지막 MOVE 칸이나 그 상하좌우 칸(통신 지연 여유), 지나갈 수 있고 물풍선이 차지하지 않은 칸
     * ({@link #holds}: 던진 물풍선이 내려앉을 칸, 미끄러지는 물풍선이 막 들어가려는 칸 포함), 놓아 둔 수가 능력치보다 적음.
     * 물줄기가 남은 칸에 놓으면 바로 터진다 (클라이언트 BalloonField.place와 같다).
     */
    public synchronized List<MatchEvent> placeBalloon(long connectionId, long playerId, int col, int row, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING) {
            return List.of();
        }
        MatchPlayer owner = ownPlayer(connectionId, playerId);
        if (owner == null) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        // 타이머보다 먼저 왔으면 내려앉기·보호막 끝을 먼저 처리한다 (물줄기 위에 내려앉았으면 갇혀 놓지 못한다)
        endItemEffectsIfDue(owner, now, events);
        Cell cell = new Cell(col, row);
        if (!owner.isFree(now)
                || owner.cell().distanceTo(cell) > 1
                || map.isSolid(col, row) || holds(cell)
                || owner.placedBalloons() >= owner.balloonCapacity()) {
            return events;
        }
        boolean wet = flameAt(cell, now) != null;
        long fuseMs = wet ? 0 : FUSE_MS;
        Balloon balloon = new Balloon(++lastBalloonId, owner.playerId(), cell, owner.waterRange(), now + fuseMs);
        balloons.add(balloon);
        owner.balloonPlaced();
        events.add(toAll(MatchMessages.BALLOON_PLACED, new BalloonPlaced(balloon.balloonId(), owner.playerId(),
                col, row, balloon.range(), fuseMs)));
        if (wet) {
            // 갇힌 사람의 닿음 판정은 MIN_TRAPPED_MS 뒤에 한다
            explodeNow(now, events);
        }
        return events;
    }

    /**
     * ITEM_PICKUP. 인정되면 방 전체에 ITEM_TAKEN을 보낸다. 인정되지 않으면 조용히 버린다.
     * 아이템이 아직 있음, 마지막 MOVE 칸이 그 아이템 칸, 살아 있고 갇히지 않음 (Notion 명세), 스프링 신발로 공중에 있지 않음.
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
        if (taker == null) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        // 타이머보다 먼저 왔으면 내려앉기·보호막 끝을 먼저 처리한다 (내려앉은 칸의 아이템은 거기서 줍는다)
        endItemEffectsIfDue(taker, now, events);
        FieldItem item = items.get(itemId);
        if (item == null || taker.state() != MatchPlayer.State.ALIVE || taker.isAirborne(now)
                || !taker.cell().equals(item.cell())) {
            return events;
        }
        events.add(take(taker, item));
        return events;
    }

    /** 아이템을 맵에서 치우고 먹인 뒤(액티브는 손에 든다), 방 전체에 보낼 ITEM_TAKEN을 만든다 */
    private MatchEvent take(MatchPlayer taker, FieldItem item) {
        items.remove(item.itemId());
        taker.takeItem(item.type());
        Stats stats = new Stats(taker.balloonCapacity(), taker.waterRange(), taker.speedLevel());
        return toAll(MatchMessages.ITEM_TAKEN, new ItemTaken(item.itemId(), taker.playerId(), item.type().id(), stats,
                heldId(taker), taker.heldCount()));
    }

    /** 손에 든 액티브 아이템의 id. 빈손이면 null */
    private static String heldId(MatchPlayer player) {
        return player.heldItem() == null ? null : player.heldItem().id();
    }

    /** 살아 있는 사람이 선 칸에 아이템이 있으면 줍는다. 갇혔다가 풀리거나 내려앉아 칸에 "들어가지" 않고 서게 됐을 때 부른다 */
    private void pickUpUnderfoot(MatchPlayer player, List<MatchEvent> events) {
        FieldItem underfoot = itemAt(player.cell());
        if (player.state() == MatchPlayer.State.ALIVE && underfoot != null) {
            events.add(take(player, underfoot));
        }
    }

    /**
     * ITEM_USE. 손에 든 액티브 아이템을 쓴다. 쓰면 효과를 적용하고 방 전체에 ITEM_USED를 보낸다.
     * 손에 든 것과 다르거나 지금 쓸 수 없으면 조용히 버린다 (Notion 명세: 에러 코드 없음). 아이템은 그대로 남는다.
     * 조건과 효과는 클라이언트 {@code ItemActions.useItem}과 같다.
     *
     * <ul>
     *   <li>바늘: 갇혀 있을 때만. 구출처럼 {@link #ESCAPE_MS} 동안 빠져나오고, save는 아무도 얻지 않는다.
     *       갇힌 채 아이템 칸에 서 있었으면 ITEM_USED 바로 뒤에 그 아이템을 줍는다.</li>
     *   <li>보호막: 땅에 선 보통 상태이고 이미 쓰는 중이 아닐 때. {@link #SHIELD_MS} 동안 물줄기에 갇히지 않는다.</li>
     *   <li>스프링 신발: 땅에 선 보통 상태일 때. 마지막 MOVE의 칸과 방향에서 {@link #JUMP_TILES}칸 앞 칸에 내려앉는다.
     *       바로 앞 칸은 무엇이든 넘고, 내려앉을 칸이 맵 밖·막힌 칸이거나 물풍선이 차지했으면({@link #holds}) 쓰지 않는다.
     *       {@link #JUMP_MS} 동안 공중에 있다 (물줄기에 맞지 않고, MOVE·물풍선·줍기를 받지 않는다).</li>
     * </ul>
     *
     * <p>스프링 신발의 방향은 서버가 따로 셈하지 않는다 (클라이언트는 빠져나오기가 끝나면 아래를 보게 바꾼다).
     * 그래서 클라이언트는 방향을 바꾼 뒤 ITEM_USE 전에 MOVE를 먼저 보내야 한다. 같은 연결의 메시지는 차례대로 처리된다.</p>
     *
     * @param item 쓰려는 아이템. 모르는 id라 null이어도 조용히 버린다
     */
    public synchronized List<MatchEvent> useItem(long connectionId, long playerId, ItemType item, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING) {
            return List.of();
        }
        MatchPlayer user = ownPlayer(connectionId, playerId);
        if (user == null || item == null || !user.isInPlay()) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        endItemEffectsIfDue(user, now, events);
        if (user.heldItem() != item || !applyItem(user, item, now)) {
            return events;
        }
        user.useHeldItem();
        Long durationMs = switch (item) {
            case SHIELD -> SHIELD_MS;
            case SPRING_SHOE -> JUMP_MS;
            default -> null;
        };
        Cell landing = item == ItemType.SPRING_SHOE ? user.cell() : null;
        events.add(toAll(MatchMessages.ITEM_USED, new ItemUsed(user.playerId(), item.id(), durationMs,
                landing == null ? null : landing.col(), landing == null ? null : landing.row(), heldId(user),
                user.heldCount())));
        if (item == ItemType.NEEDLE) {
            // 클라이언트는 갇힌 동안 보낸 ITEM_PICKUP이 버려졌어도 그 칸에서 다시 보내지 않는다 (구출과 같다)
            pickUpUnderfoot(user, events);
        }
        return events;
    }

    /** @return 지금 쓸 수 있어 효과를 적용했는지 */
    private boolean applyItem(MatchPlayer user, ItemType item, long now) {
        switch (item) {
            case NEEDLE -> {
                if (user.state() != MatchPlayer.State.TRAPPED || user.trappedUntil() <= now) {
                    return false;
                }
                user.rescue(now + ESCAPE_MS);
                return true;
            }
            case SHIELD -> {
                if (!user.isFree(now) || user.isShielded(now)) {
                    return false;
                }
                user.raiseShield(now + SHIELD_MS);
                return true;
            }
            case SPRING_SHOE -> {
                Cell landing = new Cell(user.cell().col() + user.facing().dx() * JUMP_TILES,
                        user.cell().row() + user.facing().dy() * JUMP_TILES);
                // 클라이언트 BalloonField.holds와 같다: 던진 물풍선이 내려앉을 칸, 미끄러지는 물풍선이 막 들어가려는 칸도 막는다
                if (!user.isFree(now) || map.isSolid(landing.col(), landing.row()) || holds(landing)) {
                    return false;
                }
                user.jumpTo(landing, now + JUMP_MS);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 시간이 다 된 아이템 효과를 끝낸다. 타이머가 부르고, 타이머보다 먼저 온 그 사람의 입력(MOVE·BALLOON_PLACE·ITEM_PICKUP·
     * ITEM_USE)도 처리하기 전에 부른다.
     * 내려앉으면(클라이언트 PlayerGear.advance) 물줄기가 남은 칸이면 갇히고, 아니면 그 칸의 아이템을 줍는다
     * (클라이언트는 내려앉은 칸에서 아이템을 바로 먹는다). 보호막이 끝난 순간 물줄기 위에 서 있으면 갇힌다.
     * 물줄기는 처리하는 지금이 아니라 효과가 끝난 순간의 것으로 본다 (그 사이에 물줄기가 사라졌어도 클라이언트처럼 갇힌다).
     */
    private void endItemEffectsIfDue(MatchPlayer player, long now, List<MatchEvent> events) {
        if (player.isLandingDue(now)) {
            player.land();
            trapIfWetAt(player, player.landsAt(), now, events);
            pickUpUnderfoot(player, events);
        }
        if (player.isShieldDue(now)) {
            player.lowerShield();
            trapIfWetAt(player, player.shieldUntil(), now, events);
        }
    }

    /**
     * BOX_PUSH. 밀 수 있으면 서버 맵에서 상자를 한 칸 옮기고 방 전체에 BOX_MOVED를 보낸다. 밀 수 없으면 조용히 버린다.
     *
     * <p>밀 수 있는 조건은 클라이언트 {@code BoxPusher.push}와 같다.</p>
     * <ul>
     *   <li>미는 사람이 살아 있고 갇히지 않았고 빠져나오는 중도 아니다 (클라이언트 PlayerState.NORMAL).</li>
     *   <li>마지막 MOVE가 멈춘 상태이고 direction 쪽을 보며, 그 칸(몸 가운데 칸)의 direction 쪽 바로 다음 칸이 (col, row)다.
     *       클라이언트는 바로 앞 상자에 막혀 움직이지 못한 채 같은 쪽을 누르고 있을 때만 민다.</li>
     *   <li>(col, row)가 밀리는 상자({@link Tile#isPushable}: 상자 P, 철로 보강된 상자 c·b·a)이고,
     *       밀려 들어와 아직 미끄러지는 중({@link #SLIDE_MS})이 아니다. 보강된 상자는 남은 맞을 횟수째 옮겨진다.</li>
     *   <li>밀려 갈 칸이 상자를 받을 수 있다 ({@link #canReceiveBox}).</li>
     * </ul>
     * <p>막힌 채 {@code BoxPusher.PUSH_SECONDS}(0.25초) 동안 눌렀는지는 다시 재지 않는다. MOVE는 바뀔 때만 오고 도착 간격이
     * 통신 지연에 따라 흔들려서, 서버 시계로 재면 제대로 민 요청도 버릴 수 있다.</p>
     *
     * <p>맵 칸은 미는 순간 옮긴다. 클라이언트가 그림을 미끄러뜨리는 동안에도 상자는 이미 새 칸에 있으므로, 그 사이 터진 물줄기는
     * 비워진 원래 칸을 지나가 새 칸의 상자를 맞힌다. 고정 드롭도 상자를 따라간다 ({@link GameMap#moveBlock}).</p>
     */
    public synchronized List<MatchEvent> pushBox(long connectionId, long playerId, int col, int row, Facing direction,
                                                 long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING || direction == null) {
            return List.of();
        }
        MatchPlayer pusher = ownPlayer(connectionId, playerId);
        Cell box = new Cell(col, row);
        Long slidesUntil = slidingUntil.get(box);
        if (pusher == null || !pusher.isFree(now) || pusher.moving() || pusher.facing() != direction
                || !pusher.cell().equals(new Cell(col - direction.dx(), row - direction.dy()))
                || !map.isInside(col, row) || !map.tileAt(col, row).isPushable()
                || (slidesUntil != null && now < slidesUntil)) {
            return List.of();
        }
        Cell to = new Cell(col + direction.dx(), row + direction.dy());
        if (!canReceiveBox(to, now)) {
            return List.of();
        }
        map.moveBlock(col, row, to.col(), to.row());
        slidingUntil.values().removeIf(until -> until <= now);
        slidingUntil.put(to, now + SLIDE_MS);
        return List.of(toAll(MatchMessages.BOX_MOVED, new BoxMoved(col, row, to.col(), to.row())));
    }

    /**
     * 밀린 상자를 받을 수 있는 칸인지 (클라이언트 BoxPusher.canReceive와 GameWorld.BOX_BLOCKERS).
     * 맵 안의 바닥 타일이어야 하고(블럭·덤불·집 등이 없음), 물풍선({@link #holds}: 던진 물풍선이 내려앉을 칸, 미끄러지는 물풍선이
     * 막 들어가려는 칸 포함)·아이템·물줄기와 몸이 조금이라도 걸친 사람이 없어야 한다.
     * 쓰러지는 연출 중인 사람도 막는다. 물줄기가 남은 칸에 들어간 상자는 바로 부서질 수 있는데,
     * 거기서 나온 아이템이 그 물줄기에 바로 사라지므로 밀지 않는다.
     * 스프링 신발로 내려앉을 칸도 막는다 (클라이언트 CellOccupancy.Occupant.JUMP_LANDING). 서버는 뛰는 순간 위치를
     * 내려앉을 칸 가운데로 옮기므로({@link MatchPlayer#jumpTo}) 아래 몸 검사가 그 칸을 막는다.
     */
    private boolean canReceiveBox(Cell cell, long now) {
        if (!map.isInside(cell.col(), cell.row()) || !map.tileAt(cell.col(), cell.row()).isGround()
                || holds(cell) || itemAt(cell) != null || flameAt(cell, now) != null) {
            return false;
        }
        for (MatchPlayer player : players.values()) {
            if (player.isOnMap(now) && player.overlaps(cell)) {
                return false;
            }
        }
        return true;
    }

    /**
     * BALLOON_THROW. 장갑으로 물풍선을 던진다. 던지면 방 전체에 BALLOON_THROWN을 보낸다. 던질 수 없으면 조용히 버린다.
     *
     * <p>던질 수 있는 조건은 클라이언트 {@code ItemActions.pressBalloonKey}와 같다.</p>
     * <ul>
     *   <li>던지는 사람이 장갑을 먹었고, 살아 있고 갇히지 않았으며 빠져나오는 중도 공중도 아니다 (클라이언트 PlayerState.NORMAL).</li>
     *   <li>마지막 MOVE의 방향이 direction이고, (col, row)가 그 MOVE 칸(몸 가운데 칸)이거나 direction 쪽 바로 다음 칸이다.
     *       걷는 중이어도 던진다. 클라이언트는 선 칸의 물풍선을 먼저 고르지만, 서버는 두 칸 가운데 어느 쪽이든 받는다
     *       (통신 지연으로 발밑 물풍선이 클라이언트에 늦게 보일 수 있다).</li>
     *   <li>(col, row)에 가만히 놓인 물풍선이 있다. 누구 물풍선이든 던진다. 미끄러지거나 나는 물풍선은 던지지 않는다.</li>
     *   <li>내려앉을 칸({@link #landingCell})이 제자리가 아니다.</li>
     * </ul>
     * <p>물풍선은 거리에 맞춰 한 칸에 {@link #FLIGHT_MS_PER_TILE}씩(적어도 {@link #MIN_FLIGHT_MS}) 난다. 나는 동안은 어느 칸에도
     * 없어서 길을 막지 않고, 물줄기가 지나가며 연쇄로 터지지도 않는다. 다만 내려앉을 칸은 차지한 것으로 친다 ({@link #holds}).
     * 퓨즈는 그대로 흘러, 나는 사이 다 되면 내려앉자마자 터진다. 내려앉는 일은 타이머가 한다 ({@link #land}).</p>
     */
    public synchronized List<MatchEvent> throwBalloon(long connectionId, long playerId, int col, int row,
                                                      Facing direction, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING || direction == null) {
            return List.of();
        }
        MatchPlayer thrower = ownPlayer(connectionId, playerId);
        if (thrower == null) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        // 타이머보다 먼저 왔으면 그때까지의 물풍선 이동·폭발을 먼저 처리한다. 퓨즈가 다 된 물풍선은 던지지 않고 터지고,
        // 내려앉을 때가 된 물풍선은 내려앉아 던질 수 있다 (클라이언트도 물풍선을 옮기고 터뜨린 뒤 물풍선 키를 본다)
        explodeNow(now, events);
        // 내려앉기·보호막 끝도 먼저 처리한다 (물줄기 위에 내려앉았으면 갇혀 던지지 못한다)
        endItemEffectsIfDue(thrower, now, events);
        if (phase != Phase.PLAYING) {
            return events;
        }
        Cell from = new Cell(col, row);
        if (!thrower.isFree(now) || !thrower.canThrowBalloons() || thrower.facing() != direction
                || !(from.equals(thrower.cell()) || from.equals(ahead(thrower.cell(), direction, 1)))) {
            return events;
        }
        Balloon balloon = restingAt(from);
        if (balloon == null) {
            return events;
        }
        Cell landing = landingCell(from, direction, THROW_TILES, balloon);
        if (landing.equals(from)) {
            return events;
        }
        events.add(fly(balloon, thrower.playerId(), direction, landing, now));
        return events;
    }

    /**
     * BALLOON_KICK. 신발·빨간 악마로 막힌 앞 칸의 물풍선을 찬다. 미끄러지기 시작하면 방 전체에 BALLOON_KICKED를 보낸다.
     * 찰 수 없으면 조용히 버린다.
     *
     * <p>찰 수 있는 조건은 클라이언트 {@code ItemActions.kick}과 같다.</p>
     * <ul>
     *   <li>차는 사람이 신발이나 빨간 악마를 먹었고, 살아 있고 갇히지 않았으며 빠져나오는 중도 공중도 아니다.</li>
     *   <li>마지막 MOVE가 멈춘 상태이고 direction 쪽을 보며, 그 칸(몸 가운데 칸)의 direction 쪽 바로 다음 칸이 (col, row)다.
     *       클라이언트는 그 물풍선에 막혀 움직이지 못한 채 그쪽을 누를 때 찬다.</li>
     *   <li>(col, row)에 가만히 놓인 물풍선이 있고(누구 것이든), 차는 사람의 몸이 그 칸에 걸쳐 있지 않다.
     *       걸쳐 있으면 놓는 순간 그 칸에 있어 아직 지나갈 수 있는 사람이라 막히지 않았다 (클라이언트 WaterBalloon.lets).</li>
     *   <li>미끄러질 수 있다 ({@link #keepsSliding}). 못 미끄러지는데 바로 앞이 그쪽으로 가시 난 블럭이면 그 자리에서 바로 터진다
     *       (BALLOON_EXPLODED만 보낸다).</li>
     * </ul>
     * <p>미끄러지는 일은 타이머가 한다 ({@link #slide}).</p>
     */
    public synchronized List<MatchEvent> kickBalloon(long connectionId, long playerId, int col, int row,
                                                     Facing direction, long now) {
        startPlayIfDue(now);
        if (phase != Phase.PLAYING || direction == null) {
            return List.of();
        }
        MatchPlayer kicker = ownPlayer(connectionId, playerId);
        if (kicker == null) {
            return List.of();
        }
        List<MatchEvent> events = new ArrayList<>();
        // 던지기와 같다: 그때까지의 물풍선 이동·폭발과 내려앉기·보호막 끝을 먼저 처리한다
        explodeNow(now, events);
        endItemEffectsIfDue(kicker, now, events);
        if (phase != Phase.PLAYING) {
            return events;
        }
        Cell cell = new Cell(col, row);
        if (!kicker.isFree(now) || !kicker.canPushBalloons() || kicker.moving() || kicker.facing() != direction
                || !cell.equals(ahead(kicker.cell(), direction, 1)) || kicker.overlaps(cell)) {
            return events;
        }
        Balloon balloon = restingAt(cell);
        if (balloon == null) {
            return events;
        }
        if (keepsSliding(balloon, direction)) {
            balloon.startSliding(direction, now + HALF_SLIDE_MS);
            events.add(toAll(MatchMessages.BALLOON_KICKED, new BalloonKicked(balloon.balloonId(), kicker.playerId(),
                    col, row, direction.name())));
        } else if (facesThorn(cell, direction)) {
            balloon.burstBy(now);
            explodeNow(now, events);
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
            falling.clear();
            return;
        }
        // 한 팀만 남았으면 로딩 중이어도 ROUND_BEGIN 없이 바로 끝낸다
        finishIfDecided(events);
        beginRoundIfAllLoaded(now, events);
    }

    /**
     * 타이머가 자주(수십 ms마다) 부른다. 로딩 시간 초과, 카운트다운 끝, 던진·찬 물풍선 옮기기, 물풍선 폭발,
     * 아이템 효과 끝(내려앉기·보호막), 갇힘 시간 초과, 제한 시간을 처리한다
     */
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
                // 클라이언트 BalloonField.update와 같은 차례: 물풍선을 옮긴 뒤 때가 된 것을 터뜨린다
                moveBalloons(now, events);
                explodeDueBalloons(now, events);
                // 클라이언트 한 틱과 같은 차례: 내려앉은 자리를 이번 폭발이 덮으면 아이템이 먼저 사라지고 갇힌다
                for (MatchPlayer player : players.values()) {
                    endItemEffectsIfDue(player, now, events);
                }
                flames.removeIf(flame -> flame.until() <= now);
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
     * 던진 물풍선을 내려앉히고 찬 물풍선을 미끄러뜨린다. 지금까지 일어났어야 할 일(내려앉는 때, 칸 경계를 넘는 때, 칸 가운데에 오는 때)을
     * 모든 물풍선에 걸쳐 일어난 시각 순서대로 하나씩 처리하고(같은 시각이면 놓은 순서), 판정은 그 시각의 맵·물풍선·물줄기로 한다.
     * 그래서 물풍선끼리의 이동 순서는 tick 간격에 흔들리지 않는다. 다만 퓨즈로 터지는 것과 그 물줄기는 tick(또는 입력)을 처리하는
     * 시각에 생기므로, 그 사이에 일어난 이동과의 앞뒤는 tick 한 번(수십 ms)만큼 어긋날 수 있다 (클라이언트도 틱 단위로 본다).
     * 미끄러지는 물풍선은 퓨즈가 다 된 뒤의 일은 처리하지 않는다 (그 순간 있던 칸에서 터진다).
     * 그 자리에서 터져야 하면 퓨즈를 그 시각으로 당겨 두고, 바로 뒤 {@link #explodeDueBalloons}가 터뜨린다.
     */
    private void moveBalloons(long now, List<MatchEvent> events) {
        while (true) {
            Balloon next = null;
            for (Balloon balloon : balloons) {
                long at = nextMoveAt(balloon);
                if (at <= now && (next == null || at < nextMoveAt(next))) {
                    next = balloon;
                }
            }
            if (next == null) {
                return;
            }
            if (next.isFlying()) {
                land(next, events);
            } else {
                slide(next, events);
            }
        }
    }

    /** 이 물풍선에 다음으로 일어날 일의 시각. 가만히 있거나, 미끄러지는데 그 전에 퓨즈가 다 되면 없다 (Long.MAX_VALUE) */
    private static long nextMoveAt(Balloon balloon) {
        if (balloon.isFlying()) {
            return balloon.landsAt();
        }
        if (balloon.isSliding() && balloon.nextSlideAt() <= balloon.fuseAt()) {
            return balloon.nextSlideAt();
        }
        return Long.MAX_VALUE;
    }

    /**
     * 입력을 처리하는 지금(now)까지 일어났어야 할 물풍선 이동과 폭발을 처리하고 경기가 끝났는지 본다. tick과 같은 차례다.
     * 입력 처리 중에 터뜨려야 할 때(물줄기 칸에 놓기, 가시 면으로 차기)와, 묵은 물풍선 상태로 던지기·차기를 판정하지 않으려 할 때 부른다.
     * 이동을 먼저 처리하므로, 미끄러지는 물풍선이 아직 처리되지 않은 이전 칸에서 터지거나 나중에 생긴 물줄기를 보지 않는다.
     */
    private void explodeNow(long now, List<MatchEvent> events) {
        moveBalloons(now, events);
        explodeDueBalloons(now, events);
        finishIfDecided(events);
    }

    /**
     * 날던 물풍선을 내려앉힌다 (클라이언트 BalloonField.land).
     * <ul>
     *   <li>나는 사이 그 칸이 막혔거나 다른 물풍선이 차지했으면 같은 방향의 다음 빈 칸으로 더 날아간다
     *       (BALLOON_THROWN, playerId 없음). 맵 끝까지 빈 칸이 없으면 그 자리에서 터진다.</li>
     *   <li>물줄기가 남은 칸이나 바닥 가시 칸에 내려앉으면 바로 터진다.</li>
     *   <li>퓨즈가 나는 사이 다 됐으면 내려앉자마자 터진다 (나는 동안 기다린 것을 {@link #explodeDueBalloons}가 터뜨린다).</li>
     * </ul>
     * 사람이 있는 칸에도 내려앉는다. 그 사람은 칸을 벗어날 때까지 지나갈 수 있다 (클라이언트가 막지 않는다).
     */
    private void land(Balloon balloon, List<MatchEvent> events) {
        long at = balloon.landsAt();
        Facing direction = balloon.flightDirection();
        balloon.stopFlying();
        if (!canLand(balloon.cell(), balloon)) {
            Cell next = landingCell(balloon.cell(), direction, 1, balloon);
            if (next.equals(balloon.cell())) {
                balloon.burstBy(at);
            } else {
                events.add(fly(balloon, null, direction, next, at));
            }
        } else if (popsAt(balloon.cell(), at)) {
            balloon.burstBy(at);
        }
    }

    /**
     * 미끄러지는 물풍선의 다음 일 하나를 처리한다 (클라이언트 BalloonField.slide). 한 칸에 {@link #BALLOON_SLIDE_MS_PER_TILE}이 걸린다.
     * <ul>
     *   <li>칸 가운데에 올 때마다 {@link #keepsSliding}으로 더 갈지 정한다. 더 가지 않으면 그 칸에 멈춘다.</li>
     *   <li>칸 가운데를 떠나 반 칸을 가면 다음 칸으로 넘어간다. 그 사이 다음 칸이 막혔으면 지금 칸 가운데로 돌아가 멈춘다
     *       (클라이언트와 같게 둔다. 서버에서는 막 들어가려는 칸으로 상자를 밀지 않으므로 생기지 않는다).
     *       넘어간 칸에 물줄기가 남았거나 바닥 가시가 있으면 그 칸에서 바로 터진다.</li>
     *   <li>사람·아이템·덤불은 막지 않고 지나간다. 다른 물풍선 위도 지나간다.</li>
     * </ul>
     */
    private void slide(Balloon balloon, List<MatchEvent> events) {
        long at = balloon.nextSlideAt();
        Facing direction = balloon.slideDirection();
        if (balloon.isLeavingCenter()) {
            Cell next = ahead(balloon.cell(), direction, 1);
            if (map.isSolid(next.col(), next.row())) {
                stopSliding(balloon, at, events);
                return;
            }
            balloon.enter(next, at + HALF_SLIDE_MS);
            if (popsAt(next, at)) {
                balloon.stopSliding();
                balloon.burstBy(at);
            }
        } else if (keepsSliding(balloon, direction)) {
            balloon.leaveCenter(at + HALF_SLIDE_MS);
        } else {
            stopSliding(balloon, at, events);
        }
    }

    /**
     * 미끄러지던 물풍선을 지금 칸 가운데에 세우고 BALLOON_STOPPED를 보낸다. 바로 앞이 그쪽으로 가시 난 블럭이거나,
     * 다른 물풍선이 있는 칸이라 한 칸에 둘이 서게 되면 멈추지 않고 그 자리에서 터진다 (클라이언트 BalloonField.stopHere).
     */
    private void stopSliding(Balloon balloon, long at, List<MatchEvent> events) {
        boolean pops = facesThorn(balloon.cell(), balloon.slideDirection()) || balloonsAt(balloon.cell()).size() > 1;
        balloon.stopSliding();
        if (pops) {
            balloon.burstBy(at);
            return;
        }
        events.add(toAll(MatchMessages.BALLOON_STOPPED,
                new BalloonStopped(balloon.balloonId(), balloon.cell().col(), balloon.cell().row())));
    }

    /**
     * 칸 가운데에 있는 미끄러지는 물풍선이 다음 칸으로 갈지 (클라이언트 BalloonField.keepsSliding).
     * 앞쪽으로 막힌 칸(블럭·맵 끝)이 나오기 전까지의 칸 가운데 물풍선이 내려앉을 수 있는 칸({@link #canLand})이 하나라도 있으면 간다.
     * 그래서 물풍선은 막힌 칸 바로 앞의 빈 칸에 멈추고, 그 칸에 다른 물풍선이 있으면 그 앞의 빈 칸에 멈춘다.
     */
    private boolean keepsSliding(Balloon balloon, Facing direction) {
        for (int step = 1; ; step++) {
            Cell cell = ahead(balloon.cell(), direction, step);
            if (map.isSolid(cell.col(), cell.row())) {
                return false;
            }
            if (canLand(cell, balloon)) {
                return true;
            }
        }
    }

    /** cell의 travel 쪽 바로 다음 칸이 그쪽으로 가시 난 블럭인지 (클라이언트 BalloonField.facesThorn) */
    private boolean facesThorn(Cell cell, Facing travel) {
        Cell next = ahead(cell, travel, 1);
        return map.isInside(next.col(), next.row()) && map.tileAt(next.col(), next.row()).popsBalloonMoving(travel);
    }

    /**
     * from에서 direction 쪽으로 distance칸째부터 찾은, 던진 물풍선이 내려앉을 칸 (클라이언트 BalloonField.landingCell).
     * <ol>
     *   <li>distance칸째가 내려앉을 수 있는 칸({@link #canLand})이면 거기. 아니면 같은 방향으로 다음 빈 칸까지 더 간다.
     *       사람·덤불·아이템이 있는 칸도 빈 칸이다.</li>
     *   <li>맵 끝까지 빈 칸이 없으면(distance칸째가 맵 밖인 경우도) 맵 끝에서 거꾸로 돌아오며 처음 만난 빈 칸.</li>
     *   <li>그것도 없으면 from 그대로.</li>
     * </ol>
     *
     * @param self 내려앉을 물풍선 자신. 자기 칸은 비어 있는 것으로 본다
     */
    private Cell landingCell(Cell from, Facing direction, int distance, Balloon self) {
        int last = 0;
        while (map.isInside(from.col() + direction.dx() * (last + 1), from.row() + direction.dy() * (last + 1))) {
            last++;
        }
        for (int step = distance; step <= last; step++) {
            if (canLand(ahead(from, direction, step), self)) {
                return ahead(from, direction, step);
            }
        }
        for (int step = Math.min(last, distance - 1); step >= 1; step--) {
            if (canLand(ahead(from, direction, step), self)) {
                return ahead(from, direction, step);
            }
        }
        return from;
    }

    /**
     * 물풍선이 내려앉거나 미끄러져 멈출 수 있는 칸인지: 지나갈 수 있는 칸이고, self 말고 다른 물풍선의 칸(나는 물풍선은 내려앉을 칸)이
     * 아니다. 사람은 보지 않는다. {@link #holds}와 달리 다른 물풍선이 미끄러져 막 들어가려는 칸은 막지 않는다
     * (클라이언트 BalloonField.canLand: 앞서 미끄러지는 물풍선을 뒤따르는 물풍선은 앞의 것이 비켜 줄 칸까지 따라간다).
     */
    private boolean canLand(Cell cell, Balloon self) {
        return !map.isSolid(cell.col(), cell.row()) && !hasBalloon(cell, self);
    }

    /** 놓이거나 내려앉거나 미끄러져 들어온 물풍선이 그 칸에서 바로 터지는지: 물줄기가 남은 칸이거나 바닥이 가시({@code !})다 */
    private boolean popsAt(Cell cell, long at) {
        return flameAt(cell, at) != null
                || (map.isInside(cell.col(), cell.row()) && map.groundAt(cell.col(), cell.row()) == Tile.SPIKE);
    }

    /**
     * 던진 물풍선을 지금 칸에서 to 칸으로 날린다. 나는 시간은 거리에 맞춘다.
     *
     * @param playerId 던진 사람. 내려앉을 칸이 막혀 더 날아가면 null
     * @param at       날아오르는 시각
     * @return 방 전체에 보낼 BALLOON_THROWN
     */
    private MatchEvent fly(Balloon balloon, Long playerId, Facing direction, Cell to, long at) {
        Cell from = balloon.cell();
        long flightMs = Math.max(MIN_FLIGHT_MS, from.distanceTo(to) * FLIGHT_MS_PER_TILE);
        balloon.startFlying(direction, to, at + flightMs);
        return toAll(MatchMessages.BALLOON_THROWN, new BalloonThrown(balloon.balloonId(), playerId, from.col(), from.row(),
                to.col(), to.row(), direction.name(), flightMs));
    }

    /** from에서 direction 쪽으로 steps칸 간 칸 */
    private static Cell ahead(Cell from, Facing direction, int steps) {
        return new Cell(from.col() + direction.dx() * steps, from.row() + direction.dy() * steps);
    }

    /**
     * 터질 때가 된 물풍선을 연쇄까지 한꺼번에 터뜨려 BALLOON_EXPLODED 하나로 알리고, 물줄기에 있는 플레이어를 가둔다.
     *
     * <p>한 번에 터지는 물풍선은 모두 같은 순간 터진 것으로 본다. 그래서 줄기를 다 정할 때까지 물풍선을 맵에서 빼지 않고,
     * 어느 줄기든 물풍선이 있는 칸(이번에 함께 터지는 것 포함) 앞에서 멈춘다. 그러면 놓은 순서와 상관없이 결과가 같다.</p>
     *
     * <p>아이템은 클라이언트 GameWorld와 같은 순서로 다룬다. 블럭을 맞히고, 다 부서진 블럭 자리에 아이템을 놓고,
     * 물줄기가 덮은 칸의 아이템을 없앤다. 블럭 자리는 물줄기가 들어가지 않은 칸이라 방금 나온 아이템은 남는다.</p>
     *
     * <p>나는 물풍선은 퓨즈가 다 돼도 내려앉을 때까지 기다리고, 물줄기에도 맞지 않는다. 미끄러지는 물풍선은 가운데가 가장 가까운
     * 칸에서 터지고 맞는다. 한 칸에 물풍선이 둘 있으면(미끄러지는 물풍선이 다른 물풍선 위를 지나는 중) 함께 터진다
     * (클라이언트 BalloonField.burst와 같다).</p>
     */
    private void explodeDueBalloons(long now, List<MatchEvent> events) {
        Deque<Balloon> queue = new ArrayDeque<>();
        Set<Balloon> exploding = new LinkedHashSet<>();
        for (Balloon balloon : balloons) {
            if (!balloon.isFlying() && balloon.fuseAt() <= now && exploding.add(balloon)) {
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
            for (Balloon overlapping : balloonsAt(balloon.cell())) {
                if (exploding.add(overlapping)) {
                    queue.add(overlapping);
                }
            }
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
                    List<Balloon> others = balloonsAt(new Cell(col, row));
                    if (!others.isEmpty()) {
                        // 닿은 물풍선도 같은 순간 터져 그 칸을 덮는다
                        for (Balloon other : others) {
                            if (exploding.add(other)) {
                                queue.add(other);
                            }
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

    /** 땅에 선 보통 상태이고 보호막을 쓰는 중이 아닌 사람이 물줄기에 맞았으면({@link #soakingFlame}) 가둔다 */
    private void trapIfWet(MatchPlayer player, long now, List<MatchEvent> events) {
        trapIfWetAt(player, now, now, events);
    }

    /** {@link #trapIfWet}을 at 시각의 상태·물줄기로 판정하고, 갇히면 now부터 갇힌다 */
    private void trapIfWetAt(MatchPlayer player, long at, long now, List<MatchEvent> events) {
        if (!player.isFree(at) || player.isShielded(at)) {
            return;
        }
        Flame flame = soakingFlame(player, at);
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
                pickUpUnderfoot(trapped, events);
            }
        }
    }

    private static MatchPlayer nearest(MatchPlayer trapped, List<MatchPlayer> candidates) {
        return nearestTo(trapped.x(), trapped.y(), candidates);
    }

    /** candidates 가운데 중심이 (x, y)에 가장 가까운 사람. 거리가 같으면 playerId가 작은 사람. 없으면 null */
    private static MatchPlayer nearestTo(double x, double y, List<MatchPlayer> candidates) {
        return candidates.stream()
                .min(Comparator.comparingDouble((MatchPlayer other) -> Math.hypot(other.x() - x, other.y() - y))
                        .thenComparingLong(MatchPlayer::playerId))
                .orElse(null);
    }

    private static boolean touches(MatchPlayer a, MatchPlayer b) {
        return Math.abs(a.x() - b.x()) < TOUCH_DISTANCE && Math.abs(a.y() - b.y()) < TOUCH_DISTANCE;
    }

    /**
     * 터뜨린다. PLAYER_POPPED 바로 뒤에 먹은 아이템을 흩뿌리고, 하나라도 놓였으면 ITEM_SCATTERED를 보낸다
     * (놓을 칸이 하나도 없으면 보내지 않는다). 손에 든 액티브 아이템은 흩뿌리지 않고 사라진다.
     *
     * @param killer 닿아서 터뜨린 사람. 시간이 지나 터졌으면 null
     */
    private void pop(MatchPlayer player, MatchPlayer killer, long now, List<MatchEvent> events) {
        Cell poppedAt = player.cell();
        player.pop(now + DYING_MS);
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
                if (map.tileAt(col, row) == Tile.FLOOR && !holds(cell) && itemAt(cell) == null
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
     * 액티브 아이템(바늘·보호막·스프링 신발)도 패시브와 똑같이 놓는다.
     * 탱크는 비율표에 없어서 고정 드롭(캠프 08의 내구도 상자)으로만 나온다.
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
        falling.clear();
        events.add(toAll(MatchMessages.GAME_OVER, new GameOver(reason, standings)));
    }

    /**
     * 남은 시간이 출현 시각이 된 비행기를 띄우고, 날고 있는 비행기가 지나친 칸에 아이템을 떨어뜨리고,
     * 떨어지던 아이템이 칸에 닿으면 그 칸에 선 사람에게 먹인다.
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
        landFallingDrops(now, events);
    }

    /** 비행기를 띄운다. 지나갈 줄과 떨어뜨릴 칸은 지금 맵으로 고르고({@link AirDrop#plan}), 지나가는 순서로 줄 세운다 */
    private void launchAirplane(long now, List<MatchEvent> events) {
        int airplaneId = ++lastAirplaneId;
        Facing heading = AirDrop.headingOf(airplaneId - 1);
        AirDrop.Plan plan = airDrop.plan();
        List<PassingDrop> drops = new ArrayList<>();
        for (AirDrop.Drop drop : plan.drops()) {
            drops.add(new PassingDrop(drop, now + AirDrop.passMs(map, heading, drop.col())));
        }
        drops.sort(Comparator.comparingLong(PassingDrop::passAt));
        airplanes.add(new Airplane(airplaneId, new ArrayDeque<>(drops)));
        events.add(toAll(MatchMessages.AIRPLANE_APPEARED, new AirplaneAppeared(airplaneId, heading.name(), plan.row())));
    }

    /**
     * 비행기가 drop 칸의 열을 지나는 순간. 칸이 비어 있으면 아이템을 놓고 ITEM_DROPPED로 알린다.
     * 서버는 이때부터 아이템이 맵에 있다고 본다. 클라이언트는 fallMs 동안 떨어지는 모습을 보여 준 뒤 놓는다.
     * 칸에 닿는 시각(지금 + fallMs)을 적어 두었다가 {@link #landFallingDrops}가 그 칸에 선 사람에게 먹인다.
     */
    private void release(int airplaneId, AirDrop.Drop drop, long now, List<MatchEvent> events) {
        Cell cell = new Cell(drop.col(), drop.row());
        if (!canDropAt(cell, now)) {
            return;
        }
        DroppedItem placed = placeItem(cell, drop.item());
        long fallMs = AirDrop.FALL_MS;
        falling.add(new FallingDrop(placed.itemId(), now + fallMs));
        events.add(toAll(MatchMessages.ITEM_DROPPED, new ItemDropped(airplaneId, placed.itemId(), placed.col(),
                placed.row(), placed.item(), fallMs)));
    }

    /**
     * 비행기가 지나가는 순간 이 칸에 떨어뜨릴 수 있는지. 지나간 시각이 아니라 그 뒤 처음 처리하는 tick 시각(now)의 칸을 본다
     * (tick이 수십 ms마다라 많아야 한 tick 차이다). 아래 중 하나라도 있으면 떨어뜨리지 않는다 (덤불 칸은 떨어뜨린다).
     * <ul>
     *   <li>막힌 칸 (블럭·집 등 지나갈 수 없는 칸, 맵 바깥)</li>
     *   <li>물풍선이 차지한 칸({@link #holds}: 던진 물풍선이 내려앉을 칸 포함), 이미 놓인 아이템</li>
     *   <li>물줄기가 남은 칸. 클라이언트는 물줄기 칸의 아이템을 바로 지운다</li>
     * </ul>
     * <p>사람은 막지 않는다. 사람이 서 있어도 떨어뜨리고, 아이템이 닿는 순간 그 칸에 선 사람이 바로 먹는다
     * ({@link #landFallingDrops}).</p>
     */
    private boolean canDropAt(Cell cell, long now) {
        return !map.isSolid(cell.col(), cell.row()) && !holds(cell) && itemAt(cell) == null
                && flameAt(cell, now) == null;
    }

    /**
     * 칸에 닿을 때가 된 아이템을 그 칸에 선 사람에게 바로 먹이고 ITEM_TAKEN을 보낸다. 클라이언트의 ITEM_PICKUP을 기다리지 않는다.
     * <ul>
     *   <li>선 사람: 충돌 박스 중심이 그 칸이고 살아 있으며 공중에 있지 않은 사람 (ITEM_PICKUP과 같은 기준).
     *       갇힌 사람은 구해질 때, 스프링 신발로 뛰는 중인 사람은 내려앉을 때 발밑 아이템으로 먹는다.
     *       여럿이면 칸 가운데에 가장 가까운 사람, 거리가 같으면 playerId가 작은 사람이다.</li>
     *   <li>떨어지는 동안 이미 주웠거나 물줄기에 사라진 아이템은 아무 일도 없다.</li>
     *   <li>닿을 때 아무도 없으면 그대로 맵에 남고, 그 뒤 들어오는 사람이 ITEM_PICKUP으로 줍는다. 클라이언트 화면에서는
     *       통신 지연만큼 늦게 닿으므로, 그 사이 들어온 사람의 클라이언트는 닿을 때 ITEM_PICKUP을 보낸다.</li>
     * </ul>
     * <p>tick 안에서 폭발·갇힘을 처리한 뒤에 본다. tick이 늦어 닿는 시각과 터지는 시각이 한 tick 안에 겹치면
     * 물줄기를 먼저 본다 (떨어뜨리기와 마찬가지로 tick 시각 기준이다).</p>
     */
    private void landFallingDrops(long now, List<MatchEvent> events) {
        for (Iterator<FallingDrop> it = falling.iterator(); it.hasNext(); ) {
            FallingDrop drop = it.next();
            if (drop.landsAt() > now) {
                continue;
            }
            it.remove();
            FieldItem item = items.get(drop.itemId());
            if (item == null) {
                continue;
            }
            MatchPlayer taker = standingOn(item.cell(), now);
            if (taker != null) {
                events.add(take(taker, item));
            }
        }
    }

    /** 충돌 박스 중심이 cell에 있고 살아 있으며 공중에 있지 않은 사람 가운데 칸 가운데에 가장 가까운 사람. 없으면 null */
    private MatchPlayer standingOn(Cell cell, long now) {
        List<MatchPlayer> standing = players.values().stream()
                .filter(player -> player.state() == MatchPlayer.State.ALIVE && !player.isAirborne(now)
                        && player.cell().equals(cell))
                .toList();
        return nearestTo(GameMap.centerX(cell.col()), GameMap.centerY(cell.row()), standing);
    }

    /** 이 연결의 플레이어. 다른 연결의 playerId면 null */
    private MatchPlayer ownPlayer(long connectionId, long playerId) {
        MatchPlayer player = players.get(playerId);
        return player != null && player.connectionId() == connectionId ? player : null;
    }

    /** cell에 가만히 놓인(미끄러지지도 날지도 않는) 물풍선. 없으면 null */
    private Balloon restingAt(Cell cell) {
        for (Balloon balloon : balloons) {
            if (balloon.isResting() && balloon.cell().equals(cell)) {
                return balloon;
            }
        }
        return null;
    }

    /**
     * cell에 있는 물풍선 모두. 나는 물풍선은 어느 칸에도 없고, 미끄러지는 물풍선은 가운데가 가장 가까운 칸에 있다.
     * 미끄러지는 물풍선이 다른 물풍선 위를 지나는 동안에는 둘일 수 있다. 물줄기가 맞히는 물풍선이다.
     */
    private List<Balloon> balloonsAt(Cell cell) {
        List<Balloon> found = new ArrayList<>();
        for (Balloon balloon : balloons) {
            if (!balloon.isFlying() && balloon.cell().equals(cell)) {
                found.add(balloon);
            }
        }
        return found;
    }

    /** except 말고 다른 물풍선의 칸이 cell인지. 놓였거나 미끄러지는 물풍선은 지금 칸, 나는 물풍선은 내려앉을 칸이다 */
    private boolean hasBalloon(Cell cell, Balloon except) {
        for (Balloon balloon : balloons) {
            if (balloon != except && balloon.cell().equals(cell)) {
                return true;
            }
        }
        return false;
    }

    /**
     * cell을 물풍선이 차지했거나 곧 차지할지 (클라이언트 BalloonField.holds): 놓였거나 미끄러지는 물풍선이 있는 칸,
     * 미끄러지는 물풍선이 칸 가운데를 떠나 막 들어가려는 칸, 던진 물풍선이 내려앉을 칸.
     * 이런 칸에는 물풍선을 놓거나 상자를 밀어 넣거나 스프링 신발로 내려앉지 않고, 비행기도 아이템을 떨어뜨리지 않는다.
     */
    private boolean holds(Cell cell) {
        if (hasBalloon(cell, null)) {
            return true;
        }
        for (Balloon balloon : balloons) {
            if (balloon.isSliding() && balloon.isLeavingCenter()
                    && ahead(balloon.cell(), balloon.slideDirection(), 1).equals(cell)) {
                return true;
            }
        }
        return false;
    }

    private FieldItem itemAt(Cell cell) {
        for (FieldItem item : items.values()) {
            if (item.cell().equals(cell)) {
                return item;
            }
        }
        return null;
    }

    /**
     * player를 맞힌 물줄기. 맞지 않았으면 null (클라이언트 BattleRules.soakingOwners와 같은 판정).
     *
     * <p>충돌 박스가 걸친 가로 줄마다, 세로 열마다 물줄기가 덮은 칸과 겹친 넓이를 박스 넓이에 대한 비율로 재어, 어느 한 줄이나
     * 한 열에서라도 {@link #SOAK_SHARE} 이상이면 맞은 것이다. 그래서 네 칸이 만나는 곳에 서면 십자 물줄기가 그 가운데 세 칸을
     * 덮어도 가로 줄기·세로 줄기와 각각 절반씩만 겹쳐 맞지 않는다(크아의 걸치기).</p>
     *
     * <p>맞았으면 박스와 가장 많이 겹친 물줄기 칸의 물줄기를 고른다(넓이가 같으면 위·왼쪽 칸). 그 칸에서는
     * {@link #flameAt}처럼 가까이에서 터진 물줄기다.</p>
     */
    private Flame soakingFlame(MatchPlayer player, long now) {
        int left = player.leftCol();
        int right = player.rightCol();
        int top = player.topRow();
        int bottom = player.bottomRow();
        boolean soaked = false;
        for (int row = top; row <= bottom && !soaked; row++) {
            double wet = 0;
            for (int col = left; col <= right; col++) {
                if (flameAt(new Cell(col, row), now) != null) {
                    wet += player.widthShareIn(col);
                }
            }
            soaked = player.heightShareIn(row) * wet >= SOAK_SHARE - SHARE_SLACK;
        }
        for (int col = left; col <= right && !soaked; col++) {
            double wet = 0;
            for (int row = top; row <= bottom; row++) {
                if (flameAt(new Cell(col, row), now) != null) {
                    wet += player.heightShareIn(row);
                }
            }
            soaked = player.widthShareIn(col) * wet >= SOAK_SHARE - SHARE_SLACK;
        }
        if (!soaked) {
            return null;
        }
        Flame best = null;
        double bestShare = -1;
        for (int row = top; row <= bottom; row++) {
            for (int col = left; col <= right; col++) {
                Flame flame = flameAt(new Cell(col, row), now);
                double share = player.widthShareIn(col) * player.heightShareIn(row);
                if (flame != null && share > bestShare) {
                    best = flame;
                    bestShare = share;
                }
            }
        }
        return best;
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

    /**
     * 맵 위의 물풍선. range는 놓을 때의 주인 물줄기 길이이고 나중에 바뀌지 않는다.
     * 가만히 놓였거나, 차여 미끄러지거나, 던져져 난다. 칸은 놓인 칸, 미끄러지는 중이면 가운데가 가장 가까운 칸,
     * 나는 중이면 내려앉을 칸이다. 움직이는 동안에도 퓨즈는 그대로 흐른다.
     */
    private static final class Balloon {

        private final int balloonId;
        private final long ownerId;
        private final int range;
        private Cell cell;
        private long fuseAt;
        /** 미끄러지는 방향. 미끄러지지 않으면 null */
        private Facing slideDirection;
        /** 칸 가운데를 떠나 다음 칸 쪽으로 가는 중인지. 아니면 지금 칸 가운데로 다가오는 중이다 */
        private boolean leavingCenter;
        /** 미끄러지며 다음에 판정할 시각: 칸 경계를 넘을 때(leavingCenter) 또는 칸 가운데에 올 때 */
        private long nextSlideAt;
        /** 나는 방향. 날지 않으면 null */
        private Facing flightDirection;
        private long landsAt;

        Balloon(int balloonId, long ownerId, Cell cell, int range, long fuseAt) {
            this.balloonId = balloonId;
            this.ownerId = ownerId;
            this.cell = cell;
            this.range = range;
            this.fuseAt = fuseAt;
        }

        int balloonId() {
            return balloonId;
        }

        long ownerId() {
            return ownerId;
        }

        Cell cell() {
            return cell;
        }

        int range() {
            return range;
        }

        long fuseAt() {
            return fuseAt;
        }

        /** 퓨즈를 at으로 당긴다. 그 자리에서 바로 터져야 할 때 쓴다 (이미 더 이르면 그대로) */
        void burstBy(long at) {
            fuseAt = Math.min(fuseAt, at);
        }

        boolean isResting() {
            return slideDirection == null && flightDirection == null;
        }

        boolean isSliding() {
            return slideDirection != null;
        }

        boolean isFlying() {
            return flightDirection != null;
        }

        Facing slideDirection() {
            return slideDirection;
        }

        boolean isLeavingCenter() {
            return leavingCenter;
        }

        long nextSlideAt() {
            return nextSlideAt;
        }

        /** 지금 칸 가운데에서 direction 쪽으로 미끄러지기 시작한다. leavesAt에 칸 경계를 넘는다 */
        void startSliding(Facing direction, long leavesAt) {
            slideDirection = direction;
            leaveCenter(leavesAt);
        }

        /** 칸 가운데를 떠난다. crossesAt에 칸 경계를 넘는다 */
        void leaveCenter(long crossesAt) {
            leavingCenter = true;
            nextSlideAt = crossesAt;
        }

        /** 칸 경계를 넘어 next 칸에 들어간다. centerAt에 그 칸 가운데에 온다 */
        void enter(Cell next, long centerAt) {
            cell = next;
            leavingCenter = false;
            nextSlideAt = centerAt;
        }

        /** 지금 칸 가운데에 선다 */
        void stopSliding() {
            slideDirection = null;
            leavingCenter = false;
        }

        Facing flightDirection() {
            return flightDirection;
        }

        long landsAt() {
            return landsAt;
        }

        /** direction 쪽 to 칸으로 날아 landsAt에 내려앉는다. 칸은 바로 내려앉을 칸이 된다 */
        void startFlying(Facing direction, Cell to, long landsAt) {
            flightDirection = direction;
            cell = to;
            this.landsAt = landsAt;
        }

        /** 내려앉는다. 칸은 이미 내려앉을 칸이다 */
        void stopFlying() {
            flightDirection = null;
        }
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

    /** 비행기에서 떨어지는 중인 아이템과 칸에 닿는 시각 */
    private record FallingDrop(int itemId, long landsAt) {
    }
}

package hansung.arcade.domain.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 경기 중인 플레이어 한 명의 서버 쪽 상태. {@link Match} lock 안에서만 만진다.
 * 위치는 클라이언트가 계산해 MOVE로 보낸 마지막 값이다 (충돌 박스 중심, 맵 픽셀 좌표).
 */
public final class MatchPlayer {

    /** 손에 쌓아 들 수 있는 액티브 아이템 개수 (클라이언트 PlayerGear.MAX_ACTIVE_COUNT) */
    static final int MAX_HELD_COUNT = 99;

    public enum State {
        /** 움직이고 물풍선을 놓을 수 있다 */
        ALIVE,
        /** 물방울에 갇혔다. 움직일 수는 있지만 물풍선은 놓을 수 없다 */
        TRAPPED,
        /** 터져서 탈락했다 */
        POPPED,
        /** 경기에서 나갔다 (나가기 버튼, 끊김, 로딩 시간 초과) */
        LEFT
    }

    /** 충돌 박스의 반 너비(px). 한 칸보다 양쪽 2px씩 작다 (클라이언트 {@code Player.HALF_HITBOX}) */
    static final double HALF_HITBOX = GameMap.TILE_SIZE / 2.0 - 2;
    /** 충돌 박스가 칸 경계에 딱 맞닿은 것을 걸친 것으로 보지 않게 빼는 값 (클라이언트 {@code Player.EPSILON}) */
    private static final double EDGE = 1e-6;

    private final long playerId;
    private final long connectionId;
    private final String nickname;
    private final Mascot mascot;
    private final Team team;
    private final int spawn;

    private double x;
    private double y;
    private Facing facing = Facing.DOWN;
    private boolean moving;
    private State state = State.ALIVE;
    private long trappedUntil;
    private long trappedAt;
    private long escapingUntil;
    /** 터진 뒤 쓰러지는 연출이 끝나 맵에서 사라지는 시각 */
    private long goneAt;
    private int placedBalloons;
    private int kills;
    private int saves;

    private int balloons;
    private int waterRange;
    private int speedLevel;
    private boolean canPushBalloons;
    private boolean canThrowBalloons;
    private boolean hasTank;
    /** 이 경기에서 먹은 패시브 아이템, 먹은 순서. 터지면 맵에 흩뿌린다 */
    private final List<ItemType> takenItems = new ArrayList<>();
    /** 손에 든 액티브 아이템. 없으면 null */
    private ItemType heldItem;
    private int heldCount;
    private boolean shielded;
    private long shieldUntil;
    private boolean jumping;
    private long landsAt;

    public MatchPlayer(long playerId, long connectionId, String nickname, Mascot mascot, Team team, int spawn,
                       Cell spawnCell) {
        this.playerId = playerId;
        this.connectionId = connectionId;
        this.nickname = Objects.requireNonNull(nickname);
        this.mascot = Objects.requireNonNull(mascot);
        this.team = Objects.requireNonNull(team);
        this.spawn = spawn;
        this.x = GameMap.centerX(spawnCell.col());
        this.y = GameMap.centerY(spawnCell.row());
        this.balloons = mascot.balloons().base();
        this.waterRange = mascot.waterRange().base();
        this.speedLevel = mascot.speedLevel().base();
    }

    public long playerId() {
        return playerId;
    }

    public long connectionId() {
        return connectionId;
    }

    public String nickname() {
        return nickname;
    }

    public Mascot mascot() {
        return mascot;
    }

    public Team team() {
        return team;
    }

    /** 맵 스폰 번호 (1부터) */
    public int spawn() {
        return spawn;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public Facing facing() {
        return facing;
    }

    public boolean moving() {
        return moving;
    }

    /**
     * 충돌 박스가 cell에 조금이라도 걸쳤는지. 칸 경계에 딱 맞닿은 것은 걸친 것으로 보지 않는다
     * (클라이언트 {@code Player.overlapsTile}과 같다)
     */
    public boolean overlaps(Cell cell) {
        return cell.col() >= GameMap.colAt(x - HALF_HITBOX) && cell.col() <= GameMap.colAt(x + HALF_HITBOX - EDGE)
                && cell.row() >= GameMap.rowAt(y - HALF_HITBOX) && cell.row() <= GameMap.rowAt(y + HALF_HITBOX - EDGE);
    }

    /** 충돌 박스가 걸친 가장 왼쪽 열 (클라이언트 {@code Player.leftCol}) */
    int leftCol() {
        return GameMap.colAt(x - HALF_HITBOX);
    }

    /** 충돌 박스가 걸친 가장 오른쪽 열. 칸 경계에 딱 맞닿은 것은 걸친 것으로 보지 않는다 */
    int rightCol() {
        return GameMap.colAt(x + HALF_HITBOX - EDGE);
    }

    /** 충돌 박스가 걸친 가장 위 줄 */
    int topRow() {
        return GameMap.rowAt(y - HALF_HITBOX);
    }

    /** 충돌 박스가 걸친 가장 아래 줄. 칸 경계에 딱 맞닿은 것은 걸친 것으로 보지 않는다 */
    int bottomRow() {
        return GameMap.rowAt(y + HALF_HITBOX - EDGE);
    }

    /** 충돌 박스 너비 가운데 col 열 안에 든 비율 (0~1, 클라이언트 {@code Player.widthShareIn}) */
    double widthShareIn(int col) {
        return share(x, col);
    }

    /** 충돌 박스 높이 가운데 row 줄 안에 든 비율 (0~1) */
    double heightShareIn(int row) {
        return share(y, row);
    }

    private static double share(double center, int index) {
        double from = Math.max(center - HALF_HITBOX, index * (double) GameMap.TILE_SIZE);
        double to = Math.min(center + HALF_HITBOX, (index + 1) * (double) GameMap.TILE_SIZE);
        return Math.max(0, to - from) / (2 * HALF_HITBOX);
    }

    /** 충돌 박스 중심이 있는 칸 */
    public Cell cell() {
        return new Cell(GameMap.colAt(x), GameMap.rowAt(y));
    }

    public State state() {
        return state;
    }

    /** 아직 경기에 남아 있는지 (갇혀 있어도 남아 있다) */
    public boolean isInPlay() {
        return state == State.ALIVE || state == State.TRAPPED;
    }

    public long trappedUntil() {
        return trappedUntil;
    }

    /** 마지막으로 갇힌 시각 */
    public long trappedAt() {
        return trappedAt;
    }

    /** 구해져 빠져나오는 중인지. 이 동안은 갇히지 않고, 물풍선을 놓거나 다른 사람에게 닿지 않는다 */
    public boolean isEscaping(long now) {
        return state == State.ALIVE && now < escapingUntil;
    }

    /**
     * 살아 있고 빠져나오는 중도 아니며, 스프링 신발로 공중에 있지도 않음 (클라이언트 PlayerState.NORMAL이고 점프 중이 아님).
     * 이때만 물줄기에 갇히고, 물풍선을 놓고, 갇힌 사람에게 닿고, 보호막·스프링 신발을 쓴다.
     */
    public boolean isFree(long now) {
        return state == State.ALIVE && !isEscaping(now) && !isAirborne(now);
    }

    /** 손에 든 액티브 아이템 (바늘·보호막·스프링 신발). 없으면 null */
    public ItemType heldItem() {
        return heldItem;
    }

    /** 손에 든 액티브 아이템 개수. 없으면 0 */
    public int heldCount() {
        return heldCount;
    }

    /** 보호막을 쓰는 중인지. 이 동안은 물줄기에 갇히지 않는다 */
    public boolean isShielded(long now) {
        return shielded && now < shieldUntil;
    }

    /** 스프링 신발로 뛰어 공중에 있는지. 공중에서는 움직이거나 물풍선을 놓거나 아이템을 줍지 못하고, 물줄기에도 맞지 않는다 */
    public boolean isAirborne(long now) {
        return jumping && now < landsAt;
    }

    /**
     * 몸이 아직 맵 위에 있는지. 경기 중인 사람과, 터진 뒤 쓰러지는 연출이 끝나지 않은 사람이다
     * (클라이언트 PlayerState가 GONE이 아님). 나간 사람은 없다.
     */
    boolean isOnMap(long now) {
        return isInPlay() || (state == State.POPPED && now < goneAt);
    }

    public int placedBalloons() {
        return placedBalloons;
    }

    public int kills() {
        return kills;
    }

    public int saves() {
        return saves;
    }

    /** 한 번에 놓아 둘 수 있는 물풍선 수. 캐릭터 시작값에 아이템을 더하고 캐릭터 최댓값에서 멈춘다 */
    public int balloonCapacity() {
        return balloons;
    }

    /** 물줄기 길이. 캐릭터 시작값에 아이템을 더하고 캐릭터 최댓값에서 멈춘다 */
    public int waterRange() {
        return waterRange;
    }

    /** 이동속도 단계. 캐릭터 시작값에 아이템을 더하고 캐릭터 최댓값에서 멈춘다 */
    public int speedLevel() {
        return speedLevel;
    }

    /** 막힌 앞 칸의 물풍선을 차서 미끄러뜨릴 수 있는지 (신발, 빨간 악마). BALLOON_KICK에서 본다 */
    boolean canPushBalloons() {
        return canPushBalloons;
    }

    /** 선 칸이나 앞 칸의 물풍선을 던질 수 있는지 (장갑). BALLOON_THROW에서 본다 */
    boolean canThrowBalloons() {
        return canThrowBalloons;
    }

    /** 탱크에 타고 있는지. 서버는 아직 탱크로 물줄기를 막지 않고 상태만 들고 있다 */
    boolean hasTank() {
        return hasTank;
    }

    /** 이 경기에서 먹은 패시브 아이템, 먹은 순서. 능력치가 최댓값이라 오르지 않은 것도 들어 있다 */
    List<ItemType> takenItems() {
        return List.copyOf(takenItems);
    }

    void moveTo(double x, double y, Facing facing, boolean moving) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.moving = moving;
    }

    void trap(long now, long until) {
        state = State.TRAPPED;
        trappedAt = now;
        trappedUntil = until;
    }

    /** @param escapeUntil 이때까지 빠져나오는 중 */
    void rescue(long escapeUntil) {
        state = State.ALIVE;
        trappedUntil = 0;
        escapingUntil = escapeUntil;
    }

    /**
     * 터진다. 손에 든 액티브 아이템은 흩뿌리지 않고 사라진다.
     *
     * @param goneAt 쓰러지는 연출이 끝나 맵에서 사라지는 시각. 그때까지 몸이 칸을 막는다
     */
    void pop(long goneAt) {
        state = State.POPPED;
        trappedUntil = 0;
        this.goneAt = goneAt;
        dropHeldItem();
    }

    void leave() {
        state = State.LEFT;
        trappedUntil = 0;
        dropHeldItem();
    }

    void balloonPlaced() {
        placedBalloons++;
    }

    void balloonGone() {
        placedBalloons = Math.max(0, placedBalloons - 1);
    }

    void addKill() {
        kills++;
    }

    void addSave() {
        saves++;
    }

    /**
     * 아이템을 먹는다. 효과는 클라이언트 {@code ItemEffects.apply}와 같다.
     *
     * <p>패시브는 능력치를 올리고 캐릭터 최댓값에서 멈춘다. 최댓값이라 오르지 않아도 먹은 것으로 센다 (터질 때 흩뿌린다).
     * 액티브는 손에 든다 ({@link #hold}). 흩뿌리지 않으므로 먹은 아이템으로 세지 않는다.</p>
     */
    void takeItem(ItemType item) {
        switch (item) {
            case BUBBLE -> balloons = mascot.balloons().clamp((long) balloons + 1);
            case FLUID -> waterRange = mascot.waterRange().clamp((long) waterRange + 1);
            case ROLLER_SHOE -> speedLevel = Math.clamp((long) speedLevel + 1, Mascot.MIN_SPEED_LEVEL,
                    mascot.speedLevel().max());
            case ULTRA -> maxOutWaterRange();
            case RED_DEVIL -> {
                maxOutWaterRange();
                canPushBalloons = true;
            }
            case SHOE -> canPushBalloons = true;
            case GLOVE -> canThrowBalloons = true;
            case TANK -> hasTank = true;
            case NEEDLE, SHIELD, SPRING_SHOE -> {
                hold(item);
                return;
            }
        }
        takenItems.add(item);
    }

    /**
     * 액티브 아이템을 손에 든다 (클라이언트 {@code PlayerGear.equip}). 손은 하나뿐이다.
     * 같은 아이템이면 개수를 하나 늘리고({@link #MAX_HELD_COUNT}까지), 다르면 들고 있던 것을 버리고 바꿔 든다.
     */
    private void hold(ItemType item) {
        if (item == heldItem) {
            heldCount = Math.min(MAX_HELD_COUNT, heldCount + 1);
        } else {
            heldItem = item;
            heldCount = 1;
        }
    }

    /** 손에 든 액티브 아이템을 하나 쓴다. 다 쓰면 손이 빈다 (클라이언트 {@code PlayerGear.useOne}) */
    void useHeldItem() {
        if (heldCount <= 0) {
            return;
        }
        heldCount--;
        if (heldCount == 0) {
            heldItem = null;
        }
    }

    private void dropHeldItem() {
        heldItem = null;
        heldCount = 0;
    }

    /** 보호막을 until까지 쓴다 */
    void raiseShield(long until) {
        shielded = true;
        shieldUntil = until;
    }

    /** 보호막 시간이 지났는데 아직 내리지 않았는지. 내리는 순간 물줄기 위에 서 있으면 갇힌다 */
    boolean isShieldDue(long now) {
        return shielded && now >= shieldUntil;
    }

    /** 마지막으로 쓴 보호막이 끝나는 시각 */
    long shieldUntil() {
        return shieldUntil;
    }

    void lowerShield() {
        shielded = false;
    }

    /**
     * 스프링 신발로 landing 칸에 뛴다. 서버는 위치를 바로 내려앉을 칸 가운데로 옮기고, landsAt까지 공중에 둔다.
     * 공중에 있는 동안에도 그 칸에 서 있는 것으로 본다 (흩뿌리기가 그 칸을 피한다). 그 칸에 물풍선이 놓이는 것은 막지 않는다
     * (클라이언트도 물풍선 위에 내려앉으면 걸어 나갈 수 있게 한다).
     */
    void jumpTo(Cell landing, long landsAt) {
        moveTo(GameMap.centerX(landing.col()), GameMap.centerY(landing.row()), facing, false);
        jumping = true;
        this.landsAt = landsAt;
    }

    /** 내려앉을 때가 지났는데 아직 내려앉히지 않았는지 */
    boolean isLandingDue(long now) {
        return jumping && now >= landsAt;
    }

    /** 마지막으로 뛴 점프가 내려앉는 시각 */
    long landsAt() {
        return landsAt;
    }

    void land() {
        jumping = false;
    }

    /** 물줄기 길이를 캐릭터 최댓값까지 한 번에 올린다 (클라이언트처럼 최댓값을 더한 뒤 맞춘다) */
    private void maxOutWaterRange() {
        waterRange = mascot.waterRange().clamp((long) waterRange + mascot.waterRange().max());
    }

    /** 터질 때 흩뿌릴 아이템을 꺼낸다. 꺼낸 뒤에는 먹은 아이템이 없다 */
    List<ItemType> handOverTakenItems() {
        List<ItemType> items = List.copyOf(takenItems);
        takenItems.clear();
        return items;
    }
}

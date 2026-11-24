package hansung.arcade.domain.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 경기 중인 플레이어 한 명의 서버 쪽 상태. {@link Match} lock 안에서만 만진다.
 * 위치는 클라이언트가 계산해 MOVE로 보낸 마지막 값이다 (충돌 박스 중심, 맵 픽셀 좌표).
 */
public final class MatchPlayer {

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
    /** 쓰는 아이템을 쌓아 둘 수 있는 가장 많은 개수 (클라이언트 {@code PlayerGear.MAX_ACTIVE_COUNT}) */
    static final int MAX_HELD_COUNT = 99;

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
    /** 손에 든 쓰는 아이템 칸 하나 (클라이언트 {@code PlayerGear}의 액티브 칸). 비면 null */
    private ItemType heldItem;
    private int heldCount;
    /** 보호막이 이때까지 물줄기를 막는다 */
    private long shieldUntil;
    /** 스프링 신발로 뛰어 이때까지 공중에 있다 */
    private long airborneUntil;

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

    /** 살아 있고 빠져나오는 중도 아님 (클라이언트 PlayerState.NORMAL) */
    public boolean isFree(long now) {
        return state == State.ALIVE && !isEscaping(now);
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

    /** 물풍선을 밀 수 있는지 (신발, 빨간 악마). 서버는 아직 밀기를 판정하지 않고 상태만 들고 있다 */
    boolean canPushBalloons() {
        return canPushBalloons;
    }

    /** 물풍선을 던질 수 있는지 (장갑). 서버는 아직 던지기를 판정하지 않고 상태만 들고 있다 */
    boolean canThrowBalloons() {
        return canThrowBalloons;
    }

    /** 탱크에 타고 있는지. 서버는 아직 탱크로 물줄기를 막지 않고 상태만 들고 있다 */
    boolean hasTank() {
        return hasTank;
    }

    /** 손에 든 쓰는 아이템. 없으면 null */
    public ItemType heldItem() {
        return heldItem;
    }

    /** 손에 든 쓰는 아이템 개수. 없으면 0 */
    public int heldCount() {
        return heldCount;
    }

    /** 보호막을 쓰는 중인지 */
    public boolean isShielded(long now) {
        return now < shieldUntil;
    }

    /** 스프링 신발로 뛰어 공중에 있는지. 공중에서는 물줄기에 맞지 않고 물풍선을 놓지 못한다 */
    public boolean isAirborne(long now) {
        return now < airborneUntil;
    }

    /** 지금 물줄기에 갇히지 않는지: 보호막을 쓰는 중이거나 공중에 있다 (클라이언트 {@code PlayerGear.ignoresWater}, 탱크는 아직 빼고) */
    boolean ignoresWater(long now) {
        return isShielded(now) || isAirborne(now);
    }

    /**
     * 보호막이나 점프가 막 끝났는지. 끝난 뒤 한 번 true를 돌려주고 잊는다.
     * 물줄기가 남은 칸에 서 있으면 그때 가두도록 경기 타이머가 묻는다.
     */
    boolean protectionJustEnded(long now) {
        long until = Math.max(shieldUntil, airborneUntil);
        if (until == 0 || now < until) {
            return false;
        }
        shieldUntil = 0;
        airborneUntil = 0;
        return true;
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

    void pop() {
        state = State.POPPED;
        trappedUntil = 0;
        clearHeld();
    }

    void leave() {
        state = State.LEFT;
        trappedUntil = 0;
        clearHeld();
    }

    /** @param until 이때까지 물줄기에 갇히지 않는다 */
    void raiseShield(long until) {
        shieldUntil = until;
    }

    /** @param until 이때 내려앉는다. 그때까지 공중에 있다 */
    void jump(long until) {
        airborneUntil = until;
    }

    /** 손에 든 쓰는 아이템을 하나 쓴다. 다 쓰면 칸이 빈다 */
    void useHeld() {
        if (heldCount <= 0) {
            return;
        }
        heldCount--;
        if (heldCount == 0) {
            heldItem = null;
        }
    }

    /** 터지거나 나가면 손에 든 아이템은 흩뿌리지 않고 함께 사라진다 */
    private void clearHeld() {
        heldItem = null;
        heldCount = 0;
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
     * <ul>
     *   <li>패시브: 바로 효과가 나고, 능력치는 캐릭터 최댓값에서 멈춘다. 최댓값이라 오르지 않아도 먹은 것으로 센다 (터질 때 흩뿌린다).</li>
     *   <li>쓰는 아이템(바늘·보호막·스프링 신발): 손에 든다. 같은 아이템이면 개수가 늘고({@link #MAX_HELD_COUNT}까지),
     *       다른 아이템이면 바꿔 든다 (전에 든 것은 사라진다). 흩뿌리는 목록에는 넣지 않는다.</li>
     * </ul>
     */
    void takeItem(ItemType item) {
        if (item.isActive()) {
            if (item == heldItem) {
                heldCount = Math.min(MAX_HELD_COUNT, heldCount + 1);
            } else {
                heldItem = item;
                heldCount = 1;
            }
            return;
        }
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
            case NEEDLE, SHIELD, SPRING_SHOE -> throw new IllegalStateException("쓰는 아이템은 위에서 손에 들었습니다: " + item);
        }
        takenItems.add(item);
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

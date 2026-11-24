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
    }

    void leave() {
        state = State.LEFT;
        trappedUntil = 0;
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
     * 패시브 아이템을 먹는다. 효과는 클라이언트 {@code ItemEffects.apply}와 같고, 능력치는 캐릭터 최댓값에서 멈춘다.
     * 최댓값이라 오르지 않아도 먹은 것으로 센다 (터질 때 흩뿌린다).
     *
     * @throws IllegalArgumentException 액티브 아이템. 서버는 아직 액티브 아이템을 맵에 놓지 않는다
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
            case NEEDLE, SHIELD, SPRING_SHOE -> throw new IllegalArgumentException("액티브 아이템은 아직 다루지 않습니다: " + item);
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

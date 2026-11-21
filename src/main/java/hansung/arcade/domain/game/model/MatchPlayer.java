package hansung.arcade.domain.game.model;

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

    /** 한 번에 놓아 둘 수 있는 물풍선 수 (아이템 API 전까지는 캐릭터 시작값) */
    public int balloonCapacity() {
        return mascot.balloons();
    }

    /** 물줄기 길이 (아이템 API 전까지는 캐릭터 시작값) */
    public int waterRange() {
        return mascot.waterRange();
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
}

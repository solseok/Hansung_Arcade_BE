package hansung.arcade.domain.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 비행기 한 번이 떨어뜨릴 칸과 아이템을 정한다. 클라이언트 {@code AirDrop.plan()}과 같은 규칙에
 * 경기 인원만큼 칸을 다시 뽑는 규칙을 더했다.
 *
 * <p>한 번 나올 때 서로 다른 칸 {@link #DROPS}개를 고르고, 칸마다 {@link #CARGO} 중 하나를 정한다.
 * 비행기는 지나가는 순간 막힌 칸(블럭 등)에는 떨어뜨리지 않아서, 맵 전체에서 아무 칸이나 고르면 0~1개만 떨어지는 일이 많다.
 * 그래서 고른 칸이 지금 막혀 있으면 {@link #attemptsFor(int) 경기 인원에 맞춘 횟수}까지 다시 뽑는다.
 * 2명이면 한 번만 뽑아 클라이언트와 같고, 한 명 늘 때마다 한 번씩 더 뽑아 8명이면 7번까지 뽑는다.
 * 그래도 다 막혀 있으면 마지막에 뽑은 칸을 그대로 쓴다 (지나갈 때 막혀 있으면 떨어뜨리지 않는다).</p>
 *
 * <p>비행기가 나오는 때, 나는 속도, 떨어지는 시간도 여기서 정한다. 모두 클라이언트 {@code Flight}·{@code FallingItem}과 같은 값이다.
 * 띄우고 떨어뜨리는 판정과 푸시(AIRPLANE_APPEARED, ITEM_DROPPED)는 경기({@link Match})가 한다.
 * 경기의 맵 사본을 읽으므로 {@link Match}의 잠금 안에서만 부른다.</p>
 *
 * <p>난수는 밖에서 받아서 테스트는 결과를 고정할 수 있다. 뽑을 때마다 칸 · 아이템 순서로 난수를 두 번 쓴다.</p>
 */
public final class AirDrop {

    /** 비행기가 떨어뜨리는 아이템: 물풍선 · 신발 · 물약 병 */
    public static final List<ItemType> CARGO = List.of(ItemType.BUBBLE, ItemType.SHOE, ItemType.FLUID);
    /** 한 번 나올 때 떨어뜨리려고 고르는 칸 수 */
    public static final int DROPS = 2;
    /** 비행기가 나오는 남은 시간(ms). 1:59, 1:36, 1:14, 0:51, 0:29, 0:07 (클라이언트 {@code AirDrop.APPEARANCES}) */
    public static final List<Long> APPEARANCES_MS = List.of(119_000L, 96_000L, 74_000L, 51_000L, 29_000L, 7_000L);
    /**
     * 출현 시각보다 이만큼 이상 늦게 처리하게 되면 그 비행기는 띄우지 않고 건너뛴다(ms). 타이머가 잠깐 멈췄다 돌아와도
     * 지난 비행기가 한꺼번에 몰려나오지 않게 한다. 클라이언트 경기 시계가 1초 단위라 1초로 둔다
     */
    public static final long LATE_SKIP_MS = 1_000;
    /** 나는 속도(px/s). 15칸 맵을 3.5초쯤에 가로지른다 (클라이언트 {@code Flight.SPEED}) */
    public static final double SPEED = 220;
    /** 떨어뜨리는 자리가 맵 끝에서 이만큼 바깥에서 들어와 반대쪽 바깥 이만큼에서 사라진다(px) (클라이언트 {@code Flight.OFFSTAGE}) */
    public static final int OFFSTAGE = 80;
    /** 떨어뜨리는 자리(비행기 배)의 높이, 맵 y(px). 비행기는 맵 맨 위를 가로지른다 (클라이언트 {@code Flight.CRUISE_Y}) */
    public static final int CRUISE_Y = 46;
    /** 떨어지는 가속도(px/s²) (클라이언트 {@code FallingItem.GRAVITY}) */
    public static final double GRAVITY = 2400;
    /** 가장 낮게 떨어질 때의 높이(px). 비행기 배보다 위쪽 칸도 잠깐은 떨어진다 (클라이언트 {@code FallingItem.MIN_HEIGHT}) */
    public static final double MIN_FALL_HEIGHT = 12;

    private final GameMap map;
    private final RandomGenerator random;
    private final int attempts;

    /**
     * @param map     비행기가 가로지를 맵 (경기의 맵 사본)
     * @param random  칸과 아이템을 고를 난수
     * @param players 경기 인원. 많을수록 막히지 않은 칸을 고를 때까지 더 많이 다시 뽑는다
     */
    public AirDrop(GameMap map, RandomGenerator random, int players) {
        this.map = Objects.requireNonNull(map);
        this.random = Objects.requireNonNull(random);
        this.attempts = attemptsFor(players);
    }

    /**
     * players명 경기에서 칸 하나를 고를 때 뽑는 최대 횟수. 2명까지 1번,
     * 넘는 한 명마다 1번씩 더한다 (3명 2번, 4명 3번, 8명 7번). 인원은 {@link PlayerCount}로 센다.
     */
    public static int attemptsFor(int players) {
        return 1 + PlayerCount.extra(players);
    }

    /** n번째(0부터) 비행기가 나는 방향. 첫 비행기는 왼쪽에서 오른쪽({@link Facing#RIGHT}), 다음은 반대로 번갈아 난다 */
    public static Facing headingOf(int launched) {
        return launched % 2 == 0 ? Facing.RIGHT : Facing.LEFT;
    }

    /**
     * 비행기가 나온 뒤 떨어뜨리는 자리가 col 열의 가운데를 지나기까지(ms). 그 순간 그 열의 고른 칸에 떨어뜨릴지 본다.
     * 떨어뜨리는 자리는 맵 끝에서 {@link #OFFSTAGE}px 바깥에서 {@link #SPEED}로 들어온다.
     */
    public static long passMs(GameMap map, Facing heading, int col) {
        double startX = heading == Facing.RIGHT ? -OFFSTAGE : map.pixelWidth() + OFFSTAGE;
        return Math.round(Math.abs(GameMap.centerX(col) - startX) / SPEED * 1000);
    }

    /**
     * 비행기 배에서 row 줄의 칸 가운데까지 떨어지는 시간(ms). 맨 윗줄은 0.1초, 아래 줄일수록 길다 (13줄 맵 맨 아랫줄 약 0.6초).
     * 높이가 {@link #MIN_FALL_HEIGHT}보다 낮으면 그 높이에서 떨어진다.
     */
    public static long fallMs(int row) {
        double height = Math.max(MIN_FALL_HEIGHT, GameMap.centerY(row) - CRUISE_Y);
        return Math.round(Math.sqrt(2 * height / GRAVITY) * 1000);
    }

    /**
     * 이번에 나올 비행기가 떨어뜨리려는 칸들. 서로 다른 칸이고, 지금 막힌 칸(지나갈 수 없는 칸)은 인원만큼 다시 뽑아 피한다.
     * 덤불처럼 지나갈 수 있는 칸은 막히지 않은 칸이다.
     */
    public List<Drop> plan() {
        int cells = map.columns() * map.rows();
        List<Drop> drops = new ArrayList<>();
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < Math.min(DROPS, cells); i++) {
            Drop drop = null;
            int index = -1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                index = unchosen(cells, taken);
                drop = new Drop(index % map.columns(), index / map.columns(), CARGO.get(pick(CARGO.size())));
                if (!map.isSolid(drop.col(), drop.row())) {
                    break;
                }
            }
            taken.add(index);
            drops.add(drop);
        }
        return List.copyOf(drops);
    }

    /** 아직 안 고른 칸들 가운데 하나. 고른 칸 번호보다 크거나 같으면 그만큼 건너뛴다 */
    private int unchosen(int cells, List<Integer> taken) {
        int cell = pick(cells - taken.size());
        for (int chosen : taken.stream().sorted().toList()) {
            if (cell >= chosen) {
                cell++;
            }
        }
        return cell;
    }

    /** 0 이상 bound 미만의 정수 하나 */
    private int pick(int bound) {
        return Math.min(bound - 1, (int) (random.nextDouble() * bound));
    }

    /** 떨어뜨리려는 칸 하나와 떨어뜨릴 아이템 */
    public record Drop(int col, int row, ItemType item) {
    }
}

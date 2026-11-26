package hansung.arcade.domain.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * 비행기 한 번이 지나갈 줄과 그 줄에서 떨어뜨릴 칸·아이템을 정한다 ({@link #plan()}).
 *
 * <p>비행기는 맵의 한 줄을 따라 가로지르고, 그 줄의 칸에만 아이템을 떨어뜨린다. 줄은 나올 때마다 맵의 모든 줄 가운데
 * 무작위로 고른다 (캠프 08 4:4 영상: 1:59 비행기는 아래쪽 줄, 1:36 비행기는 맨 윗줄을 지났다).
 * 그 줄에서 서로 다른 칸 {@link #DROPS}개를 고르고, 칸마다 {@link #CARGO} 중 하나를 정한다.
 * 비행기는 지나가는 순간 막힌 칸(블럭 등)에는 떨어뜨리지 않으므로, 고른 칸이 지금 막혀 있으면
 * {@link #attemptsFor(int) 경기 인원에 맞춘 횟수}까지 같은 줄에서 다시 뽑는다. 2명이면 한 번만 뽑고, 한 명 늘 때마다
 * 한 번씩 더 뽑아 8명이면 7번까지 뽑는다. 그래도 다 막혀 있으면 마지막에 뽑은 칸을 그대로 쓴다
 * (지나갈 때 막혀 있으면 떨어뜨리지 않는다). 줄 자체는 다시 뽑지 않는다.</p>
 *
 * <p>비행기가 나오는 때, 나는 속도·높이, 떨어지는 시간도 여기서 정한다. 나오는 때·속도·가속도는 클라이언트 {@code AirDrop}·{@code Flight}·
 * {@code FallingItem}과 같다. 줄을 따라 나는 높이({@link #DROP_HEIGHT})와 떨어지는 시간({@link #FALL_MS})은 이 규칙으로 새로 정했고,
 * 클라이언트도 같은 값으로 맞춘다 (Notion AIRPLANE_APPEARED·ITEM_DROPPED).
 * 띄우고 떨어뜨리는 판정과 푸시(AIRPLANE_APPEARED, ITEM_DROPPED)는 경기({@link Match})가 한다.
 * 경기의 맵 사본을 읽으므로 {@link Match}의 잠금 안에서만 부른다.</p>
 *
 * <p>난수는 밖에서 받아서 테스트는 결과를 고정할 수 있다. 줄을 고를 때 한 번, 그 뒤 칸을 뽑을 때마다 칸 · 아이템 순서로 두 번 쓴다.</p>
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
    /**
     * 떨어뜨리는 자리(비행기 배)가 지나가는 줄의 칸 가운데보다 이만큼 위를 난다(px). 줄의 위쪽 끝(맵 y = row × 40)이다.
     * 아이템은 여기서 그 줄의 칸 가운데까지 떨어진다
     */
    public static final int DROP_HEIGHT = GameMap.TILE_SIZE / 2;
    /** 떨어지는 가속도(px/s²) (클라이언트 {@code FallingItem.GRAVITY}) */
    public static final double GRAVITY = 2400;
    /** 비행기 배에서 줄의 칸 가운데까지 떨어지는 시간(ms). {@link #DROP_HEIGHT} 20px을 {@link #GRAVITY}로 떨어져 129ms다 */
    public static final long FALL_MS = Math.round(Math.sqrt(2.0 * DROP_HEIGHT / GRAVITY) * 1000);

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
     * 이번에 나올 비행기가 지나갈 줄과 그 줄에서 떨어뜨리려는 칸들. 줄을 먼저 무작위로 고르고, 그 줄에서 서로 다른 칸을 고른다.
     * 지금 막힌 칸(지나갈 수 없는 칸)은 같은 줄에서 인원만큼 다시 뽑아 피한다. 덤불처럼 지나갈 수 있는 칸은 막히지 않은 칸이다.
     */
    public Plan plan() {
        int row = pick(map.rows());
        int columns = map.columns();
        List<Drop> drops = new ArrayList<>();
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < Math.min(DROPS, columns); i++) {
            Drop drop = null;
            int col = -1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                col = unchosen(columns, taken);
                drop = new Drop(col, row, CARGO.get(pick(CARGO.size())));
                if (!map.isSolid(col, row)) {
                    break;
                }
            }
            taken.add(col);
            drops.add(drop);
        }
        return new Plan(row, List.copyOf(drops));
    }

    /** 아직 안 고른 열들 가운데 하나. 고른 열 번호보다 크거나 같으면 그만큼 건너뛴다 */
    private int unchosen(int columns, List<Integer> taken) {
        int col = pick(columns - taken.size());
        for (int chosen : taken.stream().sorted().toList()) {
            if (col >= chosen) {
                col++;
            }
        }
        return col;
    }

    /** 0 이상 bound 미만의 정수 하나 */
    private int pick(int bound) {
        return Math.min(bound - 1, (int) (random.nextDouble() * bound));
    }

    /** 떨어뜨리려는 칸 하나와 떨어뜨릴 아이템. row는 비행기가 지나가는 줄이다 */
    public record Drop(int col, int row, ItemType item) {
    }

    /**
     * 비행기 한 대의 계획
     *
     * @param row   지나가는 줄. 떨어뜨리는 칸은 모두 이 줄에 있다
     * @param drops 떨어뜨리려는 칸들. 고른 순서
     */
    public record Plan(int row, List<Drop> drops) {
    }
}

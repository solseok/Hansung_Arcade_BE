package hansung.arcade.domain.game.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
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
 * <p>비행기 출현·이동과 그 푸시는 아직 명세가 없어 경기({@link Match})에 붙이지 않았다. 붙일 때는
 * 경기의 맵 사본을 읽으므로 {@link Match}의 잠금 안에서만 부른다. 또 클라이언트는 물줄기가 남은 칸의 아이템을 틱마다 지우므로,
 * 물줄기가 남은 칸에 떨어진 아이템도 바로 지워야 클라이언트와 어긋나지 않는다.</p>
 *
 * <p>난수는 밖에서 받아서 테스트는 결과를 고정할 수 있다. 뽑을 때마다 칸 · 아이템 순서로 난수를 두 번 쓴다.</p>
 */
public final class AirDrop {

    /** 비행기가 떨어뜨리는 아이템: 물풍선 · 신발 · 물약 병 */
    public static final List<ItemType> CARGO = List.of(ItemType.BUBBLE, ItemType.SHOE, ItemType.FLUID);
    /** 한 번 나올 때 떨어뜨리려고 고르는 칸 수 */
    public static final int DROPS = 2;

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

    public int attempts() {
        return attempts;
    }

    /** 이번에 나올 비행기가 떨어뜨리려는 칸들. 서로 다른 칸이고, 막힌 칸은 인원만큼 다시 뽑아 피한다 */
    public List<Drop> plan() {
        return plan(cell -> !map.isSolid(cell.col(), cell.row()));
    }

    /**
     * @param droppable 떨어뜨릴 수 있는 칸인지. 아니면 다시 뽑는다
     */
    List<Drop> plan(Predicate<Cell> droppable) {
        int cells = map.columns() * map.rows();
        List<Drop> drops = new ArrayList<>();
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < Math.min(DROPS, cells); i++) {
            Drop drop = null;
            int index = -1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                index = unchosen(cells, taken);
                drop = new Drop(index % map.columns(), index / map.columns(), CARGO.get(pick(CARGO.size())));
                if (droppable.test(new Cell(drop.col(), drop.row()))) {
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

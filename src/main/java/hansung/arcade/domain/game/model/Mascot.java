package hansung.arcade.domain.game.model;

import java.util.List;
import java.util.Random;

/**
 * 캐릭터와 능력치 범위. 클라이언트 {@code hansung.arcade.ingame.entity.Mascot}의 시작값·최댓값과 같아야 한다.
 * 아이템으로 오른 능력치는 최댓값에서 멈춘다.
 *
 * @param balloons   한 번에 놓아 둘 수 있는 물풍선 수
 * @param waterRange 물줄기 길이(칸)
 * @param speedLevel 이동속도 단계. 속도 계산은 클라이언트가 하고, 서버는 ITEM_TAKEN에 실어 보내기만 한다
 */
public record Mascot(String id, StatRange balloons, StatRange waterRange, StatRange speedLevel) {

    public static final List<Mascot> ALL = List.of(
            new Mascot("sangsangbugi", new StatRange(1, 6), new StatRange(1, 7), new StatRange(5, 9)),
            new Mascot("kkukku", new StatRange(1, 10), new StatRange(1, 8), new StatRange(4, 8)),
            new Mascot("kkokko", new StatRange(1, 8), new StatRange(1, 5), new StatRange(5, 8)),
            new Mascot("sangzzi", new StatRange(1, 6), new StatRange(2, 7), new StatRange(5, 8)),
            new Mascot("hansungnyangi", new StatRange(2, 7), new StatRange(1, 9), new StatRange(4, 8)));

    /** 방에서 캐릭터를 랜덤으로 고른 값 */
    public static final String RANDOM = "random";

    /** 이동속도 단계의 바닥 (클라이언트 SpeedLevel.MIN). 다른 능력치와 달리 시작값 아래까지 내려갈 수 있다 */
    static final int MIN_SPEED_LEVEL = 1;

    /** @throws IllegalArgumentException 모르는 id */
    public static Mascot of(String id) {
        return ALL.stream().filter(mascot -> mascot.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 캐릭터입니다: " + id));
    }

    public static Mascot pick(Random random) {
        return ALL.get(random.nextInt(ALL.size()));
    }

    /** 능력치 하나의 시작값과 아이템으로 올릴 수 있는 최댓값 (클라이언트 StatRange) */
    public record StatRange(int base, int max) {

        public StatRange {
            if (base < 1 || max < base) {
                throw new IllegalArgumentException("능력치 범위가 잘못되었습니다: 시작 %d, 최대 %d".formatted(base, max));
            }
        }

        /** value를 시작값~최댓값 안으로 맞춘다 */
        int clamp(long value) {
            return Math.clamp(value, base, max);
        }
    }
}

package hansung.arcade.domain.game.model;

import java.util.List;
import java.util.Random;

/**
 * 캐릭터와 시작 능력치. 클라이언트 {@code hansung.arcade.ingame.entity.Mascot}의 시작값과 같아야 한다.
 * 아이템으로 올리는 최댓값은 아이템 API를 만들 때 더한다.
 *
 * @param balloons   한 번에 놓아 둘 수 있는 물풍선 수
 * @param waterRange 물줄기 길이(칸)
 */
public record Mascot(String id, int balloons, int waterRange) {

    public static final List<Mascot> ALL = List.of(
            new Mascot("sangsangbugi", 1, 1),
            new Mascot("kkukku", 1, 1),
            new Mascot("kkokko", 1, 1),
            new Mascot("sangzzi", 1, 2),
            new Mascot("hansungnyangi", 2, 1));

    /** 방에서 캐릭터를 랜덤으로 고른 값 */
    public static final String RANDOM = "random";

    /** @throws IllegalArgumentException 모르는 id */
    public static Mascot of(String id) {
        return ALL.stream().filter(mascot -> mascot.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 캐릭터입니다: " + id));
    }

    public static Mascot pick(Random random) {
        return ALL.get(random.nextInt(ALL.size()));
    }
}

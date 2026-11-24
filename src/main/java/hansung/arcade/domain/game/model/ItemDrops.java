package hansung.arcade.domain.game.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * 고정 드롭이 없는 블럭이 다 부서질 때 아이템이 나오는지와 어떤 아이템인지 정한다.
 * 클라이언트 {@code hansung.arcade.ingame.item.ItemDrops}와 확률, 비율, 난수 쓰는 순서가 같다.
 */
final class ItemDrops {

    /** 블럭이 부서졌을 때 아이템이 나올 확률 */
    static final double DROP_CHANCE = 0.33;
    /** 아이템이 나왔을 때 종류별 비율(%). 합이 100이고, 이 순서({@link ItemType} 선언 순서)대로 난수 구간을 나눈다 */
    static final Map<ItemType, Integer> WEIGHTS = weights();

    private ItemDrops() {
    }

    /**
     * 블럭 하나가 다 부서졌을 때 나올 아이템. 난수를 두 번까지 쓴다 (나오는지, 무엇인지).
     *
     * @return 나온 아이템. 액티브 아이템일 수도 있다. 안 나오면 비어 있다
     */
    static Optional<ItemType> roll(RandomGenerator random) {
        if (random.nextDouble() >= DROP_CHANCE) {
            return Optional.empty();
        }
        double pick = random.nextDouble() * 100;
        int bound = 0;
        ItemType last = null;
        for (Map.Entry<ItemType, Integer> entry : WEIGHTS.entrySet()) {
            bound += entry.getValue();
            last = entry.getKey();
            if (pick < bound) {
                return Optional.of(last);
            }
        }
        return Optional.ofNullable(last);
    }

    private static Map<ItemType, Integer> weights() {
        Map<ItemType, Integer> weights = new EnumMap<>(ItemType.class);
        weights.put(ItemType.BUBBLE, 22);
        weights.put(ItemType.FLUID, 22);
        weights.put(ItemType.ULTRA, 4);
        weights.put(ItemType.ROLLER_SHOE, 16);
        weights.put(ItemType.RED_DEVIL, 3);
        weights.put(ItemType.GLOVE, 5);
        weights.put(ItemType.SHOE, 6);
        weights.put(ItemType.TANK, 3);
        weights.put(ItemType.NEEDLE, 7);
        weights.put(ItemType.SHIELD, 6);
        weights.put(ItemType.SPRING_SHOE, 6);
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        if (total != 100 || weights.size() != ItemType.values().length) {
            throw new IllegalStateException("아이템 비율은 11종 모두 합쳐 100이어야 합니다: " + weights);
        }
        return Collections.unmodifiableMap(weights);
    }
}

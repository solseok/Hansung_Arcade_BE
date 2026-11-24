package hansung.arcade.domain.game.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/** nextDouble이 정해 둔 값을 차례로 돌려주는 난수. 준비한 값을 넘어 쓰거나 다른 난수를 쓰면 실패한다 */
final class ScriptedRandom implements RandomGenerator {

    private final Deque<Double> doubles = new ArrayDeque<>();

    ScriptedRandom(double... values) {
        for (double value : values) {
            doubles.add(value);
        }
    }

    @Override
    public double nextDouble() {
        if (doubles.isEmpty()) {
            throw new AssertionError("준비한 난수를 다 썼습니다.");
        }
        return doubles.poll();
    }

    @Override
    public long nextLong() {
        throw new AssertionError("nextDouble 말고는 쓰지 않아야 합니다.");
    }

    boolean isUsedUp() {
        return doubles.isEmpty();
    }
}

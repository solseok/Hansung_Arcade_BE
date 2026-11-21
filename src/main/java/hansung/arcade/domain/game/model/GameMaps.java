package hansung.arcade.domain.game.model;

import java.util.List;
import java.util.Random;

/**
 * 고를 수 있는 맵과 최대 인원. 클라이언트 {@code hansung.arcade.outgame.GameMaps}와 같다.
 */
public final class GameMaps {

    public record Entry(String id, int maxPlayers) {
    }

    public static final List<Entry> ALL = List.of(
            new Entry("camp/camp08", 8),
            new Entry("patrit/patrit14", 8),
            new Entry("village/village10", 6));

    /** 방에서 맵을 랜덤으로 고른 값. 경기를 만들 때 실제 맵으로 바꾼다 */
    public static final String RANDOM = "random";

    private GameMaps() {
    }

    public static boolean exists(String id) {
        return ALL.stream().anyMatch(entry -> entry.id().equals(id));
    }

    public static int maxPlayers(String id) {
        return ALL.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 맵입니다: " + id))
                .maxPlayers();
    }

    /** players명이 들어가는 실제 맵 하나 */
    public static String pick(int players, Random random) {
        List<Entry> fits = ALL.stream().filter(entry -> entry.maxPlayers() >= players).toList();
        if (fits.isEmpty()) {
            throw new IllegalArgumentException(players + "명이 들어가는 맵이 없습니다.");
        }
        return fits.get(random.nextInt(fits.size())).id();
    }
}

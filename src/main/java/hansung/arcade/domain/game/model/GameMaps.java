package hansung.arcade.domain.game.model;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 고를 수 있는 맵과 최대 인원. 클라이언트 {@code hansung.arcade.outgame.GameMaps}와 같다.
 */
public final class GameMaps {

    public record Entry(String id, String name, int maxPlayers) {
    }

    public static final List<Entry> ALL = List.of(new Entry("camp/camp08", "캠프 08", 8),
            new Entry("patrit/patrit14", "패트릿 14", 8), new Entry("village/village10", "빌리지 10", 6));

    /**
     * 방에서 맵을 랜덤으로 고른 값. 경기를 만들 때 실제 맵으로 바꾼다
     */
    public static final String RANDOM = "random";

    public static final String DEFAULT_ID = "camp/camp08";

    static final int RANDOM_MAX_PLAYERS = 8;

    /** 탱크가 나오는 맵. 탱크는 캠프 08 전용이라 다른 맵에서는 블럭에서 나오지 않는다 */
    static final Set<String> TANK_MAPS = Set.of("camp/camp08");

    /**
     * 이 맵에서는 블럭에서 나오지 않는 아이템. 지금은 캠프 08이 아닌 맵의 탱크뿐이다.
     * 확률표·고정 드롭 모두에 적용한다 ({@link Match}가 블럭을 부술 때).
     *
     * @param mapId 실제 맵 id (경기를 열 때 random은 이미 실제 맵으로 바뀌어 있다)
     */
    static Set<ItemType> excludedDrops(String mapId) {
        return TANK_MAPS.contains(mapId) ? Set.of() : Set.of(ItemType.TANK);
    }

    public static boolean selectable(String id) {
        return RANDOM.equals(id) || exists(id);
    }

    public static int capacityOf(String id) {
        return RANDOM.equals(id) ? RANDOM_MAX_PLAYERS : maxPlayers(id);
    }

    public static String name(String id) {
        if (RANDOM.equals(id)) {
            return "랜덤 맵";
        }
        return ALL.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 맵입니다: " + id)).name();
    }

    private GameMaps() {
    }

    public static boolean exists(String id) {
        return ALL.stream().anyMatch(entry -> entry.id().equals(id));
    }

    public static int maxPlayers(String id) {
        return ALL.stream().filter(entry -> entry.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 맵입니다: " + id)).maxPlayers();
    }

    /**
     * players명이 들어가는 실제 맵 하나
     */
    public static String pick(int players, Random random) {
        List<Entry> fits = ALL.stream().filter(entry -> entry.maxPlayers() >= players).toList();
        if (fits.isEmpty()) {
            throw new IllegalArgumentException(players + "명이 들어가는 맵이 없습니다.");
        }
        return fits.get(random.nextInt(fits.size())).id();
    }
}

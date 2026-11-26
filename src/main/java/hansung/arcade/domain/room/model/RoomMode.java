package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.Team;

import java.util.Arrays;
import java.util.List;

/**
 * 방의 경기 방식. 메시지에는 이름 그대로(SOLO 등) 쓴다. 경기는 같은 팀 색끼리 한 편으로 판정하므로,
 * 방식은 대기실에서 고를 수 있는 팀 색으로 나타난다.
 *
 * <ul>
 *   <li>개인전: 지금까지처럼 8색 모두 고를 수 있다</li>
 *   <li>팀전: {@link #TEAM_COLORS} 4색만 고를 수 있다. 진영이 둘인 캠프 08은 {@link #CAMP_TEAM_COLORS} 2색이다</li>
 * </ul>
 */
public enum RoomMode {
    SOLO, TEAM;

    /** 팀전에서 고를 수 있는 색. 비어 있는 팀을 줄 때 이 순서로 고른다 (1P 빨강, 2P 파랑) */
    static final List<Team> TEAM_COLORS = List.of(Team.RED, Team.BLUE, Team.YELLOW, Team.GREEN);

    /** 진영이 둘인 맵(캠프 08)의 팀전에서 고를 수 있는 색 */
    static final List<Team> CAMP_TEAM_COLORS = List.of(Team.RED, Team.BLUE);

    private static final String CAMP_08 = "camp/camp08";

    /**
     * 이 방식과 맵에서 고를 수 있는 팀 색. 비어 있는 팀을 줄 때 앞에서부터 고른다.
     * 랜덤 맵은 실제 맵을 경기 때 정하므로 팀전 4색이다.
     */
    public List<Team> teamsFor(String mapId) {
        if (this == SOLO) {
            return List.of(Team.values());
        }
        return CAMP_08.equals(mapId) ? CAMP_TEAM_COLORS : TEAM_COLORS;
    }

    /** 이름(SOLO 등)에 해당하는 방식. 없으면 null */
    public static RoomMode parse(String name) {
        return Arrays.stream(values()).filter(mode -> mode.name().equals(name)).findFirst().orElse(null);
    }
}

package hansung.arcade.domain.game.web.dto;

/**
 * BALLOON_KICK body. {@code {"playerId": 12, "col": 6, "row": 7, "direction": "RIGHT"}}
 *
 * @param col       찰 물풍선 칸 (막힌 앞 칸)
 * @param row       찰 물풍선 칸
 * @param direction 차는 방향 UP·DOWN·LEFT·RIGHT
 */
public record BalloonKickRequest(Long playerId, Integer col, Integer row, String direction) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && col != null && row != null && direction != null;
    }
}

package hansung.arcade.domain.game.web.dto;

/**
 * BALLOON_THROW body. {@code {"playerId": 12, "col": 6, "row": 7, "direction": "RIGHT"}}
 *
 * @param col       던질 물풍선 칸 (선 칸이나 바라보는 앞 칸)
 * @param row       던질 물풍선 칸
 * @param direction 던지는 방향 UP·DOWN·LEFT·RIGHT
 */
public record BalloonThrowRequest(Long playerId, Integer col, Integer row, String direction) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && col != null && row != null && direction != null;
    }
}

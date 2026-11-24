package hansung.arcade.domain.game.web.dto;

/**
 * BOX_PUSH body. {@code {"playerId": 12, "col": 6, "row": 7, "direction": "RIGHT"}}
 *
 * @param col       상자 칸
 * @param row       상자 칸
 * @param direction 미는 방향 UP·DOWN·LEFT·RIGHT
 */
public record BoxPushRequest(Long playerId, Integer col, Integer row, String direction) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && col != null && row != null && direction != null;
    }
}

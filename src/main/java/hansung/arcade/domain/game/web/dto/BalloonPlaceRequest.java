package hansung.arcade.domain.game.web.dto;

/**
 * BALLOON_PLACE body. {@code {"playerId": 12, "col": 5, "row": 7}}
 */
public record BalloonPlaceRequest(Long playerId, Integer col, Integer row) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && col != null && row != null;
    }
}

package hansung.arcade.domain.game.web.dto;

/**
 * MOVE body. {@code {"playerId": 12, "x": 312.5, "y": 208.0, "facing": "LEFT", "moving": true}}
 *
 * @param x 맵 픽셀 x, 충돌 박스 중심
 * @param y 맵 픽셀 y, 충돌 박스 중심
 */
public record MoveRequest(Long playerId, Double x, Double y, String facing, Boolean moving) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && x != null && y != null && facing != null && moving != null;
    }
}

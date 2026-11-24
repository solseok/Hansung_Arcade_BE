package hansung.arcade.domain.game.web.dto;

/**
 * ITEM_PICKUP body. {@code {"playerId": 12, "itemId": 4}}
 *
 * @param itemId BALLOON_EXPLODED·ITEM_SCATTERED에서 받은 아이템 번호
 */
public record ItemPickupRequest(Long playerId, Integer itemId) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && itemId != null;
    }
}

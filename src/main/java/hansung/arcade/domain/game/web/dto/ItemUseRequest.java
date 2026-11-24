package hansung.arcade.domain.game.web.dto;

/**
 * ITEM_USE body. {@code {"playerId": 12, "item": "needle"}}
 *
 * @param item 쓸 액티브 아이템 id (needle, shield, spring_shoe)
 */
public record ItemUseRequest(Long playerId, String item) {

    /** 필드가 모두 있는지. 하나라도 빠지면 조용히 버린다 */
    public boolean isComplete() {
        return playerId != null && item != null;
    }
}

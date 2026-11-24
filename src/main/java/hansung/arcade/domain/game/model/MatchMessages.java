package hansung.arcade.domain.game.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 인게임 푸시의 type과 body 모양. Notion "네프 API" 인게임·아웃게임 DB의 명세와 필드 이름이 같아야 한다.
 */
public final class MatchMessages {

    public static final String GAME_STARTED = "GAME_STARTED";
    public static final String ROUND_BEGIN = "ROUND_BEGIN";
    public static final String PLAYER_MOVED = "PLAYER_MOVED";
    public static final String BALLOON_PLACED = "BALLOON_PLACED";
    public static final String BALLOON_EXPLODED = "BALLOON_EXPLODED";
    public static final String PLAYER_TRAPPED = "PLAYER_TRAPPED";
    public static final String PLAYER_RESCUED = "PLAYER_RESCUED";
    public static final String PLAYER_POPPED = "PLAYER_POPPED";
    public static final String ITEM_TAKEN = "ITEM_TAKEN";
    /** 손에 든 쓰는 아이템(바늘·보호막·스프링 신발)을 썼다 */
    public static final String ITEM_USED = "ITEM_USED";
    /** 터진 사람이 먹은 아이템을 흩뿌렸다. Notion에 아직 없는 메시지라 제안한 모양이다 */
    public static final String ITEM_SCATTERED = "ITEM_SCATTERED";
    /** 비행기가 나와 맵 위를 가로지르기 시작했다 */
    public static final String AIRPLANE_APPEARED = "AIRPLANE_APPEARED";
    /** 비행기가 지나가며 칸에 아이템을 떨어뜨렸다 */
    public static final String ITEM_DROPPED = "ITEM_DROPPED";
    public static final String PLAYER_LEFT = "PLAYER_LEFT";
    public static final String GAME_OVER = "GAME_OVER";

    private MatchMessages() {
    }

    public record GameStarted(String mapId, List<StartedPlayer> players) {
    }

    public record StartedPlayer(long playerId, String nickname, String character, String team, int spawn) {
    }

    public record RoundBegin(long countdownMs, long timeLimitMs) {
    }

    public record PlayerMoved(long playerId, double x, double y, String facing, boolean moving) {
    }

    public record BalloonPlaced(int balloonId, long ownerId, int col, int row, int range, long fuseMs) {
    }

    /**
     * @param items        다 부서진 블럭에서 나온 아이템
     * @param removedItems 물줄기에 맞아 사라진 아이템의 itemId
     */
    public record BalloonExploded(List<ExplodedBalloon> balloons, List<ChangedTile> tiles, List<DroppedItem> items,
                                  List<Integer> removedItems) {
    }

    /**
     * 맵에 새로 놓인 아이템 (블럭에서 나옴, 터진 사람이 흩뿌림). itemId는 경기마다 1부터.
     *
     * @param item 아이템 id ({@link ItemType#id()}: bubble, fluid …)
     */
    public record DroppedItem(int itemId, int col, int row, String item) {
    }

    /** 물줄기가 실제로 뻗은 칸 수 (막히면 짧다) */
    public record ExplodedBalloon(int balloonId, int col, int row, int up, int down, int left, int right) {
    }

    /** 바뀐 뒤의 맵 기호 (블럭이 부서지면 그 칸 바닥 기호) */
    public record ChangedTile(int col, int row, String tile) {
    }

    public record PlayerTrapped(long playerId, int balloonId, long popMs) {
    }

    public record PlayerRescued(long playerId, long rescuerId) {
    }

    /** @param killerId TIMEOUT이면 null */
    public record PlayerPopped(long playerId, Long killerId, String reason) {
    }

    /**
     * @param heldItem  먹은 뒤 손에 든 쓰는 아이템 id. 없으면 null
     * @param heldCount 손에 든 쓰는 아이템 개수. 없으면 0
     */
    public record ItemTaken(int itemId, long playerId, String item, Stats stats, String heldItem, int heldCount) {
    }

    /**
     * 쓰는 아이템을 썼다. 값이 없는 필드(null)는 보내지 않는다.
     *
     * @param durationMs 효과가 이어지는 시간. 보호막 {@link Match#SHIELD_MS}, 스프링 신발 {@link Match#JUMP_MS}, 바늘은 없음
     * @param col        스프링 신발이 내려앉을 칸. 다른 아이템은 없음
     * @param row        스프링 신발이 내려앉을 칸. 다른 아이템은 없음
     * @param heldItem   쓴 뒤 손에 남은 쓰는 아이템 id. 다 썼으면 없음
     * @param heldCount  쓴 뒤 손에 남은 개수. 다 썼으면 0
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemUsed(long playerId, String item, Long durationMs, Integer col, Integer row, String heldItem,
                           int heldCount) {
    }

    /** 아이템을 먹은 뒤의 능력치 */
    public record Stats(int balloons, int waterRange, int speedLevel) {
    }

    /**
     * @param col   터진 칸. 클라이언트가 여기서 각 칸으로 날아가는 연출을 한다
     * @param row   터진 칸
     * @param items 흩뿌려 맵에 놓인 아이템
     */
    public record ItemScattered(long playerId, int col, int row, List<DroppedItem> items) {
    }

    /** @param heading 나는 방향. RIGHT(왼쪽에서 오른쪽) 또는 LEFT */
    public record AirplaneAppeared(int airplaneId, String heading) {
    }

    /**
     * 서버는 보낸 순간부터 아이템이 맵에 있다고 본다. 그래서 클라이언트는 떨어지는 동안 removedItems·ITEM_TAKEN에 이 itemId가
     * 오면 놓지 않고, 닿는 순간 발밑에 있으면 ITEM_PICKUP을 보낸다 (Notion ITEM_DROPPED 클라이언트 주의사항).
     *
     * @param fallMs 비행기 배에서 칸까지 떨어지는 시간. 떨어지는 그림에만 쓴다
     */
    public record ItemDropped(int airplaneId, int itemId, int col, int row, String item, long fallMs) {
    }

    public record PlayerLeft(List<Long> playerIds, String reason) {
    }

    public record GameOver(String reason, List<Standing> standings) {
    }

    public record Standing(long playerId, String nickname, String outcome, int kills, int saves) {
    }
}

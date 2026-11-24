package hansung.arcade.domain.game.model;

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
    /** 터진 사람이 먹은 아이템을 흩뿌렸다. Notion에 아직 없는 메시지라 제안한 모양이다 */
    public static final String ITEM_SCATTERED = "ITEM_SCATTERED";
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

    /** @param heldItem 손에 든 액티브 아이템. 서버가 아직 액티브 아이템을 다루지 않으므로 늘 null */
    public record ItemTaken(int itemId, long playerId, String item, Stats stats, String heldItem) {
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

    public record PlayerLeft(List<Long> playerIds, String reason) {
    }

    public record GameOver(String reason, List<Standing> standings) {
    }

    public record Standing(long playerId, String nickname, String outcome, int kills, int saves) {
    }
}

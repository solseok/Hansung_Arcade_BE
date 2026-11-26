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
    /** 장갑으로 물풍선을 던졌다 (내려앉을 칸이 막혀 더 날아갈 때도) */
    public static final String BALLOON_THROWN = "BALLOON_THROWN";
    /** 신발·빨간 악마로 물풍선을 차서 미끄러지기 시작했다 */
    public static final String BALLOON_KICKED = "BALLOON_KICKED";
    /** 미끄러지던 물풍선이 멈췄다 */
    public static final String BALLOON_STOPPED = "BALLOON_STOPPED";
    public static final String PLAYER_TRAPPED = "PLAYER_TRAPPED";
    public static final String PLAYER_RESCUED = "PLAYER_RESCUED";
    public static final String PLAYER_POPPED = "PLAYER_POPPED";
    public static final String ITEM_TAKEN = "ITEM_TAKEN";
    public static final String ITEM_USED = "ITEM_USED";
    /** 터진 사람이 먹은 아이템을 흩뿌렸다. Notion에 아직 없는 메시지라 제안한 모양이다 */
    public static final String ITEM_SCATTERED = "ITEM_SCATTERED";
    /** 비행기가 나와 맵 위를 가로지르기 시작했다 */
    public static final String AIRPLANE_APPEARED = "AIRPLANE_APPEARED";
    /** 비행기가 지나가며 칸에 아이템을 떨어뜨렸다 */
    public static final String ITEM_DROPPED = "ITEM_DROPPED";
    public static final String BOX_MOVED = "BOX_MOVED";
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
     * 물풍선이 (col, row)에서 날아올라 flightMs 뒤 (toCol, toRow)에 내려앉는다. 나는 동안은 어느 칸에도 없다.
     *
     * @param playerId 던진 사람. 내려앉을 칸이 막혀 같은 쪽으로 더 날아가는 것이면 null
     * @param direction 나는 방향 UP·DOWN·LEFT·RIGHT
     */
    public record BalloonThrown(int balloonId, Long playerId, int col, int row, int toCol, int toRow, String direction,
                                long flightMs) {
    }

    /**
     * 물풍선이 (col, row)에서 direction 쪽으로 미끄러지기 시작했다. 멈추면 BALLOON_STOPPED, 가는 중에 터지면 BALLOON_EXPLODED가 온다.
     *
     * @param playerId 찬 사람
     */
    public record BalloonKicked(int balloonId, long playerId, int col, int row, String direction) {
    }

    /** 미끄러지던 물풍선이 (col, row) 칸 가운데에 멈췄다 */
    public record BalloonStopped(int balloonId, int col, int row) {
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

    /** 물줄기가 실제로 뻗은 칸 수 (막히면 짧다). (col, row)는 터진 칸 (미끄러지던 물풍선이면 가운데가 가장 가까운 칸) */
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
     * @param heldItem  먹은 뒤 손에 든 액티브 아이템 (needle, shield, spring_shoe). 없으면 null.
     *                  패시브를 먹어도 들고 있던 것을 그대로 알려 준다
     * @param heldCount 먹은 뒤 손에 든 개수. 같은 것을 또 먹으면 쌓이고, 다른 것을 먹으면 바꿔 들어 1이다. 없으면 0
     */
    public record ItemTaken(int itemId, long playerId, String item, Stats stats, String heldItem, int heldCount) {
    }

    /**
     * 액티브 아이템을 썼다. 효과를 적용한 뒤 보낸다.
     *
     * @param durationMs 효과가 이어지는 시간 (보호막, 스프링 신발 점프). 바늘은 바로 빠져나오기 시작하므로 null
     * @param col        스프링 신발이 내려앉을 칸. 다른 아이템은 null
     * @param row        스프링 신발이 내려앉을 칸. 다른 아이템은 null
     * @param heldItem   쓰고 손에 남은 아이템. 다 썼으면 null
     * @param heldCount  쓰고 남은 개수. 0이면 손이 비었다
     */
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
     * 오면 놓지 않는다. 사람이 있어도 떨어뜨리고, 닿는 순간 그 칸에 선 사람은 서버가 바로 먹여 ITEM_TAKEN을 보낸다.
     * 그래도 클라이언트는 닿는 순간 내 플레이어가 그 칸에 있으면 ITEM_PICKUP을 보낸다. 통신 지연으로 서버가 닿을 때 칸이
     * 비어 보였던 경우를 메우고, 서버가 이미 먹였으면 조용히 버린다 (Notion ITEM_DROPPED).
     *
     * @param fallMs 비행기 배에서 칸까지 떨어지는 시간. 클라이언트는 떨어지는 그림에, 서버는 닿는 순간(보낸 시각 + fallMs)을
     *               정하는 데 쓴다
     */
    public record ItemDropped(int airplaneId, int itemId, int col, int row, String item, long fallMs) {
    }

    /** 밀린 상자가 있던 칸(바닥이 된다)과 간 칸 */
    public record BoxMoved(int fromCol, int fromRow, int toCol, int toRow) {
    }

    public record PlayerLeft(List<Long> playerIds, String reason) {
    }

    public record GameOver(String reason, List<Standing> standings) {
    }

    public record Standing(long playerId, String nickname, String outcome, int kills, int saves) {
    }
}

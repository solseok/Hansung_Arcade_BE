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

    public record BalloonExploded(List<ExplodedBalloon> balloons, List<ChangedTile> tiles, List<Object> items,
                                  List<Integer> removedItems) {
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

    public record PlayerLeft(List<Long> playerIds, String reason) {
    }

    public record GameOver(String reason, List<Standing> standings) {
    }

    public record Standing(long playerId, String nickname, String outcome, int kills, int saves) {
    }
}

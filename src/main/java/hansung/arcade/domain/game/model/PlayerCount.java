package hansung.arcade.domain.game.model;

/**
 * 아이템을 경기 인원에 맞춰 늘릴 때 인원을 세는 규칙. {@link ItemDrops}와 {@link AirDrop}이 함께 쓴다.
 * 인원은 경기를 열 때의 참가자 수다 (한 연결의 1P·2P는 두 명으로 센다).
 */
final class PlayerCount {

    /** 늘리기 시작하는 인원. 이 인원까지는 기본값 그대로다 */
    static final int BASE = 2;
    /** 한 경기의 최대 인원 (방 정원의 최댓값). 이보다 많아도 이 인원으로 센다 */
    static final int MAX = 8;

    private PlayerCount() {
    }

    /**
     * {@link #BASE}명을 넘는 인원. {@link #MAX}명까지만 센다 (2명 이하 0, 3명 1, 8명 이상 6).
     *
     * @throws IllegalArgumentException 인원이 한 명보다 적으면
     */
    static int extra(int players) {
        if (players < 1) {
            throw new IllegalArgumentException("경기 인원은 한 명 이상이어야 합니다: " + players);
        }
        return Math.max(0, Math.min(players, MAX) - BASE);
    }
}

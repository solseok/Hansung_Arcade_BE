package hansung.arcade.domain.player.model;

import java.util.Objects;

/**
 * 로그인한 플레이어 한 명. 한 연결에 1P·2P 두 명까지 있을 수 있다.
 *
 * @param playerId 서버가 로그인 때 정하는 1 이상의 정수. 접속해 있는 동안 바뀌지 않는다 (0은 시스템)
 * @param nickname {@link NicknameRule}로 다듬은 닉네임
 */
public record Player(long playerId, String nickname) {

    public Player {
        if (playerId < 1) {
            throw new IllegalArgumentException("playerId는 1 이상이어야 합니다: " + playerId);
        }
        Objects.requireNonNull(nickname);
    }

    /** 중복 검사용 키. 영문(A~Z)만 대소문자를 구분하지 않는다. 다른 글자는 그대로 비교한다 */
    public static String nicknameKey(String nickname) {
        StringBuilder key = new StringBuilder(nickname.length());
        for (int i = 0; i < nickname.length(); i++) {
            char c = nickname.charAt(i);
            key.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return key.toString();
    }
}

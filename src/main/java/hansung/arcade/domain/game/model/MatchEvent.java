package hansung.arcade.domain.game.model;

import java.util.List;

/**
 * 경기가 보내야 하는 푸시 하나. {@link Match}는 lock 안에서 이것만 만들고, 실제로 보내는 것은 lock 밖의 web 계층이다.
 *
 * @param recipients 받을 연결 번호들
 * @param type       메시지 type (PLAYER_MOVED 등)
 * @param body       JSON 객체가 되는 record ({@link MatchMessages})
 */
public record MatchEvent(List<Long> recipients, String type, Object body) {

    public MatchEvent {
        recipients = List.copyOf(recipients);
    }
}

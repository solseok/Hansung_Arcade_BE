package hansung.arcade.domain.session.web.dto;

import hansung.arcade.domain.player.model.Player;

import java.util.List;

/**
 * LOGIN 성공 응답 body. {@code {"players": [{"playerId": 12, "nickname": "상상부기"}]}}
 */
public record LoginResponse(List<PlayerResponse> players) {

    public record PlayerResponse(long playerId, String nickname) {
    }

    public static LoginResponse from(List<Player> players) {
        return new LoginResponse(players.stream()
                .map(player -> new PlayerResponse(player.playerId(), player.nickname()))
                .toList());
    }
}

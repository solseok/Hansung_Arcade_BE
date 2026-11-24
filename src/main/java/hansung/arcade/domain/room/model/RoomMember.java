package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.Team;
import java.util.Objects;

/**
 * 대기실 참가자 한 명의 불변 상태. 변경 시 새 값을 만든다.
 */
public record RoomMember(long playerId, long connectionId, String nickname, int slot, String character, Team team,
        boolean ready) {

    public RoomMember {
        Objects.requireNonNull(nickname);
        Objects.requireNonNull(character);
        Objects.requireNonNull(team);
    }

    RoomMember withReady(boolean value) {
        return new RoomMember(playerId, connectionId, nickname, slot, character, team, value);
    }

    RoomMember withSlot(int value) {
        return new RoomMember(playerId, connectionId, nickname, value, character, team, ready);
    }

    RoomMember withCharacter(String value) {
        return new RoomMember(playerId, connectionId, nickname, slot, value, team, false);
    }

    RoomMember withTeam(Team value) {
        return new RoomMember(playerId, connectionId, nickname, slot, character, value, false);
    }
}

package hansung.arcade.domain.room.model;

import hansung.arcade.domain.game.model.Team;

import java.util.Objects;

public record RoomMember(
        long playerId,
        long connectionId,
        String nickname,
        int slot,
        String character,
        Team team,
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

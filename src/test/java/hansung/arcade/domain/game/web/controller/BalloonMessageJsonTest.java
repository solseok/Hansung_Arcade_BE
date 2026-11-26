package hansung.arcade.domain.game.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.Message;
import hansung.arcade.domain.game.model.MatchMessages;
import hansung.arcade.domain.game.model.MatchMessages.BalloonKicked;
import hansung.arcade.domain.game.model.MatchMessages.BalloonStopped;
import hansung.arcade.domain.game.model.MatchMessages.BalloonThrown;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BALLOON_THROWN·BALLOON_KICKED·BALLOON_STOPPED가 Notion 명세 모양의 JSON으로 나가는지 */
class BalloonMessageJsonTest {

    private static JsonNode bodyOf(String type, Object body) throws Exception {
        JsonNode message = Json.MAPPER.readTree(Message.push(type, body).toJson());
        assertEquals(type, message.get("type").asText());
        return message.get("body");
    }

    @Test
    void 던진_물풍선은_날아오르는_칸과_내려앉을_칸과_나는_시간을_싣는다() throws Exception {
        JsonNode body = bodyOf(MatchMessages.BALLOON_THROWN, new BalloonThrown(3, 12L, 1, 0, 7, 0, "RIGHT", 360));

        assertEquals(List.of(3, 12, 1, 0, 7, 0), List.of(body.get("balloonId").asInt(), body.get("playerId").asInt(),
                body.get("col").asInt(), body.get("row").asInt(), body.get("toCol").asInt(), body.get("toRow").asInt()));
        assertEquals("RIGHT", body.get("direction").asText());
        assertEquals(360, body.get("flightMs").asLong());
    }

    @Test
    void 내려앉을_칸이_막혀_더_날아가면_playerId가_빠지지_않고_null로_나간다() throws Exception {
        JsonNode body = bodyOf(MatchMessages.BALLOON_THROWN, new BalloonThrown(3, null, 7, 0, 8, 0, "RIGHT", 200));

        assertTrue(body.has("playerId") && body.get("playerId").isNull());
    }

    @Test
    void 찬_물풍선과_멈춘_물풍선은_칸을_싣는다() throws Exception {
        JsonNode kicked = bodyOf(MatchMessages.BALLOON_KICKED, new BalloonKicked(3, 12, 2, 0, "LEFT"));
        JsonNode stopped = bodyOf(MatchMessages.BALLOON_STOPPED, new BalloonStopped(3, 0, 0));

        assertEquals(List.of(3, 12, 2, 0), List.of(kicked.get("balloonId").asInt(), kicked.get("playerId").asInt(),
                kicked.get("col").asInt(), kicked.get("row").asInt()));
        assertEquals("LEFT", kicked.get("direction").asText());
        assertEquals(List.of(3, 0, 0), List.of(stopped.get("balloonId").asInt(), stopped.get("col").asInt(),
                stopped.get("row").asInt()));
    }
}

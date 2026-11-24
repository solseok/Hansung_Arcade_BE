package hansung.arcade.domain.game.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import hansung.arcade.core.net.Json;
import hansung.arcade.core.net.Message;
import hansung.arcade.domain.game.model.MatchMessages;
import hansung.arcade.domain.game.model.MatchMessages.ItemUsed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ITEM_USED가 Notion 명세 모양의 JSON으로 나가는지. 값이 없는 필드는 빠지지 않고 null로 나간다 */
class ItemMessageJsonTest {

    private static JsonNode bodyOf(Object body) throws Exception {
        String line = Message.push(MatchMessages.ITEM_USED, body).toJson();
        JsonNode message = Json.MAPPER.readTree(line);
        assertEquals("ITEM_USED", message.get("type").asText());
        return message.get("body");
    }

    @Test
    void 바늘은_durationMs와_착지_칸이_null로_나간다() throws Exception {
        JsonNode body = bodyOf(new ItemUsed(12, "needle", null, null, null, 1));

        assertEquals(12, body.get("playerId").asLong());
        assertEquals("needle", body.get("item").asText());
        for (String field : List.of("durationMs", "col", "row")) {
            assertTrue(body.has(field) && body.get(field).isNull(), field);
        }
        assertEquals(1, body.get("heldCount").asInt());
    }

    @Test
    void 보호막은_durationMs만_있고_스프링_신발은_착지_칸도_있다() throws Exception {
        JsonNode shield = bodyOf(new ItemUsed(12, "shield", 3_000L, null, null, 0));
        JsonNode spring = bodyOf(new ItemUsed(12, "spring_shoe", 400L, 5, 3, 0));

        assertEquals(3_000, shield.get("durationMs").asLong());
        assertTrue(shield.get("col").isNull() && shield.get("row").isNull());
        assertEquals(400, spring.get("durationMs").asLong());
        assertEquals(List.of(5, 3), List.of(spring.get("col").asInt(), spring.get("row").asInt()));
    }
}

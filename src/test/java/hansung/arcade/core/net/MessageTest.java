package hansung.arcade.core.net;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageTest {

    record JoinBody(int roomNumber) {
    }

    @Test
    void 요청을_읽는다() throws ProtocolException {
        Message request = Message.parse("{\"type\":\"ROOM_JOIN\",\"id\":7,\"body\":{\"roomNumber\":3},\"later\":true}");

        assertEquals("ROOM_JOIN", request.type());
        assertEquals(7L, request.id());
        assertTrue(request.expectsResponse());
        assertEquals(3, request.bodyAs(JoinBody.class).roomNumber());
    }

    @Test
    void id와_body가_없어도_읽는다() throws ProtocolException {
        Message move = Message.parse("{\"type\":\"BYE\"}");

        assertNull(move.id());
        assertFalse(move.expectsResponse());
        assertEquals("{}", move.body().toString());
    }

    @Test
    void 모양이_틀리면_BAD_MESSAGE() {
        for (String line : new String[]{"not json", "[1,2]", "{}", "{\"type\":\"\"}", "{\"type\":3}",
                "{\"type\":\"A\",\"id\":\"7\"}", "{\"type\":\"A\",\"id\":1.5}", "{\"type\":\"A\",\"body\":[]}"}) {
            ProtocolException e = assertThrows(ProtocolException.class, () -> Message.parse(line), line);
            assertEquals("BAD_MESSAGE", e.code(), line);
        }
    }

    @Test
    void type까지_읽었으면_원인_type을_들고_있다() {
        Message.Malformed e = assertThrows(Message.Malformed.class,
                () -> Message.parse("{\"type\":\"READY\",\"body\":1}"));

        assertEquals("READY", e.causeType());
    }

    @Test
    void 필드_타입이_틀린_body는_BAD_MESSAGE() throws ProtocolException {
        Message request = Message.parse("{\"type\":\"ROOM_JOIN\",\"id\":1,\"body\":{\"roomNumber\":\"셋\"}}");

        ProtocolException e = assertThrows(ProtocolException.class, () -> request.bodyAs(JoinBody.class));
        assertEquals("BAD_MESSAGE", e.code());
    }

    @Test
    void 공통_규칙의_모양으로_쓴다() throws ProtocolException {
        Message request = Message.parse("{\"type\":\"ROOM_JOIN\",\"id\":7,\"body\":{\"roomNumber\":3}}");

        assertEquals("{\"type\":\"ROOM_JOIN\",\"id\":7,\"ok\":true,\"body\":{\"roomNumber\":3}}",
                Message.ok(request, new JoinBody(3)).toJson());
        assertEquals("{\"type\":\"ROOM_JOIN\",\"id\":7,\"ok\":false,\"error\":{\"code\":\"ROOM_FULL\",\"message\":\"자리가 없습니다.\"}}",
                Message.fail(request, "ROOM_FULL", "자리가 없습니다.").toJson());
        assertEquals("{\"type\":\"ROOM_STATE\",\"body\":{}}", Message.push("ROOM_STATE", null).toJson());
        assertEquals("{\"type\":\"ERROR\",\"body\":{\"code\":\"WRONG_STATE\",\"message\":\"안 됨\",\"type\":\"READY\"}}",
                Message.error("WRONG_STATE", "안 됨", "READY").toJson());
    }

    @Test
    void 문자열_속_줄바꿈은_이스케이프해서_한_줄로_쓴다() {
        String json = Message.push("CHAT_MESSAGE", Map.of("text", "첫 줄\n둘째 줄")).toJson();

        assertFalse(json.contains("\n"), json);
        assertTrue(json.contains("\\n"), json);
    }

    @Test
    void body는_JSON_객체여야_한다() {
        assertThrows(IllegalArgumentException.class, () -> Message.push("X", "문자열"));
    }
}

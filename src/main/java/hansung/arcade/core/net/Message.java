package hansung.arcade.core.net;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 한 줄에 담기는 메시지 하나. 네프 API 공통 규칙의 다섯 가지 모양을 만든다.
 *
 * <pre>
 * 요청       {"type":"ROOM_JOIN","id":7,"body":{"roomNumber":3}}
 * 응답 성공  {"type":"ROOM_JOIN","id":7,"ok":true,"body":{...}}        {@link #ok}
 * 응답 실패  {"type":"ROOM_JOIN","id":7,"ok":false,"error":{...}}      {@link #fail}
 * 응답 없음  {"type":"MOVE","body":{...}}
 * 푸시       {"type":"ROOM_STATE","body":{...}}                        {@link #push}
 * </pre>
 *
 * @param type  늘 있다. 응답은 요청과 같은 type
 * @param id    응답을 받는 요청과 그 응답에만 있다. 없으면 null
 * @param ok    응답에만 있다. 없으면 null
 * @param body  실패 응답이 아니면 늘 있다 (내용이 없어도 {@code {}})
 * @param error 실패 응답에만 있다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(String type, Long id, Boolean ok, JsonNode body, Error error) {

    /** 실패 응답의 내용 */
    public record Error(String code, String message) {
    }

    public Message {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type이 비어 있습니다.");
        }
    }

    /** 서버가 먼저 보내는 메시지 (id 없음). body는 JSON 객체가 되는 record·Map·{@link JsonNode} */
    public static Message push(String type, Object body) {
        return new Message(type, null, null, toBody(body), null);
    }

    /** 요청에 대한 성공 응답 */
    public static Message ok(Message request, Object body) {
        return new Message(request.type, request.id, true, toBody(body), null);
    }

    /** 요청에 대한 실패 응답. body 없이 error만 보낸다 */
    public static Message fail(Message request, String code, String message) {
        return new Message(request.type, request.id, false, null, new Error(code, message));
    }

    /**
     * id가 없는 메시지의 실패나 읽을 수 없는 줄을 알리는 {@code ERROR} 푸시.
     *
     * @param causeType 원인이 된 메시지의 type. 모르면 null
     */
    public static Message error(String code, String message, String causeType) {
        ObjectNode body = Json.object().put("code", code).put("message", message);
        if (causeType != null) {
            body.put("type", causeType);
        }
        return push(Protocol.ERROR, body);
    }

    /**
     * 받은 한 줄을 읽는다. body가 없으면 {@code {}}로 본다. 모르는 필드는 무시한다.
     *
     * @throws ProtocolException JSON 객체가 아니거나 type·id·body 모양이 틀리면 BAD_MESSAGE
     */
    public static Message parse(String line) throws ProtocolException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new Malformed(null, null, "JSON 형식이 아닙니다.");
        }
        if (root == null || !root.isObject()) {
            throw new Malformed(null, null, "메시지는 JSON 객체여야 합니다.");
        }
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual() || type.asText().isBlank()) {
            throw new Malformed(null, null, "type이 없습니다.");
        }
        String typeName = type.asText();

        Long id = null;
        JsonNode idNode = root.get("id");
        if (idNode != null && !idNode.isNull()) {
            if (!idNode.isIntegralNumber() || !idNode.canConvertToLong()) {
                throw new Malformed(typeName, null, "id는 정수여야 합니다.");
            }
            id = idNode.asLong();
        }

        JsonNode body = root.get("body");
        if (body == null || body.isNull()) {
            body = Json.object();
        } else if (!body.isObject()) {
            throw new Malformed(typeName, id, "body는 JSON 객체여야 합니다.");
        }
        return new Message(typeName, id, null, body, null);
    }

    /** 한 줄 JSON. 줄 끝의 \n은 보내는 쪽({@link Connection})이 붙인다. */
    public String toJson() {
        try {
            return Json.MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(type + " 메시지를 JSON으로 바꾸지 못했습니다.", e);
        }
    }

    /** 응답을 기다리는 요청인지 (id가 있는지) */
    public boolean expectsResponse() {
        return id != null;
    }

    /**
     * body를 record 등 자바 객체로 읽는다. 모르는 필드는 무시한다.
     *
     * <pre>
     * record JoinBody(int roomNumber) {}
     * JoinBody body = request.bodyAs(JoinBody.class);
     * </pre>
     *
     * @throws ProtocolException 필드 타입이 맞지 않으면 BAD_MESSAGE
     */
    public <T> T bodyAs(Class<T> type) throws ProtocolException {
        try {
            return Json.MAPPER.treeToValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ProtocolException(ErrorCode.BAD_MESSAGE, "body의 필드가 올바르지 않습니다.");
        }
    }

    private static JsonNode toBody(Object body) {
        if (body == null) {
            return Json.object();
        }
        JsonNode node = body instanceof JsonNode json ? json : Json.MAPPER.valueToTree(body);
        if (!node.isObject()) {
            throw new IllegalArgumentException("body는 JSON 객체여야 합니다: " + node.getNodeType());
        }
        return node;
    }

    @Override
    public String toString() {
        return toJson();
    }

    /**
     * 읽을 수 없는 줄. 읽어 낸 만큼(type, id)을 들고 있어서, id까지 읽었으면 ERROR 푸시 대신
     * 같은 id의 {@code ok:false} 응답으로 알릴 수 있다 (클라이언트가 응답을 기다리다 멈추지 않게).
     */
    static final class Malformed extends ProtocolException {

        private final String causeType;
        private final Long id;

        Malformed(String causeType, Long id, String message) {
            super(ErrorCode.BAD_MESSAGE, message);
            this.causeType = causeType;
            this.id = id;
        }

        /** 같은 id로 보낼 실패 응답. id를 못 읽었으면 null */
        Message failResponse() {
            return id == null ? null : new Message(causeType, id, false, null, new Error(code(), getMessage()));
        }

        /** 읽어 낸 type. 못 읽었으면 null */
        String causeType() {
            return causeType;
        }
    }
}

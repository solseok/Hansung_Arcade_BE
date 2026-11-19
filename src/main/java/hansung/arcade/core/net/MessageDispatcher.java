package hansung.arcade.core.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 받은 한 줄을 type에 맞는 {@link MessageHandler}로 넘기고, 결과를 공통 규칙대로 돌려보낸다.
 *
 * <ul>
 *   <li>읽을 수 없는 줄 → ERROR 푸시 (BAD_MESSAGE)</li>
 *   <li>모르는 type → id가 있으면 ok:false, 없으면 ERROR (UNKNOWN_TYPE)</li>
 *   <li>{@link ProtocolException} → 같은 방식으로 그 code를 알린다</li>
 *   <li>예상 못 한 예외 → SERVER_ERROR로 알리고 스택은 서버 로그에 남긴다. 연결은 끊지 않는다</li>
 * </ul>
 *
 * PING(연결 확인)과 BYE(정상 종료)는 처음부터 등록되어 있다.
 */
public final class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    private final Map<String, MessageHandler> handlers = new ConcurrentHashMap<>();

    public MessageDispatcher() {
        register(Protocol.PING, (connection, request) -> null);
        register(Protocol.BYE, (connection, request) -> {
            connection.close("BYE");
            return null;
        });
    }

    public void register(String type, MessageHandler handler) {
        Objects.requireNonNull(handler);
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException(type + " 핸들러가 이미 등록되어 있습니다.");
        }
    }

    public boolean handles(String type) {
        return handlers.containsKey(type);
    }

    /** 받은 한 줄을 처리한다. 무슨 일이 있어도 예외를 밖으로 던지지 않는다. */
    public void dispatch(Connection connection, String line) {
        Message request;
        try {
            request = Message.parse(line);
        } catch (Message.Malformed e) {
            log.debug("#{} 읽을 수 없는 줄: {}", connection.id(), e.getMessage());
            Message response = e.failResponse();
            connection.send(response != null ? response : Message.error(e.code(), e.getMessage(), e.causeType()));
            return;
        } catch (ProtocolException e) {
            connection.send(Message.error(e.code(), e.getMessage(), null));
            return;
        }

        MessageHandler handler = handlers.get(request.type());
        if (handler == null) {
            fail(connection, request, new ProtocolException(ErrorCode.UNKNOWN_TYPE));
            return;
        }

        try {
            Object body = handler.handle(connection, request);
            if (request.expectsResponse() && !connection.isClosed()) {
                connection.send(Message.ok(request, body));
            }
        } catch (ProtocolException e) {
            fail(connection, request, e);
        } catch (RuntimeException e) {
            log.error("#{} {} 처리 중 오류", connection.id(), request.type(), e);
            fail(connection, request, new ProtocolException(ErrorCode.SERVER_ERROR));
        }
    }

    private static void fail(Connection connection, Message request, ProtocolException e) {
        if (request.expectsResponse()) {
            connection.send(Message.fail(request, e.code(), e.getMessage()));
        } else {
            connection.send(Message.error(e.code(), e.getMessage(), request.type()));
        }
    }
}

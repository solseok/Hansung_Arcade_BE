package hansung.arcade.core.net;

/**
 * type 하나를 처리한다. {@link MessageDispatcher#register}로 등록한다.
 *
 * <p>받는 스레드(연결마다 하나)에서 불리므로 여러 연결의 핸들러가 동시에 돈다.
 * 방·로비처럼 여러 연결이 함께 쓰는 상태는 핸들러 쪽에서 lock으로 지킨다.</p>
 *
 * <pre>
 * dispatcher.register("ROOM_JOIN", (connection, request) -> {
 *     JoinBody body = request.bodyAs(JoinBody.class);
 *     if (room.isFull()) {
 *         throw new ProtocolException("ROOM_FULL", "자리가 없습니다.");
 *     }
 *     return room.snapshot();   // ok:true 응답의 body
 * });
 * </pre>
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * @return 요청에 id가 있으면 {@code ok:true} 응답의 body (record·Map·JsonNode, null이면 {@code {}}).
     *         id가 없는 메시지면 무시된다
     * @throws ProtocolException 처리할 수 없으면. id가 있으면 {@code ok:false} 응답, 없으면 ERROR 푸시가 나간다
     */
    Object handle(Connection connection, Message request) throws ProtocolException;
}

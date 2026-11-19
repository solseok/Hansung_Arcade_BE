package hansung.arcade.core.net;

/**
 * 연결이 생기고 끊길 때 알림을 받는다. {@link GameServer#addListener}로 등록한다.
 * 로비·방 같은 도메인은 여기서 접속자 정리(방 나가기, 방장 넘기기, 닉네임 풀기)를 한다.
 */
public interface ConnectionListener {

    /** 접속 직후, 받기를 시작하기 전에 accept 스레드에서 불린다. 여기서 보낸 메시지는 HELLO 다음에 나간다 */
    default void onOpen(Connection connection) {
    }

    /**
     * 연결이 끝났을 때 한 번만 불린다. BYE, 상대가 닫음, 15초 무응답, 오류, 서버 종료 모두 여기로 모인다.
     *
     * @param reason 로그용 이유
     */
    default void onClose(Connection connection, String reason) {
    }
}

package hansung.arcade.domain.session.model;

/**
 * 연결 하나가 지금 어느 화면에 있는지. 메시지마다 "보낼 수 있는 상태"를 검사하는 데 쓴다.
 * 대기실·게임 중은 방 API를 만들 때 더한다.
 */
public enum SessionState {
    /** 연결 직후, 로그인 전. LOGIN만 보낼 수 있다 */
    CONNECTED,
    /** 로그인 뒤, 방 밖 */
    LOBBY,
    /** 연결이 끝났다. 더는 아무것도 처리하지 않는다 */
    CLOSED
}

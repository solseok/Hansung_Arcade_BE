package hansung.arcade.domain.session.model;

/**
 * 연결의 로그인 생명주기. 대기실·경기 상태는 RoomService와 GameService가 관리한다.
 */
public enum SessionState {
    /** 연결 직후, 로그인 전. LOGIN만 보낼 수 있다 */
    CONNECTED,
    /** 로그인 완료. 방 안인지 여부는 RoomService에서 확인한다 */
    LOBBY,
    /** 연결이 끝났다. 더는 아무것도 처리하지 않는다 */
    CLOSED
}

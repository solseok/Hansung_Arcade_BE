package hansung.arcade.core.net;

/**
 * 네프 API 공통 규칙의 숫자와 공통 메시지 type. 바뀌면 Notion "네프 API" 페이지부터 고치고 여기를 맞춘다.
 */
public final class Protocol {

    /** 메시지 모양(공통 규칙, 필드 이름)이 바뀔 때만 1씩 올린다. 연결 직후 HELLO로 알린다 */
    public static final int VERSION = 1;

    /** 한 줄 최대 길이 (UTF-8 바이트, \n 제외). 넘으면 MESSAGE_TOO_LONG을 보내고 끊는다 */
    public static final int MAX_LINE_BYTES = 32 * 1024;

    /** 이 시간 동안 아무 줄도 못 받으면 끊긴 것으로 본다. 클라이언트는 5초마다 PING을 보낸다 */
    public static final int IDLE_TIMEOUT_MS = 15_000;

    /** 서버 → 클라: accept 직후 한 번 */
    public static final String HELLO = "HELLO";
    /** 클라 → 서버 → 클라: 연결 확인 (요청 → 응답) */
    public static final String PING = "PING";
    /** 클라 → 서버: 정상 종료 (응답 없음) */
    public static final String BYE = "BYE";
    /** 서버 → 클라: id가 없는 메시지의 실패, 읽을 수 없는 줄 */
    public static final String ERROR = "ERROR";

    private Protocol() {
    }
}

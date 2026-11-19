package hansung.arcade.core.boot;

import hansung.arcade.core.server.ServerContext;

/**
 * 서버 구동 단계 하나. 필요한 객체를 만들어 {@link ServerContext}에 등록한다.
 */
public interface BootStep {

    /** 로그와 오류 메시지에 쓰는 단계 이름 */
    String name();

    void run(ServerContext context) throws Exception;
}

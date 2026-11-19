package hansung.arcade.core.boot;

import hansung.arcade.core.server.ServerContext;
import hansung.arcade.core.server.ServerProperties;

/**
 * 1단계: application.properties와 실행 인자를 읽어 {@link ServerProperties}를 등록한다.
 */
public final class PropertiesStep implements BootStep {

    @Override
    public String name() {
        return "설정 로드";
    }

    @Override
    public void run(ServerContext context) {
        context.register(ServerProperties.class, ServerProperties.load(context.primarySource(), context.args()));
    }
}

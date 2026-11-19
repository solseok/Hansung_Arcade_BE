package hansung.arcade.core.server;

import hansung.arcade.core.boot.AcceptStep;
import hansung.arcade.core.boot.BootException;
import hansung.arcade.core.boot.BootStep;
import hansung.arcade.core.boot.DispatcherStep;
import hansung.arcade.core.boot.PropertiesStep;
import hansung.arcade.core.boot.ServerSocketStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 서버 구동기. 부트 단계({@link BootStep})를 순서대로 실행해 서버를 조립한다.
 *
 * <pre>
 * public static void main(String[] args) {
 *     ServerApplication.run(HansungArcadeServerApplication.class, args);
 * }
 * </pre>
 *
 * 단계 중 하나라도 실패하면 어느 단계에서 왜 실패했는지 로그로 알리고 종료한다.
 * 구동이 끝나면 main 스레드는 돌아가고, accept 스레드가 서버를 계속 살려 둔다.
 */
public final class ServerApplication {

    private static final Logger log = LoggerFactory.getLogger(ServerApplication.class);

    private final Class<?> primarySource;
    private final List<BootStep> steps;

    ServerApplication(Class<?> primarySource, List<BootStep> steps) {
        this.primarySource = primarySource;
        this.steps = List.copyOf(steps);
    }

    public static void run(Class<?> primarySource, String... args) {
        Banner.print(primarySource, System.out);
        ServerApplication application = new ServerApplication(primarySource, defaultSteps());
        try {
            application.boot(args);
        } catch (BootException e) {
            try {
                log.error(e.getMessage(), e.getCause());
            } finally {
                // 포트만 열린 채 반쯤 뜬 상태로 남지 않게 반드시 종료한다.
                System.exit(1);
            }
        }
    }

    static List<BootStep> defaultSteps() {
        return List.of(
                new PropertiesStep(),
                new DispatcherStep(),
                new ServerSocketStep(),
                new AcceptStep());
    }

    ServerContext boot(String... args) {
        ServerContext context = new ServerContext(primarySource, List.of(args));
        long bootStart = System.nanoTime();
        log.info("{} 구동 시작", primarySource.getSimpleName());

        for (int i = 0; i < steps.size(); i++) {
            BootStep step = steps.get(i);
            long stepStart = System.nanoTime();
            try {
                step.run(context);
            } catch (Exception | LinkageError e) {
                // 클래스를 초기화하지 못해도(LinkageError) 어느 단계인지 알린다. 메모리 부족 같은 JVM 오류는 감싸지 않는다.
                throw new BootException(step.name(), e);
            }
            log.info("({}/{}) {} 완료 - {} ms", i + 1, steps.size(), step.name(), elapsedMillis(stepStart));
        }

        log.info("구동 완료 - {} ms", elapsedMillis(bootStart));
        return context;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

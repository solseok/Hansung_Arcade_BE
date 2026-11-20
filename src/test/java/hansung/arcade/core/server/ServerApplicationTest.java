package hansung.arcade.core.server;

import hansung.arcade.core.boot.AcceptStep;
import hansung.arcade.core.boot.BootException;
import hansung.arcade.core.boot.BootStep;
import hansung.arcade.core.boot.DispatcherStep;
import hansung.arcade.core.boot.PropertiesStep;
import hansung.arcade.core.boot.ServerSocketStep;
import hansung.arcade.core.boot.ServiceStep;
import hansung.arcade.core.net.GameServer;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerApplicationTest {

    private record RecordingStep(String name, List<String> log, Runnable action) implements BootStep {
        @Override
        public void run(ServerContext context) {
            log.add(name);
            action.run();
        }
    }

    @Test
    void 단계를_순서대로_실행하고_앞_단계가_등록한_객체를_뒤_단계가_쓴다() {
        List<String> log = new ArrayList<>();
        BootStep producer = new BootStep() {
            @Override
            public String name() {
                return "생산";
            }

            @Override
            public void run(ServerContext context) {
                log.add("생산");
                context.register(String.class, "준비됨");
            }
        };
        BootStep consumer = new BootStep() {
            @Override
            public String name() {
                return "소비";
            }

            @Override
            public void run(ServerContext context) {
                log.add("소비:" + context.get(String.class));
            }
        };

        new ServerApplication(ServerApplicationTest.class, List.of(producer, consumer)).boot();

        assertEquals(List.of("생산", "소비:준비됨"), log);
    }

    @Test
    void 실패한_단계에서_멈추고_단계_이름을_알려준다() {
        List<String> log = new ArrayList<>();
        List<BootStep> steps = List.of(
                new RecordingStep("첫째", log, () -> {
                }),
                new RecordingStep("둘째", log, () -> {
                    throw new IllegalStateException("고장");
                }),
                new RecordingStep("셋째", log, () -> {
                }));

        BootException e = assertThrows(BootException.class,
                () -> new ServerApplication(ServerApplicationTest.class, steps).boot());

        assertEquals(List.of("첫째", "둘째"), log);
        assertEquals("둘째", e.stepName());
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(e.getMessage().contains("고장"), e.getMessage());
    }

    @Test
    void 기본_단계는_설정_처리기_소켓_접속_순서다() {
        List<Class<?>> types = ServerApplication.defaultSteps().stream().<Class<?>>map(Object::getClass).toList();

        assertEquals(List.of(PropertiesStep.class, ServiceStep.class, DispatcherStep.class, ServerSocketStep.class, AcceptStep.class), types);
    }

    @Test
    void 포트를_이미_쓰고_있으면_소켓_단계에서_실패한다() throws Exception {
        try (ServerSocket taken = new ServerSocket(0)) {
            List<BootStep> steps = List.of(new PropertiesStep(), new ServiceStep(), new DispatcherStep(), new ServerSocketStep());

            BootException e = assertThrows(BootException.class,
                    () -> new ServerApplication(ServerApplicationTest.class, steps).boot("--network.port=" + taken.getLocalPort()));

            assertEquals("서버 소켓 열기", e.stepName());
            assertTrue(e.getMessage().contains("이미 다른 프로그램"), e.getMessage());
        }
    }

    @Test
    void 포트_0이면_빈_포트를_연다() {
        List<BootStep> steps = List.of(new PropertiesStep(), new ServiceStep(), new DispatcherStep(), new ServerSocketStep());

        ServerContext context = new ServerApplication(ServerApplicationTest.class, steps).boot("--network.port=0");

        try (GameServer server = context.get(GameServer.class)) {
            assertTrue(server.port() > 0);
        }
    }
}

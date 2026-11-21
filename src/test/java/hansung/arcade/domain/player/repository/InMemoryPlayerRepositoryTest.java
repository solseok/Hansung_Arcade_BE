package hansung.arcade.domain.player.repository;

import hansung.arcade.domain.player.exception.DuplicateNicknameException;
import hansung.arcade.domain.player.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPlayerRepositoryTest {

    private final InMemoryPlayerRepository repository = new InMemoryPlayerRepository();

    @Test
    void 등록한_순서대로_1부터_playerId를_붙인다() {
        List<Player> first = repository.saveAll(List.of("상상부기", "꾸꾸"));
        List<Player> second = repository.saveAll(List.of("한성"));

        assertEquals(List.of(new Player(1, "상상부기"), new Player(2, "꾸꾸")), first);
        assertEquals(List.of(new Player(3, "한성")), second);
        assertEquals(first.get(1), repository.findById(2).orElseThrow());
        assertEquals(3, repository.findAll().size());
    }

    @Test
    void 영문은_대소문자를_구분하지_않고_중복으로_본다() {
        repository.saveAll(List.of("Bugi"));

        DuplicateNicknameException e = assertThrows(DuplicateNicknameException.class,
                () -> repository.saveAll(List.of("bUGI")));
        assertEquals("bUGI", e.nickname());
        assertTrue(repository.existsByNickname("BUGI"));
    }

    @Test
    void 두_닉네임_중_하나라도_겹치면_아무것도_등록하지_않는다() {
        repository.saveAll(List.of("꾸꾸"));

        assertThrows(DuplicateNicknameException.class, () -> repository.saveAll(List.of("상상부기", "꾸꾸")));

        assertFalse(repository.existsByNickname("상상부기"));
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void 지우면_같은_닉네임을_다시_쓸_수_있다() {
        List<Player> saved = repository.saveAll(List.of("상상부기", "꾸꾸"));

        repository.deleteAll(saved);
        repository.deleteAll(saved); // 두 번 지워도 괜찮다

        assertTrue(repository.findAll().isEmpty());
        assertEquals(List.of(new Player(3, "꾸꾸")), repository.saveAll(List.of("꾸꾸")));
    }

    @Test
    void 이미_풀린_닉네임을_다른_사람이_쓰면_옛_플레이어로_지워도_남는다() {
        List<Player> old = repository.saveAll(List.of("꾸꾸"));
        repository.deleteAll(old);
        repository.saveAll(List.of("꾸꾸"));

        repository.deleteAll(old);

        assertTrue(repository.existsByNickname("꾸꾸"));
    }

    @Test
    void 여러_연결이_같은_닉네임을_동시에_요청하면_하나만_성공한다() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // 절반은 1P("부기"), 절반은 2P("꾸꾸", "부기")로 겹치게 보낸다
                List<String> nicknames = i % 2 == 0 ? List.of("부기") : List.of("꾸꾸", "부기");
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        repository.saveAll(nicknames);
                        succeeded.incrementAndGet();
                    } catch (DuplicateNicknameException ignored) {
                        // 기대한 실패
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, succeeded.get());
        assertEquals(1, repository.findAll().stream().filter(p -> p.nickname().equals("부기")).count());
    }
}

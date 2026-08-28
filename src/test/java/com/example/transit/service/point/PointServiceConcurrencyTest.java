package com.example.transit.service.point;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 동시 차감 경합 검증 - 이 기능의 핵심 증거자료.
 * <p>
 * PointService는 {@code LedgerAccountRepository.findForUpdate}({@code SELECT ... FOR UPDATE})로
 * 사용자 포인트 계정 행을 잡아 차감을 직렬화한다. 락이 없다면 여러 스레드가 같은 잔액을 읽고
 * 각자 차감해서 잔액이 음수가 되거나(초과 인출) 분개가 유실된다.
 */
@SpringBootTest
class PointServiceConcurrencyTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(2_000_000);

    @Autowired
    private PointService pointService;

    @Test
    void 잔액을_초과하는_동시_차감_요청은_잔액_한도까지만_성공하고_음수가_되지_않는다() throws InterruptedException {
        long userId = USER_SEQ.incrementAndGet();
        int threads = 30;
        long perSpend = 100;
        long initial = 1_000; // 딱 10건만 성공 가능

        pointService.earn(userId, initial, "seed:" + userId, "test", 0L);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    pointService.spend(userId, perSpend, "spend:" + userId + ":" + idx, "test", 0L);
                    ok.incrementAndGet();
                } catch (InsufficientPointException e) {
                    insufficient.incrementAndGet();
                } catch (Exception e) {
                    unexpected.add(e.getClass().getName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "제한 시간 내 종료");
        pool.shutdown();

        assertEquals("[]", unexpected.toString(), "락 타임아웃 등 예상 못한 예외 없음");
        assertEquals(10, ok.get(), "잔액으로 감당 가능한 건수만 성공");
        assertEquals(20, insufficient.get());
        assertEquals(0, pointService.balanceOf(userId), "정확히 0, 절대 음수 아님");
    }

    @Test
    void 잔액이_충분하면_모든_동시_차감이_성공하고_합이_정확하다() throws InterruptedException {
        long userId = USER_SEQ.incrementAndGet();
        int threads = 30;
        long perSpend = 100;

        pointService.earn(userId, 5_000, "seed:" + userId, "test", 0L);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    pointService.spend(userId, perSpend, "s:" + userId + ":" + idx, "test", 0L);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, fail.get());
        assertEquals(threads, ok.get());
        assertEquals(5_000 - threads * perSpend, pointService.balanceOf(userId));
    }
}

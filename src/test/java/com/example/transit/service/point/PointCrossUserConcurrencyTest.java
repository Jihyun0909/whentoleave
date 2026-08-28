package com.example.transit.service.point;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서로 다른 사용자들이 동시에 포인트를 적립·차감하면, 공유 계정 {@code POINT_CONTRA}에서
 * {@code @Version} 낙관적 락 충돌이 나고 {@code RetryingTransactionRunner}가 트랜잭션째로
 * 재시도한다. 이 테스트는 그래도 (1) 예외 없이 전부 성공하고 (2) 개별 잔액이 정확하며
 * (3) 불변식 {@code POINT_CONTRA.balance == Σ(POINT.balance)}가 유지됨을 확인한다.
 */
@SpringBootTest
class PointCrossUserConcurrencyTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(3_000_000);

    @Autowired
    private PointService pointService;
    @Autowired
    private LedgerAccountRepository accounts;

    private long contraBalance() {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(
                        AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA)
                .map(LedgerAccount::getBalance).orElse(0L);
    }

    private long sumUserPoints(long[] userIds) {
        long sum = 0;
        for (long id : userIds) {
            sum += accounts.findByOwnerTypeAndOwnerIdAndKind(AccountOwnerType.USER, id, AccountKind.POINT)
                    .map(LedgerAccount::getBalance).orElse(0L);
        }
        return sum;
    }

    @Test
    void 여러_사용자가_동시에_적립_차감해도_전부_성공하고_불변식이_유지된다() throws InterruptedException {
        // 현실적인 동시 버스트 규모. 공유 CONTRA 계정에서 낙관적 락 충돌이 나지만
        // RetryingTransactionRunner가 흡수한다(극단적 경합에서의 한계는 문서에 명시).
        int users = 12;
        long[] userIds = new long[users];
        for (int i = 0; i < users; i++) {
            userIds[i] = USER_SEQ.incrementAndGet();
        }
        long contraBefore = contraBalance();

        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        ExecutorService pool = Executors.newFixedThreadPool(users);

        for (long userId : userIds) {
            pool.submit(() -> {
                try {
                    start.await();
                    pointService.earn(userId, 1_000, "earn:" + userId, "test", 0L);   // +1000
                    pointService.spend(userId, 400, "spend:" + userId, "test", 0L);    // -400  => 600
                } catch (Exception e) {
                    failures.add(e.getClass().getName() + ": " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "제한 시간 내 종료");
        pool.shutdown();

        assertEquals("[]", failures.toString(), "재시도로 전부 흡수되어 예외 없음");
        for (long userId : userIds) {
            assertEquals(600, pointService.balanceOf(userId), "userId=" + userId);
        }
        // 이 테스트 사용자들은 0에서 시작했으므로, CONTRA 순증가 == 이들의 POINT 잔액 합
        assertEquals(sumUserPoints(userIds), contraBalance() - contraBefore,
                "불변식: CONTRA 증가분 == Σ(USER POINT)");
        assertEquals(users * 600L, sumUserPoints(userIds));
    }
}

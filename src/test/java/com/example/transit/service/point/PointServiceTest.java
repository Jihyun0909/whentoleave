package com.example.transit.service.point;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 단일 스레드 동작 검증. 동시성은 {@link PointServiceConcurrencyTest}에서 따로 본다.
 * ({@code @Transactional}을 안 붙인다 - PointService가 자체 트랜잭션을 열고, 테스트는
 * 고유 userId로 격리한다.)
 */
@SpringBootTest
class PointServiceTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(1_000_000);

    @Autowired
    private PointService pointService;
    @Autowired
    private LedgerAccountRepository accounts;

    private long contraBalance() {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(
                        AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA)
                .map(LedgerAccount::getBalance).orElse(0L);
    }

    @Test
    void 적립_후_차감이_잔액에_반영된다() {
        long userId = USER_SEQ.incrementAndGet();

        pointService.earn(userId, 1000, "earn:" + userId, "ride", 1L);
        assertEquals(1000, pointService.balanceOf(userId));

        pointService.spend(userId, 300, "spend:" + userId, "ride", 1L);
        assertEquals(700, pointService.balanceOf(userId));
    }

    @Test
    void 잔액보다_많이_차감하면_InsufficientPointException() {
        long userId = USER_SEQ.incrementAndGet();
        pointService.earn(userId, 100, "earn:" + userId, "ride", 1L);

        assertThrows(InsufficientPointException.class,
                () -> pointService.spend(userId, 101, "spend:" + userId, "ride", 1L));
        assertEquals(100, pointService.balanceOf(userId), "실패한 차감은 잔액을 안 건드린다");
    }

    @Test
    void CONTRA_잔액은_항상_전체_사용자_POINT_합과_같다() {
        long before = contraBalance();
        long u1 = USER_SEQ.incrementAndGet();
        long u2 = USER_SEQ.incrementAndGet();

        pointService.earn(u1, 500, "earn:" + u1, "ride", 1L);
        pointService.earn(u2, 800, "earn:" + u2, "ride", 1L);
        pointService.spend(u1, 200, "spend:" + u1, "ride", 1L);

        // 이 테스트가 더한 순변화: +500 +800 -200 = +1100
        assertEquals(before + 1100, contraBalance());
    }

    @Test
    void 같은_key로_적립을_두_번_하면_한_번만_반영된다() {
        long userId = USER_SEQ.incrementAndGet();
        pointService.earn(userId, 500, "dup:" + userId, "ride", 7L);
        pointService.earn(userId, 500, "dup:" + userId, "ride", 7L);

        assertEquals(500, pointService.balanceOf(userId));
    }

    @Test
    void 적립액이_0이면_아무것도_안_한다() {
        long userId = USER_SEQ.incrementAndGet();
        pointService.earn(userId, 0, "zero:" + userId, "ride", 1L);
        assertEquals(0, pointService.balanceOf(userId));
    }
}

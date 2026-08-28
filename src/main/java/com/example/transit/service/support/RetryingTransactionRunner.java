package com.example.transit.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * "트랜잭션 안에서 작업을 돌리되, 낙관적 락 충돌이 나면 트랜잭션째로 다시 시도한다."
 * <p>
 * 포인트 원장에서 사용자별 {@code POINT} 계정은 비관적 락으로 직렬화하지만, 전 사용자가
 * 공유하는 {@code POINT_CONTRA} 계정은 {@code @Version} 낙관적 락만 건다 - 다른 사용자의
 * 트랜잭션과 부딪히면 여기서 재실행한다(트랜잭션이 롤백됐으므로 처음부터 다시 하는 게 안전).
 * <p>
 * 이미 트랜잭션 안이면({@code RideService.complete} → {@code PointService.spend}) 안쪽
 * {@link #run}은 그 트랜잭션에 합류할 뿐이라 여기서 재시도가 일어나지 않고, 가장 바깥의
 * {@link #run}이 커밋 시점의 충돌을 받아 전체를 다시 돌린다.
 */
@Component
public class RetryingTransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetryingTransactionRunner.class);
    private static final int MAX_ATTEMPTS = 12;
    private static final long BASE_BACKOFF_MILLIS = 10;
    private static final long MAX_BACKOFF_MILLIS = 800;

    private final TransactionTemplate transactionTemplate;

    public RetryingTransactionRunner(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws RetryExhaustedException 최대 시도 횟수만큼 낙관적 락 충돌이 이어졌을 때
     */
    public <T> T run(Supplier<T> work) {
        OptimisticLockingFailureException last = null;
        long sleep = BASE_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (OptimisticLockingFailureException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    log.debug("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_ATTEMPTS);
                    sleep = backoff(sleep);
                }
            }
        }
        throw new RetryExhaustedException(MAX_ATTEMPTS, last);
    }

    public void run(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    /**
     * decorrelated jitter: 다음 대기 = random(base, 직전 대기 * 3), 상한 있음.
     * 여러 스레드가 같은 리듬으로 재충돌하는 걸 피하면서 경합이 길어질수록 넓게 퍼진다.
     */
    private static long backoff(long previousSleep) {
        long next = Math.min(MAX_BACKOFF_MILLIS,
                ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS, previousSleep * 3 + 1));
        try {
            Thread.sleep(next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryExhaustedException(-1, e);
        }
        return next;
    }
}

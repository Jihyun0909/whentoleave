package com.example.transit.service.point;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 기본 포인트 락 전략: 별도 분산 락 없이 트랜잭션 경계만 연다. 실제 직렬화는 이 트랜잭션
 * 안에서 {@code LedgerAccountRepository.findForUpdate}가 거는 행 수준 비관적 락이 담당한다.
 * <p>
 * {@code app.point.lock-strategy} 미설정 또는 {@code db}일 때 활성.
 */
@Component
@ConditionalOnProperty(name = "app.point.lock-strategy", havingValue = "db", matchIfMissing = true)
public class DbLockPointLockStrategy implements PointLockStrategy {

    private final TransactionTemplate transactionTemplate;

    public DbLockPointLockStrategy(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T executeGuarded(long userId, Supplier<T> operation) {
        return transactionTemplate.execute(status -> operation.get());
    }
}

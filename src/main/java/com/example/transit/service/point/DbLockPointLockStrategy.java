package com.example.transit.service.point;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 기본 포인트 락 전략: 별도 락 획득 없음. 한 사용자의 포인트 연산 직렬화는 이 안에서 실행되는
 * {@code LedgerAccountRepository.findForUpdate}({@code SELECT ... FOR UPDATE})가 담당하고,
 * 전 사용자 공유 계정({@code POINT_CONTRA})의 경합은 {@code RetryingTransactionRunner}의
 * 낙관적 락 재시도가 처리한다.
 * <p>
 * {@code app.point.lock-strategy} 미설정 또는 {@code db}일 때 활성.
 * <p>
 * Redisson 전략을 추가한다면 여기서 사용자별 {@code RLock}을 잡되, 트랜잭션·재시도 경계는
 * 이 호출을 감싸는 {@code RetryingTransactionRunner} 바깥에 있어야 하므로
 * {@code PointService}의 호출 순서(락 → 재시도 → 트랜잭션)를 재구성해야 한다.
 */
@Component
@ConditionalOnProperty(name = "app.point.lock-strategy", havingValue = "db", matchIfMissing = true)
public class DbLockPointLockStrategy implements PointLockStrategy {

    @Override
    public <T> T executeGuarded(long userId, Supplier<T> operation) {
        return operation.get();
    }
}

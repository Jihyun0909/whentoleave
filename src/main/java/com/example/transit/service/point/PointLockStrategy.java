package com.example.transit.service.point;

import java.util.function.Supplier;

/**
 * 한 사용자의 포인트 연산을 직렬화하는 방법을 추상화한다. 기획안의
 * "DB Lock(비관적 락) 또는 Redis(Redisson) 기반 분산 락" 중 무엇을 쓰는지를 여기서 가른다.
 * <p>
 * 기본 구현({@code DbLockPointLockStrategy})은 트랜잭션 경계만 열고, 실제 상호배제는 그 안에서
 * {@code LedgerAccountRepository.findForUpdate}가 거는 {@code SELECT ... FOR UPDATE}로 한다.
 * 멀티 인스턴스로 확장해 DB 락만으로 부족해지면 {@code RedissonPointLockStrategy}를 추가해
 * "분산 락 획득 → 트랜잭션" 순서로 감싸면 된다({@code app.point.lock-strategy=redisson}).
 */
public interface PointLockStrategy {

    <T> T executeGuarded(long userId, Supplier<T> operation);

    default void executeGuarded(long userId, Runnable operation) {
        executeGuarded(userId, () -> {
            operation.run();
            return null;
        });
    }
}

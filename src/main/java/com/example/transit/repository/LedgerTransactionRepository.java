package com.example.transit.repository;

import com.example.transit.domain.ledger.LedgerTransaction;
import org.springframework.data.repository.Repository;

import java.util.Optional;

/**
 * 불변 테이블이라 {@code JpaRepository} 대신 {@code Repository} 마커를 쓰고 필요한 메서드만
 * 노출한다 - update/delete 진입점 자체를 두지 않는다.
 */
public interface LedgerTransactionRepository extends Repository<LedgerTransaction, Long> {

    LedgerTransaction save(LedgerTransaction transaction);

    Optional<LedgerTransaction> findById(Long id);

    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);
}

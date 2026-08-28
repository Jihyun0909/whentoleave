package com.example.transit.repository;

import com.example.transit.domain.ledger.LedgerEntry;
import com.example.transit.service.point.PointLedgerLine;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 불변 테이블. 필요한 메서드만 노출한다({@link LedgerTransactionRepository} 주석 참고). */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    LedgerEntry save(LedgerEntry entry);

    List<LedgerEntry> findByTransactionId(Long transactionId);

    List<LedgerEntry> findByAccountIdOrderByIdDesc(Long accountId);

    /** 한 트랜잭션의 차변 합 - 대변 합. 0이어야 분개가 균형이다. */
    @Query("select coalesce(sum(case when e.direction = com.example.transit.domain.ledger.EntryDirection.DEBIT "
            + "then e.amount else -e.amount end), 0) "
            + "from LedgerEntry e where e.transactionId = :transactionId")
    long signedSum(@Param("transactionId") Long transactionId);

    /** 한 계정의 이력(분개 + 트랜잭션 메타), 최신순. */
    @Query("select new com.example.transit.service.point.PointLedgerLine("
            + "e.direction, e.amount, t.type, t.refType, t.refId, e.createdAt) "
            + "from LedgerEntry e join LedgerTransaction t on t.id = e.transactionId "
            + "where e.accountId = :accountId order by e.id desc")
    List<PointLedgerLine> findHistory(@Param("accountId") Long accountId);
}

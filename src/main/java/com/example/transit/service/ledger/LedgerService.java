package com.example.transit.service.ledger;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.domain.ledger.LedgerEntry;
import com.example.transit.domain.ledger.LedgerTransaction;
import com.example.transit.domain.ledger.LedgerTransactionType;
import com.example.transit.repository.LedgerAccountRepository;
import com.example.transit.repository.LedgerEntryRepository;
import com.example.transit.repository.LedgerTransactionRepository;
import com.example.transit.service.audit.AuditLogWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 복식부기 분개를 기록한다. 한 번 호출 = 한 {@link LedgerTransaction} + 균형 잡힌 {@link LedgerEntry}
 * 집합. 트랜잭션·분개는 불변이고, 계정 잔액({@link LedgerAccount#getBalance()})은 같은 DB
 * 트랜잭션에서 함께 갱신한다.
 * <p>
 * 호출부는 이미 락을 잡은({@code LedgerAccountRepository.findForUpdate}) 계정 객체를 넘겨야 한다 -
 * 이 클래스는 락 획득 순서를 모른다.
 * <p>
 * {@code @Transactional}을 붙이지 않는다: 호출부(PointService 등)가 락과 함께 트랜잭션 경계를
 * 관리하고, 이 메서드는 그 안에서 실행된다.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final LedgerAccountRepository accounts;
    private final AuditLogWriter audit;

    public LedgerService(LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                         LedgerAccountRepository accounts, AuditLogWriter audit) {
        this.transactions = transactions;
        this.entries = entries;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** 분개 한 줄: 어느 계정에 어느 방향으로 얼마. */
    public record Posting(LedgerAccount account, EntryDirection direction, long amount) {
    }

    /**
     * @param idempotencyKey 안정적인 문자열(예 "PAYBACK:ride:42"). 같은 키로 다시 들어오면
     *                       아무것도 하지 않고 기존 트랜잭션을 돌려준다.
     * @return 새로 만들었거나 이미 존재하던 {@link LedgerTransaction}
     */
    public LedgerTransaction post(LedgerTransactionType type, String idempotencyKey,
                                  String refType, Long refId, String memo, List<Posting> postings) {
        return transactions.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    audit.record(AuditEvent.DUPLICATE_IDEMPOTENCY, null, refType, refId,
                            "이미 처리된 분개: " + idempotencyKey);
                    return existing;
                })
                .orElseGet(() -> insert(type, idempotencyKey, refType, refId, memo, postings));
    }

    private LedgerTransaction insert(LedgerTransactionType type, String idempotencyKey,
                                     String refType, Long refId, String memo, List<Posting> postings) {
        validate(postings, refType, refId);

        LedgerTransaction tx;
        try {
            tx = transactions.save(new LedgerTransaction(type, refType, refId, idempotencyKey, memo));
        } catch (DataIntegrityViolationException raced) {
            // 같은 키로 동시 삽입 - 유니크 제약이 잡았다. 상대가 만든 걸 돌려준다.
            return transactions.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> raced);
        }

        for (Posting p : postings) {
            entries.save(new LedgerEntry(tx.getId(), p.account().getId(), p.direction(), p.amount()));
            p.account().apply(p.direction(), p.amount());
            accounts.save(p.account());
        }
        return tx;
    }

    private void validate(List<Posting> postings, String refType, Long refId) {
        if (postings.size() < 2) {
            throw new LedgerPostingException("분개는 최소 2줄이어야 합니다");
        }
        long signedSum = 0;
        for (Posting p : postings) {
            if (p.amount() <= 0) {
                throw new LedgerPostingException("분개 금액은 양수여야 합니다: " + p.amount());
            }
            signedSum += p.direction() == EntryDirection.DEBIT ? p.amount() : -p.amount();
        }
        if (signedSum != 0) {
            audit.record(AuditEvent.LEDGER_IMBALANCE, null, refType, refId,
                    "차변-대변 차이 " + signedSum);
            log.error("분개 불균형 refType={} refId={} 차이={}", refType, refId, signedSum);
            throw new LedgerPostingException("차변 합과 대변 합이 다릅니다: " + signedSum);
        }
    }
}

package com.example.transit.service.point;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.domain.ledger.LedgerTransactionType;
import com.example.transit.repository.LedgerAccountRepository;
import com.example.transit.repository.LedgerEntryRepository;
import com.example.transit.service.audit.AuditLogWriter;
import com.example.transit.service.ledger.LedgerAccountService;
import com.example.transit.service.ledger.LedgerService;
import com.example.transit.service.support.RetryingTransactionRunner;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 선불 포인트 적립·차감. 복식부기로만 잔액을 움직인다:
 * <ul>
 *   <li>적립(5% 페이백): DEBIT {@code POINT_CONTRA} / CREDIT 사용자 {@code POINT}</li>
 *   <li>차감(결제): DEBIT 사용자 {@code POINT} / CREDIT {@code POINT_CONTRA}</li>
 * </ul>
 * 동시성 제어는 두 층이다:
 * <ol>
 *   <li>사용자별 {@code POINT} 계정 - {@code findForUpdate}({@code SELECT ... FOR UPDATE}) 비관적 락.
 *       한 사용자의 연산은 여기서 직렬화되어 잔액이 음수가 되지 않는다.</li>
 *   <li>전 사용자 공유 {@code POINT_CONTRA} 계정 - {@code @Version} 낙관적 락. 다른 사용자와
 *       부딪히면 {@link RetryingTransactionRunner}가 트랜잭션째로 다시 시도한다. 공유 행을
 *       비관적으로 잡지 않으므로 느린 트랜잭션 하나가 전체를 막지 않는다.</li>
 * </ol>
 */
@Service
public class PointService {

    private final PointLockStrategy lockStrategy;
    private final RetryingTransactionRunner retryRunner;
    private final LedgerAccountService ledgerAccounts;
    private final LedgerAccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final LedgerService ledger;
    private final AuditLogWriter audit;

    public PointService(PointLockStrategy lockStrategy, RetryingTransactionRunner retryRunner,
                        LedgerAccountService ledgerAccounts, LedgerAccountRepository accounts,
                        LedgerEntryRepository entries, LedgerService ledger, AuditLogWriter audit) {
        this.lockStrategy = lockStrategy;
        this.retryRunner = retryRunner;
        this.ledgerAccounts = ledgerAccounts;
        this.accounts = accounts;
        this.entries = entries;
        this.ledger = ledger;
        this.audit = audit;
    }

    /**
     * @param amount 적립할 포인트(0 이하면 아무것도 안 함 - 요금이 작아 5%가 1 미만인 경우)
     * @param idempotencyKey 예 "PAYBACK:ride:42"
     */
    public void earn(long userId, long amount, String idempotencyKey, String refType, Long refId) {
        if (amount <= 0) {
            return;
        }
        retryRunner.run(() -> lockStrategy.executeGuarded(userId, () -> {
            LedgerAccount userPoint = lockUserPoint(userId);
            LedgerAccount contra = systemContra();
            ledger.post(LedgerTransactionType.PAYBACK, idempotencyKey, refType, refId, "이용 완료 5% 적립",
                    List.of(new LedgerService.Posting(contra, EntryDirection.DEBIT, amount),
                            new LedgerService.Posting(userPoint, EntryDirection.CREDIT, amount)));
            return null;
        }));
    }

    /**
     * @throws InsufficientPointException 잔액 부족 (감사 로그 {@link AuditEvent#INSUFFICIENT_POINT} 기록 후)
     */
    public void spend(long userId, long amount, String idempotencyKey, String refType, Long refId) {
        if (amount <= 0) {
            return;
        }
        try {
            retryRunner.run(() -> lockStrategy.executeGuarded(userId, () -> {
                LedgerAccount userPoint = lockUserPoint(userId);
                if (userPoint.getBalance() < amount) {
                    throw new InsufficientPointException(amount, userPoint.getBalance());
                }
                LedgerAccount contra = systemContra();
                ledger.post(LedgerTransactionType.SPEND, idempotencyKey, refType, refId, "선불 포인트 차감",
                        List.of(new LedgerService.Posting(userPoint, EntryDirection.DEBIT, amount),
                                new LedgerService.Posting(contra, EntryDirection.CREDIT, amount)));
                return null;
            }));
        } catch (InsufficientPointException e) {
            // 락·트랜잭션을 벗어난 뒤에 기록한다(락을 쥔 채 REQUIRES_NEW로 감사 테이블에 쓰면
            // 커넥션 풀이 빠듯할 때 2번째 커넥션을 못 얻어 교착).
            audit.record(AuditEvent.INSUFFICIENT_POINT, userId, refType, refId,
                    "요청 " + amount + " / 잔액 " + e.balance());
            throw e;
        }
    }

    public long balanceOf(long userId) {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(AccountOwnerType.USER, userId, AccountKind.POINT)
                .map(LedgerAccount::getBalance)
                .orElse(0L);
    }

    /** 사용자 포인트 계정의 적립·차감 이력(최신순). 계정이 없으면 빈 목록. */
    public List<PointLedgerLine> historyOf(long userId) {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(AccountOwnerType.USER, userId, AccountKind.POINT)
                .map(account -> entries.findHistory(account.getId()))
                .orElseGet(List::of);
    }

    /** 사용자 POINT 계정을 비관적 락으로 잡는다(없으면 만든다). */
    private LedgerAccount lockUserPoint(long userId) {
        return ledgerAccounts.getForUpdate(AccountOwnerType.USER, userId, AccountKind.POINT);
    }

    /** 공유 CONTRA 계정은 락 없이 읽는다. 갱신 충돌은 {@code @Version} + 재시도로 처리한다. */
    private LedgerAccount systemContra() {
        return ledgerAccounts.getOrCreate(
                AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA);
    }
}

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
import com.example.transit.service.ledger.LedgerService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 선불 포인트 적립·차감. 복식부기로만 잔액을 움직인다:
 * <ul>
 *   <li>적립(5% 페이백): DEBIT {@code POINT_CONTRA} / CREDIT 사용자 {@code POINT}</li>
 *   <li>차감(결제): DEBIT 사용자 {@code POINT} / CREDIT {@code POINT_CONTRA}</li>
 * </ul>
 * 한 사용자의 연산은 {@link PointLockStrategy}로 직렬화하고, 그 안에서 사용자 포인트 계정 행을
 * {@code SELECT ... FOR UPDATE}로 잡아 잔액이 음수가 되거나 분개가 유실되는 경합을 막는다.
 * <p>
 * 알려진 한계: {@code POINT_CONTRA}는 모든 포인트 연산이 공유하는 단일 행이라, 여기에도 락을
 * 걸면 사용자 간에도 직렬화된다. 정합성 우선 설계이며, 처리량이 문제되면 이 계정을 샤딩하거나
 * (사용자 계정만 비관적 락 + CONTRA는 낙관적 락 + 재시도)로 바꾼다.
 */
@Service
public class PointService {

    private final PointLockStrategy lockStrategy;
    private final LedgerAccountRepository accounts;
    private final LedgerEntryRepository entries;
    private final LedgerService ledger;
    private final AuditLogWriter audit;

    public PointService(PointLockStrategy lockStrategy, LedgerAccountRepository accounts,
                        LedgerEntryRepository entries, LedgerService ledger, AuditLogWriter audit) {
        this.lockStrategy = lockStrategy;
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
        lockStrategy.executeGuarded(userId, () -> {
            LedgerAccount userPoint = lockOrCreate(AccountOwnerType.USER, userId, AccountKind.POINT);
            LedgerAccount contra = lockOrCreateSystemContra();
            ledger.post(LedgerTransactionType.PAYBACK, idempotencyKey, refType, refId, "이용 완료 5% 적립",
                    List.of(new LedgerService.Posting(contra, EntryDirection.DEBIT, amount),
                            new LedgerService.Posting(userPoint, EntryDirection.CREDIT, amount)));
        });
    }

    /**
     * @throws InsufficientPointException 잔액 부족 (감사 로그 {@link AuditEvent#INSUFFICIENT_POINT} 기록 후)
     */
    public void spend(long userId, long amount, String idempotencyKey, String refType, Long refId) {
        if (amount <= 0) {
            return;
        }
        try {
            lockStrategy.executeGuarded(userId, () -> {
                LedgerAccount userPoint = lockOrCreate(AccountOwnerType.USER, userId, AccountKind.POINT);
                if (userPoint.getBalance() < amount) {
                    throw new InsufficientPointException(amount, userPoint.getBalance());
                }
                LedgerAccount contra = lockOrCreateSystemContra();
                ledger.post(LedgerTransactionType.SPEND, idempotencyKey, refType, refId, "선불 포인트 차감",
                        List.of(new LedgerService.Posting(userPoint, EntryDirection.DEBIT, amount),
                                new LedgerService.Posting(contra, EntryDirection.CREDIT, amount)));
            });
        } catch (InsufficientPointException e) {
            // 락·트랜잭션을 벗어난 뒤에 기록한다. 락을 쥔 채로 감사 테이블에 REQUIRES_NEW로 쓰면
            // (커넥션 풀이 빠듯할 때) 2번째 커넥션을 못 얻어 교착에 빠질 수 있다.
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

    private LedgerAccount lockOrCreateSystemContra() {
        return lockOrCreate(AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA);
    }

    private LedgerAccount lockOrCreate(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        return accounts.findForUpdate(ownerType, ownerId, kind).orElseGet(() -> {
            try {
                accounts.saveAndFlush(ownerType == AccountOwnerType.SYSTEM
                        ? LedgerAccount.forSystem(kind)
                        : LedgerAccount.forUser(ownerId, kind));
            } catch (DataIntegrityViolationException concurrentCreate) {
                // 다른 트랜잭션이 먼저 같은 계정을 만들었다 - 아래에서 다시 읽어 락을 잡는다.
            }
            return accounts.findForUpdate(ownerType, ownerId, kind)
                    .orElseThrow(() -> new IllegalStateException("계정 생성 직후 재조회 실패"));
        });
    }
}

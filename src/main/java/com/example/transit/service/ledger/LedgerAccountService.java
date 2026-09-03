package com.example.transit.service.ledger;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.repository.LedgerAccountRepository;
import jakarta.persistence.LockModeType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * 원장 계정 조회/생성. 계정이 아직 없을 때의 "동시 최초 생성" 경합을 안전하게 처리한다.
 * <p>
 * 생성은 {@link LedgerAccountCreator}의 {@code REQUIRES_NEW} 트랜잭션에 위임한다. 경합으로
 * 유니크 제약 위반이 나도 그 트랜잭션만 롤백되고 호출부의 주 트랜잭션(비관적 락을 쥐고 있는)은
 * 오염되지 않는다 - 같은 세션에서 예외 후 계속 쓰면 {@code AssertionFailure: null identifier}가 난다.
 */
@Service
public class LedgerAccountService {

    private final LedgerAccountRepository accounts;
    private final LedgerAccountCreator creator;

    public LedgerAccountService(LedgerAccountRepository accounts, LedgerAccountCreator creator) {
        this.accounts = accounts;
        this.creator = creator;
    }

    /** 계정을 {@link LockModeType#PESSIMISTIC_WRITE}로 잡아서 돌려준다(없으면 만들고 다시 잡는다). */
    public LedgerAccount getForUpdate(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        return accounts.findForUpdate(ownerType, ownerId, kind).orElseGet(() -> {
            ensureExists(ownerType, ownerId, kind);
            return accounts.findForUpdate(ownerType, ownerId, kind)
                    .orElseThrow(() -> notFoundAfterCreate(ownerType, ownerId, kind));
        });
    }

    /** 계정을 락 없이 돌려준다(없으면 만든다). 공유 계정처럼 낙관적 락으로 다룰 대상용. */
    public LedgerAccount getOrCreate(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind).orElseGet(() -> {
            ensureExists(ownerType, ownerId, kind);
            return accounts.findByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind)
                    .orElseThrow(() -> notFoundAfterCreate(ownerType, ownerId, kind));
        });
    }

    private void ensureExists(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        if (accounts.existsByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind)) {
            return;
        }
        try {
            creator.create(ownerType, ownerId, kind);
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            // 경합으로 이미 만들어졌으면 존재할 것이다. 그게 아니면 진짜 오류(스키마 등)이므로 다시 던진다.
            if (!accounts.existsByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind)) {
                throw e;
            }
        }
    }

    private static IllegalStateException notFoundAfterCreate(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        return new IllegalStateException("계정 생성 직후 재조회 실패: " + ownerType + "/" + ownerId + "/" + kind);
    }
}

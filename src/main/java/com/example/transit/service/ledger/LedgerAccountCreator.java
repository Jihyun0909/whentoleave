package com.example.transit.service.ledger;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.repository.LedgerAccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 INSERT만 담당하는 별도 빈. {@link LedgerAccountService}와 분리한 이유는
 * {@link Propagation#REQUIRES_NEW}가 프록시를 타야 하기 때문이다(같은 빈 내부 호출이면
 * 트랜잭션 어드바이스가 적용되지 않는다).
 */
@Component
public class LedgerAccountCreator {

    private final LedgerAccountRepository accounts;

    public LedgerAccountCreator(LedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    /**
     * 새 트랜잭션에서 계정을 만든다. 동시 최초 생성 경합으로 유니크 제약 위반이 나면 이 트랜잭션만
     * 롤백되고 예외가 호출부로 전파된다 - 호출부(주 트랜잭션)는 오염되지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void create(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        accounts.save(LedgerAccount.of(ownerType, ownerId, kind));
    }
}

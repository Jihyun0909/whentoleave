package com.example.transit.config;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.service.ledger.LedgerAccountService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 시스템 원장 계정을 기동 시 미리 만든다. {@code POINT_CONTRA}는 모든 포인트 연산이 건드리는
 * 공유 계정이라, 첫 요청들이 이걸 동시에 생성하려고 경합하지 않도록 여기서 선점한다
 * (없어도 {@link LedgerAccountService#getOrCreate}가 처리하지만, 경합을 아예 없앤다).
 */
@Component
@Order(0)
public class LedgerSeedInitializer implements ApplicationRunner {

    private final LedgerAccountService ledgerAccounts;

    public LedgerSeedInitializer(LedgerAccountService ledgerAccounts) {
        this.ledgerAccounts = ledgerAccounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        ledgerAccounts.getOrCreate(
                AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA);
    }
}

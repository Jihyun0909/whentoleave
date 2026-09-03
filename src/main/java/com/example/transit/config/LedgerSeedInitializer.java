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
 * 시스템 원장 계정을 기동 시 전부 만든다. 모든 요청이 공유하는 계정이라 첫 요청들이 동시에
 * 생성하려고 경합하는 것도 막고, 배치처럼 "생성 후 같은 트랜잭션에서 재조회"가 곤란한 경로에서도
 * 항상 존재하도록 보장한다.
 */
@Component
@Order(0)
public class LedgerSeedInitializer implements ApplicationRunner {

    private static final AccountKind[] SYSTEM_KINDS = {
            AccountKind.POINT_CONTRA,       // 포인트 발행/소멸 상계
            AccountKind.FARE_CLEARING,      // 정산 자금원
            AccountKind.COMMISSION_INCOME,  // 플랫폼 수수료 수익
    };

    private final LedgerAccountService ledgerAccounts;

    public LedgerSeedInitializer(LedgerAccountService ledgerAccounts) {
        this.ledgerAccounts = ledgerAccounts;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (AccountKind kind : SYSTEM_KINDS) {
            ledgerAccounts.getOrCreate(AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, kind);
        }
    }
}

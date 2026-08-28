package com.example.transit.service.settlement;

import com.example.transit.domain.Partner;
import com.example.transit.repository.PartnerRepository;
import com.example.transit.repository.SettlementRepository;
import com.example.transit.service.ledger.LedgerPostingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class SettlementServiceTest {

    private static final AtomicLong SEQ = new AtomicLong(6_000_000);

    @Autowired private SettlementService settlementService;
    @Autowired private PartnerRepository partners;
    @Autowired private SettlementRepository settlements;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 비활성_제휴사는_calculate에서_SettlementException() {
        Partner p = partners.save(new Partner("비활성-" + SEQ.incrementAndGet(), 1000, false));
        LocalDate d = LocalDate.now();

        assertThrows(SettlementException.class, () -> settlementService.calculate(p.getId(), d, d, 1L));
    }

    @Test
    void 수수료율이_범위를_벗어나면_calculate에서_SettlementException() {
        Partner p = partners.save(new Partner("이상요율-" + SEQ.incrementAndGet(), 12_000, true));
        LocalDate d = LocalDate.now();

        assertThrows(SettlementException.class, () -> settlementService.calculate(p.getId(), d, d, 1L));
    }

    @Test
    void commit_중_원장_분개가_깨지면_정산행까지_통째로_롤백된다() {
        Partner p = partners.save(new Partner("롤백-" + SEQ.incrementAndGet(), 1000, true));
        LocalDate d = LocalDate.now();
        // payout + commission != gross 인 잘못된 draft → LedgerService.post가 LedgerPostingException
        var badDraft = new SettlementDraft(p.getId(), d, d, 10_000, 1_000, 8_000, List.of(1L, 2L), 1L);

        var tx = new TransactionTemplate(transactionManager);
        assertThrows(LedgerPostingException.class,
                () -> tx.executeWithoutResult(status -> settlementService.commit(badDraft)));

        assertNull(settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(p.getId(), d, d).orElse(null),
                "분개 실패 시 settlement 저장도 롤백되어야 한다");
        assertTrue(settlements.findByPartnerIdOrderByIdDesc(p.getId(),
                org.springframework.data.domain.PageRequest.of(0, 5)).isEmpty());
    }
}

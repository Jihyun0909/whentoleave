package com.example.transit.service.ledger;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.domain.ledger.LedgerTransaction;
import com.example.transit.domain.ledger.LedgerTransactionType;
import com.example.transit.repository.LedgerAccountRepository;
import com.example.transit.repository.LedgerEntryRepository;
import com.example.transit.repository.LedgerTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LedgerServiceTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(900_000);

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private LedgerAccountRepository accounts;
    @Autowired
    private LedgerTransactionRepository transactions;
    @Autowired
    private LedgerEntryRepository entries;

    private LedgerAccount newUserPoint() {
        return accounts.save(LedgerAccount.forUser(USER_SEQ.incrementAndGet(), AccountKind.POINT));
    }

    private LedgerAccount newContra() {
        // 테스트마다 별도 CONTRA를 못 만들므로(유니크), 있으면 재사용
        return accounts.findByOwnerTypeAndOwnerIdAndKind(
                        com.example.transit.domain.ledger.AccountOwnerType.SYSTEM,
                        LedgerAccount.SYSTEM_OWNER_ID, AccountKind.POINT_CONTRA)
                .orElseGet(() -> accounts.save(LedgerAccount.forSystem(AccountKind.POINT_CONTRA)));
    }

    @Test
    @Transactional
    void 균형_분개는_양쪽_잔액을_움직이고_트랜잭션과_분개를_남긴다() {
        LedgerAccount user = newUserPoint();
        LedgerAccount contra = newContra();
        long contraBefore = contra.getBalance();

        LedgerTransaction tx = ledgerService.post(LedgerTransactionType.PAYBACK, "test:" + USER_SEQ.get(),
                "ride", 1L, "적립", List.of(
                        new LedgerService.Posting(contra, EntryDirection.DEBIT, 500),
                        new LedgerService.Posting(user, EntryDirection.CREDIT, 500)));

        assertEquals(500, user.getBalance());
        assertEquals(contraBefore + 500, contra.getBalance());
        assertEquals(2, entries.findByTransactionId(tx.getId()).size());
        assertEquals(0, entries.signedSum(tx.getId()), "차변 합 == 대변 합");
    }

    @Test
    @Transactional
    void 불균형_분개는_거부된다() {
        LedgerAccount user = newUserPoint();
        LedgerAccount contra = newContra();

        assertThrows(LedgerPostingException.class, () -> ledgerService.post(
                LedgerTransactionType.PAYBACK, "test-imbalance:" + USER_SEQ.get(), "ride", 1L, "적립", List.of(
                        new LedgerService.Posting(contra, EntryDirection.DEBIT, 500),
                        new LedgerService.Posting(user, EntryDirection.CREDIT, 400))));
    }

    @Test
    @Transactional
    void 같은_idempotency_key로_다시_post하면_적용은_한_번() {
        LedgerAccount user = newUserPoint();
        LedgerAccount contra = newContra();
        String key = "test-idem:" + USER_SEQ.get();
        List<LedgerService.Posting> postings = List.of(
                new LedgerService.Posting(contra, EntryDirection.DEBIT, 300),
                new LedgerService.Posting(user, EntryDirection.CREDIT, 300));

        LedgerTransaction first = ledgerService.post(LedgerTransactionType.PAYBACK, key, "ride", 1L, "적립", postings);
        LedgerTransaction second = ledgerService.post(LedgerTransactionType.PAYBACK, key, "ride", 1L, "적립", postings);

        assertEquals(first.getId(), second.getId());
        assertEquals(300, user.getBalance(), "두 번째 호출은 잔액을 또 올리지 않는다");
        assertTrue(transactions.existsByIdempotencyKey(key));
    }
}

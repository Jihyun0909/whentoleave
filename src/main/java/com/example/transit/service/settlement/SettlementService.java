package com.example.transit.service.settlement;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.Partner;
import com.example.transit.domain.Payment;
import com.example.transit.domain.PaymentStatus;
import com.example.transit.domain.Settlement;
import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.EntryDirection;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.domain.ledger.LedgerTransactionType;
import com.example.transit.repository.PartnerRepository;
import com.example.transit.repository.PaymentRepository;
import com.example.transit.repository.SettlementRepository;
import com.example.transit.service.audit.AuditLogWriter;
import com.example.transit.service.ledger.LedgerAccountService;
import com.example.transit.service.ledger.LedgerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 제휴사 정산의 계산·확정·실패기록. 배치 스텝이 프로세서/라이터/스킵리스너에서 이 메서드들을 부른다.
 * <p>
 * 분개(정산 확정): DEBIT {@code FARE_CLEARING} gross / CREDIT 제휴사 {@code CASH} payout /
 * CREDIT {@code COMMISSION_INCOME} commission. (차변 gross == 대변 payout+commission)
 */
@Service
public class SettlementService {

    private final PartnerRepository partners;
    private final PaymentRepository payments;
    private final SettlementRepository settlements;
    private final LedgerAccountService ledgerAccounts;
    private final LedgerService ledger;
    private final AuditLogWriter audit;

    public SettlementService(PartnerRepository partners, PaymentRepository payments,
                             SettlementRepository settlements, LedgerAccountService ledgerAccounts,
                             LedgerService ledger, AuditLogWriter audit) {
        this.partners = partners;
        this.payments = payments;
        this.settlements = settlements;
        this.ledgerAccounts = ledgerAccounts;
        this.ledger = ledger;
        this.audit = audit;
    }

    /**
     * @return 정산안. 미정산 결제가 0건이면 {@code null}(스킵). 재실행 멱등성은 결제의
     *         {@code settled_at} 마킹으로 보장된다 - 이미 정산된 결제는 조회에서 빠진다.
     * @throws SettlementException 정산 불가(비활성 제휴사, 수수료율 이상)
     */
    public SettlementDraft calculate(long partnerId, LocalDate periodStart, LocalDate periodEnd,
                                     Long batchJobExecutionId) {
        Partner partner = partners.findById(partnerId)
                .orElseThrow(() -> new SettlementException(partnerId, "존재하지 않는 제휴사"));
        if (!partner.isActive()) {
            throw new SettlementException(partnerId, "비활성 제휴사는 정산할 수 없습니다");
        }
        int bps = partner.getCommissionBasisPoints();
        if (bps < 0 || bps > 10_000) {
            throw new SettlementException(partnerId, "수수료율이 범위를 벗어났습니다: " + bps + "bp");
        }

        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();
        List<Payment> unsettled = payments
                .findByPartnerIdAndStatusAndSettledAtIsNullAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                        partnerId, PaymentStatus.PAID, from, to);
        if (unsettled.isEmpty()) {
            return null;
        }

        long gross = unsettled.stream().mapToLong(Payment::getGrossAmount).sum();
        long commission = gross * bps / 10_000;
        long payout = gross - commission;
        List<Long> paymentIds = unsettled.stream().map(Payment::getId).toList();

        return new SettlementDraft(partnerId, periodStart, periodEnd, gross, commission, payout,
                paymentIds, batchJobExecutionId);
    }

    /**
     * 정산 확정. 배치 청크 트랜잭션 안에서 돈다(여기서 예외가 나면 이 제휴사 청크가 통째로 롤백된다).
     */
    public void commit(SettlementDraft draft) {
        Settlement settlement = settlements
                .findByPartnerIdAndPeriodStartAndPeriodEnd(draft.partnerId(), draft.periodStart(), draft.periodEnd())
                .orElseGet(() -> new Settlement(draft.partnerId(), draft.periodStart(), draft.periodEnd()));
        settlement.addSettledBatch(draft.grossAmount(), draft.commissionAmount(), draft.payoutAmount(),
                draft.paymentCount(), draft.batchJobExecutionId());
        settlements.save(settlement);

        LedgerAccount fareClearing = ledgerAccounts.getForUpdate(
                AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.FARE_CLEARING);
        LedgerAccount partnerCash = ledgerAccounts.getForUpdate(
                AccountOwnerType.PARTNER, draft.partnerId(), AccountKind.CASH);
        LedgerAccount commissionIncome = ledgerAccounts.getForUpdate(
                AccountOwnerType.SYSTEM, LedgerAccount.SYSTEM_OWNER_ID, AccountKind.COMMISSION_INCOME);

        // idempotency는 "이번에 정산되는 결제 집합"으로 잡는다(각 결제는 정확히 한 번만 정산되므로
        // 결제 id 범위가 이 배치를 유일하게 식별한다). 한 회차를 여러 번 돌려 서로 다른 결제를
        // 정산해도 각각 다른 키가 되고, 같은 배치를 재시도하면 같은 키라 post가 중복 없이 무시한다.
        String idempotencyKey = "SETTLEMENT:partner:" + draft.partnerId() + ":pmts:"
                + draft.paymentIds().get(0) + "-" + draft.paymentIds().get(draft.paymentIds().size() - 1);
        ledger.post(LedgerTransactionType.SETTLEMENT, idempotencyKey,
                "settlement", settlement.getId(),
                "정산 " + draft.periodStart(),
                List.of(new LedgerService.Posting(fareClearing, EntryDirection.DEBIT, draft.grossAmount()),
                        new LedgerService.Posting(partnerCash, EntryDirection.CREDIT, draft.payoutAmount()),
                        new LedgerService.Posting(commissionIncome, EntryDirection.CREDIT, draft.commissionAmount())));

        List<Payment> paid = payments.findAllById(draft.paymentIds());
        paid.forEach(Payment::markSettled);
        payments.saveAll(paid);
    }

    /**
     * 실패 기록. 배치 스킵 처리에서 부르며, 롤백된 청크와 무관하게 남아야 하므로 별도 트랜잭션.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(long partnerId, LocalDate periodStart, LocalDate periodEnd,
                              String reason, Long batchJobExecutionId) {
        Settlement existing = settlements
                .findByPartnerIdAndPeriodStartAndPeriodEnd(partnerId, periodStart, periodEnd)
                .orElse(null);

        // 이미 성공한 회차(DONE)를 FAILED로 뒤집지 않는다. 그 뒤에 들어온 결제의 정산 실패는
        // 감사 로그로만 남기고, 해당 결제들은 미정산으로 남아 다음 성공 실행에서 처리된다.
        if (existing == null || !existing.isDone()) {
            Settlement settlement = existing != null ? existing
                    : new Settlement(partnerId, periodStart, periodEnd);
            settlement.markFailed(reason, batchJobExecutionId);
            settlements.save(settlement);
        }

        audit.record(AuditEvent.SETTLEMENT_FAILED, null, "partner", partnerId,
                periodStart + " 정산 실패: " + reason);
    }
}

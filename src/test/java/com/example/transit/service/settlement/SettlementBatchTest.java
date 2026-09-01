package com.example.transit.service.settlement;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.Partner;
import com.example.transit.domain.Payment;
import com.example.transit.domain.PaymentStatus;
import com.example.transit.domain.Settlement;
import com.example.transit.domain.SettlementStatus;
import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import com.example.transit.repository.AuditLogRepository;
import com.example.transit.repository.LedgerAccountRepository;
import com.example.transit.repository.PartnerRepository;
import com.example.transit.repository.PaymentRepository;
import com.example.transit.repository.SettlementRepository;
import com.example.transit.repository.TaxiRideRepository;
import com.example.transit.service.ride.RideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제 Spring Batch Job을 실행해 정산 흐름을 검증한다. 배치는 자체 트랜잭션(청크·REQUIRES_NEW)으로
 * 돌아서 {@code @Transactional} 테스트로 롤백할 수 없으므로, 매 테스트 전에 이용·결제·정산을 비워
 * 격리한다. 대부분의 테스트는 {@code launcher.run(date, partnerId)}로 자기 제휴사만 정산해서
 * 집계(settled/failed 수)까지 결정적으로 검증한다.
 */
@SpringBootTest
class SettlementBatchTest {

    private static final AtomicLong SEQ = new AtomicLong(5_000_000);

    @Autowired private SettlementLauncher launcher;
    @Autowired private RideService rideService;
    @Autowired private PartnerRepository partners;
    @Autowired private PaymentRepository payments;
    @Autowired private TaxiRideRepository rides;
    @Autowired private SettlementRepository settlements;
    @Autowired private LedgerAccountRepository accounts;
    @Autowired private AuditLogRepository auditLogs;

    @BeforeEach
    void isolate() {
        settlements.deleteAll();
        payments.deleteAll();
        rides.deleteAll();
    }

    private Partner newPartner(String name, int bps, boolean active) {
        return partners.save(new Partner(name + "-" + SEQ.incrementAndGet(), bps, active));
    }

    private Payment completedRide(long partnerId, long fare) {
        long userId = SEQ.incrementAndGet();
        var ride = rideService.request(userId, partnerId, "A", "B", fare);
        rideService.start(userId, ride.getId());
        var result = rideService.complete(userId, ride.getId(), 0);
        return payments.findByRideId(result.ride().getId()).orElseThrow();
    }

    private List<Settlement> settlementsOf(long partnerId, LocalDate day) {
        return settlements.findByPartnerIdAndPeriodStartAndPeriodEndOrderByIdDesc(partnerId, day, day);
    }

    private long cash(long partnerId) {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(AccountOwnerType.PARTNER, partnerId, AccountKind.CASH)
                .map(LedgerAccount::getBalance).orElse(0L);
    }

    private long unsettledCount(long partnerId, LocalDate day) {
        return payments.findByPartnerIdAndStatusAndSettledAtIsNullAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                partnerId, PaymentStatus.PAID, day.atStartOfDay(), day.plusDays(1).atStartOfDay()).size();
    }

    @Test
    void 정상_정산_확정_원장_반영_결제_마킹() {
        Partner p = newPartner("A정산", 1500, true);   // 15%
        completedRide(p.getId(), 20_000);
        completedRide(p.getId(), 30_000);              // gross 50,000 → commission 7,500 payout 42,500
        LocalDate today = LocalDate.now();

        SettlementRunResult run = launcher.run(today, p.getId());

        assertEquals("COMPLETED", run.status());
        assertEquals(1, run.settledPartners());
        assertEquals(0, run.failedPartners());

        List<Settlement> rows = settlementsOf(p.getId(), today);
        assertEquals(1, rows.size());
        Settlement s = rows.get(0);
        assertEquals(SettlementStatus.DONE, s.getStatus());
        assertEquals(50_000, s.getGrossAmount());
        assertEquals(7_500, s.getCommissionAmount());
        assertEquals(42_500, s.getPayoutAmount());
        assertEquals(2, s.getPaymentCount());
        assertNotNull(s.getBatchJobExecutionId());

        assertEquals(42_500, cash(p.getId()));
        assertEquals(0, unsettledCount(p.getId(), today));
    }

    @Test
    void 재실행하면_이미_정산된_결제는_다시_처리되지_않는다() {
        Partner p = newPartner("재실행", 1000, true);
        completedRide(p.getId(), 10_000);
        LocalDate today = LocalDate.now();

        SettlementRunResult first = launcher.run(today, p.getId());
        SettlementRunResult rerun = launcher.run(today, p.getId());

        assertTrue(rerun.jobExecutionId() > first.jobExecutionId(),
                "JDBC JobRepository - 실행 id가 증가(영속)");
        assertEquals(0, rerun.settledPartners(), "미정산 결제가 없으므로 아무것도 안 함");
        assertEquals(1, settlementsOf(p.getId(), today).size(), "정산 행은 그대로 1개");
        assertEquals(9_000, cash(p.getId()), "원장도 이중 반영 안 됨 (payout 9,000)");
    }

    @Test
    void 정산_후_들어온_결제는_재실행_시_새_행으로_정산된다() {
        Partner p = newPartner("추가정산", 1000, true);   // 10%
        completedRide(p.getId(), 10_000);
        LocalDate today = LocalDate.now();
        launcher.run(today, p.getId());

        completedRide(p.getId(), 40_000);
        launcher.run(today, p.getId());

        List<Settlement> rows = settlementsOf(p.getId(), today);
        assertEquals(2, rows.size(), "실행마다 행 하나");
        assertEquals(40_000 + 10_000, rows.stream().mapToLong(Settlement::getGrossAmount).sum());
        assertEquals(45_000, cash(p.getId()), "원장 CASH는 두 실행분 합계(9,000 + 36,000)");
    }

    @Test
    void 부분_실패_비활성_제휴사는_FAILED_행_결제는_미정산_유지_나머지는_정상() {
        Partner ok = newPartner("정상제휴사", 1200, true);
        Partner bad = newPartner("문제제휴사", 1200, true);
        completedRide(ok.getId(), 10_000);
        Payment badPayment = completedRide(bad.getId(), 20_000);
        LocalDate today = LocalDate.now();

        bad.deactivate();
        partners.save(bad);

        // 전체 실행(이 테스트만 격리돼 있어 집계가 결정적이다)
        SettlementRunResult run = launcher.run(today);

        assertEquals("COMPLETED", run.status(), "부분 실패는 잡 자체를 실패시키지 않는다");
        assertEquals(1, run.settledPartners());
        assertEquals(1, run.failedPartners());

        assertEquals(SettlementStatus.DONE, settlementsOf(ok.getId(), today).get(0).getStatus());

        Settlement badRow = settlementsOf(bad.getId(), today).get(0);
        assertEquals(SettlementStatus.FAILED, badRow.getStatus());
        assertEquals(0, badRow.getPaymentCount());
        assertTrue(badRow.getFailureReason().contains("비활성"));

        assertNull(payments.findById(badPayment.getId()).orElseThrow().getSettledAt(), "결제는 롤백되어 미정산");
        assertEquals(0, cash(bad.getId()), "실패 제휴사 CASH 원장 안 움직임");
        assertTrue(auditLogs.findAllByOrderByIdDesc(PageRequest.of(0, 20)).getContent().stream()
                .anyMatch(l -> l.getEvent() == AuditEvent.SETTLEMENT_FAILED && bad.getId().equals(l.getRefId())));
    }

    @Test
    void 이전_실행의_DONE_행은_이후_실행의_실패로_바뀌지_않는다() {
        Partner p = newPartner("이력보존", 1000, true);
        completedRide(p.getId(), 10_000);
        LocalDate today = LocalDate.now();
        launcher.run(today, p.getId());   // 행 1: DONE

        completedRide(p.getId(), 40_000);
        p.deactivate();
        partners.save(p);
        launcher.run(today, p.getId());   // 행 2: FAILED

        List<Settlement> rows = settlementsOf(p.getId(), today);
        assertEquals(2, rows.size());
        assertEquals(1, rows.stream().filter(r -> r.getStatus() == SettlementStatus.DONE).count());
        assertEquals(1, rows.stream().filter(r -> r.getStatus() == SettlementStatus.FAILED).count());
        assertEquals(9_000, cash(p.getId()), "성공했던 정산(payout 9,000)은 그대로");
    }

    @Test
    void 미래_날짜는_거부된다() {
        try {
            launcher.run(LocalDate.now().plusDays(1));
            org.junit.jupiter.api.Assertions.fail("미래 날짜는 예외여야 한다");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("미래"));
        }
    }
}

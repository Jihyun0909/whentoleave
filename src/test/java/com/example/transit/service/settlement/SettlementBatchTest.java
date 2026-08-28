package com.example.transit.service.settlement;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.Partner;
import com.example.transit.domain.Payment;
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

@SpringBootTest
class SettlementBatchTest {

    private static final AtomicLong USER_SEQ = new AtomicLong(5_000_000);

    @Autowired private SettlementLauncher launcher;
    @Autowired private RideService rideService;
    @Autowired private PartnerRepository partners;
    @Autowired private PaymentRepository payments;
    @Autowired private TaxiRideRepository rides;
    @Autowired private SettlementRepository settlements;
    @Autowired private LedgerAccountRepository accounts;
    @Autowired private AuditLogRepository auditLogs;

    /**
     * 배치는 "오늘 미정산 결제가 있는 모든 제휴사"를 훑으므로, 다른 테스트가 남긴 데이터가 섞이면
     * 집계(settled/failed 수)가 흔들린다. 매 테스트 전에 이용·결제·정산을 비워 격리한다.
     */
    @BeforeEach
    void isolate() {
        settlements.deleteAll();
        payments.deleteAll();
        rides.deleteAll();
    }

    private Partner newPartner(String name, int bps, boolean active) {
        return partners.save(new Partner(name + "-" + USER_SEQ.incrementAndGet(), bps, active));
    }

    /** 한 제휴사에 이용 완료 1건을 만든다. @return 그 결제 */
    private Payment completedRide(long partnerId, long fare) {
        long userId = USER_SEQ.incrementAndGet();
        var ride = rideService.request(userId, partnerId, "A", "B", fare);
        rideService.start(userId, ride.getId());
        var result = rideService.complete(userId, ride.getId(), 0);
        return payments.findByRideId(result.ride().getId()).orElseThrow();
    }

    private long balance(AccountOwnerType type, long ownerId, AccountKind kind) {
        return accounts.findByOwnerTypeAndOwnerIdAndKind(type, ownerId, kind)
                .map(LedgerAccount::getBalance).orElse(0L);
    }

    @Test
    void 정상_정산_두_제휴사_확정_원장_반영_결제_마킹_재실행_스킵() {
        Partner a = newPartner("A정산", 1500, true);   // 15%
        Partner b = newPartner("B정산", 1000, true);   // 10%
        completedRide(a.getId(), 20_000);
        completedRide(a.getId(), 30_000);              // A gross 50,000 → commission 7,500 payout 42,500
        completedRide(b.getId(), 10_000);              // B gross 10,000 → commission 1,000 payout 9,000
        LocalDate today = LocalDate.now();

        SettlementRunResult run = launcher.run(today);

        assertEquals("COMPLETED", run.status());
        assertEquals(0, run.failedPartners());

        Settlement sa = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(a.getId(), today, today).orElseThrow();
        assertEquals(SettlementStatus.DONE, sa.getStatus());
        assertEquals(50_000, sa.getGrossAmount());
        assertEquals(7_500, sa.getCommissionAmount());
        assertEquals(42_500, sa.getPayoutAmount());
        assertEquals(2, sa.getPaymentCount());
        assertNotNull(sa.getBatchJobExecutionId());

        // 원장: 제휴사 CASH = payout, 그리고 gross = payout + commission
        assertEquals(42_500, balance(AccountOwnerType.PARTNER, a.getId(), AccountKind.CASH));
        assertEquals(9_000, balance(AccountOwnerType.PARTNER, b.getId(), AccountKind.CASH));

        // 결제 전부 settled 마킹
        long unsettledForA = payments.findByPartnerIdAndStatusAndSettledAtIsNullAndPaidAtGreaterThanEqualAndPaidAtLessThan(
                a.getId(), com.example.transit.domain.PaymentStatus.PAID,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay()).size();
        assertEquals(0, unsettledForA);

        // 재실행 → 이미 DONE이라 아무것도 안 바뀜
        long jobExecBefore = sa.getBatchJobExecutionId();
        SettlementRunResult rerun = launcher.run(today);
        assertEquals(0, rerun.settledPartners());
        assertEquals(jobExecBefore,
                settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(a.getId(), today, today)
                        .orElseThrow().getBatchJobExecutionId());
    }

    @Test
    void 정산_후_들어온_결제는_재실행_시_같은_회차에_누적된다() {
        Partner p = newPartner("누적정산", 1000, true);   // 10%
        completedRide(p.getId(), 10_000);
        LocalDate today = LocalDate.now();

        launcher.run(today);
        Settlement first = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(p.getId(), today, today).orElseThrow();
        assertEquals(10_000, first.getGrossAmount());
        assertEquals(1, first.getPaymentCount());

        // 같은 날에 결제가 더 들어온 뒤 재실행
        completedRide(p.getId(), 40_000);
        launcher.run(today);

        Settlement merged = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(p.getId(), today, today).orElseThrow();
        assertEquals(50_000, merged.getGrossAmount(), "이전 10,000 + 새 40,000");
        assertEquals(5_000, merged.getCommissionAmount());
        assertEquals(45_000, merged.getPayoutAmount());
        assertEquals(2, merged.getPaymentCount());
        assertEquals(45_000, balance(AccountOwnerType.PARTNER, p.getId(), AccountKind.CASH));
    }

    @Test
    void 이미_DONE인_회차는_이후_결제_정산_실패로_뒤집히지_않는다() {
        Partner p = newPartner("보존", 1000, true);
        completedRide(p.getId(), 10_000);
        LocalDate today = LocalDate.now();
        launcher.run(today);   // → DONE, gross 10,000

        // 이후 결제가 들어온 뒤 제휴사 비활성화 → 재실행 시 그 결제 정산 실패
        completedRide(p.getId(), 40_000);
        p.deactivate();
        partners.save(p);

        SettlementRunResult run = launcher.run(today);
        assertEquals(1, run.failedPartners());

        Settlement s = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(p.getId(), today, today).orElseThrow();
        assertEquals(SettlementStatus.DONE, s.getStatus(), "이전 성공 회차는 그대로 DONE");
        assertEquals(10_000, s.getGrossAmount());
        assertTrue(auditLogs.findAllByOrderByIdDesc(PageRequest.of(0, 20)).getContent().stream()
                .anyMatch(l -> l.getEvent() == AuditEvent.SETTLEMENT_FAILED && p.getId().equals(l.getRefId())));
    }

    @Test
    void 부분_실패_비활성_제휴사는_FAILED로_남고_결제는_미정산_유지_나머지는_정상() {
        Partner ok = newPartner("정상제휴사", 1200, true);
        Partner bad = newPartner("문제제휴사", 1200, true);
        completedRide(ok.getId(), 10_000);
        Payment badPayment = completedRide(bad.getId(), 20_000);
        LocalDate today = LocalDate.now();

        // 이용 완료 후 제휴사 비활성화 → 정산 시점엔 정산 불가
        Partner reloaded = partners.findById(bad.getId()).orElseThrow();
        reloaded.deactivate();
        partners.save(reloaded);

        SettlementRunResult run = launcher.run(today);

        assertEquals("COMPLETED", run.status(), "부분 실패는 잡 자체를 실패시키지 않는다");
        assertEquals(1, run.settledPartners());
        assertEquals(1, run.failedPartners());

        Settlement okS = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(ok.getId(), today, today).orElseThrow();
        assertEquals(SettlementStatus.DONE, okS.getStatus());

        Settlement badS = settlements.findByPartnerIdAndPeriodStartAndPeriodEnd(bad.getId(), today, today).orElseThrow();
        assertEquals(SettlementStatus.FAILED, badS.getStatus());
        assertEquals(0, badS.getPaymentCount());
        assertTrue(badS.getFailureReason().contains("비활성"));

        // 실패 제휴사의 결제는 여전히 미정산 (롤백됨) — 다음 실행에서 재시도 가능
        Payment stillUnsettled = payments.findById(badPayment.getId()).orElseThrow();
        assertNull(stillUnsettled.getSettledAt());
        // 실패 제휴사 CASH 원장도 안 움직임
        assertEquals(0, balance(AccountOwnerType.PARTNER, bad.getId(), AccountKind.CASH));

        // 감사 로그
        List<com.example.transit.domain.AuditLog> logs =
                auditLogs.findAllByOrderByIdDesc(PageRequest.of(0, 20)).getContent();
        assertTrue(logs.stream().anyMatch(l -> l.getEvent() == AuditEvent.SETTLEMENT_FAILED
                && bad.getId().equals(l.getRefId())));
    }
}

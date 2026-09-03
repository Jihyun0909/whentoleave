package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 배치 실행이 한 제휴사에 대해 처리한 정산 결과. append-only 로그다 - 배치 실행마다 한 행이
 * 생기고 한 번 쓰이면 바뀌지 않는다(mutator 없음).
 * <p>
 * 멱등성은 {@code payment.settled_at} 마킹으로 보장된다: 이미 정산된 결제는 다음 실행의 조회에서
 * 빠지므로, 마감된 기간을 재실행하면 미정산 결제가 0건이라 새 행이 안 생긴다. 뒤늦게 결제가
 * 들어온 뒤 재실행하면 그 몫만큼 새 행이 하나 더 생긴다("그 제휴사·그 날의 총합" = 행들의 합).
 * <p>
 * {@code gross = commission + payout}. commission은 플랫폼 수수료 수익, payout은 제휴사 가상 계좌.
 */
@Entity
@Table(name = "settlement",
        indexes = @Index(name = "ix_settlement_partner_period",
                columnList = "partner_id, period_start, period_end"))
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 이 정산을 만든 Spring Batch JobExecution id (추적용). */
    @Column(name = "batch_job_execution_id")
    private Long batchJobExecutionId;

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "commission_amount", nullable = false)
    private long commissionAmount;

    @Column(name = "payout_amount", nullable = false)
    private long payoutAmount;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @Column(name = "status", nullable = false, length = 10)
    private SettlementStatus status;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Settlement() {
        // JPA
    }

    private Settlement(Long partnerId, LocalDate periodStart, LocalDate periodEnd, Long batchJobExecutionId) {
        this.partnerId = partnerId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.batchJobExecutionId = batchJobExecutionId;
    }

    public static Settlement done(Long partnerId, LocalDate periodStart, LocalDate periodEnd,
                                  Long batchJobExecutionId, long grossAmount, long commissionAmount,
                                  long payoutAmount, int paymentCount) {
        Settlement s = new Settlement(partnerId, periodStart, periodEnd, batchJobExecutionId);
        s.grossAmount = grossAmount;
        s.commissionAmount = commissionAmount;
        s.payoutAmount = payoutAmount;
        s.paymentCount = paymentCount;
        s.status = SettlementStatus.DONE;
        return s;
    }

    public static Settlement failed(Long partnerId, LocalDate periodStart, LocalDate periodEnd,
                                    Long batchJobExecutionId, String failureReason) {
        Settlement s = new Settlement(partnerId, periodStart, periodEnd, batchJobExecutionId);
        s.status = SettlementStatus.FAILED;
        s.failureReason = failureReason;
        return s;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public Long getBatchJobExecutionId() {
        return batchJobExecutionId;
    }

    public long getGrossAmount() {
        return grossAmount;
    }

    public long getCommissionAmount() {
        return commissionAmount;
    }

    public long getPayoutAmount() {
        return payoutAmount;
    }

    public int getPaymentCount() {
        return paymentCount;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

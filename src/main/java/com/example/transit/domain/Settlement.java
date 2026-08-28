package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 제휴사의 하루치 정산 결과. {@code (partner_id, period_start, period_end)}에 유니크를 걸어
 * 배치를 다시 돌려도 회차가 하나만 존재하게 한다. 성공 시 {@code DONE}, 부분 실패 시 {@code FAILED}로
 * 남고, 다음 실행에서 같은 회차를 다시 계산해 이 행을 갱신한다({@code DONE}이면 건드리지 않음).
 * <p>
 * {@code gross = commission + payout}. commission은 플랫폼 수수료 수익, payout은 제휴사 가상 계좌
 * ({@code CASH})로 가는 몫.
 */
@Entity
@Table(name = "settlement",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_partner_period",
                columnNames = {"partner_id", "period_start", "period_end"}))
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

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "commission_amount", nullable = false)
    private long commissionAmount;

    @Column(name = "payout_amount", nullable = false)
    private long payoutAmount;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private SettlementStatus status;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    /** 이 정산을 마지막으로 처리한 Spring Batch JobExecution id. 추적용. */
    @Column(name = "batch_job_execution_id")
    private Long batchJobExecutionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Settlement() {
        // JPA
    }

    public Settlement(Long partnerId, LocalDate periodStart, LocalDate periodEnd) {
        this.partnerId = partnerId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = SettlementStatus.PENDING;
    }

    /**
     * 이번 배치가 정산한 몫을 누적한다. 같은 회차(파트너·기간)에 나중에 결제가 더 들어와 다시
     * 돌아도 이 행에 더해지므로, 이 행은 언제나 "그 파트너·그 날의 정산 총합"이다.
     * (실사용은 마감된 과거 날짜만 정산해서 사실상 1회지만, 열린 기간을 조기 정산해도 안전하다.)
     */
    public void addSettledBatch(long grossAmount, long commissionAmount, long payoutAmount,
                                int paymentCount, Long batchJobExecutionId) {
        this.grossAmount += grossAmount;
        this.commissionAmount += commissionAmount;
        this.payoutAmount += payoutAmount;
        this.paymentCount += paymentCount;
        this.status = SettlementStatus.DONE;
        this.failureReason = null;
        this.batchJobExecutionId = batchJobExecutionId;
    }

    public void markFailed(String failureReason, Long batchJobExecutionId) {
        this.status = SettlementStatus.FAILED;
        this.failureReason = failureReason;
        this.batchJobExecutionId = batchJobExecutionId;
    }

    public boolean isDone() {
        return status == SettlementStatus.DONE;
    }

    @PrePersist
    private void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void onUpdate() {
        this.updatedAt = LocalDateTime.now();
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

    public Long getBatchJobExecutionId() {
        return batchJobExecutionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

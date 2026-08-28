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

import java.time.LocalDateTime;

/**
 * 택시 이용 완료 시 만들어지는 결제(가상). ride 하나당 최대 하나({@code ride_id} 유니크).
 * <p>
 * grossAmount = pointUsed + cashAmount. pointUsed는 선불 포인트 차감분, cashAmount는
 * "나머지는 외부 결제수단으로 냈다고 친다"는 시뮬레이션 값(별도 원장 없음).
 * pointEarned는 grossAmount의 5% 페이백.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_id", nullable = false, unique = true)
    private Long rideId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "point_used", nullable = false)
    private long pointUsed;

    @Column(name = "cash_amount", nullable = false)
    private long cashAmount;

    @Column(name = "point_earned", nullable = false)
    private long pointEarned;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaymentStatus status;

    /** 정산 배치가 이 결제를 처리한 시각. null이면 미정산 (PR C). */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Payment() {
        // JPA
    }

    public Payment(Long rideId, Long userId, Long partnerId, long grossAmount,
                   long pointUsed, long pointEarned) {
        this.rideId = rideId;
        this.userId = userId;
        this.partnerId = partnerId;
        this.grossAmount = grossAmount;
        this.pointUsed = pointUsed;
        this.cashAmount = grossAmount - pointUsed;
        this.pointEarned = pointEarned;
        this.status = PaymentStatus.PAID;
        this.paidAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELLED;
    }

    public void markSettled() {
        this.settledAt = LocalDateTime.now();
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRideId() {
        return rideId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public long getGrossAmount() {
        return grossAmount;
    }

    public long getPointUsed() {
        return pointUsed;
    }

    public long getCashAmount() {
        return cashAmount;
    }

    public long getPointEarned() {
        return pointEarned;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public LocalDateTime getSettledAt() {
        return settledAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }
}

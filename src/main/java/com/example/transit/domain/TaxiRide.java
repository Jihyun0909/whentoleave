package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 가상 택시 이용. 포인트 적립·정산이 매달릴 도메인이라 실제 배차/주행 없이 상태만 시뮬레이션한다.
 * {@code userId}/{@code partnerId}는 (기존 도메인들처럼) 관계 매핑 없이 id 컬럼으로만 들고 있는다.
 */
@Entity
@Table(name = "taxi_ride")
public class TaxiRide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "partner_id", nullable = false)
    private Long partnerId;

    @Column(name = "origin", nullable = false, length = 100)
    private String origin;

    @Column(name = "destination", nullable = false, length = 100)
    private String destination;

    /** 이용 요금(원). 결제 금액이자 5% 페이백의 기준. */
    @Column(name = "fare_amount", nullable = false)
    private long fareAmount;

    @Column(name = "status", nullable = false, length = 15)
    private RideStatus status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TaxiRide() {
        // JPA
    }

    public TaxiRide(Long userId, Long partnerId, String origin, String destination, long fareAmount) {
        this.userId = userId;
        this.partnerId = partnerId;
        this.origin = origin;
        this.destination = destination;
        this.fareAmount = fareAmount;
        this.status = RideStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
    }

    /** @throws IllegalStateException 허용되지 않는 상태 전이 */
    public void transitionTo(RideStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("이용 상태를 " + status + " → " + next + " 로 바꿀 수 없습니다");
        }
        this.status = next;
        if (next == RideStatus.COMPLETED) {
            this.completedAt = LocalDateTime.now();
        }
    }

    @PrePersist
    @PreUpdate
    private void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public String getOrigin() {
        return origin;
    }

    public String getDestination() {
        return destination;
    }

    public long getFareAmount() {
        return fareAmount;
    }

    public RideStatus getStatus() {
        return status;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }
}

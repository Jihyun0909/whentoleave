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
 * 가상 제휴 택시회사. 실제 제휴 심사·사업자 등록은 스코프 밖이라, 운영자가 시드로만 만든다
 * ({@code PartnerSeedInitializer}).
 * <p>
 * 수수료율은 basis point(1bp = 0.01%) 정수로 들고 있는다 - 금액 계산을 전부 정수로 유지해
 * 반올림 모호성을 없앤다. 예) 1500 = 15.00%.
 */
@Entity
@Table(name = "partner")
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "commission_basis_points", nullable = false)
    private int commissionBasisPoints;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Partner() {
        // JPA
    }

    public Partner(String name, int commissionBasisPoints, boolean active) {
        this.name = name;
        this.commissionBasisPoints = commissionBasisPoints;
        this.active = active;
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

    public String getName() {
        return name;
    }

    public int getCommissionBasisPoints() {
        return commissionBasisPoints;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

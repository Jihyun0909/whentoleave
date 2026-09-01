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
 * 회원. 테이블명이 {@code app_user}인 건 {@code user}가 PostgreSQL 예약어라서다.
 * <p>
 * B2C/B2B 구분은 {@link #role} 하나로만 한다({@link Role} 주석 참고). 제휴사 관리자
 * ({@link Role#PARTNER_ADMIN})는 {@link #partnerId}가 반드시 채워져 있어야 하지만,
 * partner 엔티티/FK는 PR B에서 도입하므로 지금은 제약 없는 {@code Long} 컬럼으로만 둔다.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** BCrypt 해시. 평문은 어디에도 저장하지 않는다. */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /** {@link Role#PARTNER_ADMIN}일 때 소속 제휴사 id. 그 외에는 null. */
    @Column(name = "partner_id")
    private Long partnerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppUser() {
        // JPA
    }

    private AppUser(String email, String passwordHash, Role role, Long partnerId) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.partnerId = partnerId;
    }

    /** B2C 일반 회원 가입. */
    public static AppUser newUser(String email, String passwordHash) {
        return new AppUser(email, passwordHash, Role.USER, null);
    }

    /** 제휴사 관리자·운영자는 지금은 시드/관리 콘솔로만 만든다(공개 가입 없음). */
    public static AppUser newStaff(String email, String passwordHash, Role role, Long partnerId) {
        return new AppUser(email, passwordHash, role, partnerId);
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

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public Long getPartnerId() {
        return partnerId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

package com.example.transit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 금융 이력 감사 로그. append-only({@link Immutable}, setter 없음). 비정상 결제/차감 시도와
 * 정산 실패를 기록한다. 본 흐름이 롤백돼도 로그는 남아야 하므로, 쓰는 쪽
 * ({@code AuditLogWriter})이 별도 트랜잭션({@code REQUIRES_NEW})으로 커밋한다.
 */
@Entity
@Table(name = "audit_log")
@Immutable
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 행위 주체(app_user.id). 시스템/배치면 null. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event", nullable = false, length = 30)
    private AuditEvent event;

    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AuditLog() {
        // JPA
    }

    public AuditLog(Long actorUserId, AuditEvent event, String refType, Long refId, String detail) {
        this.actorUserId = actorUserId;
        this.event = event;
        this.refType = refType;
        this.refId = refId;
        this.detail = detail;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public AuditEvent getEvent() {
        return event;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

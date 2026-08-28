package com.example.transit.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 한 번의 금융 이벤트 = 분개 묶음의 헤더. 한 번 쓰이면 절대 바뀌지 않는다({@link Immutable},
 * setter 없음, 전용 repository는 delete를 노출하지 않음). 정정은 {@link LedgerTransactionType#REVERSE}
 * 역분개로만 한다.
 * <p>
 * {@link #idempotencyKey}에 유니크 제약을 걸어, 배치 재시도·중복 요청이 같은 분개를 두 번
 * 만들지 못하게 한다("PAYBACK:ride:42" 같은 안정적인 문자열을 호출부가 만든다).
 */
@Entity
@Table(name = "ledger_transaction",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ledger_transaction_idempotency_key",
                columnNames = "idempotency_key"))
@Immutable
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private LedgerTransactionType type;

    @Column(name = "ref_type", length = 20)
    private String refType;

    @Column(name = "ref_id")
    private Long refId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LedgerTransaction() {
        // JPA
    }

    public LedgerTransaction(LedgerTransactionType type, String refType, Long refId,
                             String idempotencyKey, String memo) {
        this.type = type;
        this.refType = refType;
        this.refId = refId;
        this.idempotencyKey = idempotencyKey;
        this.memo = memo;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public LedgerTransactionType getType() {
        return type;
    }

    public String getRefType() {
        return refType;
    }

    public Long getRefId() {
        return refId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getMemo() {
        return memo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

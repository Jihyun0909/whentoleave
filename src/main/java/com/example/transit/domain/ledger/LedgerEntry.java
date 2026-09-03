package com.example.transit.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * 분개 한 줄. 특정 계정에 특정 방향으로 특정 금액. 불변({@link Immutable}).
 * {@code amount}는 항상 양수이고, 늘/줄임 여부는 {@link LedgerAccount#apply}가 방향과
 * 계정 정상잔액으로 판단한다.
 */
@Entity
@Table(name = "ledger_entry")
@Immutable
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "direction", nullable = false, length = 6)
    private EntryDirection direction;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LedgerEntry() {
        // JPA
    }

    public LedgerEntry(Long transactionId, Long accountId, EntryDirection direction, long amount) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
    }

    @PrePersist
    private void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

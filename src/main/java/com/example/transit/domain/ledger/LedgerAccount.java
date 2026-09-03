package com.example.transit.domain.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * 원장 계정. 잔액({@link #balance})은 원장(분개의 합)에서 파생되는 값을 <b>materialized</b>한
 * 캐시다 - 매번 SUM을 돌리지 않으려고 분개와 같은 트랜잭션에서 함께 갱신한다.
 * <p>
 * 동시 차감 경합은 이 행을 비관적 락({@code SELECT ... FOR UPDATE})으로 잡아 직렬화한다
 * ({@code LedgerAccountRepository.findForUpdate}). {@link Version}은 락을 안 쓰는 경로에서의
 * 2차 방어선이다.
 * <p>
 * SYSTEM 계정은 {@code ownerId = 0}을 쓴다(널이면 유니크 제약이 DB마다 다르게 동작해서
 * 시스템 계정이 중복 생성될 수 있다).
 */
@Entity
@Table(name = "ledger_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ledger_account_owner_kind",
                columnNames = {"owner_type", "owner_id", "kind"}))
public class LedgerAccount {

    /** SYSTEM 계정의 owner_id 자리값. */
    public static final long SYSTEM_OWNER_ID = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // enum은 autoApply 컨버터(com.example.transit.domain.EnumStringConverter)로 varchar 저장한다.
    @Column(name = "owner_type", nullable = false, length = 10)
    private AccountOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "kind", nullable = false, length = 20)
    private AccountKind kind;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LedgerAccount() {
        // JPA
    }

    private LedgerAccount(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.kind = kind;
        this.balance = 0L;
    }

    public static LedgerAccount forUser(long userId, AccountKind kind) {
        return new LedgerAccount(AccountOwnerType.USER, userId, kind);
    }

    public static LedgerAccount forPartner(long partnerId, AccountKind kind) {
        return new LedgerAccount(AccountOwnerType.PARTNER, partnerId, kind);
    }

    public static LedgerAccount forSystem(AccountKind kind) {
        return new LedgerAccount(AccountOwnerType.SYSTEM, SYSTEM_OWNER_ID, kind);
    }

    public static LedgerAccount of(AccountOwnerType ownerType, long ownerId, AccountKind kind) {
        return switch (ownerType) {
            case USER -> forUser(ownerId, kind);
            case PARTNER -> forPartner(ownerId, kind);
            case SYSTEM -> forSystem(kind);
        };
    }

    /**
     * 분개 한 줄을 이 계정 잔액에 반영한다. 방향과 계정의 정상잔액이 같으면 늘고, 다르면 준다.
     *
     * @param amount 항상 양수
     */
    public void apply(EntryDirection direction, long amount) {
        boolean increases = (direction == EntryDirection.DEBIT) == kind.debitNormal();
        this.balance += increases ? amount : -amount;
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

    public AccountOwnerType getOwnerType() {
        return ownerType;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public AccountKind getKind() {
        return kind;
    }

    public long getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }
}

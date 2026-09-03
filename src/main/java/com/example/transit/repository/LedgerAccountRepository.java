package com.example.transit.repository;

import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.domain.ledger.LedgerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByOwnerTypeAndOwnerIdAndKind(
            AccountOwnerType ownerType, long ownerId, AccountKind kind);

    boolean existsByOwnerTypeAndOwnerIdAndKind(
            AccountOwnerType ownerType, long ownerId, AccountKind kind);

    /**
     * 잔액 갱신 전 이 행을 {@code SELECT ... FOR UPDATE}로 잡는다. 같은 계정을 동시에 건드리는
     * 트랜잭션들을 여기서 직렬화해 잔액이 음수가 되거나 분개가 유실되는 걸 막는다.
     * <p>
     * 호출하는 서비스 메서드는 반드시 {@code @Transactional}이어야 한다(락은 그 트랜잭션이
     * 커밋/롤백될 때 풀린다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a "
            + "where a.ownerType = :ownerType and a.ownerId = :ownerId and a.kind = :kind")
    Optional<LedgerAccount> findForUpdate(@Param("ownerType") AccountOwnerType ownerType,
                                          @Param("ownerId") long ownerId,
                                          @Param("kind") AccountKind kind);
}

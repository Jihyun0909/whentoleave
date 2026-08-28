package com.example.transit.repository;

import com.example.transit.domain.Settlement;
import com.example.transit.domain.SettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByPartnerIdAndPeriodStartAndPeriodEnd(
            Long partnerId, LocalDate periodStart, LocalDate periodEnd);

    boolean existsByPartnerIdAndPeriodStartAndPeriodEndAndStatus(
            Long partnerId, LocalDate periodStart, LocalDate periodEnd, SettlementStatus status);

    Page<Settlement> findByPartnerIdOrderByIdDesc(Long partnerId, Pageable pageable);

    Page<Settlement> findAllByOrderByIdDesc(Pageable pageable);
}

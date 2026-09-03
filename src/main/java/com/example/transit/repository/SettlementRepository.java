package com.example.transit.repository;

import com.example.transit.domain.Settlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    List<Settlement> findByPartnerIdAndPeriodStartAndPeriodEndOrderByIdDesc(
            Long partnerId, LocalDate periodStart, LocalDate periodEnd);

    Page<Settlement> findByPartnerIdOrderByIdDesc(Long partnerId, Pageable pageable);

    Page<Settlement> findAllByOrderByIdDesc(Pageable pageable);
}

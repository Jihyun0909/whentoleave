package com.example.transit.repository;

import com.example.transit.domain.Payment;
import com.example.transit.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByRideId(Long rideId);

    boolean existsByRideId(Long rideId);

    /** 정산 대상 기간에 미정산 결제를 가진 제휴사 id 목록. */
    @Query("select distinct p.partnerId from Payment p "
            + "where p.status = :status and p.settledAt is null "
            + "and p.paidAt >= :from and p.paidAt < :to "
            + "order by p.partnerId")
    List<Long> findPartnerIdsWithUnsettledPayments(@Param("status") PaymentStatus status,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    /** 한 제휴사의 해당 기간 미정산 결제. */
    List<Payment> findByPartnerIdAndStatusAndSettledAtIsNullAndPaidAtGreaterThanEqualAndPaidAtLessThan(
            Long partnerId, PaymentStatus status, LocalDateTime from, LocalDateTime to);
}

package com.example.transit.api.dto.settlement;

import com.example.transit.domain.Settlement;
import com.example.transit.domain.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record SettlementResponse(
        long id,
        long partnerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        long grossAmount,
        long commissionAmount,
        long payoutAmount,
        int paymentCount,
        SettlementStatus status,
        String failureReason,
        Long batchJobExecutionId,
        LocalDateTime createdAt
) {
    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(s.getId(), s.getPartnerId(), s.getPeriodStart(), s.getPeriodEnd(),
                s.getGrossAmount(), s.getCommissionAmount(), s.getPayoutAmount(), s.getPaymentCount(),
                s.getStatus(), s.getFailureReason(), s.getBatchJobExecutionId(), s.getCreatedAt());
    }
}

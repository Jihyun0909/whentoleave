package com.example.transit.api.dto.settlement;

import com.example.transit.service.settlement.SettlementRunResult;

public record SettlementRunResponse(
        long jobExecutionId,
        String status,
        String exitCode,
        int settledPartners,
        int failedPartners
) {
    public static SettlementRunResponse from(SettlementRunResult result) {
        return new SettlementRunResponse(result.jobExecutionId(), result.status(), result.exitCode(),
                result.settledPartners(), result.failedPartners());
    }
}

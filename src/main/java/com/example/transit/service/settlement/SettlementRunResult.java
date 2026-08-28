package com.example.transit.service.settlement;

/**
 * @param jobExecutionId  Spring Batch 실행 id
 * @param status          BatchStatus (COMPLETED / FAILED 등)
 * @param exitCode        ExitStatus 코드
 * @param settledPartners 정상 확정된 제휴사 수(write count)
 * @param failedPartners  실패로 건너뛴 제휴사 수(skip count)
 */
public record SettlementRunResult(
        long jobExecutionId,
        String status,
        String exitCode,
        int settledPartners,
        int failedPartners
) {
}

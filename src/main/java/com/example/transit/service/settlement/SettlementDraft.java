package com.example.transit.service.settlement;

import java.time.LocalDate;
import java.util.List;

/**
 * 프로세서가 계산해서 라이터로 넘기는 한 제휴사의 정산안. 아직 아무것도 저장되지 않았다.
 *
 * @param paymentIds 이 정산에 포함되는 결제들. 라이터가 확정 시 {@code settled_at}을 찍는다.
 */
public record SettlementDraft(
        long partnerId,
        LocalDate periodStart,
        LocalDate periodEnd,
        long grossAmount,
        long commissionAmount,
        long payoutAmount,
        List<Long> paymentIds,
        Long batchJobExecutionId
) {
    public int paymentCount() {
        return paymentIds.size();
    }
}

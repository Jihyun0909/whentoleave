package com.example.transit.api.dto.settlement;

import java.time.LocalDate;

/**
 * @param settlementDate 정산 대상 날짜(그 날 결제된 미정산 건). null이면 어제.
 */
public record SettlementRunRequest(LocalDate settlementDate) {
}

package com.example.transit.api.dto.ride;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * @param pointToUse 이번 결제에 쓸 선불 포인트(0이면 포인트 미사용). 요금을 넘으면 400.
 */
public record RideCompleteRequest(
        @PositiveOrZero long pointToUse
) {
}

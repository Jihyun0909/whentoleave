package com.example.transit.api.dto.ride;

import com.example.transit.service.ride.RideService;

public record CompletionResponse(
        RideResponse ride,
        PaymentResponse payment
) {
    public static CompletionResponse from(RideService.CompletionResult result) {
        return new CompletionResponse(
                RideResponse.from(result.ride()),
                PaymentResponse.from(result.payment()));
    }
}

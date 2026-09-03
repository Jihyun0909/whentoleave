package com.example.transit.api.dto.ride;

import com.example.transit.domain.Payment;
import com.example.transit.domain.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentResponse(
        long id,
        long rideId,
        long grossAmount,
        long pointUsed,
        long cashAmount,
        long pointEarned,
        PaymentStatus status,
        LocalDateTime paidAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getRideId(), payment.getGrossAmount(),
                payment.getPointUsed(), payment.getCashAmount(), payment.getPointEarned(),
                payment.getStatus(), payment.getPaidAt());
    }
}

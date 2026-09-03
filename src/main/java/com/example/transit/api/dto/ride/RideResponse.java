package com.example.transit.api.dto.ride;

import com.example.transit.domain.RideStatus;
import com.example.transit.domain.TaxiRide;

import java.time.LocalDateTime;

public record RideResponse(
        long id,
        long partnerId,
        String origin,
        String destination,
        long fareAmount,
        RideStatus status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt
) {
    public static RideResponse from(TaxiRide ride) {
        return new RideResponse(ride.getId(), ride.getPartnerId(), ride.getOrigin(), ride.getDestination(),
                ride.getFareAmount(), ride.getStatus(), ride.getRequestedAt(), ride.getCompletedAt());
    }
}

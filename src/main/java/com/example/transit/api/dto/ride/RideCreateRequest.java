package com.example.transit.api.dto.ride;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RideCreateRequest(
        @NotNull Long partnerId,
        @NotBlank @Size(max = 100) String origin,
        @NotBlank @Size(max = 100) String destination,
        @Positive long fareAmount
) {
}

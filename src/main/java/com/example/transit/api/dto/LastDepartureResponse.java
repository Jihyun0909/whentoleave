package com.example.transit.api.dto;

import com.example.transit.service.LastDepartureResult;

import java.time.LocalTime;

public record LastDepartureResponse(boolean feasible, LocalTime departureTime, boolean nextDay, String reason) {

    public static LastDepartureResponse from(LastDepartureResult result) {
        return switch (result) {
            case LastDepartureResult.Feasible f ->
                    new LastDepartureResponse(true, f.departureTime(), f.nextDay(), null);
            case LastDepartureResult.Infeasible i ->
                    new LastDepartureResponse(false, null, false, i.reason());
        };
    }
}

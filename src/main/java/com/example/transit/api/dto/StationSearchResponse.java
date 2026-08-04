package com.example.transit.api.dto;

import com.example.transit.service.StationCandidate;
import com.example.transit.service.StationResolution;

import java.util.List;

public record StationSearchResponse(
        String status, // RESOLVED, AMBIGUOUS, NOT_FOUND
        Double x,
        Double y,
        String displayName,
        List<StationCandidate> candidates
) {

    public static StationSearchResponse from(StationResolution resolution) {
        return switch (resolution) {
            case StationResolution.Resolved r ->
                    new StationSearchResponse("RESOLVED", r.x(), r.y(), r.displayName(), null);
            case StationResolution.Ambiguous a ->
                    new StationSearchResponse("AMBIGUOUS", null, null, null, a.candidates());
            case StationResolution.NotFound n ->
                    new StationSearchResponse("NOT_FOUND", null, null, null, null);
        };
    }
}

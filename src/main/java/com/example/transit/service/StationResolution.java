package com.example.transit.service;

import java.util.List;

public sealed interface StationResolution {

    record Resolved(double x, double y, String displayName) implements StationResolution {
    }

    record Ambiguous(List<StationCandidate> candidates) implements StationResolution {
    }

    record NotFound(String name) implements StationResolution {
    }
}

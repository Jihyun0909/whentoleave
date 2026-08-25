package com.example.transit.service.client.dto;

import java.util.List;

/** Google Routes API computeRoutes 요청 바디. */
public record GoogleRoutesRequest(Waypoint origin, Waypoint destination, String travelMode,
                                   boolean computeAlternativeRoutes, TransitPreferences transitPreferences) {

    public static GoogleRoutesRequest transit(double sx, double sy, double ex, double ey,
                                               List<String> allowedTravelModes) {
        TransitPreferences preferences = allowedTravelModes == null || allowedTravelModes.isEmpty()
                ? null : new TransitPreferences(allowedTravelModes);
        return new GoogleRoutesRequest(
                new Waypoint(new Location(new LatLng(sy, sx))),
                new Waypoint(new Location(new LatLng(ey, ex))),
                "TRANSIT", true, preferences);
    }

    public record Waypoint(Location location) {
    }

    public record Location(LatLng latLng) {
    }

    /** @param latitude 위도(y), @param longitude 경도(x). */
    public record LatLng(double latitude, double longitude) {
    }

    /** @param allowedTravelModes 예: ["SUBWAY"], ["BUS"]. 비워두면(null) 제한 없음. */
    public record TransitPreferences(List<String> allowedTravelModes) {
    }
}

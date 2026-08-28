package com.example.transit.service.client.dto;

import java.util.List;

/**
 * Google Routes API computeRoutes 요청 바디.
 *
 * @param departureTime RFC3339 타임스탬프(예: "2026-08-30T23:00:00+09:00"). arrivalTime과 동시에
 *                       설정할 수 없다(Google API 제약). null이면 이 필드를 안 보낸다.
 * @param arrivalTime    RFC3339 타임스탬프. TRANSIT 모드에서만 의미가 있다. departureTime과 배타적.
 */
public record GoogleRoutesRequest(Waypoint origin, Waypoint destination, String travelMode,
                                   boolean computeAlternativeRoutes, TransitPreferences transitPreferences,
                                   String departureTime, String arrivalTime) {

    /**
     * @param departureTime null이 아니면 이 시각 기준으로 경로를 계산한다(막차 모드 - 특정 도착
     *                       시각이 없으니 "이 날짜의 심야 시간대" 기준점을 넘긴다).
     * @param arrivalTime    null이 아니면 이 시각까지 도착하는 경로를 계산한다(목표 도착시간 모드).
     *                       departureTime과 동시에 넘기면 안 된다(둘 중 하나만 null이 아니어야 함).
     */
    public static GoogleRoutesRequest transit(double sx, double sy, double ex, double ey,
                                               List<String> allowedTravelModes,
                                               String departureTime, String arrivalTime) {
        TransitPreferences preferences = allowedTravelModes == null || allowedTravelModes.isEmpty()
                ? null : new TransitPreferences(allowedTravelModes);
        return new GoogleRoutesRequest(
                new Waypoint(new Location(new LatLng(sy, sx))),
                new Waypoint(new Location(new LatLng(ey, ex))),
                "TRANSIT", true, preferences, departureTime, arrivalTime);
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

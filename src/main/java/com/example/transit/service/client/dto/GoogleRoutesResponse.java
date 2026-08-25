package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Google Routes API computeRoutes(TRANSIT) 응답 중 우리가 실제로 쓰는 필드만 매핑한다.
 * <p>
 * <b>2026-08-20 라이브 테스트로 확인.</b> 실제 응답 예시(필드 경로만):
 * <pre>
 * {
 *   "routes": [{
 *     "legs": [{
 *       "distanceMeters": 21613, "duration": "2795s",
 *       "steps": [{
 *         "travelMode": "WALK" | "TRANSIT",
 *         "distanceMeters": 95, "staticDuration": "96s",
 *         "transitDetails": {
 *           "stopDetails": {
 *             "departureStop": {"name": "강남", "location": {"latLng": {"latitude":.., "longitude":..}}},
 *             "arrivalStop":   {"name": "을지로입구", "location": {...}},
 *             "departureTime": "2026-08-20T06:44:00Z", "arrivalTime": "2026-08-20T07:23:00Z"
 *           },
 *           "headsign": "외선순환 방면", "headway": "360s",
 *           "transitLine": {"name": "서울 지하철 2호선", "nameShort": "2호선",
 *                           "vehicle": {"type": "SUBWAY"}},
 *           "stopCount": 20
 *         }
 *       }]
 *     }]
 *   }]
 * }
 * </pre>
 * <b>주의:</b>
 * <ul>
 *   <li>ODsay와 달리 도보(WALK) 스텝은 실측 도로/보행로 기반 거리·시간을 준다(직선거리 추정이 아님) -
 *       {@code distanceMeters}/{@code staticDuration}이 채워져서 온다. 다만 아주 짧은 환승 내
 *       도보(같은 역 승강장 이동 등)는 이 필드들이 아예 비어 올 수 있다.</li>
 *   <li>{@code duration}/{@code staticDuration}은 "2795s" 같은 문자열이라 끝의 's'를 떼고 파싱해야 한다.</li>
 *   <li>{@code departureTime}/{@code arrivalTime}은 요청 시점(또는 지정한 departureTime) 기준의
 *       실제 예정 시각이다 - ODsay의 경로탐색과 달리 <b>이 결과는 시각에 의존적</b>이다. 좌표만으로
 *       무기한 캐싱하면 안 되고, 짧은 TTL로만 캐싱해야 한다({@link com.example.transit.service.client.GoogleRoutesClient}).</li>
 *   <li>{@code headway}는 그 시점의 배차간격(초)을 참고로 준다 - 요일별 공식 배차간격표는 아니므로
 *       막차 계산에는 못 쓰고 참고 표시용으로만 쓴다.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleRoutesResponse(List<Route> routes) {

    public boolean hasRoutes() {
        return routes != null && !routes.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(List<Leg> legs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Leg(List<Step> steps, Integer distanceMeters, String duration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String travelMode, Integer distanceMeters, String staticDuration,
                        TransitDetails transitDetails) {

        public boolean isTransit() {
            return "TRANSIT".equals(travelMode);
        }

        public boolean isWalk() {
            return "WALK".equals(travelMode);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitDetails(StopDetails stopDetails, String headsign, String headway,
                                  TransitLine transitLine, Integer stopCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StopDetails(Stop departureStop, Stop arrivalStop, String departureTime, String arrivalTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stop(String name, Location location) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(LatLng latLng) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LatLng(Double latitude, Double longitude) {
    }

    /** @param name 전체 노선명(예: "서울 지하철 2호선"), @param nameShort 표시용 축약명(예: "2호선", "360"). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransitLine(String name, String nameShort, Vehicle vehicle) {
    }

    /** @param type "SUBWAY" | "BUS" 등. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vehicle(String type) {
    }
}

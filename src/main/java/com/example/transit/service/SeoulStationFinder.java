package com.example.transit.service;

import com.example.transit.service.client.SeoulOpenApiClient;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link SeoulOpenApiClient}로 받은 정류소 목록에서 좌표상 가장 가까운 정류소를 고른다.
 * <p>
 * 필드명 추출을 "가능한 이름 후보들을 순서대로 시도"하는 방식으로 방어적으로 짰다 - 서울
 * 열린데이터광장 응답의 정확한 필드명을 실제 키 없이는 확정할 수 없어서다(SeoulOpenApiClient
 * 클래스 주석 참고). 실제 키로 확인되면 이 후보 목록을 정확한 이름 하나로 좁히면 된다.
 * 어떤 이유로든(설정 안 됨/호출 실패/필드를 못 찾음) 매칭에 실패하면 예외 대신 빈 값을 준다 -
 * 이건 보조 기능이라 실패가 기존 막차 계산에 영향을 주면 안 된다.
 */
@Component
public class SeoulStationFinder implements RealtimeStationFinder {

    private static final Logger log = LoggerFactory.getLogger(SeoulStationFinder.class);

    private static final List<String> ID_FIELDS = List.of("STOPS_NO", "ARS_ID", "STATION_ID", "stId");
    private static final List<String> NAME_FIELDS = List.of("STOPS_NM", "STATION_NM", "STATION_NAME", "stNm");
    private static final List<String> LON_FIELDS = List.of("X_CODE", "GPS_X", "gpsX", "lon");
    private static final List<String> LAT_FIELDS = List.of("Y_CODE", "GPS_Y", "gpsY", "lat");
    private static final int EARTH_RADIUS_METERS = 6_371_000;

    private final SeoulOpenApiClient client;

    public SeoulStationFinder(SeoulOpenApiClient client) {
        this.client = client;
    }

    @Override
    public Optional<StationMatch> findNearestStation(double x, double y) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        try {
            JsonNode response = client.findStationsNearPosition(x, y);
            return nearestOf(rows(response), x, y);
        } catch (RuntimeException e) {
            log.debug("서울시 정류소 조회 실패 x={} y={}", x, y, e);
            return Optional.empty();
        }
    }

    /** 최상위 키가 서비스명이라 고정 경로로 못 찾는다 - 배열이 담긴 첫 "row" 필드를 찾아 쓴다. */
    private List<JsonNode> rows(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        JsonNode row = findRowsNode(response);
        if (row == null || !row.isArray()) {
            return List.of();
        }
        return row.isEmpty() ? List.of() : java.util.stream.StreamSupport
                .stream(row.spliterator(), false)
                .toList();
    }

    private JsonNode findRowsNode(JsonNode node) {
        JsonNode direct = node.get("row");
        if (direct != null) {
            return direct;
        }
        for (var entry : node.properties()) {
            JsonNode nested = entry.getValue() == null ? null : entry.getValue().get("row");
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private Optional<StationMatch> nearestOf(List<JsonNode> rows, double x, double y) {
        StationMatch best = null;
        double bestDistance = Double.MAX_VALUE;
        for (JsonNode row : rows) {
            String id = firstText(row, ID_FIELDS);
            Double lon = firstDouble(row, LON_FIELDS);
            Double lat = firstDouble(row, LAT_FIELDS);
            if (id == null || lon == null || lat == null) {
                continue;
            }
            double distance = distanceMeters(y, x, lat, lon);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new StationMatch(id, firstText(row, NAME_FIELDS), (int) Math.round(distance));
            }
        }
        return Optional.ofNullable(best);
    }

    private String firstText(JsonNode row, List<String> fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                return value.asString();
            }
        }
        return null;
    }

    private Double firstDouble(JsonNode row, List<String> fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                try {
                    return Double.parseDouble(value.asString());
                } catch (NumberFormatException ignored) {
                    // 이 필드명은 아니었던 것 - 다음 후보로 계속
                }
            }
        }
        return null;
    }

    /** 두 좌표 사이 거리(m). Haversine (NightBusRouteFinder와 동일한 공식). */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

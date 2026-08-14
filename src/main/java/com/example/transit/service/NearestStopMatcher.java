package com.example.transit.service;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * 좌표 기반 근접 정류소 API 응답(JsonNode)에서 가장 가까운 정류소 행(row)을 고른다.
 * <p>
 * TAGO/경기/인천 세 지역 모두 정확한 응답 필드명을 실제 키 없이 확정할 수 없어서, 필드명을
 * 후보 목록으로 순서대로 시도하는 방어적 방식을 쓴다(예전 서울시 버스정류소 매칭에서 쓰던 것과
 * 같은 접근을 세 지역이 같이 쓰도록 공용 유틸로 뺐다). 거리 계산에는 좌표 필드만 있으면 되고,
 * 정류소 ID/이름/지역코드 등 나머지 필드는 매칭된 행을 돌려받은 호출부가 {@link #text}로 직접
 * 뽑아 쓴다(지역마다 필요한 필드 종류가 달라서 - TAGO만 cityCode가 추가로 필요한 식).
 */
final class NearestStopMatcher {

    private static final int EARTH_RADIUS_METERS = 6_371_000;

    private NearestStopMatcher() {
    }

    record Match(JsonNode row, int distanceMeters) {
    }

    static Optional<Match> findNearest(List<JsonNode> rows, double x, double y,
                                        List<String> lonFields, List<String> latFields) {
        Match best = null;
        double bestDistance = Double.MAX_VALUE;
        for (JsonNode row : rows) {
            Double lon = decimal(row, lonFields);
            Double lat = decimal(row, latFields);
            if (lon == null || lat == null) {
                continue;
            }
            double distance = distanceMeters(y, x, lat, lon);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Match(row, (int) Math.round(distance));
            }
        }
        return Optional.ofNullable(best);
    }

    /** 결과가 1건이면 배열이 아니라 단일 객체로 오는 경우가 흔해(공공데이터포털) 둘 다 리스트로 정규화한다. */
    static List<JsonNode> asList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return node.isEmpty() ? List.of() : StreamSupport.stream(node.spliterator(), false).toList();
        }
        return List.of(node);
    }

    static String text(JsonNode row, List<String> fieldNames) {
        if (row == null) {
            return null;
        }
        for (String field : fieldNames) {
            JsonNode value = row.get(field);
            if (value != null && !value.isNull()) {
                return value.asString();
            }
        }
        return null;
    }

    private static Double decimal(JsonNode row, List<String> fieldNames) {
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
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

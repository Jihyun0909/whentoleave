package com.example.transit.service;

import com.example.transit.service.client.SeoulBusStopApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 서울시 정류소 전체 목록을 한 번만 받아 메모리에 들고 있다가, 좌표로 가장 가까운 정류소를 찾아준다.
 * <p>
 * 좌표 기반 정류소 조회 API(ws.bus.go.kr의 stationinfo)는 활용신청이 별개라 못 쓰고, 대신
 * 열린데이터광장의 정적 정류소 데이터셋을 쓴다. 약 11,239건뿐이라(1,000건씩 12번 호출)
 * 통째로 들고 있어도 부담이 없고, 정류소 위치는 자주 바뀌지 않아 매번 다시 받을 이유도 없다.
 * <p>
 * 처음 필요해질 때 한 번 적재한다(앱 시작을 12번의 외부 호출로 늦추지 않기 위해). 적재에
 * 실패하면 빈 목록으로 두고 다음 호출에서 다시 시도한다 - 실시간 도착정보는 보조 기능이라
 * 실패해도 막차 계산에는 영향이 없어야 한다.
 */
@Component
public class SeoulBusStopCatalog {

    private static final Logger log = LoggerFactory.getLogger(SeoulBusStopCatalog.class);

    /** 열린데이터광장이 알려주는 전체 건수를 신뢰하되, 상한을 둬서 무한정 받지 않게 한다. */
    private static final int MAX_TOTAL_STOPS = 30_000;
    /** 이 거리(m)를 넘으면 "그 정류장이 아니다"로 본다 (NearestStopMatcher와 같은 기준). */
    private static final int MAX_MATCH_DISTANCE_METERS = 200;
    private static final int EARTH_RADIUS_METERS = 6_371_000;

    private final SeoulBusStopApiClient client;
    private volatile List<SeoulBusStop> stops = List.of();

    public SeoulBusStopCatalog(SeoulBusStopApiClient client) {
        this.client = client;
    }

    /**
     * @param stopId 서울시 정류소 ID(stId) - 도착정보 조회에 쓰는 값
     * @param arsId  정류소 고유번호(표시/디버깅용)
     */
    public record SeoulBusStop(String stopId, String arsId, String name, double x, double y) {
    }

    public Optional<SeoulBusStop> findNearest(double x, double y) {
        List<SeoulBusStop> loaded = ensureLoaded();
        SeoulBusStop best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SeoulBusStop stop : loaded) {
            double distance = distanceMeters(y, x, stop.y(), stop.x());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = stop;
            }
        }
        return bestDistance <= MAX_MATCH_DISTANCE_METERS ? Optional.ofNullable(best) : Optional.empty();
    }

    private List<SeoulBusStop> ensureLoaded() {
        List<SeoulBusStop> current = stops;
        if (!current.isEmpty() || !client.isConfigured()) {
            return current;
        }
        synchronized (this) {
            if (!stops.isEmpty()) {
                return stops;
            }
            List<SeoulBusStop> loaded = load();
            stops = loaded;
            return loaded;
        }
    }

    private List<SeoulBusStop> load() {
        List<SeoulBusStop> loaded = new ArrayList<>();
        try {
            int total = MAX_TOTAL_STOPS;
            for (int start = 1; start <= total; start += SeoulBusStopApiClient.MAX_ROWS_PER_CALL) {
                int end = Math.min(start + SeoulBusStopApiClient.MAX_ROWS_PER_CALL - 1, total);
                JsonNode body = client.findStops(start, end);
                JsonNode service = body == null ? null : body.get("busStopLocationXyInfo");
                if (service == null || service.isNull()) {
                    break;
                }
                if (start == 1) {
                    JsonNode count = service.get("list_total_count");
                    if (count != null && !count.isNull()) {
                        total = Math.min(count.asInt(), MAX_TOTAL_STOPS);
                    }
                }
                JsonNode rows = service.get("row");
                if (rows == null || !rows.isArray() || rows.isEmpty()) {
                    break;
                }
                rows.forEach(row -> toStop(row).ifPresent(loaded::add));
            }
        } catch (RuntimeException e) {
            log.debug("서울 정류소 목록 적재 실패 - 실시간 버스 도착정보는 이번엔 건너뛴다", e);
            return List.of();
        }
        log.info("서울 정류소 {}건 적재 완료", loaded.size());
        return List.copyOf(loaded);
    }

    private Optional<SeoulBusStop> toStop(JsonNode row) {
        String stopId = text(row, "STOPS_NO");
        String name = text(row, "STOPS_NM");
        String arsId = text(row, "NODE_ID");
        Double x = decimal(row, "XCRD");
        Double y = decimal(row, "YCRD");
        if (stopId == null || x == null || y == null) {
            return Optional.empty();
        }
        return Optional.of(new SeoulBusStop(stopId, arsId, name, x, y));
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Double decimal(JsonNode row, String field) {
        String value = text(row, field);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

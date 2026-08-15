package com.example.transit.service;

import com.example.transit.service.client.GyeonggiStationApiClient;
import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * {@link GyeonggiStationApiClient}로 받은 좌표 근처 정류소 목록에서 가장 가까운 곳을 고른다.
 * 필드명 후보는 GBIS 문서를 참고한 추정값이다(GyeonggiStationApiClient 클래스 주석 참고).
 */
@Component
public class GyeonggiStationFinder {

    private static final Logger log = LoggerFactory.getLogger(GyeonggiStationFinder.class);

    private static final List<String> ID_FIELDS = List.of("stationId", "staId");
    private static final List<String> LON_FIELDS = List.of("x", "X", "gpsX", "posX");
    private static final List<String> LAT_FIELDS = List.of("y", "Y", "gpsY", "posY");

    private final GyeonggiStationApiClient client;

    public GyeonggiStationFinder(GyeonggiStationApiClient client) {
        this.client = client;
    }

    public Optional<String> findNearestStationId(double x, double y) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        try {
            RegionalBusStationResponse response = client.findStationsNearPosition(x, y);
            List<JsonNode> rows = rows(response);
            return NearestStopMatcher.findNearest(rows, x, y, LON_FIELDS, LAT_FIELDS)
                    .map(match -> NearestStopMatcher.text(match.row(), ID_FIELDS))
                    .filter(id -> id != null);
        } catch (RuntimeException e) {
            log.debug("경기 근접 정류소 조회 실패 x={} y={}", x, y, e);
            return Optional.empty();
        }
    }

    /** msgBody가 정류소 목록을 직접 배열로 주는지, 이름 붙은 필드 안에 담아 주는지 몰라 둘 다 방어적으로 찾는다. */
    private List<JsonNode> rows(RegionalBusStationResponse response) {
        if (response == null || response.response() == null) {
            return List.of();
        }
        JsonNode msgBody = response.response().msgBody();
        if (msgBody == null || msgBody.isNull()) {
            return List.of();
        }
        if (msgBody.isArray()) {
            return NearestStopMatcher.asList(msgBody);
        }
        for (var entry : msgBody.properties()) {
            if (entry.getValue() != null && entry.getValue().isArray()) {
                return NearestStopMatcher.asList(entry.getValue());
            }
        }
        return NearestStopMatcher.asList(msgBody);
    }
}

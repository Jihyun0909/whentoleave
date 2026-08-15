package com.example.transit.service;

import com.example.transit.service.client.IncheonStationApiClient;
import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * {@link IncheonStationApiClient}로 받은 좌표 근처 정류소 목록에서 가장 가까운 곳을 고른다.
 * 경기(GyeonggiStationFinder)와 같은 필드명 후보를 쓴다 - 같은 벤더 시스템이라는 가정.
 */
@Component
public class IncheonStationFinder {

    private static final Logger log = LoggerFactory.getLogger(IncheonStationFinder.class);

    private static final List<String> ID_FIELDS = List.of("stationId", "staId");
    private static final List<String> LON_FIELDS = List.of("x", "X", "gpsX", "posX");
    private static final List<String> LAT_FIELDS = List.of("y", "Y", "gpsY", "posY");

    private final IncheonStationApiClient client;

    public IncheonStationFinder(IncheonStationApiClient client) {
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
            log.debug("인천 근접 정류소 조회 실패 x={} y={}", x, y, e);
            return Optional.empty();
        }
    }

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

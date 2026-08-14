package com.example.transit.service;

import com.example.transit.service.client.TagoStationApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * {@link TagoStationApiClient}로 받은 좌표 근처 정류소 목록에서 가장 가까운 곳을 고른다.
 * 필드명 후보 목록은 TAGO 응답이 소문자 필드명을 쓴다는 문서 기준 관례를 따른 추정값이다
 * (TagoStationApiClient 클래스 주석 참고). 실제 키로 확인되면 후보를 정확한 이름 하나로 좁히면 된다.
 */
@Component
public class TagoStationFinder {

    private static final Logger log = LoggerFactory.getLogger(TagoStationFinder.class);

    private static final List<String> ID_FIELDS = List.of("nodeid", "nodeId");
    private static final List<String> NAME_FIELDS = List.of("nodenm", "nodeNm");
    private static final List<String> LON_FIELDS = List.of("gpslong", "gpsLong");
    private static final List<String> LAT_FIELDS = List.of("gpslati", "gpsLati");
    private static final List<String> CITY_CODE_FIELDS = List.of("citycode", "cityCode");

    private final TagoStationApiClient client;

    public TagoStationFinder(TagoStationApiClient client) {
        this.client = client;
    }

    public Optional<TagoBusStopLookup.TagoStop> findNearestStop(double x, double y) {
        if (!client.isConfigured()) {
            return Optional.empty();
        }
        try {
            TagoBusArrivalResponse response = client.findStationsNearPosition(x, y);
            List<JsonNode> rows = rows(response);
            Optional<NearestStopMatcher.Match> match = NearestStopMatcher.findNearest(rows, x, y, LON_FIELDS, LAT_FIELDS);
            return match.flatMap(this::toStop);
        } catch (RuntimeException e) {
            log.debug("TAGO 근접 정류소 조회 실패 x={} y={}", x, y, e);
            return Optional.empty();
        }
    }

    private Optional<TagoBusStopLookup.TagoStop> toStop(NearestStopMatcher.Match match) {
        String nodeId = NearestStopMatcher.text(match.row(), ID_FIELDS);
        String cityCode = NearestStopMatcher.text(match.row(), CITY_CODE_FIELDS);
        if (nodeId == null || cityCode == null) {
            return Optional.empty();
        }
        return Optional.of(new TagoBusStopLookup.TagoStop(cityCode, nodeId));
    }

    private List<JsonNode> rows(TagoBusArrivalResponse response) {
        if (response == null || response.response() == null || response.response().body() == null) {
            return List.of();
        }
        JsonNode itemsNode = response.response().body().items();
        if (itemsNode == null || itemsNode.isNull()) {
            return List.of();
        }
        return NearestStopMatcher.asList(itemsNode.get("item"));
    }
}

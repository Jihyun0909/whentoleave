package com.example.transit.service;

import com.example.transit.service.client.GyeonggiBusApiClient;
import com.example.transit.service.client.dto.RegionalBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * {@link GyeonggiBusApiClient}로 GBIS 실시간 버스 도착정보를 조회해 {@link RealtimeBusArrival}로 변환한다.
 * GBIS 항목 하나에는 도착예정 버스 두 대(predictTime1/2)가 같이 담겨 오므로, 각각을 별도의
 * {@link RealtimeBusArrival}로 펼친다. 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다.
 */
@Service
public class GyeonggiBusArrivalService implements RealtimeGyeonggiBusArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(GyeonggiBusArrivalService.class);

    private final GyeonggiBusApiClient client;

    public GyeonggiBusArrivalService(GyeonggiBusApiClient client) {
        this.client = client;
    }

    @Override
    public List<RealtimeBusArrival> findArrivals(String stationId) {
        if (!client.isConfigured() || stationId == null || stationId.isBlank()) {
            return List.of();
        }
        try {
            return toArrivals(client.findArrivals(stationId));
        } catch (RuntimeException e) {
            log.debug("경기 버스 도착정보 조회 실패 stationId={}", stationId, e);
            return List.of();
        }
    }

    private List<RealtimeBusArrival> toArrivals(RegionalBusArrivalResponse response) {
        if (response == null || response.response() == null || response.response().msgBody() == null) {
            return List.of();
        }
        List<RealtimeBusArrival> result = new ArrayList<>();
        for (JsonNode item : items(response.response().msgBody().busArrivalList())) {
            result.addAll(toArrivals(item));
        }
        return result;
    }

    /** busArrivalList는 결과가 1건이면 단일 객체로, 여러 건이면 배열로 온다 - 둘 다 리스트로 정규화한다. */
    private List<JsonNode> items(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            return node.isEmpty() ? List.of() : StreamSupport.stream(node.spliterator(), false).toList();
        }
        return List.of(node);
    }

    /** predictTime1(다음 버스)/predictTime2(그다음 버스)를 각각 별도 도착정보로 펼친다. -1은 정보 없음. */
    private List<RealtimeBusArrival> toArrivals(JsonNode item) {
        String routeName = text(item, "routeName");
        List<RealtimeBusArrival> result = new ArrayList<>();
        addIfPresent(result, routeName, integer(item, "predictTime1"), integer(item, "locationNo1"),
                text(item, "plateNo1"));
        addIfPresent(result, routeName, integer(item, "predictTime2"), integer(item, "locationNo2"),
                text(item, "plateNo2"));
        return result;
    }

    private void addIfPresent(List<RealtimeBusArrival> result, String routeName, Integer predictMinutes,
                               Integer locationNo, String plateNo) {
        if (predictMinutes == null || predictMinutes < 0) {
            return;
        }
        result.add(RealtimeBusArrival.arriving(routeName, predictMinutes * 60, locationNo, plateNo));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Integer.parseInt(value.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

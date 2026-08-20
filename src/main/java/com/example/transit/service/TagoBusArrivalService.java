package com.example.transit.service;

import com.example.transit.service.client.TagoBusApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * {@link TagoBusApiClient}로 TAGO 실시간 버스 도착정보를 조회해 {@link RealtimeBusArrival}로 변환한다.
 * 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다.
 */
@Service
public class TagoBusArrivalService implements RealtimeTagoBusArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(TagoBusArrivalService.class);

    private final TagoBusApiClient client;

    public TagoBusArrivalService(TagoBusApiClient client) {
        this.client = client;
    }

    @Override
    public List<RealtimeBusArrival> findArrivals(String cityCode, String nodeId) {
        if (!client.isConfigured() || isBlank(cityCode) || isBlank(nodeId)) {
            return List.of();
        }
        try {
            return toArrivals(client.findArrivals(cityCode, nodeId));
        } catch (RuntimeException e) {
            log.debug("TAGO 버스 도착정보 조회 실패 cityCode={} nodeId={}", cityCode, nodeId, e);
            return List.of();
        }
    }

    private List<RealtimeBusArrival> toArrivals(TagoBusArrivalResponse response) {
        if (response == null || response.response() == null || response.response().body() == null) {
            return List.of();
        }
        List<RealtimeBusArrival> result = new ArrayList<>();
        for (JsonNode item : items(response.response().body().items())) {
            RealtimeBusArrival arrival = toArrival(item);
            if (arrival.secondsUntilArrival() != null && arrival.secondsUntilArrival() >= 0) {
                result.add(arrival);
            }
        }
        return result;
    }

    /** item은 결과가 1건이면 단일 객체로, 여러 건이면 배열로 온다 - 둘 다 리스트로 정규화한다. */
    private List<JsonNode> items(JsonNode itemsNode) {
        if (itemsNode == null || itemsNode.isNull()) {
            return List.of();
        }
        JsonNode item = itemsNode.get("item");
        if (item == null || item.isNull()) {
            return List.of();
        }
        if (item.isArray()) {
            return item.isEmpty() ? List.of() : StreamSupport.stream(item.spliterator(), false).toList();
        }
        return List.of(item);
    }

    private RealtimeBusArrival toArrival(JsonNode item) {
        return RealtimeBusArrival.arriving(text(item, "routeno"), integer(item, "arrtime"),
                integer(item, "arrprevstationcnt"), null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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

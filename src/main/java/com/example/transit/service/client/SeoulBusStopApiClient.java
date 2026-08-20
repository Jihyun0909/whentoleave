package com.example.transit.service.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/**
 * 서울 열린데이터광장의 정류소 위치 데이터셋({@code busStopLocationXyInfo}) 호출.
 * <p>
 * 좌표로 정류소를 찾는 API(ws.bus.go.kr의 stationinfo)는 활용신청이 또 별개라, 대신 전체
 * 정류소 목록(약 11,239건)을 받아 로컬에서 최근접 매칭한다. 자주 바뀌지 않는 정적 데이터라
 * 한 번 받아두면 되고({@code SeoulBusStopCatalog}), 지하철 실시간과 같은 키를 쓴다.
 * <p>
 * 한 번에 최대 1,000건까지만 주므로 호출하는 쪽이 구간을 나눠 여러 번 부른다.
 */
@Component
public class SeoulBusStopApiClient {

    /** 열린데이터광장 공통 제약 - 한 요청의 최대 행 수. */
    public static final int MAX_ROWS_PER_CALL = 1000;

    private static final String SERVICE_NAME = "busStopLocationXyInfo";

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public SeoulBusStopApiClient(@Value("${seoul-bus.stop-base-url}") String baseUrl,
                                  @Value("${seoul-bus.stop-api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param startIndex 1부터 시작하는 시작 행 번호
     * @param endIndex   끝 행 번호(포함). startIndex와의 차이가 {@link #MAX_ROWS_PER_CALL} 미만이어야 한다.
     */
    public JsonNode findStops(int startIndex, int endIndex) {
        URI uri = URI.create(baseUrl + "/" + apiKey + "/json/" + SERVICE_NAME
                + "/" + startIndex + "/" + endIndex + "/");
        return restClient.get().uri(uri).retrieve().body(JsonNode.class);
    }
}

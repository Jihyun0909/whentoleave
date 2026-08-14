package com.example.transit.service.client;

import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 인천 버스정보시스템(BIMS) 정류소 좌표기반 조회.
 * <p>
 * <b>주의 - 셋 중 가장 검증이 안 됐다.</b> 경기도(GBIS)와 같은 벤더 시스템을 쓴다는 가정으로
 * {@link RegionalBusStationResponse}(경기와 공유 DTO)와 같은 파라미터명(x/y)을 그대로 쓰고 있다.
 * {@code incheon-bus.station-base-url}에 오퍼레이션까지 통째로 넣게 해서, 실제 경로가 다르면
 * 코드 수정 없이 application.yml만 고치면 되게 했다.
 */
@Component
public class IncheonStationApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public IncheonStationApiClient(@Value("${incheon-bus.station-base-url}") String baseUrl,
                                    @Value("${incheon-bus.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public RegionalBusStationResponse findStationsNearPosition(double x, double y) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + encode(apiKey)
                + "&x=" + x + "&y=" + y + "&format=json");
        return restClient.get().uri(uri).retrieve().body(RegionalBusStationResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

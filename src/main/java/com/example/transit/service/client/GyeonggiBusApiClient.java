package com.example.transit.service.client;

import com.example.transit.service.client.dto.RegionalBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 경기도 버스정보시스템(GBIS) 버스도착정보 호출.
 * <p>
 * <b>주의 - 이 클래스는 실제 인증키로 검증되지 않았다.</b> {@code base-url}에 오퍼레이션
 * ({@code getBusArrivalListv2})까지 통째로 넣게 해뒀다 - 실제 경로가 다르면 코드 수정 없이
 * application.yml만 고치면 되게 하려는 의도. data.go.kr 발급키는 Decoding(원본) 키를 넣어야
 * 한다(이 클라이언트가 URL 인코딩을 직접 하므로).
 */
@Component
public class GyeonggiBusApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public GyeonggiBusApiClient(@Value("${gyeonggi-bus.base-url}") String baseUrl,
                                 @Value("${gyeonggi-bus.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param stationId GBIS 자체 정류소 ID (Seoul ARS ID, ODsay stationId와는 다른 체계) */
    public RegionalBusArrivalResponse findArrivals(String stationId) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + encode(apiKey)
                + "&stationId=" + encode(stationId) + "&format=json");
        return restClient.get().uri(uri).retrieve().body(RegionalBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

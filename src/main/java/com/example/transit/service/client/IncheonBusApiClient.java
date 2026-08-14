package com.example.transit.service.client;

import com.example.transit.service.client.dto.RegionalBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 인천 버스정보시스템(BIMS) 버스도착정보 호출.
 * <p>
 * <b>주의 - 이 클래스는 셋 중 가장 검증이 안 됐다.</b> 인천이 경기도(GBIS)와 같은 벤더 시스템을
 * 쓴다는 가정으로 {@link RegionalBusArrivalResponse}(경기와 공유 DTO)를 그대로 쓰고 있는데,
 * base-url의 자원경로(6280000/...)와 오퍼레이션명도 GBIS 패턴을 그대로 유추한 추정값이다.
 * 실제 키로 첫 호출을 해보고 응답 스키마가 다르면 인천 전용 DTO로 분리해야 한다.
 * {@code base-url}에 오퍼레이션까지 통째로 넣게 해서, 경로가 다르면 코드 수정 없이
 * application.yml만 고치면 되게 했다. data.go.kr 발급키는 Decoding(원본) 키를 넣어야 한다.
 */
@Component
public class IncheonBusApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public IncheonBusApiClient(@Value("${incheon-bus.base-url}") String baseUrl,
                                @Value("${incheon-bus.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param stationId 인천 BIMS 자체 정류소 ID */
    public RegionalBusArrivalResponse findArrivals(String stationId) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + encode(apiKey)
                + "&stationId=" + encode(stationId) + "&format=json");
        return restClient.get().uri(uri).retrieve().body(RegionalBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

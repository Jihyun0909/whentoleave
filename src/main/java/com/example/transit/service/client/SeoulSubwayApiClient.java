package com.example.transit.service.client;

import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 서울시 지하철 실시간 도착정보(swopenAPI.seoul.go.kr) 호출.
 * <p>
 * URL 형식({@code {base}/{key}/json/realtimeStationArrival/{시작}/{끝}/{역명}})은 서울 열린데이터광장에
 * 공개된 문서 기준이라 신뢰도가 높지만, 실제 발급받은 키로는 아직 호출해보지 못했다 - 응답 필드명은
 * {@link SeoulSubwayArrivalResponse}에서 확인/보정이 필요할 수 있다.
 */
@Component
public class SeoulSubwayApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public SeoulSubwayApiClient(@Value("${seoul-subway.base-url}") String baseUrl,
                                 @Value("${seoul-subway.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /** 키가 설정되지 않았으면 이 기능 전체를 조용히 꺼둔다 (선택 기능이라 필수값이 아니다). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 역 이름(예: "강남")으로 실시간 도착정보를 조회한다. 그 역을 지나는 모든 호선·방향이 섞여 나온다. */
    public SeoulSubwayArrivalResponse findArrivals(String stationName) {
        URI uri = URI.create(baseUrl + "/" + encode(apiKey) + "/json/realtimeStationArrival/0/20/"
                + encode(stationName));
        return restClient.get().uri(uri).retrieve().body(SeoulSubwayArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

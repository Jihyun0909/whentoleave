package com.example.transit.service.client;

import com.example.transit.service.client.dto.SeoulBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 서울시 버스도착정보 호출 (data.go.kr "서울특별시_버스도착정보조회 서비스").
 * <p>
 * 2026-08-18 실제 키로 확인: {@code arrive/getLowArrInfoByStId}에 정류소 ID(stId)를 넘기면
 * 그 정류장의 모든 노선 도착정보가 온다(오퍼레이션 이름의 Low는 저상버스를 뜻하지만 실제로는
 * 전 노선을 준다). 같은 호스트의 {@code stationinfo/*}는 별개 서비스라 활용신청이 따로이며,
 * 신청 전에는 401을 준다 - 그래서 좌표->정류소 매칭은 이 클라이언트가 아니라
 * {@link SeoulBusStopApiClient}(열린데이터광장 정류소 데이터셋)가 맡는다.
 */
@Component
public class SeoulBusApiClient {

    private final RestClient restClient;
    private final String arrivalBaseUrl;
    private final String apiKey;

    public SeoulBusApiClient(@Value("${seoul-bus.arrival-base-url}") String arrivalBaseUrl,
                              @Value("${seoul-bus.arrival-api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.arrivalBaseUrl = arrivalBaseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param stopId 서울시 정류소 ID(stId). ARS ID(5자리)가 아니라 9자리 쪽을 넘겨야 한다. */
    public SeoulBusArrivalResponse findArrivals(String stopId) {
        URI uri = URI.create(arrivalBaseUrl
                + "?serviceKey=" + encode(apiKey)
                + "&stId=" + encode(stopId)
                + "&resultType=json");
        return restClient.get().uri(uri).retrieve().body(SeoulBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

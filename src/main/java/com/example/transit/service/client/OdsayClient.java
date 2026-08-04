package com.example.transit.service.client;

import com.example.transit.service.client.dto.OdsayPathResponse;
import com.example.transit.service.client.dto.OdsayScheduleResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OdsayClient {

    private final RestClient restClient;
    private final String apiKey;

    public OdsayClient(@Value("${odsay.base-url}") String baseUrl,
                        @Value("${odsay.api-key}") String apiKey) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
    }

    public OdsayScheduleResponse fetchSubwaySchedule(int stationId, int wayCode) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchSubwaySchedule")
                        .queryParam("stationID", stationId)
                        .queryParam("wayCode", wayCode)
                        .queryParam("apiKey", apiKey)
                        .build())
                .retrieve()
                .body(OdsayScheduleResponse.class);
    }

    /**
     * SearchPathType=1(지하철 전용)로 요청해서, 응답에 버스 구간이 섞여 나오지 않게 한다.
     */
    public OdsayPathResponse searchSubwayPath(double sx, double sy, double ex, double ey) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/searchPubTransPathT")
                        .queryParam("SX", sx)
                        .queryParam("SY", sy)
                        .queryParam("EX", ex)
                        .queryParam("EY", ey)
                        .queryParam("SearchPathType", 1)
                        .queryParam("apiKey", apiKey)
                        .build())
                .retrieve()
                .body(OdsayPathResponse.class);
    }
}

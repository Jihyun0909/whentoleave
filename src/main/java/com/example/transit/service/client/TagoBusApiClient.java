package com.example.transit.service.client;

import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * TAGO(국가대중교통정보센터, data.go.kr) 정류소별 버스 도착예정정보 호출.
 * <p>
 * <b>주의 - 이 클래스는 실제 인증키로 검증되지 않았다.</b> 오퍼레이션 경로
 * ({@code getSttnAcctoArvlPrearngeInfoList})와 파라미터명은 공공데이터포털에 공개된 문서
 * 기준이라 신뢰도가 있지만, 처음 연동할 때 응답을 한 번 찍어서 {@link TagoBusArrivalResponse}의
 * 필드명이 맞는지 확인해야 한다.
 * <p>
 * data.go.kr 발급키는 "Encoding"/"Decoding" 두 형태로 나오는데, 이 클라이언트는 키를 직접
 * URL 인코딩하므로 <b>Decoding(원본) 키</b>를 넣어야 한다. Encoding 키를 넣으면 이중 인코딩되어
 * 인증이 깨진다.
 */
@Component
public class TagoBusApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public TagoBusApiClient(@Value("${tago.base-url}") String baseUrl,
                             @Value("${tago.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param cityCode TAGO 자체 지역코드 (예: 서울=25 - 정확한 값은 공공데이터포털 문서로 확인 필요)
     * @param nodeId   TAGO 자체 정류소 ID (Seoul ARS ID, ODsay stationId와는 다른 체계)
     */
    public TagoBusArrivalResponse findArrivals(String cityCode, String nodeId) {
        URI uri = URI.create(baseUrl + "/getSttnAcctoArvlPrearngeInfoList"
                + "?serviceKey=" + encode(apiKey)
                + "&cityCode=" + encode(cityCode)
                + "&nodeId=" + encode(nodeId)
                + "&numOfRows=20&pageNo=1&_type=json");
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

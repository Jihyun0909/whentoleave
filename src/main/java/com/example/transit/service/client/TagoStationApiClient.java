package com.example.transit.service.client;

import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * TAGO(국가대중교통정보센터, data.go.kr) 좌표기반 근접 정류소 조회.
 * <p>
 * <b>주의 - 이 클래스는 실제 인증키로 검증되지 않았다.</b> 오퍼레이션명/파라미터명은 공공데이터포털에
 * 공개된 "정류소정보조회 서비스"의 좌표기반 근접 정류소 조회(문서상 {@code getCrdntPrxmtSttnList})
 * 문서 기준 추정값이다. {@code tago.station-base-url}에 오퍼레이션까지 통째로 넣게 해서, 실제 경로가
 * 다르면 코드 수정 없이 application.yml만 고치면 되게 했다.
 * <p>
 * 응답 봉투(header/body.items) 모양이 정류소 도착정보 조회와 같아서 {@link TagoBusArrivalResponse}를
 * 그대로 재사용한다 - data.go.kr 응답은 이 봉투 구조를 서비스 전반에서 공유하는 편이라 새 DTO를
 * 만드는 대신 재사용하는 쪽이 낫다고 판단했다.
 */
@Component
public class TagoStationApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public TagoStationApiClient(@Value("${tago.station-base-url}") String baseUrl,
                                 @Value("${tago.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param x 경도(gpsLong), @param y 위도(gpsLati) */
    public TagoBusArrivalResponse findStationsNearPosition(double x, double y) {
        URI uri = URI.create(baseUrl + "?serviceKey=" + encode(apiKey)
                + "&gpsLati=" + y + "&gpsLong=" + x
                + "&numOfRows=20&pageNo=1&_type=json");
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

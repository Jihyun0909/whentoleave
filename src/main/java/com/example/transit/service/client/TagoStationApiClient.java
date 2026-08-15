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
 * <b>주의 - 2026-08-14 라이브 테스트 결과: 경로/파라미터명은 맞지만 이 키로는 아직 호출 불가.</b>
 * {@code getCrdntPrxmtSttnList} 호출 시 SERVICE_KEY_IS_NOT_REGISTERED가 나는데, TAGO 도착정보
 * ({@link TagoBusApiClient})는 같은 계정 키로 정상 호출됐다 - "정류소정보조회 서비스"는
 * data.go.kr에서 별도로 활용신청해야 한다(현재는 "국토교통부_(TAGO)_버스도착정보"만 신청된 상태로
 * 추정). 활용신청만 되면 그대로 쓸 수 있을 가능성이 높다.
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

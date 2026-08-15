package com.example.transit.service.client;

import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 인천 버스정보시스템(BIMS) 정류소 조회.
 * <p>
 * <b>주의 - 2026-08-14 라이브 테스트로 이 클래스의 전제가 틀렸음을 확인했다.</b> 인천은 경기도와
 * 벤더가 다르고, 좌표기반 근접 정류소 조회 오퍼레이션 자체가 없다 - data.go.kr에 등록된 건
 * 정류소명(bstopNm)으로 찾는 {@code busStationService/getBusStationNmList}뿐이다(이마저 아직
 * 활용신청 미완료 - SERVICE_KEY_IS_NOT_REGISTERED). 좌표->정류소 매칭이 필요하면 이 실시간 API
 * 대신 data.go.kr의 인천 정류소 현황 파일데이터(위경도 포함, 다운로드 방식)를 받아 로컬 캐시에서
 * 최근접 매칭하는 방식으로 바꿔야 한다 - 이 클라이언트의 x/y 기반 시그니처는 그 결정이 나기 전까지
 * 실사용 불가능한 상태다.
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

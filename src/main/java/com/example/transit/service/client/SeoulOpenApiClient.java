package com.example.transit.service.client;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 서울 열린데이터광장(openapi.seoul.go.kr) API를 호출한다.
 * <p>
 * <b>주의 - 이 클래스는 실제 인증키로 검증되지 않았다.</b> 이 프로젝트는 아직 키가 없는 상태에서
 * "키 없이 만들 수 있는 부분"으로 준비해둔 것이라, 아래 두 가지는 실제 키를 발급받은 뒤
 * 응답을 한 번 찍어보고 맞춰야 한다:
 * <ol>
 *   <li>{@code station-service-name}(application.yml) — 정류소 좌표 조회 서비스의 정확한
 *       서비스명. 지금 값(busStationLocationXyInfo)은 서울 열린데이터광장에 공개된 "버스정류소
 *       위치정보" 계열 데이터셋 이름을 참고해 넣어둔 추정값이다.</li>
 *   <li>응답 안의 필드명(정류소 ID/이름/좌표) — {@link SeoulStationFinder}에서 여러 후보
 *       이름으로 방어적으로 찾아보게 해뒀지만, 실제 응답을 보고 정확한 이름으로 좁혀야 한다.</li>
 * </ol>
 * URL 형식(`{base}/{key}/json/{service}/{start}/{end}/...`) 자체는 서울 열린데이터광장의
 * 모든 서비스가 공통으로 쓰는 표준 패턴이라 이 부분은 신뢰도가 높다.
 */
@Component
public class SeoulOpenApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String stationServiceName;
    private final String apiKey;

    public SeoulOpenApiClient(@Value("${seoul-bus.base-url}") String baseUrl,
                               @Value("${seoul-bus.station-service-name}") String stationServiceName,
                               @Value("${seoul-bus.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.stationServiceName = stationServiceName;
        this.apiKey = apiKey;
    }

    /** 키가 설정되지 않았으면 이 기능 전체를 조용히 꺼둔다 (선택 기능이라 필수값이 아니다). */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 좌표 근처 정류소 목록을 조회한다. 응답 구조를 고정 DTO가 아니라 {@link JsonNode}로 받는다 -
     * 서울 열린데이터광장 응답은 최상위 키가 서비스명 자체라서(설정으로 바뀔 수 있음),
     * 고정된 필드명의 record로는 안정적으로 매핑할 수 없다.
     */
    public JsonNode findStationsNearPosition(double x, double y) {
        URI uri = URI.create(baseUrl + "/" + encode(apiKey) + "/json/" + stationServiceName + "/1/50/"
                + "?X_CODE=" + x + "&Y_CODE=" + y);
        return restClient.get().uri(uri).retrieve().body(JsonNode.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

package com.example.transit.service.client;

import com.example.transit.service.client.dto.SeoulSubwayLastTrainResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 서울교통공사 SearchLastTrainTimeByIDService(openAPI.seoul.go.kr:8088) 호출.
 * <p>
 * 2026-08-25 라이브 테스트로 확인:
 * <ul>
 *   <li>URL 형식: {@code {base}/{key}/json/SearchLastTrainTimeByIDService/{시작}/{끝}/{역코드}/{요일}/{상하행}/}
 *       - 사당(0226)·강남(0222)·신도림(0234)·낙성대(0227)·종합운동장(0218)·창동(0412)으로 확인.</li>
 *   <li>역코드(STATION_CD)는 TAGO subwayStationId 뒷자리 역번호를 4자리로 채운 값과 같다
 *       (예: 사당 TAGO id "MTRS12226" -> "0226"). 별도 이름→코드 매핑 API가 필요 없다.
 *       단, 코레일 위탁 구간(4호선 진접선 등, TAGO id가 "MTRKR"류 접두사)은 이 대응이 안 맞는다
 *       (직접 확인: 0419로 조회하면 전혀 다른 역인 한성대입구가 나옴 - 서울교통공사 자체 노선이
 *       아니라 이 API 범위 밖이라는 뜻).</li>
 *   <li>실시간 도착정보에 이미 쓰는 서울 열린데이터광장 키(SEOUL_SUBWAY_API_KEY)를 그대로
 *       쓸 수 있다 - 이 API 전용 별도 키가 필요 없다.</li>
 * </ul>
 */
@Component
public class SeoulSubwayLastTrainApiClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public SeoulSubwayLastTrainApiClient(@Value("${seoul-subway.last-train-base-url}") String baseUrl,
                                          @Value("${seoul-subway.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param stationCode TAGO subwayStationId 뒷자리 역번호를 4자리로 채운 값(예: "0226").
     * @param weekTag     "1"(평일) | "2"(토요일) | "3"(일요일/공휴일).
     * @param inOutTag    "1"(상행) | "2"(하행).
     * @param maxRows     막차부터 거슬러 올라가며 받을 최대 행 수.
     */
    public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                       int maxRows) {
        URI uri = URI.create(baseUrl + "/" + encode(apiKey) + "/json/SearchLastTrainTimeByIDService/1/"
                + maxRows + "/" + encode(stationCode) + "/" + encode(weekTag) + "/" + encode(inOutTag) + "/");
        return restClient.get().uri(uri).retrieve().body(SeoulSubwayLastTrainResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

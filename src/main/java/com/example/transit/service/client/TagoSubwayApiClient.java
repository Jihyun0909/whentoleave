package com.example.transit.service.client;

import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * TAGO(국가대중교통정보센터, data.go.kr) 지하철정보(SubwayInfo) 호출.
 * <p>
 * <b>2026-08-20 라이브 테스트로 확인.</b> 서비스 base-url은 {@code https://apis.data.go.kr/1613000/SubwayInfo}
 * (다른 TAGO 서비스들과 달리 이름 끝에 "Service"가 안 붙는다 - 처음에 이걸 몰라서 여러 번 헛다리 짚었다).
 * 오퍼레이션명도 다른 TAGO 서비스(소문자 {@code get...})와 달리 <b>대문자 {@code Get...}</b>로 시작한다.
 * <ul>
 *   <li>{@code GetKwrdFndSubwaySttnList} - 역명(키워드)으로 역 검색. 결과는 "역명×노선" 조합별로
 *       한 행씩 나온다(예: "강남"으로 검색하면 신분당선/2호선 각각 별도 행, subwayStationId도 다름).
 *       item 필드: {@code subwayStationId, subwayStationName, subwayRouteName}.</li>
 *   <li>{@code GetSubwaySttnAcctoSchdulList} - 역+방향+요일유형의 전체 시간표. 필수 파라미터
 *       {@code subwayStationId}, {@code upDownTypeCode}("U"|"D"), {@code dailyTypeCode}
 *       ("01"=평일,"02"=토요일,"03"=일요일) 셋 다 없으면 400 대신 200+에러메시지로 응답한다
 *       (data.go.kr 특유의 필수파라미터 안내 방식 - resultCode "01"). item 필드:
 *       {@code subwayStationId, subwayStationNm, subwayRouteId, endSubwayStationId,
 *       endSubwayStationNm, depTime, arrTime}(전부 "HHMMSS" 6자리 문자열),
 *       {@code dailyTypeCode, upDownTypeCode}. ODsay의 firstLastFlag 같은 "막차 여부" 플래그가
 *       없으므로, 막차는 depTime이 가장 늦은 행으로 직접 판단해야 한다. 강남역(2호선) 평일 상행
 *       기준 하루 236건 - 페이지당 1건이 아니라 넉넉히(예: 300) 한 번에 받아야 한다.</li>
 * </ul>
 * <b>주의:</b> {@code resultType=json} 파라미터가 없으면 HTTP_ERROR(04)가 난다(다른 TAGO 서비스와
 * 동일한 quirk). {@code _type=json}과 둘 다 넣는다.
 * <p>
 * data.go.kr 발급키는 "Encoding"/"Decoding" 두 형태로 나오는데, 이 클라이언트는 키를 직접
 * URL 인코딩하므로 <b>Decoding(원본) 키</b>를 넣어야 한다.
 */
@Component
public class TagoSubwayApiClient {

    private static final int SCHEDULE_MAX_ROWS = 300;

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final TagoRateLimiter rateLimiter;

    @Autowired
    public TagoSubwayApiClient(@Value("${tago.subway-base-url}") String baseUrl,
                                @Value("${tago.api-key}") String apiKey, TagoRateLimiter rateLimiter) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
    }

    /** 테스트에서 오버라이드로 HTTP 호출 자체를 안 쓸 때 쓰는 생성자. */
    public TagoSubwayApiClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, new TagoRateLimiter());
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param stationName 역명(부분/전체) - 결과는 역명×노선 조합별로 한 행씩. */
    public TagoBusArrivalResponse findStations(String stationName) {
        URI uri = URI.create(baseUrl + "/GetKwrdFndSubwaySttnList"
                + "?serviceKey=" + encode(apiKey)
                + "&_type=json&resultType=json"
                + "&subwayStationName=" + encode(stationName)
                + "&numOfRows=50&pageNo=1");
        rateLimiter.acquire();
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    /**
     * @param subwayStationId {@link #findStations}로 얻은 "역×노선" 조합의 TAGO 자체 역 ID.
     * @param upDownTypeCode  "U"(상행) | "D"(하행).
     * @param dailyTypeCode   "01"(평일) | "02"(토요일) | "03"(일요일).
     */
    public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode, String dailyTypeCode) {
        URI uri = URI.create(baseUrl + "/GetSubwaySttnAcctoSchdulList"
                + "?serviceKey=" + encode(apiKey)
                + "&_type=json&resultType=json"
                + "&subwayStationId=" + encode(subwayStationId)
                + "&upDownTypeCode=" + encode(upDownTypeCode)
                + "&dailyTypeCode=" + encode(dailyTypeCode)
                + "&numOfRows=" + SCHEDULE_MAX_ROWS + "&pageNo=1");
        rateLimiter.acquire();
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

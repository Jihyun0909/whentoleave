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
 * <p>
 * <b>알려진 커버리지 공백(2026-08-25 실사용 중 발견):</b> 1호선의 코레일(KORAIL) 운영 구간
 * 역들(TAGO subwayStationId가 "MTRKR"로 시작 - 창동·광운대·월계·석계 등, 라이브로 확인)은
 * {@code GetSubwaySttnAcctoSchdulList}가 상행/하행 둘 다 정상 응답(resultCode 00)이지만 결과
 * 0건을 준다. 서울교통공사(MTRS 접두사) 구간과 달리 TAGO가 이 구간의 시간표 자체를 안 갖고
 * 있는 것으로 보인다 - TAGO가 서울 버스를 아예 안 커버하는 것과 같은 종류의 데이터 공백이다.
 * 호출하는 쪽({@code SubwayScheduleCacheService})은 빈 목록을 그대로 받아 "이 구간 운행
 * 정보를 찾을 수 없음"으로 처리하고, 화면에는 "운행 종료"가 아니라 "시간표 정보를 확인할 수
 * 없다"는 구분된 안내가 나가야 한다({@code LastDepartureViewController#displayReason} 참고).
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

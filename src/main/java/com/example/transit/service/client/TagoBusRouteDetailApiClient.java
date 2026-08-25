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
 * TAGO(국가대중교통정보센터, data.go.kr) 버스노선정보(BusRouteInfoInqireService) 호출.
 * 정류소별 실시간 도착정보({@link TagoBusApiClient}, ArvlInfoInqireService)와는 별개 서비스라
 * base-url이 다르고, 클래스도 분리한다.
 * <p>
 * <b>2026-08-20 라이브 테스트로 확인:</b> 실제 키로 세 오퍼레이션 전부 정상 응답 확인.
 * <ul>
 *   <li>{@code getRouteNoList} - 노선번호(부분/전체)로 후보 노선들을 검색. item 필드:
 *       {@code routeid, routeno, routetp, startnodenm, startvehicletime("0500"류 문자열),
 *       endnodenm, endvehicletime(2320류 정수)}.</li>
 *   <li>{@code getRouteInfoIem} - routeId 하나의 상세. item 필드는 위와 동일 + 배차간격 3종:
 *       {@code intervaltime}(평일), {@code intervalsattime}(토요일), {@code intervalsuntime}(일요일),
 *       전부 분 단위 정수. 결과가 1건이라 item이 배열이 아니라 단일 객체로 온다.</li>
 *   <li>{@code getRouteAcctoThrghSttnList} - routeId의 경유 정류소를 순번대로. item 필드:
 *       {@code nodeid, nodenm, nodeno, nodeord}(1부터 시작하는 순번), {@code gpslati, gpslong}.
 *       ODsay의 busLaneDetail과 달리 기점부터의 누적거리를 안 주므로, 호출하는 쪽에서
 *       좌표를 순서대로 Haversine 합산해서 직접 계산해야 한다.</li>
 * </ul>
 * <b>주의:</b> {@code resultType=json} 파라미터가 없으면 파라미터 값과 무관하게 HTTP_ERROR(04)가
 * 난다(data.go.kr의 알려진 동작 - IncheonBusApiClient에서도 같은 증상 확인됨). {@code _type=json}과
 * 둘 다 넣어야 한다.
 * <p>
 * data.go.kr 발급키는 "Encoding"/"Decoding" 두 형태로 나오는데, 이 클라이언트는 키를 직접
 * URL 인코딩하므로 <b>Decoding(원본) 키</b>를 넣어야 한다.
 */
@Component
public class TagoBusRouteDetailApiClient {

    private static final int STOP_LIST_MAX_ROWS = 300;

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final TagoRateLimiter rateLimiter;

    @Autowired
    public TagoBusRouteDetailApiClient(@Value("${tago.route-info-base-url}") String baseUrl,
                                        @Value("${tago.api-key}") String apiKey, TagoRateLimiter rateLimiter) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.rateLimiter = rateLimiter;
    }

    /** 테스트에서 오버라이드로 HTTP 호출 자체를 안 쓸 때 쓰는 생성자. */
    public TagoBusRouteDetailApiClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, new TagoRateLimiter());
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @param routeNo 노선번호(부분 검색 - 예: "1"이면 "1", "83-1", "310" 등이 다 걸림). */
    public TagoBusArrivalResponse findRoutesByNo(String routeNo, String cityCode) {
        URI uri = URI.create(baseUrl + "/getRouteNoList"
                + "?serviceKey=" + encode(apiKey)
                + "&_type=json&resultType=json"
                + "&cityCode=" + encode(cityCode)
                + "&routeNo=" + encode(routeNo)
                + "&numOfRows=50&pageNo=1");
        rateLimiter.acquire();
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    /** @param routeId {@link #findRoutesByNo}로 얻은 TAGO 자체 노선 ID. */
    public TagoBusArrivalResponse fetchRouteDetail(String routeId, String cityCode) {
        URI uri = URI.create(baseUrl + "/getRouteInfoIem"
                + "?serviceKey=" + encode(apiKey)
                + "&_type=json&resultType=json"
                + "&cityCode=" + encode(cityCode)
                + "&routeId=" + encode(routeId));
        rateLimiter.acquire();
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    /** 순번(nodeord)대로의 경유 정류소 목록. 누적거리는 안 주므로 좌표로 직접 계산해야 한다. */
    public TagoBusArrivalResponse fetchRouteStops(String routeId, String cityCode) {
        URI uri = URI.create(baseUrl + "/getRouteAcctoThrghSttnList"
                + "?serviceKey=" + encode(apiKey)
                + "&_type=json&resultType=json"
                + "&cityCode=" + encode(cityCode)
                + "&routeId=" + encode(routeId)
                + "&numOfRows=" + STOP_LIST_MAX_ROWS + "&pageNo=1");
        rateLimiter.acquire();
        return restClient.get().uri(uri).retrieve().body(TagoBusArrivalResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

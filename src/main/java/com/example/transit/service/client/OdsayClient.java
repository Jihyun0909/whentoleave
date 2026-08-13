package com.example.transit.service.client;

import com.example.transit.service.client.dto.OdsayBusLaneDetailResponse;
import com.example.transit.service.client.dto.OdsayBusLaneSearchResponse;
import com.example.transit.service.client.dto.OdsayPathResponse;
import com.example.transit.service.client.dto.OdsayScheduleResponse;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * URI를 직접 조립해서 쓴다 (UriComponentsBuilder.queryParam()에 맡기지 않음).
 * 이유: '/'는 URI 스펙상 쿼리 문자열 안에서 인코딩 없이 허용되는 문자라
 * Spring이 자동으로는 인코딩하지 않는데, ODsay API 키에는 '/'가 포함될 수 있고
 * ODsay 서버는 그 '/'가 인코딩 안 된 채로 오면 키 인증에 실패한다(직접 확인함).
 * 그래서 apiKey 등 모든 값은 URLEncoder로 완전히 인코딩한 뒤, 이미 인코딩된
 * 문자열이라는 걸 URI.create()로 명시해서 이중 인코딩을 피한다.
 */
@Component
public class OdsayClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public OdsayClient(@Value("${odsay.base-url}") String baseUrl,
                        @Value("${odsay.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public OdsayScheduleResponse fetchSubwaySchedule(int stationId, int wayCode) {
        URI uri = buildUri("/searchSubwaySchedule",
                "stationID=" + stationId,
                "wayCode=" + wayCode,
                "apiKey=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(OdsayScheduleResponse.class);
    }

    /** SearchPathType=1(지하철 전용). */
    public OdsayPathResponse searchSubwayPath(double sx, double sy, double ex, double ey) {
        return searchPath(sx, sy, ex, ey, SearchPathType.SUBWAY_ONLY);
    }

    public OdsayPathResponse searchPath(double sx, double sy, double ex, double ey, SearchPathType pathType) {
        URI uri = buildUri("/searchPubTransPathT",
                "SX=" + sx,
                "SY=" + sy,
                "EX=" + ex,
                "EY=" + ey,
                "SearchPathType=" + pathType.code(),
                "apiKey=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(OdsayPathResponse.class);
    }

    /**
     * 버스 번호로 노선을 검색한다. 부분 검색이라 busNo="N"이면 서울 심야버스가 전부 나온다 —
     * 심야버스는 경로탐색(searchPubTransPathT) 결과에 아예 포함되지 않아서, 이렇게 따로
     * 목록을 받아 직접 경로를 찾는다 (NightBusRouteFinder).
     */
    public OdsayBusLaneSearchResponse searchBusLanes(String busNo, int cityCode) {
        URI uri = buildUri("/searchBusLane",
                "busNo=" + encode(busNo),
                "CID=" + cityCode,
                "apiKey=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(OdsayBusLaneSearchResponse.class);
    }

    /**
     * 버스 노선의 첫차/막차/배차간격을 조회한다. 버스는 지하철과 달리 정류장별 시간표 API가
     * 없어서, 이 노선 단위 정보로 승차 정류장의 출발 시각을 추정할 수밖에 없다.
     */
    public OdsayBusLaneDetailResponse fetchBusLaneDetail(int busId) {
        URI uri = buildUri("/busLaneDetail",
                "busID=" + busId,
                "apiKey=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(OdsayBusLaneDetailResponse.class);
    }

    /**
     * stationClass=2(지하철역)로 고정해서 요청한다. 이름은 부분 검색이라 여러 건이
     * 나올 수 있고, 정확한 이름 필터링은 호출하는 쪽(StationCandidateResolver)에서 한다.
     */
    public OdsayStationSearchResponse searchStation(String stationName) {
        URI uri = buildUri("/searchStation",
                "stationName=" + encode(stationName),
                "stationClass=2",
                "apiKey=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(OdsayStationSearchResponse.class);
    }

    private URI buildUri(String path, String... queryParams) {
        return URI.create(baseUrl + path + "?" + String.join("&", queryParams));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

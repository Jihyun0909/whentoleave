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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 같은 요청은 이 시간 동안 캐시된 응답을 쓴다.
     * <p>
     * ODsay 무료 플랜은 일일 호출 한도가 있는데, 검색 한 번에 호출이 20번 가까이 나간다
     * (자동완성 타이핑 + 역 확정 2 + 경로탐색 3 + 심야버스 노선목록/상세 19...). 실제로 하루
     * 한도를 소진한 적이 있어서, URI 단위로 응답을 캐싱해 중복 호출을 없앤다.
     * <p>
     * 노선·시간표·역 정보는 하루 단위로만 바뀌면 충분해서 길게 잡고, 경로탐색은 상대적으로
     * 짧게 둔다. 경로탐색 결과는 목표 도착 시각과 무관하므로(좌표만으로 결정), 시각을 바꿔가며
     * 재검색해도 캐시가 그대로 쓰인다 — 이게 절약 효과가 가장 크다.
     */
    private static final Duration STATIC_DATA_TTL = Duration.ofHours(12);
    private static final Duration PATH_TTL = Duration.ofHours(1);
    /** 캐시가 무한정 커지지 않도록 상한을 둔다. 넘으면 만료분부터 비우고, 그래도 넘으면 전부 비운다. */
    private static final int MAX_CACHE_ENTRIES = 2_000;

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

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
        return getCached(uri, OdsayScheduleResponse.class, STATIC_DATA_TTL);
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
        return getCached(uri, OdsayPathResponse.class, PATH_TTL);
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
        return getCached(uri, OdsayBusLaneSearchResponse.class, STATIC_DATA_TTL);
    }

    /**
     * 버스 노선의 첫차/막차/배차간격을 조회한다. 버스는 지하철과 달리 정류장별 시간표 API가
     * 없어서, 이 노선 단위 정보로 승차 정류장의 출발 시각을 추정할 수밖에 없다.
     */
    public OdsayBusLaneDetailResponse fetchBusLaneDetail(int busId) {
        URI uri = buildUri("/busLaneDetail",
                "busID=" + busId,
                "apiKey=" + encode(apiKey));
        return getCached(uri, OdsayBusLaneDetailResponse.class, STATIC_DATA_TTL);
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
        return getCached(uri, OdsayStationSearchResponse.class, STATIC_DATA_TTL);
    }

    /**
     * 같은 URI로 이미 받아둔 응답이 있으면 그걸 쓰고, 없으면 호출한 뒤 캐시에 넣는다.
     * 응답 DTO는 전부 불변 record라 여러 요청이 같은 인스턴스를 공유해도 안전하다.
     */
    private <T> T getCached(URI uri, Class<T> type, Duration ttl) {
        String key = uri.toString();
        CacheEntry cached = responseCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return type.cast(cached.body());
        }

        T body = restClient.get().uri(uri).retrieve().body(type);
        if (body != null) {
            evictIfFull();
            responseCache.put(key, new CacheEntry(body, Instant.now().plus(ttl)));
        }
        return body;
    }

    private void evictIfFull() {
        if (responseCache.size() < MAX_CACHE_ENTRIES) {
            return;
        }
        Instant now = Instant.now();
        responseCache.values().removeIf(entry -> entry.expiresAt().isBefore(now));
        if (responseCache.size() >= MAX_CACHE_ENTRIES) {
            // 만료분을 걷어내도 가득하면 통째로 비운다. 캐시일 뿐이라 다시 채우면 되고,
            // 정교한 LRU를 직접 구현하는 것보다 단순한 쪽이 낫다고 판단했다.
            responseCache.clear();
        }
    }

    private URI buildUri(String path, String... queryParams) {
        return URI.create(baseUrl + path + "?" + String.join("&", queryParams));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CacheEntry(Object body, Instant expiresAt) {
    }
}

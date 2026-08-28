package com.example.transit.service.client;

import com.example.transit.service.client.dto.GoogleRoutesRequest;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Routes API computeRoutes(TRANSIT)를 호출한다.
 * <p>
 * <b>2026-08-20 라이브 테스트로 확인:</b> {@code transitPreferences.allowedTravelModes}로
 * 지하철/버스 전용 필터링이 되고(ODsay의 SearchPathType 3분할과 동일한 역할),
 * {@code computeAlternativeRoutes: true}로 여러 경로 후보도 받을 수 있다.
 * <p>
 * ODsay와 달리 <b>POST + JSON 바디</b>이고, {@code X-Goog-Api-Key}/{@code X-Goog-FieldMask}
 * 헤더가 필수다(FieldMask 없으면 400). 응답은 {@link GoogleRoutesResponse} 참고.
 * <p>
 * <b>캐싱 전략이 ODsay와 다르다:</b> ODsay 경로탐색은 좌표만으로 결과가 결정돼서 무기한 캐싱이
 * 가능했지만, Google TRANSIT 결과는 각 구간에 그 시점 기준 실제 예정
 * 출발/도착 시각이 박혀 있어 시각에 의존적이다. 그래서 좌표+검색타입만으로 캐시 키를 잡되
 * TTL을 짧게(10분) 둔다 - 노선 조합(토폴로지) 자체는 짧은 시간 안에 잘 안 바뀌므로 절약 효과는
 * 있지만, ODsay만큼 길게 캐싱하면 안 된다.
 */
@Component
public class GoogleRoutesClient {

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CACHE_ENTRIES = 500;
    private static final String FIELD_MASK = String.join(",",
            "routes.legs.steps.travelMode",
            "routes.legs.steps.distanceMeters",
            "routes.legs.steps.staticDuration",
            "routes.legs.steps.transitDetails",
            "routes.legs.distanceMeters",
            "routes.legs.duration");

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Map<String, CacheEntry> responseCache = new ConcurrentHashMap<>();

    public GoogleRoutesClient(@Value("${google-routes.base-url}") String baseUrl,
                               @Value("${google-routes.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * @param sx 출발지 경도, @param sy 출발지 위도, @param ex 도착지 경도, @param ey 도착지 위도
     *           (ODsay/기존 코드베이스 컨벤션과 동일하게 x=경도/longitude, y=위도/latitude).
     * @param allowedTravelModes 예: List.of("SUBWAY"), List.of("BUS"). null/빈 리스트면 제한 없음(지하철+버스 전체).
     * @param departureTime RFC3339. null이 아니면 이 시각 기준으로 계산(막차 모드).
     * @param arrivalTime   RFC3339. null이 아니면 이 시각까지 도착하는 경로로 계산(목표 도착시간 모드).
     *                      departureTime과 동시에 null이 아니면 안 된다(둘 중 정확히 하나만 값이 있어야 함
     *                      - Google API 자체 제약, {@link GoogleRoutesRequest} 참고).
     */
    public GoogleRoutesResponse computeTransitRoutes(double sx, double sy, double ex, double ey,
                                                       List<String> allowedTravelModes,
                                                       String departureTime, String arrivalTime) {
        String key = cacheKey(sx, sy, ex, ey, allowedTravelModes, departureTime, arrivalTime);
        CacheEntry cached = responseCache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.body();
        }

        GoogleRoutesRequest requestBody = GoogleRoutesRequest.transit(
                sx, sy, ex, ey, allowedTravelModes, departureTime, arrivalTime);
        GoogleRoutesResponse response = restClient.post()
                .uri(baseUrl)
                .header("X-Goog-Api-Key", apiKey)
                .header("X-Goog-FieldMask", FIELD_MASK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(GoogleRoutesResponse.class);

        if (response != null) {
            evictIfFull();
            responseCache.put(key, new CacheEntry(response, Instant.now().plus(CACHE_TTL)));
        }
        return response;
    }

    private void evictIfFull() {
        if (responseCache.size() < MAX_CACHE_ENTRIES) {
            return;
        }
        Instant now = Instant.now();
        responseCache.values().removeIf(entry -> entry.expiresAt().isBefore(now));
        if (responseCache.size() >= MAX_CACHE_ENTRIES) {
            responseCache.clear();
        }
    }

    /**
     * departureTime/arrivalTime도 키에 넣는다 - 안 넣으면 "오늘 막차"와 "내일 막차"처럼 좌표는
     * 같고 기준 시각만 다른 조회가 서로의 캐시를 잘못 재사용하게 된다(실사용 중 발견: Google
     * 조회가 항상 "지금" 기준이라 다른 날짜/목표시각을 물어도 완전히 다른 경로가 나오는 문제의
     * 원인이었다 - 이 필드들을 아예 안 보내고 있었다).
     */
    private String cacheKey(double sx, double sy, double ex, double ey, List<String> allowedTravelModes,
                             String departureTime, String arrivalTime) {
        String modes = allowedTravelModes == null ? "" : String.join("+", allowedTravelModes);
        return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f|%s|%s|%s",
                sx, sy, ex, ey, modes, departureTime, arrivalTime);
    }

    private record CacheEntry(GoogleRoutesResponse body, Instant expiresAt) {
    }
}

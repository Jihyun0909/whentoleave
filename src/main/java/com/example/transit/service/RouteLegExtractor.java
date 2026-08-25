package com.example.transit.service;

import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

/**
 * Google Routes API(computeRoutes, TRANSIT) 응답에서 대중교통 구간(TransitLeg) 목록을 추출한다.
 * 구간 사이의 도보/환승 시간은 다음 구간의 transferBufferMinutes로 누적한다.
 * <p>
 * 예전엔 ODsay 응답을 다뤘는데(자체 stationId/busId를 이미 알려줬음), Google은 정류장 이름과
 * 좌표만 주기 때문에 여기서 TAGO 자체 ID로 <b>새로 해석</b>하는 일이 추가됐다 - 클래스 이름은
 * 호출부(LastDepartureService, NightBusRouteFinder)를 덜 건드리려고 그대로 유지했다.
 * <ul>
 *   <li>지하철: 역명+노선명으로 TAGO 역을 찾고({@link #resolveSubwayStation}), Google의
 *       headsign(방면 텍스트)으로 상/하행을 추정한다({@link #resolveWayCode}) - 순환선(2호선의
 *       "외선순환"/"내선순환")은 종착역명 매칭 대신 외선=상행/내선=하행으로 직접 매핑한다.
 *       단축운행 필터링용 종착역 목록({@code earlierStopNames})도 Google이 안 주는 정보라
 *       TAGO 시간표에서 별도로 재구성한다({@link #resolveEarlierStopNames}).</li>
 *   <li>버스: 정류장 좌표 → TAGO cityCode({@link TagoCityCodeResolver}) → 노선번호로 routeId 검색
 *       → 그 노선의 경유정류소 목록에서 가장 가까운 정류소를 찾는다. cityCode를 못 구하면(서울 등
 *       TAGO 미커버 지역) 경로 자체는 만들되 배차정보(busIds)는 비워둔다 - NightBusRouteFinder와
 *       같은 절충이다.</li>
 * </ul>
 */
@Component
public class RouteLegExtractor {

    private static final Logger log = LoggerFactory.getLogger(RouteLegExtractor.class);

    /**
     * 방향(상/하행) 판별과 단축운행 종착역 판별에 쓸 기준 요일유형. 실제 시간표 조회가 아니라
     * "이 역에서 이 방향으로 어떤 종착역들이 존재하는가"만 보는 용도라 평일로 고정한다 -
     * 노선 토폴로지(어느 역이 어느 종착역으로 가는 열차를 갖는지)는 요일별로 거의 안 바뀐다.
     */
    private static final String DIRECTION_CHECK_DAILY_TYPE = "01";

    /**
     * 실사용 피드백(2026-08-25): Google이 주는 도보 소요시간이 실제보다 짧게 느껴진다 -
     * 신호 대기, 계단, 환승 통로처럼 Google 도보 경로 계산이 못 반영하는 여유를 걷는 구간마다
     * 고정으로 더한다. 걷는 구간이 아예 없으면(같은 승강장 환승 등) 더하지 않는다.
     */
    private static final int WALK_BUFFER_MINUTES = 2;

    private final TagoSubwayApiClient tagoSubwayApiClient;
    private final TagoBusRouteDetailApiClient tagoBusRouteDetailApiClient;
    private final TagoCityCodeResolver tagoCityCodeResolver;
    private final SeoulBusStopCatalog seoulBusStopCatalog;

    /**
     * 역명/노선명 -> TAGO 역 매칭, (역ID+방향) -> 종착역 집합 캐시. 실사용 중 발견: 환승이 많은
     * 긴 경로는 Google이 후보 경로를 수십 개 주는데, 대부분 같은 역들을 반복해서 지나간다.
     * 캐싱 없이 후보마다 매번 TAGO를 새로 조회하면(구간당 최대 5번 - 역 찾기 2번, 방향판별 1번,
     * 단축운행 판별 2번) 200ms 요청간격 제한(TagoRateLimiter)과 겹쳐 검색 하나가 수십 초씩
     * 걸렸다(수유->신림 검색에서 TAGO 호출 146번, 소요 약 60초 확인). 역-노선 매칭과 노선
     * 토폴로지(어느 역이 어느 종착역을 갖는지)는 하루 안에 안 바뀌는 정적 정보라 무기한 캐싱해도
     * 안전하다 - 실제 열차 시간표(SubwayScheduleCacheService)와는 다른 층이라 혼동하면 안 된다.
     */
    private final Map<String, Optional<StationMatch>> stationMatchCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> terminiCache = new ConcurrentHashMap<>();

    public RouteLegExtractor(TagoSubwayApiClient tagoSubwayApiClient,
                              TagoBusRouteDetailApiClient tagoBusRouteDetailApiClient,
                              TagoCityCodeResolver tagoCityCodeResolver,
                              SeoulBusStopCatalog seoulBusStopCatalog) {
        this.tagoSubwayApiClient = tagoSubwayApiClient;
        this.tagoBusRouteDetailApiClient = tagoBusRouteDetailApiClient;
        this.tagoCityCodeResolver = tagoCityCodeResolver;
        this.seoulBusStopCatalog = seoulBusStopCatalog;
    }

    /** 가장 첫 번째 추천 경로만 뽑는다 (하위 호환용). 여러 경로를 다 시도하려면 {@link #extractAll}을 쓴다. */
    public List<TransitLeg> extract(GoogleRoutesResponse response) {
        return extractAll(response, false).get(0).legs();
    }

    /** 지하철 전용으로 추출한다 (버스가 섞인 경로 후보는 건너뜀). */
    public List<ExtractedRoute> extractAll(GoogleRoutesResponse response) {
        return extractAll(response, false);
    }

    /**
     * Google이 추천한 경로 후보들 전부에서 대중교통 구간 목록을 뽑는다.
     * 막차 계산은 "가장 빠른" 기준으로 고른 1순위 경로 하나만 보면 부정확할 수 있다 —
     * 그 경로 중간에 배차가 뜸한 구간이 끼어 있으면 막차가 훨씬 이른 시각에 끊겨버리는데,
     * 실제로는 다른 경로로 더 늦게까지 갈 수 있는 경우가 있다 (이슈 #8). 그래서 호출하는 쪽
     * (LastDepartureService)에서 후보 경로마다 계산해보고 가장 늦게 출발해도 되는 걸 고른다.
     *
     * @param allowBus false면 버스가 섞인 경로 후보를 조용히 걸러낸다. 하나도 안 남으면 예외를 던진다.
     */
    public List<ExtractedRoute> extractAll(GoogleRoutesResponse response, boolean allowBus) {
        List<GoogleRoutesResponse.Route> routes = allRoutes(response);

        List<ExtractedRoute> candidates = new ArrayList<>();
        Integer walkOnlyMinutes = null;
        for (GoogleRoutesResponse.Route route : routes) {
            try {
                candidates.add(extractRoute(route, allowBus));
            } catch (WalkOnlyRouteException e) {
                // 이 후보는 도보 전용이라 대중교통 구간이 없다 - 다른 후보 중 실제 대중교통
                // 경로가 있으면 그걸 쓰고, 끝까지 하나도 없으면(전부 도보 전용) 가장 짧은
                // 도보시간을 들고 나가서 "도보 N분이면 충분해요"라고 안내한다.
                if (walkOnlyMinutes == null || e.walkMinutes() < walkOnlyMinutes) {
                    walkOnlyMinutes = e.walkMinutes();
                }
            } catch (NoSubwayRouteFoundException ignored) {
                // 이 경로 후보는 조건에 안 맞거나 정보가 부족함 - 다른 경로 후보로 계속 시도
            }
        }
        if (candidates.isEmpty()) {
            if (walkOnlyMinutes != null) {
                throw new WalkOnlyRouteException(walkOnlyMinutes);
            }
            throw new NoSubwayRouteFoundException("이용 가능한 대중교통 경로를 찾지 못했습니다.");
        }
        return candidates;
    }

    private ExtractedRoute extractRoute(GoogleRoutesResponse.Route route, boolean allowBus) {
        List<GoogleRoutesResponse.Step> steps = allSteps(route);
        if (steps.isEmpty()) {
            throw new NoSubwayRouteFoundException("경로에 구간 정보가 없습니다.");
        }

        List<TransitLeg> legs = new ArrayList<>();
        int pendingWalkMinutes = 0;

        for (GoogleRoutesResponse.Step step : steps) {
            if (step.isWalk()) {
                pendingWalkMinutes += minutesOf(step);
                continue;
            }
            if (!step.isTransit() || step.transitDetails() == null
                    || step.transitDetails().transitLine() == null
                    || step.transitDetails().transitLine().vehicle() == null) {
                throw new NoSubwayRouteFoundException(
                        "이용할 수 없는 교통수단이 포함된 경로입니다 (travelMode=" + step.travelMode() + ").");
            }

            String vehicleType = step.transitDetails().transitLine().vehicle().type();
            if ("SUBWAY".equals(vehicleType)) {
                legs.add(subwayLeg(step.transitDetails(), minutesOf(step), bufferedWalk(pendingWalkMinutes)));
                pendingWalkMinutes = 0;
            } else if ("BUS".equals(vehicleType) && allowBus) {
                legs.add(busLeg(step.transitDetails(), minutesOf(step), bufferedWalk(pendingWalkMinutes), step.distanceMeters()));
                pendingWalkMinutes = 0;
            } else if ("BUS".equals(vehicleType)) {
                throw new NoSubwayRouteFoundException("버스가 포함된 경로입니다.");
            } else {
                throw new NoSubwayRouteFoundException("이용할 수 없는 교통수단이 포함된 경로입니다 (" + vehicleType + ").");
            }
        }

        if (legs.isEmpty()) {
            // 전 구간이 도보뿐이었다는 뜻 - 출발지/도착지가 대중교통을 탈 필요 없이 걸어갈 수
            // 있을 만큼 가까운 경우다. 이걸 그냥 "대중교통 구간 없음"으로 던지면 호출하는 쪽에서
            // "경로 없음"과 구분을 못 해 "운행 종료"라는 엉뚱한 안내가 나간다.
            if (pendingWalkMinutes > 0) {
                throw new WalkOnlyRouteException(bufferedWalk(pendingWalkMinutes));
            }
            throw new NoSubwayRouteFoundException("경로에 대중교통 구간이 없습니다.");
        }
        // 요금(fareWon)은 이번 마이그레이션에서 field mask에 안 넣어서 항상 0이다 - 화면 표시용
        // 참고정보라 계산 로직에는 영향 없다. 필요해지면 routes.travelAdvisory.transitFare를 추가하면 된다.
        return new ExtractedRoute(legs, bufferedWalk(pendingWalkMinutes));
    }

    private int bufferedWalk(int rawWalkMinutes) {
        return rawWalkMinutes > 0 ? rawWalkMinutes + WALK_BUFFER_MINUTES : 0;
    }

    private TransitLeg subwayLeg(GoogleRoutesResponse.TransitDetails details, int rideMinutes, int pendingWalkMinutes) {
        GoogleRoutesResponse.Stop departureStop = stop(details, true);
        GoogleRoutesResponse.Stop arrivalStop = stop(details, false);
        String lineNameShort = details.transitLine().nameShort();
        if (departureStop == null || departureStop.name() == null || lineNameShort == null) {
            throw new NoSubwayRouteFoundException("지하철 구간에 역 정보가 없습니다.");
        }

        StationMatch boarding = resolveSubwayStation(departureStop.name(), lineNameShort)
                .orElseThrow(() -> new NoSubwayRouteFoundException(
                        "지하철역 정보를 찾지 못했습니다: " + departureStop.name() + "(" + lineNameShort + ")"));
        int wayCode = resolveWayCode(boarding.id(), details.headsign());
        log.debug("subwayLeg station={} lineNameShort={} headsign={} -> wayCode={}",
                departureStop.name(), lineNameShort, details.headsign(), wayCode);
        Set<String> earlierStopNames = resolveEarlierStopNames(
                boarding.id(), wayCode, arrivalStop == null ? null : arrivalStop.name(), lineNameShort);

        return TransitLeg.subway(boarding.id(), wayCode, rideMinutes, pendingWalkMinutes,
                earlierStopNames, departureStop.name(), arrivalStop == null ? null : arrivalStop.name(), lineNameShort);
    }

    private record StationMatch(String id, String tagoName) {
    }

    /** 역명(부분 검색 결과)에서 노선명이 일치하는 항목의 TAGO subwayStationId/역명을 찾는다. */
    private Optional<StationMatch> resolveSubwayStation(String stationName, String googleLineNameShort) {
        return stationMatchCache.computeIfAbsent(
                stationName + "|" + googleLineNameShort,
                key -> resolveSubwayStationUncached(stationName, googleLineNameShort));
    }

    private Optional<StationMatch> resolveSubwayStationUncached(String stationName, String googleLineNameShort) {
        try {
            TagoBusArrivalResponse response = tagoSubwayApiClient.findStations(stationName);
            for (JsonNode item : items(response)) {
                String routeName = text(item, "subwayRouteName");
                String id = text(item, "subwayStationId");
                String tagoName = text(item, "subwayStationName");
                if (id != null && lineMatches(routeName, googleLineNameShort)) {
                    return Optional.of(new StationMatch(id, tagoName));
                }
            }
        } catch (RuntimeException e) {
            // 조회 실패 - 이 구간은 못 만드는 걸로 처리(호출부에서 NoSubwayRouteFoundException으로 감쌈)
        }
        return Optional.empty();
    }

    /**
     * 이 구간의 도착역보다 "앞서" 끊기는(단축운행) 열차의 종착역 이름 집합을 구한다.
     * <p>
     * Google Routes는 ODsay의 passStopList 같은 구간별 정차역 순서를 안 준다. 대신 TAGO
     * 시간표에서 같은 방향으로 실제 운행되는 열차들의 종착역 이름 집합(TERMINI)을 승차역과
     * 도착역 각각에서 구해 비교하는 방식으로 대체한다: 도착역에 도달하려면 그 역을 지나야
     * 하므로, 도착역을 지나 더 운행하는 열차의 종착역만 도착역 TERMINI에 나타난다. 승차역
     * TERMINI 중 도착역 TERMINI에는 없는 이름은 도착역 전에 끊기는 단축운행 종착역이다.
     * <p>
     * 도착역의 TAGO 역을 못 찾으면(역명 불일치 등) 안전하게 빈 Set을 반환한다 - 걸러내는
     * 근거가 불확실할 땐 아무것도 안 거르는 게 "탈 수 있는데 못 탄다"고 하는 것보다 낫다.
     */
    private Set<String> resolveEarlierStopNames(String boardingStationId, int wayCode,
                                                 String arrivalStopName, String lineNameShort) {
        if (arrivalStopName == null) {
            return Set.of();
        }
        Optional<StationMatch> alight = resolveSubwayStation(arrivalStopName, lineNameShort);
        if (alight.isEmpty()) {
            return Set.of();
        }
        String upDownTypeCode = wayCode == 1 ? "U" : "D";
        Set<String> earlier = new HashSet<>(terminiOf(boardingStationId, upDownTypeCode));
        earlier.removeAll(terminiOf(alight.get().id(), upDownTypeCode));
        // 도착역 자신이 종착역인 열차는 도착역 TERMINI(그 역에서 "출발"하는 열차 기준)에는
        // 안 잡히지만 도착역까지는 정상적으로 데려다주므로, 걸러야 할 이름에서 명시적으로 뺀다.
        if (alight.get().tagoName() != null) {
            earlier.remove(alight.get().tagoName());
        }
        return earlier;
    }

    /** subwayStationId·방향의 시간표에서 실제 쓰이는 종착역 이름 집합(중복 제거). */
    private Set<String> terminiOf(String subwayStationId, String upDownTypeCode) {
        return terminiCache.computeIfAbsent(
                subwayStationId + ":" + upDownTypeCode,
                key -> terminiOfUncached(subwayStationId, upDownTypeCode));
    }

    private Set<String> terminiOfUncached(String subwayStationId, String upDownTypeCode) {
        try {
            TagoBusArrivalResponse response =
                    tagoSubwayApiClient.fetchSchedule(subwayStationId, upDownTypeCode, DIRECTION_CHECK_DAILY_TYPE);
            Set<String> names = new HashSet<>();
            for (JsonNode item : items(response)) {
                String endName = text(item, "endSubwayStationNm");
                if (endName != null && !endName.isBlank()) {
                    names.add(endName);
                }
            }
            return names;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** "2호선"/"2호"/"신분당선"/"신분당"처럼 표기가 갈리는 노선명을 "선/호선" 접미사를 떼고 비교한다. */
    private boolean lineMatches(String tagoRouteName, String googleLineNameShort) {
        if (tagoRouteName == null || googleLineNameShort == null) {
            return false;
        }
        return normalizeLineName(tagoRouteName).equals(normalizeLineName(googleLineNameShort));
    }

    private String normalizeLineName(String name) {
        String trimmed = name.trim();
        if (trimmed.endsWith("호선")) {
            return trimmed.substring(0, trimmed.length() - 2);
        }
        if (trimmed.endsWith("선")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Google의 headsign("성수행" 등)을 TAGO 상행(U) 시간표의 종착역명과 비교해 맞으면 상행(1),
     * 아니면 하행(2)으로 본다.
     * <p>
     * 2호선처럼 headsign이 종착역명이 아니라 "외선순환"/"내선순환" 같은 순환 방향 텍스트인
     * 노선은 이 비교가 항상 실패하므로 먼저 걸러서 직접 매핑한다: <b>외선순환=상행(U),
     * 내선순환=하행(D)</b>다 - 2026-08-25 강남역(TAGO id MTRS12222) 실제 시간표로 확인:
     * 상행(U) 종착역들(신도림·을지로입구·홍대입구·서울대입구·성수)은 강남역의 공식 역번호(222)
     * 기준으로 번호가 증가(순환이라 넘어가면 다시 낮은 번호부터)하는 방향에 있고, 하행(D)
     * 종착역들(성수·삼성·신도림)은 감소하는 방향에 있다 - 이는 "역번호가 증가하는 방향이
     * 외선순환"이라는 서울교통공사 공식 규칙과 일치한다.
     */
    private int resolveWayCode(String subwayStationId, String headsign) {
        if (headsign != null) {
            if (headsign.contains("외선")) {
                return 1;
            }
            if (headsign.contains("내선")) {
                return 2;
            }
        }
        String normalizedHeadsign = normalizeHeadsign(headsign);
        if (normalizedHeadsign != null && directionMatches(subwayStationId, "U", normalizedHeadsign)) {
            return 1;
        }
        return 2;
    }

    private String normalizeHeadsign(String headsign) {
        if (headsign == null) {
            return null;
        }
        return headsign.replace("방면", "").replace("행", "").trim();
    }

    /**
     * TAGO 종착역명과 Google headsign을 비교한다.
     * <p>
     * <b>단순 contains로는 안 된다(2026-08-25 실사용 중 발견):</b> TAGO는 부역명을 괄호로 붙여
     * "불암산(당고개)"처럼 주는데 Google headsign은 "불암산행"/"당고개행"처럼 짧게 온다. 짧은
     * 쪽이 긴 쪽을 포함할 수 없으니 {@code headsign.contains(종착역명)}은 항상 실패했고, 그래서
     * 수유역(4호선)처럼 멀쩡한 노선까지 방향 판별이 안 돼 하행으로 잘못 떨어졌다. 종착역명을
     * 괄호 기준으로 분해해("불암산(당고개)" -> "불암산", "당고개") 어느 쪽이든 맞으면 인정한다.
     */
    private boolean directionMatches(String subwayStationId, String upDownTypeCode, String normalizedHeadsign) {
        return terminiOf(subwayStationId, upDownTypeCode).stream()
                .flatMap(terminus -> terminusAliases(terminus).stream())
                .anyMatch(alias -> normalizedHeadsign.contains(alias) || alias.contains(normalizedHeadsign));
    }

    /**
     * 4호선 진접선 연장 구간(진접·오남·별내별가람)은 TAGO에서 본선(수유·쌍문·창동 등, ID가
     * "MTRS14"로 시작)과 완전히 다른 ID 체계("MTRNU4" 접두사)를 쓴다(2026-08-25 라이브 확인 -
     * {@code GetKwrdFndSubwaySttnList}로 직접 조회해 접두사가 다름을 확인했다). 그래서 수유역
     * 등에서 종착역 목록을 조회해도 "진접"은 절대 안 잡히고 "불암산(당고개)"까지만 잡힌다 -
     * 실제로는 당고개보다 더 북쪽으로 이어지는 같은 노선인데(공식 노선도상 이견 없는 사실),
     * TAGO 데이터 구조상 여기서 끊겨 보이는 것뿐이다. "불암산"/"당고개"가 인정된 종착역이면
     * 이 확장 구간 역명들도 같은 방향으로 취급한다.
     */
    private static final Set<String> DANGOGAE_EXTENSION_ALIASES =
            Set.of("당고개", "불암산", "진접", "오남", "별내별가람");

    /** "불암산(당고개)" -> ["불암산(당고개)", "불암산", "당고개", ...연장구간 역명들] (빈 문자열은 제외). */
    private List<String> terminusAliases(String terminus) {
        List<String> aliases = new ArrayList<>();
        aliases.add(terminus);
        int open = terminus.indexOf('(');
        int close = terminus.lastIndexOf(')');
        if (open >= 0 && close > open) {
            aliases.add(terminus.substring(0, open).trim());
            aliases.add(terminus.substring(open + 1, close).trim());
        }
        if (aliases.stream().anyMatch(DANGOGAE_EXTENSION_ALIASES::contains)) {
            aliases.addAll(DANGOGAE_EXTENSION_ALIASES);
        }
        return aliases.stream().filter(alias -> !alias.isBlank()).distinct().toList();
    }

    private TransitLeg busLeg(GoogleRoutesResponse.TransitDetails details, int rideMinutes, int pendingWalkMinutes,
                               Integer stepDistanceMeters) {
        GoogleRoutesResponse.Stop departureStop = stop(details, true);
        GoogleRoutesResponse.Stop arrivalStop = stop(details, false);
        String busNo = details.transitLine().nameShort();
        if (departureStop == null || departureStop.location() == null
                || departureStop.location().latLng() == null || busNo == null) {
            throw new NoSubwayRouteFoundException("버스 구간에 정류장 정보가 없습니다.");
        }
        double stopX = departureStop.location().latLng().longitude();
        double stopY = departureStop.location().latLng().latitude();
        int distanceMeters = stepDistanceMeters == null ? 0 : stepDistanceMeters;
        // Google의 정류장명이 실제 개명을 못 따라가는 경우가 있다(2026-08-25 실사용 피드백 -
        // "장위3동주민센터"가 "성북청소년센터"로 바뀐 지 한참인데 Google은 옛 이름을 줌). 매칭
        // 자체는 이름이 아니라 좌표로 하므로 기능엔 영향 없지만, 표시용 이름은 우리가 실시간
        // 조회에도 쓰는 서울 열린데이터광장 정류소 목록(더 최신인 경우가 많다)이 있으면 그걸
        // 우선한다. 서울 밖 좌표거나 근처에 없으면 자연히 Google 이름으로 남는다.
        // 승차 정류장뿐 아니라 하차 정류장에도 같은 보정이 필요하다(2026-08-25 실사용 중 재확인 -
        // 처음엔 승차 쪽만 고쳐서 같은 정류장이 "하차"로 나올 땐 여전히 옛 이름이 보였다).
        String boardingStopName = seoulBusStopCatalog.findNearest(stopX, stopY)
                .map(SeoulBusStopCatalog.SeoulBusStop::name)
                .orElse(departureStop.name());
        String endName = endStopName(arrivalStop);

        Optional<String> cityCode = tagoCityCodeResolver.resolve(stopX, stopY);
        if (cityCode.isEmpty()) {
            // TAGO가 커버 안 하는 지역(서울 등) - 경로 자체는 만들되 배차정보(busIds)는 비워둔다.
            // NightBusRouteFinder와 동일한 절충: "탈 수 있다"는 알아도 "몇 시에 오는지"는 모른다.
            return TransitLeg.bus(coordinateStationId(stopX, stopY), rideMinutes, pendingWalkMinutes,
                    boardingStopName, endName, busNo, List.of(), busNo, distanceMeters, stopX, stopY, null);
        }

        Optional<String> routeId = resolveBusRouteId(busNo, cityCode.get());
        if (routeId.isEmpty()) {
            return TransitLeg.bus(coordinateStationId(stopX, stopY), rideMinutes, pendingWalkMinutes,
                    boardingStopName, endName, busNo, List.of(), busNo, distanceMeters, stopX, stopY,
                    cityCode.get());
        }

        String stationId = resolveNearestStopId(routeId.get(), cityCode.get(), stopX, stopY)
                .orElseGet(() -> coordinateStationId(stopX, stopY));
        return TransitLeg.bus(stationId, rideMinutes, pendingWalkMinutes,
                boardingStopName, endName, busNo, List.of(routeId.get()), busNo, distanceMeters, stopX, stopY,
                cityCode.get());
    }

    /** 하차 정류장 표시명 - boardingStopName과 같은 이유로 좌표 매칭이 되면 그 이름을 우선한다. */
    private String endStopName(GoogleRoutesResponse.Stop arrivalStop) {
        if (arrivalStop == null) {
            return null;
        }
        if (arrivalStop.location() == null || arrivalStop.location().latLng() == null) {
            return arrivalStop.name();
        }
        double endX = arrivalStop.location().latLng().longitude();
        double endY = arrivalStop.location().latLng().latitude();
        return seoulBusStopCatalog.findNearest(endX, endY)
                .map(SeoulBusStopCatalog.SeoulBusStop::name)
                .orElse(arrivalStop.name());
    }

    /** 노선번호(부분 검색)로 TAGO routeId를 찾는다 - routeno가 정확히 일치하는 항목만 인정한다. */
    private Optional<String> resolveBusRouteId(String busNo, String cityCode) {
        try {
            TagoBusArrivalResponse response = tagoBusRouteDetailApiClient.findRoutesByNo(busNo, cityCode);
            for (JsonNode item : items(response)) {
                String routeNo = text(item, "routeno");
                String routeId = text(item, "routeid");
                if (routeId != null && busNo.equals(routeNo)) {
                    return Optional.of(routeId);
                }
            }
        } catch (RuntimeException e) {
            // 조회 실패 - 배차정보 없이 진행(호출부에서 빈 busIds로 처리)
        }
        return Optional.empty();
    }

    /** 이 노선의 경유정류소 목록에서 좌표가 가장 가까운 정류소의 nodeId를 찾는다. */
    private Optional<String> resolveNearestStopId(String routeId, String cityCode, double x, double y) {
        try {
            TagoBusArrivalResponse response = tagoBusRouteDetailApiClient.fetchRouteStops(routeId, cityCode);
            String bestId = null;
            double bestDistance = Double.MAX_VALUE;
            for (JsonNode item : items(response)) {
                String nodeId = text(item, "nodeid");
                Double lat = decimal(item, "gpslati");
                Double lng = decimal(item, "gpslong");
                if (nodeId == null || lat == null || lng == null) {
                    continue;
                }
                double distance = distanceMeters(y, x, lat, lng);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestId = nodeId;
                }
            }
            return Optional.ofNullable(bestId);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 실제 TAGO 정류소를 못 찾았을 때 쓰는 좌표 기반 대체 ID. busIds가 비어 있어 실제 조회엔 안 쓰인다. */
    private String coordinateStationId(double x, double y) {
        return String.format(java.util.Locale.ROOT, "COORD:%.6f,%.6f", x, y);
    }

    private GoogleRoutesResponse.Stop stop(GoogleRoutesResponse.TransitDetails details, boolean departure) {
        if (details.stopDetails() == null) {
            return null;
        }
        return departure ? details.stopDetails().departureStop() : details.stopDetails().arrivalStop();
    }

    /** "96s" 같은 초 단위 문자열을 분으로 반올림한다. */
    private int minutesOf(GoogleRoutesResponse.Step step) {
        String raw = step.staticDuration();
        if (raw == null || !raw.endsWith("s")) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(raw.substring(0, raw.length() - 1));
            return (int) Math.round(seconds / 60.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<GoogleRoutesResponse.Step> allSteps(GoogleRoutesResponse.Route route) {
        List<GoogleRoutesResponse.Step> steps = new ArrayList<>();
        if (route.legs() == null) {
            return steps;
        }
        for (GoogleRoutesResponse.Leg leg : route.legs()) {
            if (leg.steps() != null) {
                steps.addAll(leg.steps());
            }
        }
        return steps;
    }

    private List<GoogleRoutesResponse.Route> allRoutes(GoogleRoutesResponse response) {
        if (response == null || !response.hasRoutes()) {
            throw new NoSubwayRouteFoundException("이동 가능한 대중교통 경로를 찾지 못했습니다.");
        }
        return response.routes();
    }

    /**
     * @param legs             경로의 대중교통 구간들(정순)
     * @param finalWalkMinutes 마지막 하차 지점에서 실제 목적지까지 걸어야 하는 시간(분)
     * @param fareWon          이 경로의 총 요금(원). 모르면 0.
     */
    public record ExtractedRoute(List<TransitLeg> legs, int finalWalkMinutes, int fareWon) {

        public ExtractedRoute(List<TransitLeg> legs, int finalWalkMinutes) {
            this(legs, finalWalkMinutes, 0);
        }

        public boolean hasBus() {
            return legs.stream().anyMatch(TransitLeg::isBus);
        }
    }

    private List<JsonNode> items(TagoBusArrivalResponse response) {
        if (response == null || response.response() == null || response.response().body() == null) {
            return List.of();
        }
        JsonNode itemsNode = response.response().body().items();
        if (itemsNode == null || itemsNode.isNull()) {
            return List.of();
        }
        JsonNode item = itemsNode.get("item");
        if (item == null || item.isNull()) {
            return List.of();
        }
        if (item.isArray()) {
            return item.isEmpty() ? List.of() : StreamSupport.stream(item.spliterator(), false).toList();
        }
        return List.of(item);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Double decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Double.parseDouble(value.asString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }
}

package com.example.transit.service;

import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 *       headsign(방면 텍스트)으로 상/하행을 추정한다({@link #resolveWayCode}) - 이 추정은
 *       순환선(2호선의 "외선순환"/"내선순환"처럼 종착역명이 아닌 노선 텍스트)에서는 못 맞히고
 *       하행으로 기본 처리되는 알려진 한계가 있다.</li>
 *   <li>버스: 정류장 좌표 → TAGO cityCode({@link TagoCityCodeResolver}) → 노선번호로 routeId 검색
 *       → 그 노선의 경유정류소 목록에서 가장 가까운 정류소를 찾는다. cityCode를 못 구하면(서울 등
 *       TAGO 미커버 지역) 경로 자체는 만들되 배차정보(busIds)는 비워둔다 - NightBusRouteFinder와
 *       같은 절충이다.</li>
 * </ul>
 */
@Component
public class RouteLegExtractor {

    /** 방향(상/하행) 판별에 쓸 기준 요일유형. 실제 시간표 조회가 아니라 종착역명만 보는 용도라 평일로 고정한다. */
    private static final String DIRECTION_CHECK_DAILY_TYPE = "01";

    private final TagoSubwayApiClient tagoSubwayApiClient;
    private final TagoBusRouteDetailApiClient tagoBusRouteDetailApiClient;
    private final TagoCityCodeResolver tagoCityCodeResolver;

    public RouteLegExtractor(TagoSubwayApiClient tagoSubwayApiClient,
                              TagoBusRouteDetailApiClient tagoBusRouteDetailApiClient,
                              TagoCityCodeResolver tagoCityCodeResolver) {
        this.tagoSubwayApiClient = tagoSubwayApiClient;
        this.tagoBusRouteDetailApiClient = tagoBusRouteDetailApiClient;
        this.tagoCityCodeResolver = tagoCityCodeResolver;
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
                legs.add(subwayLeg(step.transitDetails(), minutesOf(step), pendingWalkMinutes));
                pendingWalkMinutes = 0;
            } else if ("BUS".equals(vehicleType) && allowBus) {
                legs.add(busLeg(step.transitDetails(), minutesOf(step), pendingWalkMinutes, step.distanceMeters()));
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
                throw new WalkOnlyRouteException(pendingWalkMinutes);
            }
            throw new NoSubwayRouteFoundException("경로에 대중교통 구간이 없습니다.");
        }
        // 요금(fareWon)은 이번 마이그레이션에서 field mask에 안 넣어서 항상 0이다 - 화면 표시용
        // 참고정보라 계산 로직에는 영향 없다. 필요해지면 routes.travelAdvisory.transitFare를 추가하면 된다.
        return new ExtractedRoute(legs, pendingWalkMinutes);
    }

    private TransitLeg subwayLeg(GoogleRoutesResponse.TransitDetails details, int rideMinutes, int pendingWalkMinutes) {
        GoogleRoutesResponse.Stop departureStop = stop(details, true);
        GoogleRoutesResponse.Stop arrivalStop = stop(details, false);
        String lineNameShort = details.transitLine().nameShort();
        if (departureStop == null || departureStop.name() == null || lineNameShort == null) {
            throw new NoSubwayRouteFoundException("지하철 구간에 역 정보가 없습니다.");
        }

        String subwayStationId = resolveSubwayStation(departureStop.name(), lineNameShort)
                .orElseThrow(() -> new NoSubwayRouteFoundException(
                        "지하철역 정보를 찾지 못했습니다: " + departureStop.name() + "(" + lineNameShort + ")"));
        int wayCode = resolveWayCode(subwayStationId, details.headsign());

        return TransitLeg.subway(subwayStationId, wayCode, rideMinutes, pendingWalkMinutes,
                Set.of(), departureStop.name(), arrivalStop == null ? null : arrivalStop.name(), lineNameShort);
    }

    /** 역명(부분 검색 결과)에서 노선명이 일치하는 항목의 TAGO subwayStationId를 찾는다. */
    private Optional<String> resolveSubwayStation(String stationName, String googleLineNameShort) {
        try {
            TagoBusArrivalResponse response = tagoSubwayApiClient.findStations(stationName);
            for (JsonNode item : items(response)) {
                String routeName = text(item, "subwayRouteName");
                String id = text(item, "subwayStationId");
                if (id != null && lineMatches(routeName, googleLineNameShort)) {
                    return Optional.of(id);
                }
            }
        } catch (RuntimeException e) {
            // 조회 실패 - 이 구간은 못 만드는 걸로 처리(호출부에서 NoSubwayRouteFoundException으로 감쌈)
        }
        return Optional.empty();
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
     * Google의 headsign("외선순환 방면", "성수행" 등)을 TAGO 상행(U) 시간표의 종착역명과 비교해
     * 맞으면 상행(1), 아니면 하행(2)으로 본다.
     * <p>
     * <b>알려진 한계:</b> 2호선처럼 headsign이 종착역명이 아니라 "외선순환"/"내선순환" 같은
     * 순환 방향 텍스트인 노선은 이 비교가 항상 실패해서 하행(2)으로 기본 처리된다 - 실제 방향이
     * 다르면 그 구간의 막차 계산이 부정확해질 수 있다. 별도 유닛테스트로 이 케이스를 추적할 필요가 있다.
     */
    private int resolveWayCode(String subwayStationId, String headsign) {
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

    private boolean directionMatches(String subwayStationId, String upDownTypeCode, String normalizedHeadsign) {
        try {
            TagoBusArrivalResponse response =
                    tagoSubwayApiClient.fetchSchedule(subwayStationId, upDownTypeCode, DIRECTION_CHECK_DAILY_TYPE);
            for (JsonNode item : items(response)) {
                String endName = text(item, "endSubwayStationNm");
                if (endName != null && !endName.isBlank() && normalizedHeadsign.contains(endName)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 판별 실패 - 기본값(하행)으로 처리
        }
        return false;
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
        String endName = arrivalStop == null ? null : arrivalStop.name();

        Optional<String> cityCode = tagoCityCodeResolver.resolve(stopX, stopY);
        if (cityCode.isEmpty()) {
            // TAGO가 커버 안 하는 지역(서울 등) - 경로 자체는 만들되 배차정보(busIds)는 비워둔다.
            // NightBusRouteFinder와 동일한 절충: "탈 수 있다"는 알아도 "몇 시에 오는지"는 모른다.
            return TransitLeg.bus(coordinateStationId(stopX, stopY), rideMinutes, pendingWalkMinutes,
                    departureStop.name(), endName, busNo, List.of(), busNo, distanceMeters, stopX, stopY, null);
        }

        Optional<String> routeId = resolveBusRouteId(busNo, cityCode.get());
        if (routeId.isEmpty()) {
            return TransitLeg.bus(coordinateStationId(stopX, stopY), rideMinutes, pendingWalkMinutes,
                    departureStop.name(), endName, busNo, List.of(), busNo, distanceMeters, stopX, stopY,
                    cityCode.get());
        }

        String stationId = resolveNearestStopId(routeId.get(), cityCode.get(), stopX, stopY)
                .orElseGet(() -> coordinateStationId(stopX, stopY));
        return TransitLeg.bus(stationId, rideMinutes, pendingWalkMinutes,
                departureStop.name(), endName, busNo, List.of(routeId.get()), busNo, distanceMeters, stopX, stopY,
                cityCode.get());
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

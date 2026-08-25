package com.example.transit.service;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import com.example.transit.repository.BusStopDepartureRepository;
import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * 버스 구간에서 탈 수 있는 차편들의 출발 시각을 추정한다.
 * <p>
 * <b>왜 추정인가:</b> TAGO도 ODsay와 마찬가지로 버스에는 정류장별 시간표 API가 없다. 노선 단위로
 * "기점 기준 첫차/막차"({@code getRouteInfoIem})와 요일별 배차간격, 그리고 순번대로의 경유
 * 정류소 좌표 목록({@code getRouteAcctoThrghSttnList})만 준다. ODsay의 busLaneDetail과 달리
 * <b>정류장별 기점 누적거리를 안 주므로</b>, 경유 정류소 좌표를 순서대로 Haversine 합산해서
 * 누적거리를 직접 계산한다:
 * <pre>
 *   평균속도   = 이 구간 거리 / 이 구간 소요시간   (경로탐색 응답에서 얻음)
 *   정류장통과 = 기점 출발시각 + 기점~승차정류장 누적거리(직접 계산) / 평균속도
 *   후보 차편  = 막차부터 배차간격만큼 거슬러 올라가며 첫차까지
 * </pre>
 * <b>한 구간에 여러 노선:</b> Google Routes는 보통 구간당 노선을 하나만 주지만, 여러 개가 들어올
 * 수 있는 구조는 유지한다 - 이 경우 후보 차편은 모든 노선의 합집합이고, 막차는 그중 가장 늦은
 * 노선 기준이 된다.
 * <p>
 * <b>서울 버스는 TAGO를 아예 못 쓴다:</b> TAGO가 서울 시내버스를 커버하지 않아 {@code busIds}가
 * 항상 비어 있으므로(RouteLegExtractor 참고), 이 경우 {@link SeoulBusRouteScheduleCatalog}(정적
 * 시드)로 대체한다. 노선 전체(기점) 기준 첫차/막차라 승차 정류장 offset은 못 구하지만, 없는 것보다
 * 이르게(안전하게) 추정되는 값이라도 있는 게 낫다.
 * <p>
 * 노선 하나당 API를 두 번(상세+경유정류소) 호출해야 해서, 구간당 조회하는 노선 수를
 * {@link #MAX_LANES_PER_LEG}개로 제한하고, (노선, 정류장, 요일유형)별로 캐싱한다
 * (SubwayScheduleCacheService와 같은 lazy cache-aside 방식).
 * <p>
 * <b>캐시 미스 경합:</b> 동시 요청이 같은 키에서 캐시 미스를 겪으면 둘 다 insert를 시도할 수
 * 있다. {@code bus_stop_departure}에 (bus_id, station_id, day_type) 유니크 제약이 있으므로
 * 진 쪽은 저장 시점에 {@link org.springframework.dao.DataIntegrityViolationException}을 받는데,
 * {@link #fetchAndCache}에서 이를 이긴 쪽이 이미 커밋한 행으로 재조회해 흡수한다. 이 클래스에
 * 메서드 단위 {@code @Transactional}을 걸지 않는 것은 의도적이다 - 실패한 저장 시도의 트랜잭션이
 * 재조회와 같은 트랜잭션에 묶여 있으면(PostgreSQL은 한 문장이 실패하면 그 트랜잭션 전체가
 * "aborted" 상태가 되어 이후 어떤 명령도 실패한다) 재조회조차 실패한다. Spring Data JPA
 * 리포지토리 메서드는 기본적으로 호출마다 자기 트랜잭션을 갖기 때문에, 실패한 저장과 뒤이은
 * 재조회가 서로 다른 트랜잭션/커넥션에서 돌아 이 문제를 자연스럽게 피한다.
 */
@Service
public class BusDepartureCacheService implements BusDepartureLookup {

    private static final Logger log = LoggerFactory.getLogger(BusDepartureCacheService.class);

    private static final int MINUTES_PER_DAY = 24 * 60;
    /** 구간당 조회할 최대 노선 수. */
    private static final int MAX_LANES_PER_LEG = 4;
    /** 배차간격 정보가 없거나 파싱이 안 될 때 쓰는 기본값(분). */
    private static final int DEFAULT_INTERVAL_MINUTES = 15;
    /** 한 노선에서 만들어낼 최대 차편 수 (배차 1~2분짜리 노선에서 후보가 폭발하는 것 방지). */
    private static final int MAX_DEPARTURES_PER_LANE = 200;
    /** 기점에서 승차 정류장까지 걸린다고 인정할 최대 시간(분). 왕복 노선 누적거리 폭주 방지. */
    private static final int MAX_OFFSET_MINUTES = 60;
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final BusStopDepartureRepository repository;
    private final TagoBusRouteDetailApiClient tagoClient;
    private final SeoulBusRouteScheduleCatalog seoulBusRouteScheduleCatalog;

    public BusDepartureCacheService(BusStopDepartureRepository repository, TagoBusRouteDetailApiClient tagoClient,
                                     SeoulBusRouteScheduleCatalog seoulBusRouteScheduleCatalog) {
        this.repository = repository;
        this.tagoClient = tagoClient;
        this.seoulBusRouteScheduleCatalog = seoulBusRouteScheduleCatalog;
    }

    @Override
    public List<Integer> departureServiceMinutes(TransitLeg leg, LocalDate date) {
        if (leg.busIds().isEmpty()) {
            return seoulFallbackDepartureMinutes(leg);
        }
        DayType dayType = DayType.from(date);
        List<Integer> all = new ArrayList<>();

        for (String routeId : leg.busIds().stream().limit(MAX_LANES_PER_LEG).toList()) {
            resolve(routeId, leg, dayType)
                    .ifPresent(departure -> all.addAll(toDepartureMinutes(departure)));
        }
        return all;
    }

    /** TAGO routeId를 못 구한 경우(주로 서울) 노선번호로 정적 시드에서 찾는다. */
    private List<Integer> seoulFallbackDepartureMinutes(TransitLeg leg) {
        return seoulBusRouteScheduleCatalog.find(leg.busNo())
                .map(schedule -> toDepartureMinutes(schedule.firstTime(), false,
                        schedule.lastTime(), schedule.lastTimeNextDay(), schedule.intervalMinutes()))
                .orElse(List.of());
    }

    private Optional<BusStopDeparture> resolve(String routeId, TransitLeg leg, DayType dayType) {
        Optional<BusStopDeparture> cached =
                repository.findFirstByBusIdAndStationIdAndDayTypeOrderByIdDesc(routeId, leg.stationId(), dayType);
        if (cached.isPresent()) {
            return cached;
        }
        return fetchAndCache(routeId, leg, dayType);
    }

    private Optional<BusStopDeparture> fetchAndCache(String routeId, TransitLeg leg, DayType dayType) {
        JsonNode detail;
        List<RouteStop> stops;
        try {
            detail = firstItem(tagoClient.fetchRouteDetail(routeId, leg.cityCode()));
            stops = routeStops(tagoClient.fetchRouteStops(routeId, leg.cityCode()));
        } catch (RuntimeException e) {
            log.debug("버스 노선 상세 조회 실패 routeId={} - 이 노선은 후보에서 제외", routeId, e);
            return Optional.empty();
        }
        String firstTimeRaw = detail == null ? null : text(detail, "startvehicletime");
        String lastTimeRaw = detail == null ? null : text(detail, "endvehicletime");
        if (firstTimeRaw == null || lastTimeRaw == null) {
            return Optional.empty();
        }

        int offsetMinutes = minutesFromRouteStart(stops, leg);
        TagoTimeParser.ParsedTime first = TagoTimeParser.parseHourMinute(firstTimeRaw);
        TagoTimeParser.ParsedTime last = TagoTimeParser.parseHourMinute(lastTimeRaw);

        int firstAtStop = toServiceMinutes(first.time(), first.nextDay()) + offsetMinutes;
        int lastAtStop = toServiceMinutes(last.time(), last.nextDay()) + offsetMinutes;

        BusStopDeparture entity = new BusStopDeparture(
                routeId, leg.stationId(), dayType,
                toLocalTime(firstAtStop), firstAtStop >= MINUTES_PER_DAY,
                toLocalTime(lastAtStop), lastAtStop >= MINUTES_PER_DAY,
                intervalMinutes(detail, dayType), text(detail, "routeno"));
        try {
            return Optional.of(repository.save(entity));
        } catch (DataIntegrityViolationException e) {
            // 캐시 미스 경합에서 진 경우 (동시 요청이 같은 키로 먼저 커밋). 유니크 제약
            // 위반이므로 이긴 쪽이 이미 저장한 행을 그대로 쓰면 된다 — 이 예외로 요청을
            // 실패시키면 안 된다(이게 바로 클래스 도입부에 적어둔 원래 500 버그).
            log.debug("routeId={} stationId={} dayType={} 캐시 저장 경합 - 이미 저장된 행을 재조회",
                    routeId, leg.stationId(), dayType);
            return repository.findFirstByBusIdAndStationIdAndDayTypeOrderByIdDesc(routeId, leg.stationId(), dayType);
        }
    }

    /**
     * 기점에서 승차 정류장까지 오는 데 걸리는 시간(분)을 거리 비례로 추정한다.
     * 이 구간의 평균 속도(구간거리/구간소요시간)를 노선 전체에 적용한다 — 정류장 순번으로
     * 비례 배분하는 것보다 정류장 간격 차이를 잘 반영한다. 거리/시간 정보가 없으면 0을 쓴다
     * (그러면 기점 막차 시각을 그대로 쓰게 되어, 실제보다 이르게 = 안전한 쪽으로 추정된다).
     * <p>
     * 결과는 {@link #MAX_OFFSET_MINUTES}로 제한한다. 왕복 노선은 정류장 목록에 상·하행이 모두
     * 들어있어서 복귀 구간 정류장의 누적거리가 수십 km까지 나오는데, 그대로 쓰면 "막차가 아침
     * 8시에 지나간다" 같은 값이 나온다. 상한을 두면 추정이 이른 쪽으로 치우치는데, 막차 안내에서는
     * 늦게 알려주는 것보다 이르게 알려주는 쪽이 안전하다.
     */
    private int minutesFromRouteStart(List<RouteStop> stops, TransitLeg leg) {
        Integer stopDistance = cumulativeDistanceMeters(stops, leg.stationId());
        if (stopDistance == null || leg.distanceMeters() <= 0 || leg.rideMinutes() <= 0) {
            return 0;
        }
        double metersPerMinute = (double) leg.distanceMeters() / leg.rideMinutes();
        return Math.min(MAX_OFFSET_MINUTES, (int) Math.round(stopDistance / metersPerMinute));
    }

    /** 순번(nodeord)대로 정렬한 뒤, 기점부터 이 정류장까지 좌표를 순서대로 Haversine 합산한다. */
    private Integer cumulativeDistanceMeters(List<RouteStop> stops, String stationId) {
        if (stops.isEmpty()) {
            return null;
        }
        List<RouteStop> ordered = stops.stream().sorted(Comparator.comparingInt(RouteStop::order)).toList();
        double cumulative = 0;
        for (int i = 0; i < ordered.size(); i++) {
            RouteStop stop = ordered.get(i);
            if (i > 0) {
                RouteStop prev = ordered.get(i - 1);
                cumulative += distanceMeters(prev.lat(), prev.lng(), stop.lat(), stop.lng());
            }
            if (stationId.equals(stop.nodeId())) {
                return (int) Math.round(cumulative);
            }
        }
        return null;
    }

    private List<Integer> toDepartureMinutes(BusStopDeparture departure) {
        return toDepartureMinutes(departure.getFirstTime(), departure.isFirstTimeNextDay(),
                departure.getLastTime(), departure.isLastTimeNextDay(), departure.getIntervalMinutes());
    }

    /** 막차부터 배차간격만큼 거슬러 올라가며 첫차까지의 차편들을 만든다. */
    private List<Integer> toDepartureMinutes(LocalTime firstTime, boolean firstTimeNextDay,
                                              LocalTime lastTime, boolean lastTimeNextDay, int intervalMinutes) {
        int last = toServiceMinutes(lastTime, lastTimeNextDay);
        int first = toServiceMinutes(firstTime, firstTimeNextDay);
        int interval = Math.max(1, intervalMinutes);

        List<Integer> minutes = new ArrayList<>();
        for (int t = last; t >= first && minutes.size() < MAX_DEPARTURES_PER_LANE; t -= interval) {
            minutes.add(t);
        }
        return minutes;
    }

    private int intervalMinutes(JsonNode detail, DayType dayType) {
        String field = switch (dayType) {
            case WEEKDAY -> "intervaltime";
            case SATURDAY -> "intervalsattime";
            case HOLIDAY -> "intervalsuntime";
        };
        Integer parsed = parseInterval(text(detail, field));
        return parsed == null ? DEFAULT_INTERVAL_MINUTES : parsed;
    }

    private Integer parseInterval(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int toServiceMinutes(LocalTime time, boolean nextDay) {
        return time.getHour() * 60 + time.getMinute() + (nextDay ? MINUTES_PER_DAY : 0);
    }

    private LocalTime toLocalTime(int serviceMinutes) {
        int normalized = serviceMinutes % MINUTES_PER_DAY;
        return LocalTime.of(normalized / 60, normalized % 60);
    }

    /** item은 결과가 1건이면 단일 객체로, 여러 건이면 배열로 온다 - 여기서는 항상 1건(getRouteInfoIem)이라 첫 항목만 쓴다. */
    private JsonNode firstItem(TagoBusArrivalResponse response) {
        List<JsonNode> items = items(response);
        return items.isEmpty() ? null : items.get(0);
    }

    private List<RouteStop> routeStops(TagoBusArrivalResponse response) {
        List<RouteStop> stops = new ArrayList<>();
        for (JsonNode item : items(response)) {
            Integer order = integer(item, "nodeord");
            String nodeId = text(item, "nodeid");
            Double lat = decimal(item, "gpslati");
            Double lng = decimal(item, "gpslong");
            if (order != null && nodeId != null && lat != null && lng != null) {
                stops.add(new RouteStop(order, nodeId, lat, lng));
            }
        }
        return stops;
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

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Integer.parseInt(value.asString());
        } catch (NumberFormatException e) {
            return null;
        }
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
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private record RouteStop(int order, String nodeId, double lat, double lng) {
    }
}

package com.example.transit.service;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import com.example.transit.repository.BusStopDepartureRepository;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayBusLaneDetailResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 버스 구간에서 탈 수 있는 차편들의 출발 시각을 추정한다.
 * <p>
 * <b>왜 추정인가:</b> ODsay는 지하철에는 역별 시간표(searchSubwaySchedule)를 주지만
 * 버스에는 정류장별 시간표 API가 없다. 노선 단위로 "기점 기준 첫차/막차"와 요일별 배차간격,
 * 그리고 정류장별 기점으로부터의 누적거리만 준다. 그래서 다음과 같이 추정한다:
 * <pre>
 *   평균속도   = 이 구간 거리 / 이 구간 소요시간   (경로탐색 응답에서 얻음)
 *   정류장통과 = 기점 출발시각 + 기점~승차정류장 누적거리 / 평균속도
 *   후보 차편  = 막차부터 배차간격만큼 거슬러 올라가며 첫차까지
 * </pre>
 * <b>한 구간에 여러 노선:</b> ODsay는 한 버스 구간에 탈 수 있는 노선을 여러 개 준다
 * ("120, 130, 140 중 아무거나"). 그래서 후보 차편은 모든 노선의 합집합이어야 하고,
 * 특히 막차는 그중 가장 늦은 노선 기준이 된다.
 * <p>
 * 노선 하나당 API를 한 번 호출해야 해서, 무료 호출 한도를 지키려고 구간당 조회하는 노선 수를
 * {@link #MAX_LANES_PER_LEG}개로 제한하고, (노선, 정류장, 요일유형)별로 캐싱한다
 * (SubwayScheduleCacheService와 같은 lazy cache-aside 방식).
 */
@Service
public class BusDepartureCacheService implements BusDepartureLookup {

    private static final Logger log = LoggerFactory.getLogger(BusDepartureCacheService.class);

    private static final int MINUTES_PER_DAY = 24 * 60;
    /** 구간당 조회할 최대 노선 수. ODsay가 대표 노선을 앞쪽에 주므로 앞에서부터 자른다. */
    private static final int MAX_LANES_PER_LEG = 4;
    /** 배차간격 정보가 없거나 파싱이 안 될 때 쓰는 기본값(분). */
    private static final int DEFAULT_INTERVAL_MINUTES = 15;
    /** 한 노선에서 만들어낼 최대 차편 수 (배차 1~2분짜리 노선에서 후보가 폭발하는 것 방지). */
    private static final int MAX_DEPARTURES_PER_LANE = 200;

    private final BusStopDepartureRepository repository;
    private final OdsayClient odsayClient;

    public BusDepartureCacheService(BusStopDepartureRepository repository, OdsayClient odsayClient) {
        this.repository = repository;
        this.odsayClient = odsayClient;
    }

    @Override
    @Transactional
    public List<Integer> departureServiceMinutes(TransitLeg leg) {
        DayType dayType = DayType.from(LocalDate.now());
        List<Integer> all = new ArrayList<>();

        for (Integer busId : leg.busIds().stream().limit(MAX_LANES_PER_LEG).toList()) {
            resolve(busId, leg, dayType)
                    .ifPresent(departure -> all.addAll(toDepartureMinutes(departure)));
        }
        return all;
    }

    private Optional<BusStopDeparture> resolve(int busId, TransitLeg leg, DayType dayType) {
        Optional<BusStopDeparture> cached =
                repository.findByBusIdAndStationIdAndDayType(busId, leg.stationId(), dayType);
        if (cached.isPresent()) {
            return cached;
        }
        return fetchAndCache(busId, leg, dayType);
    }

    private Optional<BusStopDeparture> fetchAndCache(int busId, TransitLeg leg, DayType dayType) {
        OdsayBusLaneDetailResponse.Result detail;
        try {
            detail = odsayClient.fetchBusLaneDetail(busId).result();
        } catch (RuntimeException e) {
            log.debug("버스 노선 상세 조회 실패 busId={} - 이 노선은 후보에서 제외", busId, e);
            return Optional.empty();
        }
        if (detail == null || detail.busFirstTime() == null || detail.busLastTime() == null) {
            return Optional.empty();
        }

        int offsetMinutes = minutesFromRouteStart(detail, leg);
        OdsayTimeParser.ParsedTime first = OdsayTimeParser.parse(detail.busFirstTime());
        OdsayTimeParser.ParsedTime last = OdsayTimeParser.parse(detail.busLastTime());

        int firstAtStop = toServiceMinutes(first.time(), first.nextDay()) + offsetMinutes;
        int lastAtStop = toServiceMinutes(last.time(), last.nextDay()) + offsetMinutes;

        BusStopDeparture entity = new BusStopDeparture(
                busId, leg.stationId(), dayType,
                toLocalTime(firstAtStop), toLocalTime(lastAtStop), lastAtStop >= MINUTES_PER_DAY,
                intervalMinutes(detail, dayType), detail.busNo());
        return Optional.of(repository.save(entity));
    }

    /**
     * 기점에서 승차 정류장까지 오는 데 걸리는 시간(분)을 거리 비례로 추정한다.
     * 이 구간의 평균 속도(구간거리/구간소요시간)를 노선 전체에 적용한다 — 정류장 순번으로
     * 비례 배분하는 것보다 정류장 간격 차이를 잘 반영한다. 거리/시간 정보가 없으면 0을 쓴다
     * (그러면 기점 막차 시각을 그대로 쓰게 되어, 실제보다 이르게 = 안전한 쪽으로 추정된다).
     */
    private int minutesFromRouteStart(OdsayBusLaneDetailResponse.Result detail, TransitLeg leg) {
        Integer stopDistance = distanceFromRouteStart(detail, leg.stationId());
        if (stopDistance == null || leg.distanceMeters() <= 0 || leg.rideMinutes() <= 0) {
            return 0;
        }
        double metersPerMinute = (double) leg.distanceMeters() / leg.rideMinutes();
        return (int) Math.round(stopDistance / metersPerMinute);
    }

    private Integer distanceFromRouteStart(OdsayBusLaneDetailResponse.Result detail, int stationId) {
        if (detail.station() == null) {
            return null;
        }
        return detail.station().stream()
                .filter(s -> s.stationID() != null && s.stationID() == stationId)
                .map(OdsayBusLaneDetailResponse.Station::stationDistance)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 막차부터 배차간격만큼 거슬러 올라가며 첫차까지의 차편들을 만든다. */
    private List<Integer> toDepartureMinutes(BusStopDeparture departure) {
        int last = toServiceMinutes(departure.getLastTime(), departure.isLastTimeNextDay());
        int first = toServiceMinutes(departure.getFirstTime(), false);
        int interval = Math.max(1, departure.getIntervalMinutes());

        List<Integer> minutes = new ArrayList<>();
        for (int t = last; t >= first && minutes.size() < MAX_DEPARTURES_PER_LANE; t -= interval) {
            minutes.add(t);
        }
        return minutes;
    }

    private int intervalMinutes(OdsayBusLaneDetailResponse.Result detail, DayType dayType) {
        String raw = switch (dayType) {
            case WEEKDAY -> detail.bus_Interval_Week();
            case SATURDAY -> detail.bus_Interval_Sat();
            case HOLIDAY -> detail.bus_Interval_Sun();
        };
        Integer parsed = parseInterval(raw);
        if (parsed == null) {
            parsed = parseInterval(detail.busInterval());
        }
        return parsed == null ? DEFAULT_INTERVAL_MINUTES : parsed;
    }

    /** "6" 같은 숫자만 받는다. "1회 -"처럼 숫자가 아닌 값도 오므로 실패하면 null. */
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
}

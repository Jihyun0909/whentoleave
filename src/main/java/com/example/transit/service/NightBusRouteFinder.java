package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayBusLaneDetailResponse;
import com.example.transit.service.client.dto.OdsayBusLaneSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 심야버스(N버스) 경로를 직접 찾는다.
 * <p>
 * <b>왜 직접 찾나:</b> ODsay 경로탐색(searchPubTransPathT)은 심야버스를 결과에 포함하지 않는다
 * (실측 확인: N15는 busID 558로 노선 데이터는 있는데 경로 결과엔 한 번도 안 나온다).
 * 그런데 막차 앱에서 "지하철도 일반버스도 끊긴 시간에 뭘 탈 수 있나"는 가장 중요한 정보라,
 * 노선 목록을 따로 받아 직접 매칭한다:
 * <pre>
 *   1. searchBusLane(busNo="N")로 서울 심야버스 목록을 받는다 (18개 남짓)
 *   2. 각 노선의 정류장 목록에서 출발지 근처 정류장(승차)과 도착지 근처 정류장(하차)을 찾는다
 *   3. 승차가 하차보다 앞 순번이면 그 노선으로 갈 수 있는 것으로 본다
 * </pre>
 * 정류장 목록은 노선당 API 한 번이라, 하루 단위로 메모리에 캐싱한다 (노선 수가 적고
 * 하루에 한 번만 채우면 되어서 DB까지 쓸 필요는 없다).
 * <p>
 * 소요시간은 추정치다 — 노선 전체 소요시간을 주는 API가 없어서, 심야 도로 사정을 가정한
 * 평균 속도({@link #NIGHT_BUS_METERS_PER_MINUTE})로 거리에서 환산한다.
 */
@Service
public class NightBusRouteFinder {

    private static final Logger log = LoggerFactory.getLogger(NightBusRouteFinder.class);

    private static final String NIGHT_BUS_PREFIX = "N";
    private static final int SEOUL_CITY_CODE = 1000;
    /** 정류장까지 걸어갈 수 있다고 볼 최대 거리(m). 약 10분 거리. */
    private static final int MAX_WALK_METERS = 700;
    /** 도보 속도(m/분). ODsay 도보 구간과 비슷한 수준으로 맞춘다. */
    private static final double WALK_METERS_PER_MINUTE = 67.0;
    /** 심야 시간대 평균 버스 속도(m/분). 낮 버스 실측(약 200m/분)보다 도로가 비어 조금 빠르다고 본다. */
    private static final double NIGHT_BUS_METERS_PER_MINUTE = 250.0;
    /** 노선 목록/정류장 캐시 유효기간. 시간표는 하루 단위로만 바뀌면 충분하다. */
    private static final Duration CACHE_TTL = Duration.ofHours(12);
    /** 이보다 오래 타야 하면 노선을 거의 한 바퀴 도는 비현실적인 조합이라 보고 후보에서 뺀다. */
    private static final int MAX_RIDE_MINUTES = 90;

    private final OdsayClient odsayClient;
    private final Map<Integer, CachedLane> laneCache = new ConcurrentHashMap<>();
    private volatile List<OdsayBusLaneSearchResponse.Lane> catalog;
    private volatile Instant catalogLoadedAt;

    public NightBusRouteFinder(OdsayClient odsayClient) {
        this.odsayClient = odsayClient;
    }

    /** 출발지/도착지 좌표로 갈 수 있는 심야버스 경로들을 찾는다. 없으면 빈 목록. */
    public List<RouteLegExtractor.ExtractedRoute> find(double sx, double sy, double ex, double ey) {
        List<RouteLegExtractor.ExtractedRoute> routes = new ArrayList<>();

        List<OdsayBusLaneSearchResponse.Lane> lanes = catalog();
        log.debug("심야버스 후보 노선 {}개", lanes.size());
        for (OdsayBusLaneSearchResponse.Lane lane : lanes) {
            if (lane.busID() == null) {
                continue;
            }
            laneDetail(lane.busID())
                    .flatMap(detail -> toRoute(lane, detail, sx, sy, ex, ey))
                    .ifPresent(routes::add);
        }
        log.debug("심야버스 매칭 결과 {}건", routes.size());
        return routes;
    }

    private Optional<RouteLegExtractor.ExtractedRoute> toRoute(
            OdsayBusLaneSearchResponse.Lane lane, OdsayBusLaneDetailResponse.Result detail,
            double sx, double sy, double ex, double ey) {

        if (detail.station() == null) {
            return Optional.empty();
        }
        Optional<StopPair> pair = bestStopPair(detail.station(), sx, sy, ex, ey);
        log.debug("{} 매칭: {}", lane.busNo(), pair.orElse(null));
        if (pair.isEmpty()) {
            return Optional.empty();
        }

        Nearest boarding = pair.get().boarding();
        Nearest alighting = pair.get().alighting();
        int rideMeters = alighting.cumulativeDistance() - boarding.cumulativeDistance();
        int rideMinutes = (int) Math.max(1, Math.round(rideMeters / NIGHT_BUS_METERS_PER_MINUTE));

        TransitLeg leg = TransitLeg.bus(boarding.stationId(), rideMinutes, walkMinutes(boarding.distanceMeters()),
                boarding.stationName(), alighting.stationName(), "심야버스",
                List.of(lane.busID()), lane.busNo(), rideMeters);
        return Optional.of(new RouteLegExtractor.ExtractedRoute(
                List.of(leg), walkMinutes(alighting.distanceMeters())));
    }

    /**
     * 승차/하차 정류장을 <b>짝으로</b> 고른다.
     * <p>
     * 각각 "가장 가까운 정류장"을 따로 고르면 안 된다 — 심야버스는 대부분 왕복 노선이라
     * 같은 정류장이 상·하행으로 두 번 들어있고(정류장 ID도 다르다), 하필 반대 방향 정류장을
     * 집으면 "승차가 하차보다 뒤"라서 갈 수 있는 노선인데도 버려진다 (실제로 N15 수유->사당이
     * 이 이유로 누락됐다). 그래서 순서가 맞는(승차 idx < 하차 idx) 조합을 다 보고 고른다.
     * <p>
     * 고르는 기준은 도보 거리가 아니라 <b>총 소요시간(도보+승차)</b>이다. 도보만 보면 노선을
     * 거의 한 바퀴 도는 조합이 뽑힐 수 있다 — 실제로 건대입구->신림에서 승차 193분짜리
     * 말도 안 되는 경로가 나왔다. 그렇게 고르고도 비현실적으로 오래 걸리면
     * ({@link #MAX_RIDE_MINUTES} 초과) 그 노선은 아예 후보에서 뺀다.
     */
    private Optional<StopPair> bestStopPair(List<OdsayBusLaneDetailResponse.Station> stations,
                                             double sx, double sy, double ex, double ey) {
        List<Nearest> boardingCandidates = stopsWithinWalk(stations, sx, sy);
        List<Nearest> alightingCandidates = stopsWithinWalk(stations, ex, ey);

        StopPair best = null;
        for (Nearest boarding : boardingCandidates) {
            for (Nearest alighting : alightingCandidates) {
                if (boarding.index() >= alighting.index()
                        || alighting.cumulativeDistance() <= boarding.cumulativeDistance()) {
                    continue;
                }
                int rideMeters = alighting.cumulativeDistance() - boarding.cumulativeDistance();
                double totalMinutes = walkMinutes(boarding.distanceMeters())
                        + walkMinutes(alighting.distanceMeters())
                        + rideMeters / NIGHT_BUS_METERS_PER_MINUTE;
                if (best == null || totalMinutes < best.totalMinutes()) {
                    best = new StopPair(boarding, alighting, totalMinutes);
                }
            }
        }
        if (best != null && rideMinutesOf(best) > MAX_RIDE_MINUTES) {
            return Optional.empty();
        }
        return Optional.ofNullable(best);
    }

    private int rideMinutesOf(StopPair pair) {
        int rideMeters = pair.alighting().cumulativeDistance() - pair.boarding().cumulativeDistance();
        return (int) Math.max(1, Math.round(rideMeters / NIGHT_BUS_METERS_PER_MINUTE));
    }

    /** 좌표에서 걸어갈 수 있는 거리 안에 있는 정류장 전부. */
    private List<Nearest> stopsWithinWalk(List<OdsayBusLaneDetailResponse.Station> stations,
                                           double x, double y) {
        List<Nearest> found = new ArrayList<>();
        for (OdsayBusLaneDetailResponse.Station station : stations) {
            if (station.x() == null || station.y() == null || station.stationID() == null
                    || station.stationDistance() == null || station.idx() == null) {
                continue;
            }
            double meters = distanceMeters(y, x, station.y(), station.x());
            if (meters <= MAX_WALK_METERS) {
                found.add(new Nearest(station.stationID(), station.stationName(), station.idx(),
                        station.stationDistance(), meters));
            }
        }
        return found;
    }

    private int walkMinutes(double meters) {
        return (int) Math.max(1, Math.round(meters / WALK_METERS_PER_MINUTE));
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final int earthRadiusMeters = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<OdsayBusLaneSearchResponse.Lane> catalog() {
        if (catalog != null && catalogLoadedAt != null
                && Duration.between(catalogLoadedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return catalog;
        }
        try {
            OdsayBusLaneSearchResponse response = odsayClient.searchBusLanes(NIGHT_BUS_PREFIX, SEOUL_CITY_CODE);
            List<OdsayBusLaneSearchResponse.Lane> lanes =
                    response.result() == null || response.result().lane() == null
                            ? List.of() : response.result().lane();
            // busNo=N은 부분검색이라 "N"으로 시작하지 않는 노선이 섞일 수 있어 한 번 더 거른다.
            catalog = lanes.stream()
                    .filter(l -> l.busNo() != null && l.busNo().startsWith(NIGHT_BUS_PREFIX))
                    .toList();
            catalogLoadedAt = Instant.now();
        } catch (RuntimeException e) {
            log.debug("심야버스 목록 조회 실패 - 이번 요청에서는 심야버스를 제외", e);
            catalog = catalog == null ? List.of() : catalog;
        }
        return catalog;
    }

    private Optional<OdsayBusLaneDetailResponse.Result> laneDetail(int busId) {
        CachedLane cached = laneCache.get(busId);
        if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).compareTo(CACHE_TTL) < 0) {
            return Optional.ofNullable(cached.detail());
        }
        try {
            OdsayBusLaneDetailResponse.Result detail = odsayClient.fetchBusLaneDetail(busId).result();
            laneCache.put(busId, new CachedLane(detail, Instant.now()));
            return Optional.ofNullable(detail);
        } catch (RuntimeException e) {
            log.debug("심야버스 노선 상세 조회 실패 busId={}", busId, e);
            return Optional.empty();
        }
    }

    private record Nearest(int stationId, String stationName, int index, int cumulativeDistance,
                            double distanceMeters) {
    }

    private record StopPair(Nearest boarding, Nearest alighting, double totalMinutes) {
    }

    private record CachedLane(OdsayBusLaneDetailResponse.Result detail, Instant loadedAt) {
    }
}

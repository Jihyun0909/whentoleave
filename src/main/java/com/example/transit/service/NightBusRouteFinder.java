package com.example.transit.service;

import com.example.transit.domain.NightBusStop;
import com.example.transit.repository.NightBusStopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 서울 심야버스(N버스) 경로를 직접 찾는다.
 * <p>
 * <b>왜 직접 찾나:</b> 경로탐색(Google Routes 포함, ODsay 시절부터 동일)은 심야버스를 결과에
 * 포함하지 않는다. 그런데 막차 앱에서 "지하철도 일반버스도 끊긴 시간에 뭘 탈 수 있나"는 가장
 * 중요한 정보라, 노선의 정류장 목록을 직접 들고 매칭한다.
 * <p>
 * <b>정류장 데이터 출처가 ODsay 시절과 다르다:</b> ODsay는 이 노선·정류장 목록을 실시간
 * API로 받아왔지만, TAGO는 서울 시내버스를 아예 커버하지 않는다(2026-08 라이브 확인: 도시코드
 * 목록에 서울 없음). 그래서 정류장 순서·좌표를 {@link NightBusStop}에 정적으로 시드해두고
 * ({@code night-bus-stops.csv}) 여기서 읽어온다 - 노선 수가 적고(~14개) 자주 안 바뀌어서
 * 가능한 절충이다. <b>시드 데이터가 비어 있으면 이 클래스는 조용히 빈 목록을 반환한다</b>
 * (기능이 아예 없는 것처럼 동작) - 잘못된 좌표를 대충 채우는 것보다 안전하다.
 * <p>
 * <b>배차/막차 시각은 이번 마이그레이션에서 아직 못 채운다:</b> TAGO가 서울 버스를 커버하지
 * 않으므로 {@code BusDepartureCacheService}(TAGO 노선상세 기반)로 심야버스의 첫차/막차/배차간격을
 * 구할 방법이 없다. 그래서 여기서 만드는 {@link TransitLeg}는 {@code busIds}가 항상 빈 리스트고,
 * {@code LastDepartureCalculator}는 이 구간에 대해 "운행 정보를 찾을 수 없습니다"로 응답한다 -
 * 즉 "이 노선을 타면 갈 수는 있다"는 알아도 "몇 시에 막차인지"는 아직 모른다. 서울시 실시간
 * 버스 도착 API(seoul-bus.*)는 "지금 오는 버스"만 알려줄 뿐 시간표를 안 줘서 이 문제를
 * 못 풀어준다 - 별도로 서울 심야버스 시간표 소스를 찾아야 하는 후속 과제로 남는다.
 * <p>
 * 소요시간은 추정치다 — 노선 전체 소요시간을 주는 API가 없어서, 심야 도로 사정을 가정한
 * 평균 속도({@link #NIGHT_BUS_METERS_PER_MINUTE})로 거리에서 환산한다.
 */
@Service
public class NightBusRouteFinder {

    private static final Logger log = LoggerFactory.getLogger(NightBusRouteFinder.class);

    /** 정류장까지 걸어갈 수 있다고 볼 최대 거리(m). 약 10분 거리. */
    private static final int MAX_WALK_METERS = 700;
    /** 도보 속도(m/분). */
    private static final double WALK_METERS_PER_MINUTE = 67.0;
    /** 심야 시간대 평균 버스 속도(m/분). 낮 버스 실측(약 200m/분)보다 도로가 비어 조금 빠르다고 본다. */
    private static final double NIGHT_BUS_METERS_PER_MINUTE = 250.0;
    /** 이보다 오래 타야 하면 노선을 거의 한 바퀴 도는 비현실적인 조합이라 보고 후보에서 뺀다. */
    private static final int MAX_RIDE_MINUTES = 90;
    private static final int EARTH_RADIUS_METERS = 6_371_000;

    private final NightBusStopRepository repository;

    public NightBusRouteFinder(NightBusStopRepository repository) {
        this.repository = repository;
    }

    /** 출발지/도착지 좌표로 갈 수 있는 심야버스 경로들을 찾는다. 없으면 빈 목록. */
    public List<RouteLegExtractor.ExtractedRoute> find(double sx, double sy, double ex, double ey) {
        List<RouteLegExtractor.ExtractedRoute> routes = new ArrayList<>();

        List<String> busNos = repository.findDistinctBusNos();
        log.debug("심야버스 후보 노선 {}개(시드 데이터)", busNos.size());
        for (String busNo : busNos) {
            List<NightBusStop> stops = repository.findByBusNoOrderBySequenceAsc(busNo);
            toRoute(busNo, stops, sx, sy, ex, ey).ifPresent(routes::add);
        }
        log.debug("심야버스 매칭 결과 {}건", routes.size());
        return routes;
    }

    private Optional<RouteLegExtractor.ExtractedRoute> toRoute(
            String busNo, List<NightBusStop> stops, double sx, double sy, double ex, double ey) {

        Optional<StopPair> pair = bestStopPair(stops, sx, sy, ex, ey);
        log.debug("{} 매칭: {}", busNo, pair.orElse(null));
        if (pair.isEmpty()) {
            return Optional.empty();
        }

        Nearest boarding = pair.get().boarding();
        Nearest alighting = pair.get().alighting();
        int rideMeters = alighting.cumulativeDistance() - boarding.cumulativeDistance();
        int rideMinutes = (int) Math.max(1, Math.round(rideMeters / NIGHT_BUS_METERS_PER_MINUTE));

        // busIds는 비워둔다 - TAGO가 서울 버스를 안 커버해서 이 노선의 TAGO routeId를 모른다
        // (클래스 상단 주석 참고). 그래도 "이 노선을 타면 갈 수 있다"는 정보 자체는 유효하다.
        // Google Routes를 아예 안 거치는 경로라(직접 구성) googleDepartureTime도 null.
        TransitLeg leg = TransitLeg.bus(boarding.stationId(), rideMinutes, walkMinutes(boarding.distanceMeters()),
                boarding.stationName(), alighting.stationName(), "심야버스",
                List.of(), busNo, rideMeters, boarding.x(), boarding.y(), null, null);
        return Optional.of(new RouteLegExtractor.ExtractedRoute(
                List.of(leg), walkMinutes(alighting.distanceMeters())));
    }

    /**
     * 승차/하차 정류장을 <b>짝으로</b> 고른다.
     * <p>
     * 각각 "가장 가까운 정류장"을 따로 고르면 안 된다 — 심야버스는 대부분 왕복 노선이라
     * 같은 정류장이 상·하행으로 두 번 들어있고(정류장 ID도 다르다), 하필 반대 방향 정류장을
     * 집으면 "승차가 하차보다 뒤"라서 갈 수 있는 노선인데도 버려진다. 그래서 순서가 맞는
     * (승차 순번 < 하차 순번) 조합을 다 보고 고른다.
     * <p>
     * 고르는 기준은 도보 거리가 아니라 <b>총 소요시간(도보+승차)</b>이다. 도보만 보면 노선을
     * 거의 한 바퀴 도는 조합이 뽑힐 수 있다. 그렇게 고르고도 비현실적으로 오래 걸리면
     * ({@link #MAX_RIDE_MINUTES} 초과) 그 노선은 아예 후보에서 뺀다.
     */
    private Optional<StopPair> bestStopPair(List<NightBusStop> stops, double sx, double sy, double ex, double ey) {
        List<Nearest> boardingCandidates = stopsWithinWalk(stops, sx, sy);
        List<Nearest> alightingCandidates = stopsWithinWalk(stops, ex, ey);

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

    /** 순번대로 정렬돼 있다는 전제로, 기점부터의 누적거리를 계산하면서 좌표에서 걸어갈 수 있는 정류장을 고른다. */
    private List<Nearest> stopsWithinWalk(List<NightBusStop> stops, double x, double y) {
        List<Nearest> found = new ArrayList<>();
        double cumulative = 0;
        for (int i = 0; i < stops.size(); i++) {
            NightBusStop stop = stops.get(i);
            if (i > 0) {
                NightBusStop prev = stops.get(i - 1);
                cumulative += distanceMeters(prev.getY(), prev.getX(), stop.getY(), stop.getX());
            }
            double meters = distanceMeters(y, x, stop.getY(), stop.getX());
            if (meters <= MAX_WALK_METERS) {
                found.add(new Nearest(stop.getStationId(), stop.getStationName(), i,
                        (int) Math.round(cumulative), meters, stop.getX(), stop.getY()));
            }
        }
        return found;
    }

    private int walkMinutes(double meters) {
        return (int) Math.max(1, Math.round(meters / WALK_METERS_PER_MINUTE));
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record Nearest(String stationId, String stationName, int index, int cumulativeDistance,
                            double distanceMeters, Double x, Double y) {
    }

    private record StopPair(Nearest boarding, Nearest alighting, double totalMinutes) {
    }
}

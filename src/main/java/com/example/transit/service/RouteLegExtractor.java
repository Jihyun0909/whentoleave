package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ODsay 경로탐색 응답에서 대중교통 구간(TransitLeg) 목록을 추출한다.
 * 구간 사이의 도보/환승 시간은 다음 구간의 transferBufferMinutes로 누적한다.
 */
@Component
public class RouteLegExtractor {

    private static final int TRAFFIC_TYPE_SUBWAY = 1;
    private static final int TRAFFIC_TYPE_BUS = 2;
    private static final int TRAFFIC_TYPE_WALK = 3;

    /** 가장 첫 번째 추천 경로만 뽑는다 (하위 호환용). 여러 경로를 다 시도하려면 {@link #extractAll}을 쓴다. */
    public List<TransitLeg> extract(OdsayPathResponse response) {
        return extractAll(response, false).get(0).legs();
    }

    /** 지하철 전용으로 추출한다 (버스가 섞인 경로 후보는 건너뜀). */
    public List<ExtractedRoute> extractAll(OdsayPathResponse response) {
        return extractAll(response, false);
    }

    /**
     * ODsay가 추천한 경로 후보들 전부에서 대중교통 구간 목록을 뽑는다.
     * 막차 계산은 ODsay가 "가장 빠른" 기준으로 고른 1순위 경로 하나만 보면 부정확할 수 있다 —
     * 그 경로 중간에 배차가 뜸한 구간이 끼어 있으면 막차가 훨씬 이른 시각에 끊겨버리는데,
     * 실제로는 다른 경로로 더 늦게까지 갈 수 있는 경우가 있다 (이슈 #8). 그래서 호출하는 쪽
     * (LastDepartureService)에서 후보 경로마다 계산해보고 가장 늦게 출발해도 되는 걸 고른다.
     *
     * @param allowBus false면 버스가 섞인 경로 후보를 조용히 걸러낸다. 하나도 안 남으면 예외를 던진다.
     */
    public List<ExtractedRoute> extractAll(OdsayPathResponse response, boolean allowBus) {
        List<OdsayPathResponse.Path> paths = allPaths(response);

        List<ExtractedRoute> candidates = new ArrayList<>();
        Integer walkOnlyMinutes = null;
        for (OdsayPathResponse.Path path : paths) {
            try {
                candidates.add(extractRoute(path, allowBus));
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

    private ExtractedRoute extractRoute(OdsayPathResponse.Path path, boolean allowBus) {
        if (path.subPath() == null || path.subPath().isEmpty()) {
            throw new NoSubwayRouteFoundException("경로에 구간 정보가 없습니다.");
        }

        List<TransitLeg> legs = new ArrayList<>();
        int pendingWalkMinutes = 0;

        for (OdsayPathResponse.SubPath subPath : path.subPath()) {
            int trafficType = subPath.trafficType() == null ? -1 : subPath.trafficType();
            int sectionTime = subPath.sectionTime() == null ? 0 : subPath.sectionTime();

            if (trafficType == TRAFFIC_TYPE_WALK) {
                pendingWalkMinutes += sectionTime;
            } else if (trafficType == TRAFFIC_TYPE_SUBWAY) {
                legs.add(subwayLeg(subPath, sectionTime, pendingWalkMinutes));
                pendingWalkMinutes = 0;
            } else if (trafficType == TRAFFIC_TYPE_BUS && allowBus) {
                legs.add(busLeg(subPath, sectionTime, pendingWalkMinutes));
                pendingWalkMinutes = 0;
            } else {
                throw new NoSubwayRouteFoundException(
                        "이용할 수 없는 교통수단이 포함된 경로입니다 (trafficType=" + trafficType + ").");
            }
        }

        if (legs.isEmpty()) {
            // 전 구간이 도보(trafficType=3)뿐이었다는 뜻 - 출발지/도착지가 대중교통을 탈 필요
            // 없이 걸어갈 수 있을 만큼 가까운 경우다. 이걸 그냥 "대중교통 구간 없음"으로 던지면
            // 호출하는 쪽에서 "경로 없음"과 구분을 못 해 "운행 종료"라는 엉뚱한 안내가 나간다.
            if (pendingWalkMinutes > 0) {
                throw new WalkOnlyRouteException(pendingWalkMinutes);
            }
            throw new NoSubwayRouteFoundException("경로에 대중교통 구간이 없습니다.");
        }
        // 마지막 구간 뒤에 남은 도보(= 마지막 하차 지점에서 실제 목적지까지 걷는 시간)는
        // 다음 구간이 없어 버퍼로 붙일 곳이 없으므로 별도로 들고 나간다.
        int fareWon = path.info() == null || path.info().payment() == null ? 0 : path.info().payment();
        return new ExtractedRoute(legs, pendingWalkMinutes, fareWon);
    }

    private TransitLeg subwayLeg(OdsayPathResponse.SubPath subPath, int sectionTime, int pendingWalkMinutes) {
        if (subPath.startID() == null || subPath.wayCode() == null) {
            throw new NoSubwayRouteFoundException("지하철 구간에 역 정보가 없습니다.");
        }
        return TransitLeg.subway(subPath.startID(), subPath.wayCode(), sectionTime,
                pendingWalkMinutes, earlierStopNames(subPath), subPath.startName(), subPath.endName(),
                laneName(subPath));
    }

    /**
     * 버스는 지하철과 달리 "정류장별 시간표" API가 없어서, 노선의 막차/배차간격으로 승차 정류장의
     * 출발 시각을 추정해야 한다 (BusDepartureEstimator). 그 추정에 필요한 busID와
     * 구간 정류장 수를 여기서 같이 담아둔다.
     */
    private TransitLeg busLeg(OdsayPathResponse.SubPath subPath, int sectionTime, int pendingWalkMinutes) {
        OdsayPathResponse.Lane lane = firstLane(subPath);
        if (subPath.startID() == null || lane == null) {
            throw new NoSubwayRouteFoundException("버스 구간에 노선 정보가 없습니다.");
        }
        List<Integer> busIds = subPath.lane().stream()
                .map(OdsayPathResponse.Lane::busID)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (busIds.isEmpty()) {
            throw new NoSubwayRouteFoundException("버스 구간에 노선 ID가 없습니다.");
        }
        int distance = subPath.distance() == null ? 0 : subPath.distance();
        return TransitLeg.bus(subPath.startID(), sectionTime, pendingWalkMinutes,
                subPath.startName(), subPath.endName(), lane.busNo(), busIds, lane.busNo(), distance,
                subPath.startX(), subPath.startY());
    }

    /**
     * @param legs             경로의 대중교통 구간들(정순)
     * @param finalWalkMinutes 마지막 하차 지점에서 실제 목적지까지 걸어야 하는 시간(분)
     * @param fareWon          이 경로의 총 요금(원). 모르면 0 (심야버스처럼 직접 만든 경로).
     */
    public record ExtractedRoute(List<TransitLeg> legs, int finalWalkMinutes, int fareWon) {

        public ExtractedRoute(List<TransitLeg> legs, int finalWalkMinutes) {
            this(legs, finalWalkMinutes, 0);
        }

        public boolean hasBus() {
            return legs.stream().anyMatch(TransitLeg::isBus);
        }
    }

    /**
     * passStopList에서 "도착역보다 앞선" 정차역 이름만 뽑는다 (마지막 항목=도착역 자체는 제외).
     * 정보가 없으면 빈 Set을 반환한다 — 이 경우 막차 후보 필터링에서는 아무것도 제외하지 않는다
     * (정보가 없다고 후보를 다 막아버리면 더 나쁘다).
     */
    private Set<String> earlierStopNames(OdsayPathResponse.SubPath subPath) {
        if (subPath.passStopList() == null || subPath.passStopList().stations() == null) {
            return Set.of();
        }
        List<OdsayPathResponse.Station> stations = subPath.passStopList().stations();
        if (stations.size() <= 1) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < stations.size() - 1; i++) {
            String name = stations.get(i).stationName();
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** lane 배열의 첫 번째 노선 이름을 쓴다 (여러 노선이 겹치는 구간이면 그중 대표 하나). */
    private String laneName(OdsayPathResponse.SubPath subPath) {
        OdsayPathResponse.Lane lane = firstLane(subPath);
        return lane == null ? null : lane.name();
    }

    private OdsayPathResponse.Lane firstLane(OdsayPathResponse.SubPath subPath) {
        if (subPath.lane() == null || subPath.lane().isEmpty()) {
            return null;
        }
        return subPath.lane().get(0);
    }

    private List<OdsayPathResponse.Path> allPaths(OdsayPathResponse response) {
        if (response == null || response.result() == null
                || response.result().path() == null || response.result().path().isEmpty()) {
            throw new NoSubwayRouteFoundException("이동 가능한 대중교통 경로를 찾지 못했습니다.");
        }
        return response.result().path();
    }
}

package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ODsay 경로탐색 응답(SearchPathType=1로 요청한 지하철 전용 경로)에서
 * 지하철 구간(SubwayLeg) 목록을 추출한다.
 * 구간 사이의 도보/환승 시간은 다음 지하철 구간의 transferBufferMinutes로 누적한다.
 */
@Component
public class RouteLegExtractor {

    private static final int TRAFFIC_TYPE_SUBWAY = 1;
    private static final int TRAFFIC_TYPE_WALK = 3;

    /** 가장 첫 번째 추천 경로만 뽑는다 (하위 호환용). 여러 경로를 다 시도하려면 {@link #extractAll}을 쓴다. */
    public List<SubwayLeg> extract(OdsayPathResponse response) {
        return extractAll(response).get(0);
    }

    /**
     * ODsay가 추천한 경로 후보들(pathType=1) 전부에서 지하철 구간 목록을 뽑는다.
     * 막차 계산은 ODsay가 "가장 빠른" 기준으로 고른 1순위 경로 하나만 보면 부정확할 수 있다 —
     * 그 경로 중간에 배차가 뜸한 구간이 끼어 있으면 막차가 훨씬 이른 시각에 끊겨버리는데,
     * 실제로는 다른 경로로 더 늦게까지 갈 수 있는 경우가 있다 (이슈 #8). 그래서 호출하는 쪽
     * (LastDepartureService)에서 후보 경로마다 계산해보고 가장 늦게 출발해도 되는 걸 고른다.
     * <p>
     * 버스가 섞이는 등 지하철 전용이 아닌 경로 후보는 조용히 걸러내고, 하나도 안 남으면 예외를 던진다.
     */
    public List<List<SubwayLeg>> extractAll(OdsayPathResponse response) {
        List<OdsayPathResponse.Path> paths = allPaths(response);

        List<List<SubwayLeg>> candidates = new ArrayList<>();
        for (OdsayPathResponse.Path path : paths) {
            try {
                candidates.add(extractLegs(path));
            } catch (NoSubwayRouteFoundException ignored) {
                // 이 경로 후보는 지하철 전용이 아니거나 정보가 부족함 - 다른 경로 후보로 계속 시도
            }
        }
        if (candidates.isEmpty()) {
            throw new NoSubwayRouteFoundException("지하철로만 이동 가능한 경로를 찾지 못했습니다.");
        }
        return candidates;
    }

    private List<SubwayLeg> extractLegs(OdsayPathResponse.Path path) {
        if (path.subPath() == null || path.subPath().isEmpty()) {
            throw new NoSubwayRouteFoundException("경로에 구간 정보가 없습니다.");
        }

        List<SubwayLeg> legs = new ArrayList<>();
        int pendingWalkMinutes = 0;

        for (OdsayPathResponse.SubPath subPath : path.subPath()) {
            int trafficType = subPath.trafficType() == null ? -1 : subPath.trafficType();
            int sectionTime = subPath.sectionTime() == null ? 0 : subPath.sectionTime();

            if (trafficType == TRAFFIC_TYPE_WALK) {
                pendingWalkMinutes += sectionTime;
            } else if (trafficType == TRAFFIC_TYPE_SUBWAY) {
                if (subPath.startID() == null || subPath.wayCode() == null) {
                    throw new NoSubwayRouteFoundException("지하철 구간에 역 정보가 없습니다.");
                }
                Set<String> earlierStopNames = earlierStopNames(subPath);
                legs.add(new SubwayLeg(subPath.startID(), subPath.wayCode(), sectionTime,
                        pendingWalkMinutes, earlierStopNames, subPath.startName(), laneName(subPath)));
                pendingWalkMinutes = 0;
            } else {
                throw new NoSubwayRouteFoundException(
                        "지하철 전용 경로가 아닙니다 (trafficType=" + trafficType + " 포함).");
            }
        }

        if (legs.isEmpty()) {
            throw new NoSubwayRouteFoundException("경로에 지하철 구간이 없습니다.");
        }
        return legs;
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
        if (subPath.lane() == null || subPath.lane().isEmpty()) {
            return null;
        }
        return subPath.lane().get(0).name();
    }

    private List<OdsayPathResponse.Path> allPaths(OdsayPathResponse response) {
        if (response == null || response.result() == null
                || response.result().path() == null || response.result().path().isEmpty()) {
            throw new NoSubwayRouteFoundException("지하철로 이동 가능한 경로를 찾지 못했습니다.");
        }
        return response.result().path();
    }
}

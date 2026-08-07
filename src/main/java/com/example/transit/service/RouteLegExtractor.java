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

    public List<SubwayLeg> extract(OdsayPathResponse response) {
        List<OdsayPathResponse.SubPath> subPaths = firstPathSubPaths(response);

        List<SubwayLeg> legs = new ArrayList<>();
        int pendingWalkMinutes = 0;

        for (OdsayPathResponse.SubPath subPath : subPaths) {
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
                        pendingWalkMinutes, earlierStopNames));
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

    private List<OdsayPathResponse.SubPath> firstPathSubPaths(OdsayPathResponse response) {
        if (response == null || response.result() == null
                || response.result().path() == null || response.result().path().isEmpty()) {
            throw new NoSubwayRouteFoundException("지하철로 이동 가능한 경로를 찾지 못했습니다.");
        }
        OdsayPathResponse.Path path = response.result().path().get(0);
        if (path.subPath() == null || path.subPath().isEmpty()) {
            throw new NoSubwayRouteFoundException("경로에 구간 정보가 없습니다.");
        }
        return path.subPath();
    }
}

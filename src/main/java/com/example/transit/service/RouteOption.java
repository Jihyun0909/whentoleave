package com.example.transit.service;

import java.time.LocalTime;
import java.util.List;

/**
 * 화면에 나란히 보여줄 경로 후보 하나 (지하철 / 지하철+버스 / 버스 각각의 최선 결과).
 *
 * @param modeLabel            "지하철", "지하철+버스", "버스"
 * @param departureTime        출발지에서 나서야 하는 시각 (최단 막차 - 환승 여유 없이 정확히 맞춰 타는 기준)
 * @param nextDay              departureTime이 "다음날 이 시각"인지
 * @param legs                 이 경로의 대중교통 구간들(정순, 표시용)
 * @param finalWalkMinutes     마지막 하차 지점에서 목적지까지 도보 시간(분)
 * @param totalMinutes         문 앞에서 문 앞까지 총 소요시간(분)
 * @param estimated            버스 구간이 포함되어 시각이 추정치인지 (버스는 정류장별 시간표가 없음)
 * @param lastTrainDeparture   목표 도착시간 역산 결과가 사실상 그냥 막차와 같은 경우
 * @param fareWon              총 요금(원). 0이면 요금 정보 없음(심야버스처럼 직접 만든 경로).
 * @param safeDepartureTime    환승마다 여유 버퍼를 둔 "안전 막차" 출발 시각. null이면 이 경로엔 그런
 *                             여유를 둘 수 없음(예: 환승이 없거나, 여유를 두면 아예 못 감) - 이 경우
 *                             화면에서는 최단 막차만 보여준다.
 * @param safeNextDay          safeDepartureTime이 "다음날 이 시각"인지. safeDepartureTime이 null이면 의미 없음.
 * @param subwayDirections     legs와 같은 순서·길이. 지하철 구간이면 실제로 타는 차편의 방면(종착역명,
 *                              예: "당고개") - 화면에 "OO행"으로 보여준다. 버스 구간이거나 방면
 *                              정보를 못 구했으면 null.
 * @param legBoardServiceMinutes legs와 같은 순서·길이. 각 구간에서 실제로 선택된 차편의 탑승
 *                              시각(서비스일 기준 분) - 환승 도보 직후를 그냥 탑승 시각으로 가정하지
 *                              않고, 실제 시간표상 그 이후 가장 빠른 차편 시각을 보여주는 데 쓴다.
 */
public record RouteOption(String modeLabel, LocalTime departureTime, boolean nextDay,
                           List<TransitLeg> legs, int finalWalkMinutes, int totalMinutes,
                           boolean estimated, boolean lastTrainDeparture, int fareWon,
                           LocalTime safeDepartureTime, boolean safeNextDay, List<String> subwayDirections,
                           List<Integer> legBoardServiceMinutes) {

    private static final int MINUTES_PER_DAY = 24 * 60;

    public int departureServiceMinutes() {
        return departureTime.getHour() * 60 + departureTime.getMinute() + (nextDay ? MINUTES_PER_DAY : 0);
    }

    public boolean hasSafeDeparture() {
        return safeDepartureTime != null;
    }
}

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
 */
public record RouteOption(String modeLabel, LocalTime departureTime, boolean nextDay,
                           List<TransitLeg> legs, int finalWalkMinutes, int totalMinutes,
                           boolean estimated, boolean lastTrainDeparture, int fareWon,
                           LocalTime safeDepartureTime, boolean safeNextDay) {

    private static final int MINUTES_PER_DAY = 24 * 60;

    public int departureServiceMinutes() {
        return departureTime.getHour() * 60 + departureTime.getMinute() + (nextDay ? MINUTES_PER_DAY : 0);
    }

    public boolean hasSafeDeparture() {
        return safeDepartureTime != null;
    }
}

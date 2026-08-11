package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 출발지/도착지 좌표를 받아 ODsay 경로탐색 -> 지하철 구간 추출 -> 막차/목표도착시간 역산까지 잇는다.
 */
@Service
public class LastDepartureService {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final OdsayClient odsayClient;
    private final RouteLegExtractor routeLegExtractor;
    private final LastDepartureCalculator calculator;

    public LastDepartureService(OdsayClient odsayClient,
                                 RouteLegExtractor routeLegExtractor,
                                 LastDepartureCalculator calculator) {
        this.odsayClient = odsayClient;
        this.routeLegExtractor = routeLegExtractor;
        this.calculator = calculator;
    }

    public LastDepartureResult calculate(double sx, double sy, double ex, double ey) {
        return calculate(sx, sy, ex, ey, null);
    }

    /**
     * @param targetArrivalTime null이면 막차 기준, 값이 있으면 그 시각까지 도착하기 위한 역산.
     *                           현재 시각보다 이른 시각이면 "오늘 밤 자정 넘어서"로 보고 다음날로 해석한다
     *                           (예: 저녁 8시에 "새벽 1시까지" 라고 입력하면 오늘 밤~내일 새벽을 의미).
     */
    public LastDepartureResult calculate(double sx, double sy, double ex, double ey, LocalTime targetArrivalTime) {
        try {
            OdsayPathResponse response = odsayClient.searchSubwayPath(sx, sy, ex, ey);
            List<SubwayLeg> legs = routeLegExtractor.extract(response);
            Integer targetArrivalMinutes = targetArrivalTime == null
                    ? null
                    : resolveTargetArrivalMinutes(targetArrivalTime);
            return calculator.calculate(legs, targetArrivalMinutes);
        } catch (NoSubwayRouteFoundException e) {
            return new LastDepartureResult.Infeasible(e.getMessage());
        }
    }

    private int resolveTargetArrivalMinutes(LocalTime targetArrivalTime) {
        int targetMinutes = targetArrivalTime.getHour() * 60 + targetArrivalTime.getMinute();
        int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        return targetMinutes < nowMinutes ? targetMinutes + MINUTES_PER_DAY : targetMinutes;
    }
}

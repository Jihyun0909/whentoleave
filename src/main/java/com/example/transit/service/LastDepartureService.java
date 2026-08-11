package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.OptionalInt;

/**
 * 출발지/도착지 좌표를 받아 ODsay 경로탐색 -> 지하철 구간 추출 -> 막차/목표도착시간 역산까지 잇는다.
 */
@Service
public class LastDepartureService {

    private static final int MINUTES_PER_DAY = 24 * 60;
    /** 목표 시각이 이 시각 이전(0시~새벽 6시)이면 "오늘 밤 자정 넘어서"로 해석한다. 그 이후 시각인데 이미 지났으면 진짜로 지난 것으로 본다. */
    private static final int EARLY_MORNING_CUTOFF_MINUTES = 6 * 60;

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
     *                           현재 시각보다 이르더라도 새벽 6시 이전이면 "오늘 밤 자정 넘어서"로 본다
     *                           (예: 저녁 8시에 "새벽 1시까지"). 그 외에 이미 지난 시각이면 Infeasible.
     */
    public LastDepartureResult calculate(double sx, double sy, double ex, double ey, LocalTime targetArrivalTime) {
        if (targetArrivalTime == null) {
            return calculate(sx, sy, ex, ey);
        }

        OptionalInt targetArrivalMinutes = resolveTargetArrivalMinutes(targetArrivalTime);
        if (targetArrivalMinutes.isEmpty()) {
            return new LastDepartureResult.Infeasible(
                    "목표 도착 시각(" + targetArrivalTime + ")이 이미 지난 시각입니다. 아직 오지 않은 시각을 입력해주세요.");
        }

        try {
            OdsayPathResponse response = odsayClient.searchSubwayPath(sx, sy, ex, ey);
            List<SubwayLeg> legs = routeLegExtractor.extract(response);
            return calculator.calculate(legs, targetArrivalMinutes.getAsInt());
        } catch (NoSubwayRouteFoundException e) {
            return new LastDepartureResult.Infeasible(e.getMessage());
        }
    }

    private OptionalInt resolveTargetArrivalMinutes(LocalTime targetArrivalTime) {
        int targetMinutes = targetArrivalTime.getHour() * 60 + targetArrivalTime.getMinute();
        int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();

        if (targetMinutes >= nowMinutes) {
            return OptionalInt.of(targetMinutes);
        }
        if (targetMinutes < EARLY_MORNING_CUTOFF_MINUTES) {
            return OptionalInt.of(targetMinutes + MINUTES_PER_DAY); // 오늘 밤 자정 넘어서
        }
        return OptionalInt.empty(); // 이미 지난 시각
    }
}

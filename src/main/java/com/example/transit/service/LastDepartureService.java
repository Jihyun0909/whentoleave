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
        Integer targetArrivalMinutes = null;
        if (targetArrivalTime != null) {
            OptionalInt resolved = resolveTargetArrivalMinutes(targetArrivalTime);
            if (resolved.isEmpty()) {
                return new LastDepartureResult.Infeasible(
                        "목표 도착 시각(" + targetArrivalTime + ")이 이미 지난 시각입니다. 아직 오지 않은 시각을 입력해주세요.");
            }
            targetArrivalMinutes = resolved.getAsInt();
        }

        List<List<SubwayLeg>> pathCandidates;
        try {
            OdsayPathResponse response = odsayClient.searchSubwayPath(sx, sy, ex, ey);
            pathCandidates = routeLegExtractor.extractAll(response);
        } catch (NoSubwayRouteFoundException e) {
            return new LastDepartureResult.Infeasible(e.getMessage());
        }

        return bestOf(pathCandidates, targetArrivalMinutes);
    }

    /**
     * ODsay가 추천한 경로 후보마다 역산해보고, 그중 가장 늦게 출발해도 되는 결과를 고른다.
     * "1순위 추천 경로" 하나만 보면, 그 경로 중간에 배차가 뜸한 구간이 껴 있을 때 막차가
     * 실제보다 훨씬 이르게 계산되는 문제가 있었다 (이슈 #8 — 가평->신림, 청량리 환승 수인분당선
     * 연장구간처럼 하루 몇 대 안 다니는 구간을 타는 경로가 1순위로 나온 경우).
     */
    private LastDepartureResult bestOf(List<List<SubwayLeg>> pathCandidates, Integer targetArrivalMinutes) {
        LastDepartureResult.Feasible best = null;
        String fallbackReason = null;

        for (List<SubwayLeg> legs : pathCandidates) {
            LastDepartureResult result = calculator.calculate(legs, targetArrivalMinutes);
            if (result instanceof LastDepartureResult.Feasible feasible) {
                if (best == null || isLater(feasible, best)) {
                    best = feasible;
                }
            } else if (fallbackReason == null) {
                fallbackReason = ((LastDepartureResult.Infeasible) result).reason();
            }
        }

        if (best != null) {
            return best;
        }
        return new LastDepartureResult.Infeasible(
                fallbackReason != null ? fallbackReason : "가능한 경로를 찾지 못했습니다.");
    }

    private boolean isLater(LastDepartureResult.Feasible candidate, LastDepartureResult.Feasible current) {
        return toServiceMinutes(candidate) > toServiceMinutes(current);
    }

    private int toServiceMinutes(LastDepartureResult.Feasible feasible) {
        int minutes = feasible.departureTime().getHour() * 60 + feasible.departureTime().getMinute();
        return feasible.nextDay() ? minutes + MINUTES_PER_DAY : minutes;
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

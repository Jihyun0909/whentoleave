package com.example.transit.service;

import com.example.transit.domain.SubwayLastTrain;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;

/**
 * 여러 지하철 구간(환승 포함)에 대해, 가장 마지막 구간부터 거꾸로 계산해서
 * 첫 구간 승차역에서 몇 시까지 타야 막차로 목적지까지 도착할 수 있는지 구한다.
 * <p>
 * 자정을 넘나드는 시각 비교에서 wrap-around 버그가 나지 않도록,
 * 내부 계산은 전부 "서비스일 기준 분"(0시=0, 24시 이후는 1440 이상)의 정수로 한다.
 */
@Service
public class LastDepartureCalculator {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private final LastTrainLookup lastTrainLookup;

    public LastDepartureCalculator(LastTrainLookup lastTrainLookup) {
        this.lastTrainLookup = lastTrainLookup;
    }

    public LastDepartureResult calculate(List<SubwayLeg> legs) {
        if (legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }

        Integer requiredArrivalMinutes = null; // 마지막 구간은 다음 제약이 없음
        int firstLegUsableMinutes = -1;

        for (int i = legs.size() - 1; i >= 0; i--) {
            SubwayLeg leg = legs.get(i);

            List<SubwayLastTrain> candidates = lastTrainLookup.getLastTrains(leg.stationId(), leg.wayCode());
            if (candidates.isEmpty()) {
                return new LastDepartureResult.Infeasible(
                        "역 " + leg.stationId() + "의 막차 정보를 찾을 수 없습니다.");
            }
            int lastTrainMinutes = maxDepartureMinutes(candidates);

            int usableMinutes;
            if (requiredArrivalMinutes == null) {
                usableMinutes = lastTrainMinutes;
            } else {
                int deadline = requiredArrivalMinutes - leg.rideMinutes();
                if (lastTrainMinutes > deadline) {
                    return new LastDepartureResult.Infeasible(
                            "역 " + leg.stationId() + "의 막차로는 다음 환승을 놓칩니다. "
                                    + "더 이른 경로를 확인해주세요.");
                }
                usableMinutes = lastTrainMinutes;
            }

            if (i == 0) {
                firstLegUsableMinutes = usableMinutes;
            } else {
                requiredArrivalMinutes = usableMinutes - leg.transferBufferMinutes();
            }
        }

        return toFeasible(firstLegUsableMinutes);
    }

    private int maxDepartureMinutes(List<SubwayLastTrain> candidates) {
        return candidates.stream()
                .mapToInt(train -> toServiceMinutes(train.getDepartureTime(), train.isNextDay()))
                .max()
                .orElseThrow();
    }

    private int toServiceMinutes(LocalTime time, boolean nextDay) {
        int minutes = time.getHour() * 60 + time.getMinute();
        return nextDay ? minutes + MINUTES_PER_DAY : minutes;
    }

    private LastDepartureResult.Feasible toFeasible(int serviceMinutes) {
        boolean nextDay = serviceMinutes >= MINUTES_PER_DAY;
        int normalized = nextDay ? serviceMinutes - MINUTES_PER_DAY : serviceMinutes;
        return new LastDepartureResult.Feasible(LocalTime.of(normalized / 60, normalized % 60), nextDay);
    }
}

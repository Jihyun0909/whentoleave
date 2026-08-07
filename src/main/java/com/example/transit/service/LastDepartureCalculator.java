package com.example.transit.service;

import com.example.transit.domain.SubwayLastTrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 여러 지하철 구간(환승 포함)에 대해, 가장 마지막 구간부터 거꾸로 계산해서
 * 첫 구간 승차역에서 몇 시까지 타야 막차로 목적지까지 도착할 수 있는지 구한다.
 * <p>
 * 자정을 넘나드는 시각 비교에서 wrap-around 버그가 나지 않도록,
 * 내부 계산은 전부 "서비스일 기준 분"(0시=0, 24시 이후는 1440 이상)의 정수로 한다.
 */
@Service
public class LastDepartureCalculator {

    private static final Logger log = LoggerFactory.getLogger(LastDepartureCalculator.class);
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
            List<Integer> reachableMinutes = reachableCandidates(candidates, leg).stream()
                    .map(train -> toServiceMinutes(train.getDepartureTime(), train.isNextDay()))
                    .toList();

            log.info("leg[{}] stationId={} wayCode={} rideMinutes={} transferBufferMinutes={} "
                            + "earlierStopNames={} candidates(all)={} candidates(reachable)={} requiredArrivalMinutes={}",
                    i, leg.stationId(), leg.wayCode(), leg.rideMinutes(), leg.transferBufferMinutes(),
                    leg.earlierStopNames(),
                    candidates.stream().map(t -> t.getEndStationName() + "=" + toServiceMinutes(t.getDepartureTime(), t.isNextDay())).toList(),
                    reachableMinutes, requiredArrivalMinutes);

            int usableMinutes;
            if (requiredArrivalMinutes == null) {
                usableMinutes = reachableMinutes.stream().mapToInt(Integer::intValue).max().orElseThrow();
            } else {
                int deadline = requiredArrivalMinutes - leg.rideMinutes();
                // 가장 늦은 후보 하나만 보고 포기하지 않는다 - 그 후보가 마감을 못 맞춰도,
                // 그보다 이른 다른 후보가 마감 안에 들어올 수 있으므로 그중 제일 늦은 걸 찾는다.
                Optional<Integer> best = reachableMinutes.stream()
                        .filter(minutes -> minutes <= deadline)
                        .max(Integer::compareTo);
                log.info("leg[{}] deadline={} chosen={}", i, deadline, best.orElse(null));
                if (best.isEmpty()) {
                    return new LastDepartureResult.Infeasible(
                            "역 " + leg.stationId() + "의 막차로는 다음 환승을 놓칩니다. "
                                    + "더 이른 경로를 확인해주세요.");
                }
                usableMinutes = best.get();
            }

            if (i == 0) {
                firstLegUsableMinutes = usableMinutes;
            } else {
                requiredArrivalMinutes = usableMinutes - leg.transferBufferMinutes();
            }
        }

        return toFeasible(firstLegUsableMinutes);
    }

    /**
     * 목적지가 이 구간의 도착역보다 앞에 있는(즉 단축운행으로 도착역까지 못 가는) 후보는 뺀다.
     * 전부 걸러져서 하나도 안 남으면(정보 부족 등), 걸러내기 전 전체 목록으로 되돌아간다 —
     * 잘못 걸러서 "갈 수 있는데 못 간다"고 하는 것보다는 낫다.
     */
    private List<SubwayLastTrain> reachableCandidates(List<SubwayLastTrain> candidates, SubwayLeg leg) {
        if (leg.earlierStopNames().isEmpty()) {
            return candidates;
        }
        List<SubwayLastTrain> filtered = candidates.stream()
                .filter(train -> !leg.earlierStopNames().contains(train.getEndStationName()))
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
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

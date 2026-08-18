package com.example.transit.service;

import com.example.transit.domain.SubwaySchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 여러 지하철 구간(환승 포함)에 대해, 가장 마지막 구간부터 거꾸로 계산해서
 * 첫 구간 승차역에서 몇 시까지 타야 목적지까지 도착할 수 있는지 구한다.
 * 목표 도착시간을 안 주면 막차 기준(가장 늦은 후보)으로, 주면 그 시각까지 도착하는 것을
 * 만족하는 가장 늦은 후보로 계산한다 (이슈 #6).
 * <p>
 * 자정을 넘나드는 시각 비교에서 wrap-around 버그가 나지 않도록,
 * 내부 계산은 전부 "서비스일 기준 분"(0시=0, 24시 이후는 1440 이상)의 정수로 한다.
 */
@Service
public class LastDepartureCalculator {

    private static final Logger log = LoggerFactory.getLogger(LastDepartureCalculator.class);
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final LastTrainLookup lastTrainLookup;
    private final BusDepartureLookup busDepartureLookup;

    public LastDepartureCalculator(LastTrainLookup lastTrainLookup, BusDepartureLookup busDepartureLookup) {
        this.lastTrainLookup = lastTrainLookup;
        this.busDepartureLookup = busDepartureLookup;
    }

    /** 지하철만 다루는 테스트용 - 버스 구간이 들어오면 후보가 없어 Infeasible이 된다. */
    public static LastDepartureCalculator subwayOnly(LastTrainLookup lastTrainLookup) {
        return new LastDepartureCalculator(lastTrainLookup, (leg, date) -> List.of());
    }

    /** 오늘 기준 막차 계산 (테스트 편의용). */
    public LastDepartureResult calculate(List<TransitLeg> legs) {
        return calculate(legs, null, 0, LocalDate.now());
    }

    /** 오늘 기준 계산 (테스트 편의용). */
    public LastDepartureResult calculate(List<TransitLeg> legs, Integer targetArrivalMinutes) {
        return calculate(legs, targetArrivalMinutes, 0, LocalDate.now());
    }

    /** 오늘 기준 계산 (테스트 편의용). */
    public LastDepartureResult calculate(List<TransitLeg> legs, Integer targetArrivalMinutes, int finalWalkMinutes) {
        return calculate(legs, targetArrivalMinutes, finalWalkMinutes, LocalDate.now());
    }

    /**
     * @param targetArrivalMinutes null이면 막차 기준(마지막 구간에 도착 제약 없음),
     *                              값이 있으면 그 시각(서비스일 기준 분)까지 도착해야 하는 것으로 보고 역산한다.
     * @param finalWalkMinutes     마지막 지하철 하차역에서 실제 목적지까지 걸어야 하는 시간(분).
     *                              목표 도착시간이 있으면 이 시간만큼 미리 당겨서 역산해야 한다.
     * @param date                 어느 날짜의 시간표로 계산할지 (평일/토요일/공휴일이 갈린다).
     */
    public LastDepartureResult calculate(List<TransitLeg> legs, Integer targetArrivalMinutes,
                                          int finalWalkMinutes, LocalDate date) {
        return calculate(legs, targetArrivalMinutes, finalWalkMinutes, date, 0);
    }

    /**
     * @param transferSafetyMarginMinutes 환승마다 추가로 확보할 여유시간(분). 0이면 기존과 동일한
     *                                     "정확히 맞춰 타는" 최단 막차 계산이다. 값을 주면 각 환승
     *                                     지점(구간과 구간 사이)마다 그만큼 일찍 도착하는 걸 목표로
     *                                     역산해서, 실제 배차가 시간표와 1~2분 어긋나거나 걸음이
     *                                     느려도 놓치지 않을 확률을 높인다. 첫 구간 승차역까지 걷는
     *                                     시간에는 적용하지 않는다 - 그건 환승이 아니라 출발 시각을
     *                                     스스로 정하는 구간이라 놓칠 위험이 없다.
     */
    public LastDepartureResult calculate(List<TransitLeg> legs, Integer targetArrivalMinutes,
                                          int finalWalkMinutes, LocalDate date, int transferSafetyMarginMinutes) {
        if (legs == null || legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }

        Integer requiredArrivalMinutes = targetArrivalMinutes == null ? null : targetArrivalMinutes - finalWalkMinutes;
        int firstLegUsableMinutes = -1;

        for (int i = legs.size() - 1; i >= 0; i--) {
            TransitLeg leg = legs.get(i);

            List<Integer> reachableMinutes = departureCandidates(leg, date);
            if (reachableMinutes.isEmpty()) {
                return new LastDepartureResult.Infeasible(
                        (leg.isBus() ? "버스 " : "역 ") + leg.stationId() + "의 운행 정보를 찾을 수 없습니다.");
            }

            int usableMinutes;
            if (requiredArrivalMinutes == null) {
                usableMinutes = reachableMinutes.stream().mapToInt(Integer::intValue).max().orElseThrow();
                log.debug("leg[{}] stationId={} wayCode={} 마지막 구간, 선택={}",
                        i, leg.stationId(), leg.wayCode(), usableMinutes);
            } else {
                int deadline = requiredArrivalMinutes - leg.rideMinutes();
                // 가장 늦은 후보 하나만 보고 포기하지 않는다 - 그 후보가 마감을 못 맞춰도,
                // 그보다 이른 다른 후보가 마감 안에 들어올 수 있으므로 그중 제일 늦은 걸 찾는다.
                Optional<Integer> best = reachableMinutes.stream()
                        .filter(minutes -> minutes <= deadline)
                        .max(Integer::compareTo);
                log.debug("leg[{}] stationId={} wayCode={} rideMinutes={} transferBufferMinutes={} "
                                + "requiredArrivalMinutes={} deadline={} 후보={} 선택={}",
                        i, leg.stationId(), leg.wayCode(), leg.rideMinutes(), leg.transferBufferMinutes(),
                        requiredArrivalMinutes, deadline, reachableMinutes, best.orElse(null));
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
                requiredArrivalMinutes = usableMinutes - leg.transferBufferMinutes() - transferSafetyMarginMinutes;
            }
        }

        // firstLegUsableMinutes는 "첫 구간 승차역에서 열차를 타는 시각"이다. legs.get(0)의
        // transferBufferMinutes는 실제 출발지(주소 등)에서 그 승차역까지 걷는 시간이므로,
        // 그만큼 더 일찍 출발해야 한다 (이슈: 주소를 출발지로 입력했을 때 그 도보시간이
        // 계산에 전혀 반영되지 않던 문제).
        int departureMinutes = firstLegUsableMinutes - legs.get(0).transferBufferMinutes();
        return toFeasible(departureMinutes, legs, finalWalkMinutes);
    }

    /**
     * 이 구간에서 탈 수 있는 차편들의 출발 시각(서비스일 기준 분). 지하철은 실제 시간표에서,
     * 버스는 노선 막차/배차간격 기반 추정치에서 가져온다 - 이 차이를 여기서 흡수해서
     * 역산 로직 자체는 교통수단을 구분하지 않는다.
     */
    private List<Integer> departureCandidates(TransitLeg leg, LocalDate date) {
        if (leg.isBus()) {
            return busDepartureLookup.departureServiceMinutes(leg, date);
        }
        List<SubwaySchedule> candidates = lastTrainLookup.getLastTrains(leg.stationId(), leg.wayCode(), date);
        return reachableCandidates(candidates, leg).stream()
                .map(train -> toServiceMinutes(train.getDepartureTime(), train.isNextDay()))
                .toList();
    }

    /**
     * 목적지가 이 구간의 도착역보다 앞에 있는(즉 단축운행으로 도착역까지 못 가는) 후보는 뺀다.
     * 전부 걸러져서 하나도 안 남으면(정보 부족 등), 걸러내기 전 전체 목록으로 되돌아간다 —
     * 잘못 걸러서 "갈 수 있는데 못 간다"고 하는 것보다는 낫다.
     */
    private List<SubwaySchedule> reachableCandidates(List<SubwaySchedule> candidates, TransitLeg leg) {
        if (leg.earlierStopNames().isEmpty()) {
            return candidates;
        }
        List<SubwaySchedule> filtered = candidates.stream()
                .filter(train -> !leg.earlierStopNames().contains(train.getEndStationName()))
                .toList();
        return filtered.isEmpty() ? candidates : filtered;
    }

    private int toServiceMinutes(LocalTime time, boolean nextDay) {
        int minutes = time.getHour() * 60 + time.getMinute();
        return nextDay ? minutes + MINUTES_PER_DAY : minutes;
    }

    private LastDepartureResult.Feasible toFeasible(int serviceMinutes, List<TransitLeg> legs, int finalWalkMinutes) {
        boolean nextDay = serviceMinutes >= MINUTES_PER_DAY;
        int normalized = nextDay ? serviceMinutes - MINUTES_PER_DAY : serviceMinutes;
        // isLastTrainDeparture는 서비스 레이어(LastDepartureService)가 목표시간 결과와 순수 막차
        // 결과를 비교해서 판단할 몫이라 여기서는 항상 false로 둔다.
        return new LastDepartureResult.Feasible(
                LocalTime.of(normalized / 60, normalized % 60), nextDay, legs, finalWalkMinutes, false);
    }
}

package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.SearchPathType;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * 출발지/도착지 좌표를 받아 ODsay 경로탐색 -> 지하철 구간 추출 -> 막차/목표도착시간 역산까지 잇는다.
 */
@Service
public class LastDepartureService {

    private static final int MINUTES_PER_DAY = 24 * 60;
    /** 목표 시각이 이 시각 이전(0시~새벽 6시)이면 "오늘 밤 자정 넘어서"로 해석한다. 그 이후 시각인데 이미 지났으면 진짜로 지난 것으로 본다. */
    private static final int EARLY_MORNING_CUTOFF_MINUTES = 6 * 60;

    /** 심야버스는 SearchPathType으로 표현되지 않는 별도 경로원이라 라벨을 따로 둔다. */
    private static final String NIGHT_BUS_LABEL = "심야버스";
    /** 서울 심야버스 중 가장 이른 첫차가 23시대라, 이보다 이른 도착 목표면 탐색 자체를 건너뛴다. */
    private static final int NIGHT_BUS_EARLIEST_MINUTES = 23 * 60;

    private final OdsayClient odsayClient;
    private final RouteLegExtractor routeLegExtractor;
    private final LastDepartureCalculator calculator;
    private final NightBusRouteFinder nightBusRouteFinder;

    public LastDepartureService(OdsayClient odsayClient,
                                 RouteLegExtractor routeLegExtractor,
                                 LastDepartureCalculator calculator,
                                 NightBusRouteFinder nightBusRouteFinder) {
        this.odsayClient = odsayClient;
        this.routeLegExtractor = routeLegExtractor;
        this.calculator = calculator;
        this.nightBusRouteFinder = nightBusRouteFinder;
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
        return calculate(sx, sy, ex, ey, targetArrivalTime, LocalDate.now());
    }

    public LastDepartureResult calculate(double sx, double sy, double ex, double ey,
                                          LocalTime targetArrivalTime, LocalDate date) {
        Integer targetArrivalMinutes = null;
        if (targetArrivalTime != null) {
            OptionalInt resolved = resolveTargetArrivalMinutes(targetArrivalTime);
            if (resolved.isEmpty()) {
                return new LastDepartureResult.Infeasible(
                        "목표 도착 시각(" + targetArrivalTime + ")이 이미 지난 시각입니다. 아직 오지 않은 시각을 입력해주세요.");
            }
            targetArrivalMinutes = resolved.getAsInt();
        }
        return calculateFor(SearchPathType.SUBWAY_ONLY, sx, sy, ex, ey, targetArrivalMinutes, date).result();
    }

    /**
     * 지하철 / 지하철+버스 / 버스 세 가지로 각각 계산해서, 성립하는 것만 모아 돌려준다.
     * 세 검색이 같은 경로로 수렴하는 경우(예: 지하철이 최선이면 "지하철+버스"도 같은 답)가 흔해서
     * 출발시각·경로가 같으면 하나로 합친다. 정렬은 소요시간이 짧은 순 - 화면에서 맨 위가 추천 경로다.
     */
    public List<RouteOption> calculateOptions(double sx, double sy, double ex, double ey,
                                               LocalTime targetArrivalTime, LocalDate date) {
        Integer targetArrivalMinutes = null;
        if (targetArrivalTime != null) {
            OptionalInt resolved = resolveTargetArrivalMinutes(targetArrivalTime);
            if (resolved.isEmpty()) {
                return List.of();
            }
            targetArrivalMinutes = resolved.getAsInt();
        }

        List<RouteOption> options = new ArrayList<>();
        for (SearchPathType pathType : List.of(
                SearchPathType.SUBWAY_ONLY, SearchPathType.ALL, SearchPathType.BUS_ONLY)) {
            Best best = calculateFor(pathType, sx, sy, ex, ey, targetArrivalMinutes, date);
            if (best.result() instanceof LastDepartureResult.Feasible feasible) {
                options.add(toOption(pathType.label(), feasible, best.fareWon()));
            }
        }
        // 심야버스는 ODsay 경로탐색에 아예 안 나와서 따로 찾아 붙인다. 지하철/버스가 다 끊긴
        // 시간대에는 이게 유일한 답인 경우가 많아, 막차 앱에서는 빠지면 안 되는 정보다.
        bestNightBus(sx, sy, ex, ey, targetArrivalMinutes, date)
                .ifPresent(feasible -> options.add(toOption(NIGHT_BUS_LABEL, feasible, 0)));

        return options.stream()
                .collect(Collectors.toMap(
                        option -> option.departureServiceMinutes() + "|" + legSignature(option),
                        option -> option,
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparingInt(RouteOption::totalMinutes))
                .toList();
    }

    /** 목표 도착시간 계산이 실패한 이유를 화면에 보여주기 위해, 지하철 기준 결과를 한 번 더 구한다. */
    public LastDepartureResult calculateSingle(double sx, double sy, double ex, double ey,
                                                LocalTime targetArrivalTime) {
        return calculate(sx, sy, ex, ey, targetArrivalTime);
    }

    private Best calculateFor(SearchPathType pathType, double sx, double sy, double ex, double ey,
                               Integer targetArrivalMinutes, LocalDate date) {
        List<RouteLegExtractor.ExtractedRoute> pathCandidates;
        try {
            OdsayPathResponse response = odsayClient.searchPath(sx, sy, ex, ey, pathType);
            pathCandidates = routeLegExtractor.extractAll(response, pathType != SearchPathType.SUBWAY_ONLY);
        } catch (NoSubwayRouteFoundException e) {
            return new Best(new LastDepartureResult.Infeasible(e.getMessage()), 0);
        } catch (RuntimeException e) {
            return new Best(new LastDepartureResult.Infeasible("경로를 찾는 중 문제가 발생했습니다."), 0);
        }

        Best best = bestOf(pathCandidates, targetArrivalMinutes, date);
        if (targetArrivalMinutes == null || !(best.result() instanceof LastDepartureResult.Feasible targetFeasible)) {
            return best;
        }

        // 목표 시각이 밤늦게라 사실상 아무 제약이 안 되면(예: 새벽 2시까지 도착), 역산 결과가
        // 그냥 막차와 똑같이 나온다 - 이 경우 "그 시각까지 도착하려면"이라는 문구가 오해를 살 수
        // 있어서(실제로는 훨씬 일찍 도착함) 화면에서 "이건 그냥 막차입니다"라고 밝혀준다.
        if (bestOf(pathCandidates, null, date).result() instanceof LastDepartureResult.Feasible lastTrainFeasible
                && toServiceMinutes(targetFeasible) == toServiceMinutes(lastTrainFeasible)) {
            return new Best(new LastDepartureResult.Feasible(targetFeasible.departureTime(), targetFeasible.nextDay(),
                    targetFeasible.legs(), targetFeasible.finalWalkMinutes(), true), best.fareWon());
        }
        return best;
    }

    /** 고른 결과와 그 경로의 요금을 같이 들고 다닌다 (요금은 계산에는 안 쓰이고 화면 표시용이라 결과 타입에 넣지 않음). */
    private record Best(LastDepartureResult result, int fareWon) {
    }

    /**
     * 심야버스는 노선마다 한 구간짜리 경로가 나오므로, 그중 가장 늦게 출발해도 되는 걸 고른다.
     * <p>
     * 목표 도착 시각이 심야버스 운행 시간대(23시~)보다 이르면 아예 찾지 않는다. 어차피 탈 수 없는
     * 경로인데, 심야버스 탐색은 노선 목록 + 노선별 상세로 API를 20번 가까이 쓰기 때문이다
     * (오후 4시 도착 목표로 검색해도 심야버스를 조회하던 문제).
     */
    private Optional<LastDepartureResult.Feasible> bestNightBus(double sx, double sy, double ex, double ey,
                                                                 Integer targetArrivalMinutes, LocalDate date) {
        if (targetArrivalMinutes != null && targetArrivalMinutes < NIGHT_BUS_EARLIEST_MINUTES) {
            return Optional.empty();
        }
        List<RouteLegExtractor.ExtractedRoute> routes;
        try {
            routes = nightBusRouteFinder.find(sx, sy, ex, ey);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (routes.isEmpty()) {
            return Optional.empty();
        }
        return bestOf(routes, targetArrivalMinutes, date).result() instanceof LastDepartureResult.Feasible feasible
                ? Optional.of(feasible) : Optional.empty();
    }

    private RouteOption toOption(String modeLabel, LastDepartureResult.Feasible feasible, int fareWon) {
        int legsMinutes = feasible.legs().stream()
                .mapToInt(leg -> leg.rideMinutes() + leg.transferBufferMinutes())
                .sum();
        boolean hasBus = feasible.legs().stream().anyMatch(TransitLeg::isBus);
        return new RouteOption(modeLabel, feasible.departureTime(), feasible.nextDay(),
                feasible.legs(), feasible.finalWalkMinutes(), legsMinutes + feasible.finalWalkMinutes(),
                hasBus, feasible.isLastTrainDeparture(), fareWon);
    }

    private String legSignature(RouteOption option) {
        return option.legs().stream()
                .map(leg -> leg.mode() + ":" + leg.stationId() + ":" + leg.rideMinutes())
                .collect(Collectors.joining(","));
    }

    /**
     * ODsay가 추천한 경로 후보마다 역산해보고, 그중 가장 늦게 출발해도 되는 결과를 고른다.
     * "1순위 추천 경로" 하나만 보면, 그 경로 중간에 배차가 뜸한 구간이 껴 있을 때 막차가
     * 실제보다 훨씬 이르게 계산되는 문제가 있었다 (이슈 #8 — 가평->신림, 청량리 환승 수인분당선
     * 연장구간처럼 하루 몇 대 안 다니는 구간을 타는 경로가 1순위로 나온 경우).
     */
    private Best bestOf(List<RouteLegExtractor.ExtractedRoute> pathCandidates, Integer targetArrivalMinutes,
                         LocalDate date) {
        LastDepartureResult.Feasible best = null;
        int bestFareWon = 0;
        String fallbackReason = null;

        for (RouteLegExtractor.ExtractedRoute route : pathCandidates) {
            LastDepartureResult result = calculator.calculate(route.legs(), targetArrivalMinutes, route.finalWalkMinutes());
            if (result instanceof LastDepartureResult.Feasible feasible) {
                if (best == null || isLater(feasible, best)) {
                    best = feasible;
                    bestFareWon = route.fareWon();
                }
            } else if (fallbackReason == null) {
                fallbackReason = ((LastDepartureResult.Infeasible) result).reason();
            }
        }

        if (best != null) {
            return new Best(best, bestFareWon);
        }
        return new Best(new LastDepartureResult.Infeasible(
                fallbackReason != null ? fallbackReason : "가능한 경로를 찾지 못했습니다."), 0);
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

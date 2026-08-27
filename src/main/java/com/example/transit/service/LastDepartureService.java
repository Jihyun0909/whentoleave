package com.example.transit.service;

import com.example.transit.service.client.GoogleRoutesClient;
import com.example.transit.service.client.SearchPathType;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

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
 * 출발지/도착지 좌표를 받아 Google Routes 경로탐색 -> 지하철/버스 구간 추출 -> 막차/목표도착시간
 * 역산까지 잇는다.
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
    /**
     * "안전 막차"에서 환승마다 추가로 확보하는 여유시간(분). 5~10분 사이 중간값 - 배차가
     * 시간표와 1~2분 어긋나거나 걸음이 느려도 놓치지 않을 정도의 여유. 막차 모드에서만
     * 의미가 있다(목표 도착시간 역산에서는 "안전"이라는 개념 자체가 불분명해서 적용하지 않는다).
     */
    private static final int SAFE_TRANSFER_MARGIN_MINUTES = 7;
    /**
     * 경로탐색 API가 조회 자체를 못 해준 경우의 안내 문구. 화면에서 "운행 종료"류 문구로
     * 바꾸지 않고 그대로 보여줘야 해서(기다린다고 해결되는 상황이 아니다) 상수로 공유한다.
     */
    public static final String ROUTE_SEARCH_UNAVAILABLE_REASON =
            "지금은 경로를 조회할 수 없습니다. 잠시 후 다시 시도해주세요.";
    /**
     * 출발지-목적지가 이 거리(m) 이내인데 ODsay가 경로를 못 찾으면(예: ODsay 자체가
     * "출,도착지가 700m이내입니다" 에러를 주는 경우), 대중교통이 필요 없을 만큼 가까운
     * 거리라고 보고 도보 시간을 추정해서 대신 보여준다. ODsay가 도보 전용 경로를 실제로
     * 계산해서 줄 때는 그 값을 그대로 쓰고(WalkOnlyRouteException), 이 추정치는 ODsay가
     * 아예 아무 경로도 안 줄 때만 쓰는 fallback이다.
     */
    private static final double WALK_ESTIMATE_DISTANCE_THRESHOLD_METERS = 1000;
    /** 도보 속도 추정(약 4km/h = 67m/분). */
    private static final double WALKING_METERS_PER_MINUTE = 67;
    /** 직선거리(Haversine)는 실제로 걷는 길보다 짧으므로, 도로거리를 어림잡아 20% 더 본다. */
    private static final double ROAD_DISTANCE_FACTOR = 1.2;

    private final GoogleRoutesClient googleRoutesClient;
    private final RouteLegExtractor routeLegExtractor;
    private final LastDepartureCalculator calculator;
    private final NightBusRouteFinder nightBusRouteFinder;

    public LastDepartureService(GoogleRoutesClient googleRoutesClient,
                                 RouteLegExtractor routeLegExtractor,
                                 LastDepartureCalculator calculator,
                                 NightBusRouteFinder nightBusRouteFinder) {
        this.googleRoutesClient = googleRoutesClient;
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
                options.add(toOption(pathType.label(), feasible, best.fareWon(), targetArrivalMinutes, date));
            }
        }
        // 심야버스는 ODsay 경로탐색에 아예 안 나와서 따로 찾아 붙인다. 지하철/버스가 다 끊긴
        // 시간대에는 이게 유일한 답인 경우가 많아, 막차 앱에서는 빠지면 안 되는 정보다.
        Integer finalTargetArrivalMinutes = targetArrivalMinutes;
        bestNightBus(sx, sy, ex, ey, targetArrivalMinutes, date)
                .ifPresent(feasible -> options.add(
                        toOption(NIGHT_BUS_LABEL, feasible, 0, finalTargetArrivalMinutes, date)));

        return options.stream()
                .collect(Collectors.toMap(
                        option -> option.departureServiceMinutes() + "|" + legSignature(option),
                        option -> option,
                        (first, duplicate) -> first,
                        LinkedHashMap::new))
                .values().stream()
                .sorted(recommendationOrder())
                .toList();
    }

    /**
     * 소요시간이 짧은 경로를 맨 위로 올린다. 목표 도착시간 모드는 예전에 "목표 시각에 가장
     * 가깝게 도착하는" 경로를 우선했는데(도착 시각이 목표를 넘는 경로는 계산 단계에서 이미
     * 걸러지므로, 남은 후보 중 딱 맞춰 도착하는 쪽을 고른다는 의도였다), 이러면 환승이 많고
     * 오래 걸리는 경로가 "딱 맞춰 도착한다"는 이유만으로 직행·단시간 경로보다 위로 올라오는
     * 문제가 있었다(실사용 중 발견: 번동->광운대에서 버스 1218 직행 19분보다 2번 환승하는
     * 43분짜리 경로가 "가장 빠름"으로 추천됨 - 환승이 많을수록 실시간 지연/놓침 위험도 커서
     * 오히려 더 나쁜 선택이었다). 소요시간 기준으로 통일한다.
     */
    private Comparator<RouteOption> recommendationOrder() {
        return Comparator.comparingInt(RouteOption::totalMinutes);
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
            GoogleRoutesResponse response;
            try {
                response = googleRoutesClient.computeTransitRoutes(sx, sy, ex, ey, allowedTravelModes(pathType));
            } catch (RestClientException e) {
                // Google이 HTTP 오류(쿼터 초과, 잘못된 요청 등)를 준 상황 - 경로가 없는 게 아니라
                // 조회 자체를 못 한 것이므로 "운행 종료"류 문구로 바뀌면 안 된다.
                throw new RouteSearchUnavailableException("경로탐색 API 오류: " + e.getMessage());
            }
            pathCandidates = routeLegExtractor.extractAll(response, pathType != SearchPathType.SUBWAY_ONLY);
        } catch (RouteSearchUnavailableException e) {
            // 경로가 없는 게 아니라 조회 자체를 못 한 상황이라, "운행 종료"류 문구로 바뀌지 않게
            // 사유를 그대로 위로 올린다(displayReason이 이 문구를 알아보고 그대로 보여준다).
            return new Best(new LastDepartureResult.Infeasible(ROUTE_SEARCH_UNAVAILABLE_REASON), 0);
        } catch (WalkOnlyRouteException e) {
            return new Best(new LastDepartureResult.Infeasible(e.getMessage(), e.walkMinutes()), 0);
        } catch (NoSubwayRouteFoundException e) {
            // 경로탐색이 아예 결과를 못 준 이유는 다양하지만, 실제로 가까운 거리라면 이유를
            // 따지지 말고 도보 시간을 추정해서 보여주는 게 사용자에게 더 유용하다 -
            // "운행 종료" 같은 엉뚱한 안내보다 낫다.
            return new Best(new LastDepartureResult.Infeasible(
                    e.getMessage(), estimateWalkMinutesIfClose(sx, sy, ex, ey)), 0);
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
                    targetFeasible.legs(), targetFeasible.finalWalkMinutes(), true,
                    targetFeasible.subwayDirections(), targetFeasible.legBoardServiceMinutes()), best.fareWon());
        }
        return best;
    }

    private List<String> allowedTravelModes(SearchPathType pathType) {
        return switch (pathType) {
            case SUBWAY_ONLY -> List.of("SUBWAY");
            case BUS_ONLY -> List.of("BUS");
            case ALL -> null;
        };
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

    private RouteOption toOption(String modeLabel, LastDepartureResult.Feasible feasible, int fareWon,
                                  Integer targetArrivalMinutes, LocalDate date) {
        int legsMinutes = feasible.legs().stream()
                .mapToInt(leg -> leg.rideMinutes() + leg.transferBufferMinutes())
                .sum();
        boolean hasBus = feasible.legs().stream().anyMatch(TransitLeg::isBus);

        // "안전 막차"는 막차 모드(목표 도착시간 없음)에서만 의미가 있다. 같은 경로(legs)를 그대로
        // 두고, 환승마다 여유 버퍼를 더 요구하며 다시 역산해서 더 이른 출발 시각을 구한다 -
        // ODsay 재검색 없이 이미 고른 경로의 시간표만 다시 본다. 환승이 없는 직행 경로는 여유를
        // 둘 지점 자체가 없어 최단 막차와 똑같은 시각이 나오는데, 그런 경우 굳이 "안전 막차"라는
        // 이름으로 같은 시각을 또 보여주면 중복일 뿐이라 화면에는 최단 막차만 남긴다.
        LocalTime safeDepartureTime = null;
        boolean safeNextDay = false;
        if (targetArrivalMinutes == null) {
            LastDepartureResult safeResult = calculator.calculate(
                    feasible.legs(), null, feasible.finalWalkMinutes(), date, SAFE_TRANSFER_MARGIN_MINUTES);
            if (safeResult instanceof LastDepartureResult.Feasible safeFeasible
                    && toServiceMinutes(safeFeasible) != toServiceMinutes(feasible)) {
                safeDepartureTime = safeFeasible.departureTime();
                safeNextDay = safeFeasible.nextDay();
            }
        }

        return new RouteOption(modeLabel, feasible.departureTime(), feasible.nextDay(),
                feasible.legs(), feasible.finalWalkMinutes(), legsMinutes + feasible.finalWalkMinutes(),
                hasBus, feasible.isLastTrainDeparture(), fareWon, safeDepartureTime, safeNextDay,
                feasible.subwayDirections(), feasible.legBoardServiceMinutes());
    }

    private String legSignature(RouteOption option) {
        return option.legs().stream()
                .map(leg -> leg.mode() + ":" + leg.stationId() + ":" + leg.rideMinutes())
                .collect(Collectors.joining(","));
    }

    /**
     * 목표 도착시간이 없으면(막차 모드) 경로 후보마다 역산해보고 그중 가장 늦게 출발해도 되는
     * 결과를 고른다. Google의 "1순위 추천 경로" 하나만 보면, 그 경로 중간에 배차가 뜸한 구간이
     * 껴 있을 때 막차가 실제보다 훨씬 이르게 계산되는 문제가 있었다 (이슈 #8 — 가평->신림,
     * 청량리 환승 수인분당선 연장구간처럼 하루 몇 대 안 다니는 구간을 타는 경로가 1순위로 나온
     * 경우). 이 안전장치는 그대로 둔다.
     * <p>
     * 목표 도착시간이 있으면(arrival 모드)는 Google이 준 순서 그대로 첫 번째로 성립하는 경로를
     * 쓴다 - "환승·도보·대기시간까지 다 포함한 실제 소요시간" 기준의 최적경로 판단은 Google이
     * 이미 하고 있고(사용자 요청: "구글맵 기준 최적경로로 안내"), 우리가 그 위에 자체 기준
     * (예: 소요시간만 비교)을 얹으면 오히려 어긋난다 - 실사용 중 발견: 총 소요시간만 비교하는
     * 기준으로도 여전히 미묘하게 이상한 경로가 골라지는 경우가 있었다.
     */
    private Best bestOf(List<RouteLegExtractor.ExtractedRoute> pathCandidates, Integer targetArrivalMinutes,
                         LocalDate date) {
        LastDepartureResult.Feasible best = null;
        int bestFareWon = 0;
        String fallbackReason = null;

        // 목표 도착시간 모드는 환승마다 여유(SAFE_TRANSFER_MARGIN_MINUTES)를 두고 계산한다 -
        // 실사용 중 발견: "내려서 도보한 시간이 다음 환승 열차 출발 시각에 정확히 맞아떨어지는"
        // 결과가 나와서 이상하다는 피드백. 안 두면 실제로 놓치기 딱 좋은 빠듯한 환승을 아무렇지
        //않게 추천하게 된다. 막차 모드(목표 시각 없음)는 "최단 막차"라는 이름 그대로 정확히
        // 맞춰 타는 걸 의도한 거라 그대로 0으로 둔다 - 그 대신 별도의 "안전 막차"를 toOption()에서
        // 추가로 계산해 보여준다.
        int transferMargin = targetArrivalMinutes != null ? SAFE_TRANSFER_MARGIN_MINUTES : 0;
        for (RouteLegExtractor.ExtractedRoute route : pathCandidates) {
            LastDepartureResult result = calculator.calculate(
                    route.legs(), targetArrivalMinutes, route.finalWalkMinutes(), date, transferMargin);
            if (result instanceof LastDepartureResult.Feasible feasible) {
                boolean replace = best == null
                        || (targetArrivalMinutes == null && isLater(feasible, best));
                if (replace) {
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

    /** 가까운 거리가 아니면 null - 그러면 화면은 원래 실패 사유(reason) 문구를 그대로 보여준다. */
    private Integer estimateWalkMinutesIfClose(double sx, double sy, double ex, double ey) {
        double meters = distanceMeters(sy, sx, ey, ex);
        if (meters > WALK_ESTIMATE_DISTANCE_THRESHOLD_METERS) {
            return null;
        }
        return Math.max(1, (int) Math.ceil(meters * ROAD_DISTANCE_FACTOR / WALKING_METERS_PER_MINUTE));
    }

    /** 두 좌표 사이 거리(m). Haversine. */
    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }
}

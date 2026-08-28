package com.example.transit.service;

import com.example.transit.service.client.GoogleRoutesClient;
import com.example.transit.service.client.SearchPathType;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    /**
     * 막차 모드(목표 도착시간이 없는 경우)에서 Google Routes에 넘길 기준 시각. 막차 계산은
     * 애초에 심야 시간대 얘기라, 이 시각 기준으로 물어야 심야버스처럼 그 시간에만 다니는
     * 경로도 후보에 섞여 나온다(2026-08-30 실사용 중 발견: Google 조회에 시각을 아예 안
     * 넘겨서 항상 "지금" 기준으로 계산되고 있었다 - 낮에 조회하면 밤에는 안 다니는 경로가,
     * 다른 날짜를 조회해도 오늘 지금 기준 경로가 나오는 문제였다).
     */
    private static final int GOOGLE_LATE_NIGHT_REFERENCE_MINUTES = 23 * 60;
    /**
     * 목표 도착시간 모드에서 "최적경로"(소요시간 최단)와 "가장 늦게 출발하는 경로"가 다른
     * 경로일 때, 이 비율 이상 소요시간 차이가 나면 둘 다 보여준다(사용자 요청, 2026-08-30).
     * 차이가 작으면 가장 늦게 출발하는 쪽만 보여줘도 충분하다 - 어차피 소요시간도 비슷하다.
     */
    private static final double DURATION_SPLIT_RATIO = 0.30;

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
    /**
     * calculateOptions()가 지하철/지하철+버스/버스/심야버스 네 갈래를 검색하는데, 이게 전부
     * 서로 독립적인데도(각자 Google Routes 호출 + TAGO 시간표 조회) 순서대로 하나씩 기다리고
     * 있었다 - 검색 한 번에 10초 넘게 걸린다는 실사용 피드백의 원인이다. 네 갈래를 동시에
     * 돌리면 전체 소요시간이 "합"이 아니라 "가장 오래 걸리는 하나"에 가까워진다.
     * 가상 스레드를 쓰는 이유: 여기서 도는 작업은 전부 블로킹 I/O(HTTP 호출)라 스레드 수를
     * 미리 정해둘 필요가 없다 - 요청마다 최대 4개뿐이라 부담도 적다.
     */
    private final ExecutorService routeSearchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public LastDepartureService(GoogleRoutesClient googleRoutesClient,
                                 RouteLegExtractor routeLegExtractor,
                                 LastDepartureCalculator calculator,
                                 NightBusRouteFinder nightBusRouteFinder) {
        this.googleRoutesClient = googleRoutesClient;
        this.routeLegExtractor = routeLegExtractor;
        this.calculator = calculator;
        this.nightBusRouteFinder = nightBusRouteFinder;
    }

    @PreDestroy
    void shutdownExecutor() {
        routeSearchExecutor.shutdown();
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
        LocalDate diagramDate = date;
        if (targetArrivalTime != null) {
            Optional<ResolvedTarget> resolved = resolveTargetArrivalMinutes(targetArrivalTime, date);
            if (resolved.isEmpty()) {
                return new LastDepartureResult.Infeasible(
                        "목표 도착 시각(" + targetArrivalTime + ")이 이미 지난 시각입니다. 아직 오지 않은 시각을 입력해주세요.");
            }
            targetArrivalMinutes = resolved.get().minutes();
            diagramDate = resolved.get().diagramDate();
        }
        // calculateFor()는 (분기됐으면) [최적경로, 가장 늦게 출발하는 경로] 순으로 준다 -
        // 이 단일 결과 API는 항상 "몇 시까지 출발하면 되는지"를 답하는 게 목적이라 마지막
        // (가장 늦게 출발하는 쪽)을 쓴다.
        List<Best> candidates = calculateFor(SearchPathType.SUBWAY_ONLY, sx, sy, ex, ey, targetArrivalMinutes, diagramDate);
        return candidates.get(candidates.size() - 1).result();
    }

    /**
     * 지하철 / 지하철+버스 / 버스 세 가지로 각각 계산해서, 성립하는 것만 모아 돌려준다.
     * 세 검색이 같은 경로로 수렴하는 경우(예: 지하철이 최선이면 "지하철+버스"도 같은 답)가 흔해서
     * 출발시각·경로가 같으면 하나로 합친다. 정렬은 소요시간이 짧은 순 - 화면에서 맨 위가 추천 경로다.
     */
    public List<RouteOption> calculateOptions(double sx, double sy, double ex, double ey,
                                               LocalTime targetArrivalTime, LocalDate date) {
        Integer targetArrivalMinutes = null;
        LocalDate diagramDate = date;
        if (targetArrivalTime != null) {
            Optional<ResolvedTarget> resolved = resolveTargetArrivalMinutes(targetArrivalTime, date);
            if (resolved.isEmpty()) {
                return List.of();
            }
            targetArrivalMinutes = resolved.get().minutes();
            diagramDate = resolved.get().diagramDate();
        }
        Integer finalTargetArrivalMinutes = targetArrivalMinutes;
        LocalDate finalDiagramDate = diagramDate;

        // 지하철/지하철+버스/버스/심야버스 네 갈래는 서로 완전히 독립적인 검색이라(각자 Google
        // Routes 호출 + TAGO 시간표 조회) 동시에 돌린다 - 순서대로 기다리면 넷의 소요시간을
        // 그대로 다 더하게 된다("검색이 10초 넘게 걸린다"는 피드백의 원인이었다).
        List<SearchPathType> pathTypes = List.of(
                SearchPathType.SUBWAY_ONLY, SearchPathType.ALL, SearchPathType.BUS_ONLY);
        List<CompletableFuture<List<Best>>> pathFutures = pathTypes.stream()
                .map(pathType -> CompletableFuture.supplyAsync(
                        () -> calculateFor(pathType, sx, sy, ex, ey, finalTargetArrivalMinutes, finalDiagramDate),
                        routeSearchExecutor))
                .toList();
        // 심야버스는 ODsay 경로탐색에 아예 안 나와서 따로 찾아 붙인다. 지하철/버스가 다 끊긴
        // 시간대에는 이게 유일한 답인 경우가 많아, 막차 앱에서는 빠지면 안 되는 정보다.
        CompletableFuture<Optional<LastDepartureResult.Feasible>> nightBusFuture = CompletableFuture.supplyAsync(
                () -> bestNightBus(sx, sy, ex, ey, finalTargetArrivalMinutes, finalDiagramDate), routeSearchExecutor);

        List<RouteOption> options = new ArrayList<>();
        for (int i = 0; i < pathTypes.size(); i++) {
            for (Best best : pathFutures.get(i).join()) {
                if (best.result() instanceof LastDepartureResult.Feasible feasible) {
                    options.add(toOption(pathTypes.get(i).label(), feasible, best.fareWon(),
                            finalTargetArrivalMinutes, finalDiagramDate));
                }
            }
        }
        nightBusFuture.join()
                .ifPresent(feasible -> options.add(
                        toOption(NIGHT_BUS_LABEL, feasible, 0, finalTargetArrivalMinutes, finalDiagramDate)));

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

    /**
     * @return 보통 원소 1개(가장 늦게 출발해도 되는 경로). 목표 도착시간 모드에서 그 경로가
     *         "최적경로"(소요시간이 가장 짧은 경로)와 다르고 소요시간 차이가
     *         {@link #DURATION_SPLIT_RATIO} 이상 나면, [최적경로, 가장 늦게 출발하는 경로]
     *         순서로 2개를 준다 - 사용자 요청: "다른 최적경로랑 소요시간이 30% 이상 차이나면
     *         최적경로랑 늦게 출발 경로 다 띄워줘". 소요시간 차이가 크지 않으면 굳이 두 개를
     *         보여줄 필요가 없다(가장 늦게 출발하는 쪽이 어차피 비슷하게 빠르므로).
     */
    private List<Best> calculateFor(SearchPathType pathType, double sx, double sy, double ex, double ey,
                                     Integer targetArrivalMinutes, LocalDate date) {
        List<RouteLegExtractor.ExtractedRoute> pathCandidates;
        try {
            GoogleRoutesResponse response;
            try {
                String departureTime = targetArrivalMinutes == null
                        ? toRfc3339(date, GOOGLE_LATE_NIGHT_REFERENCE_MINUTES) : null;
                String arrivalTime = targetArrivalMinutes == null
                        ? null : toRfc3339(date, targetArrivalMinutes);
                response = googleRoutesClient.computeTransitRoutes(
                        sx, sy, ex, ey, allowedTravelModes(pathType), departureTime, arrivalTime);
            } catch (RestClientException e) {
                // Google이 HTTP 오류(쿼터 초과, 잘못된 요청 등)를 준 상황 - 경로가 없는 게 아니라
                // 조회 자체를 못 한 것이므로 "운행 종료"류 문구로 바뀌면 안 된다.
                throw new RouteSearchUnavailableException("경로탐색 API 오류: " + e.getMessage());
            }
            pathCandidates = routeLegExtractor.extractAll(response, pathType != SearchPathType.SUBWAY_ONLY);
        } catch (RouteSearchUnavailableException e) {
            // 경로가 없는 게 아니라 조회 자체를 못 한 상황이라, "운행 종료"류 문구로 바뀌지 않게
            // 사유를 그대로 위로 올린다(displayReason이 이 문구를 알아보고 그대로 보여준다).
            return List.of(new Best(new LastDepartureResult.Infeasible(ROUTE_SEARCH_UNAVAILABLE_REASON), 0));
        } catch (WalkOnlyRouteException e) {
            return List.of(new Best(new LastDepartureResult.Infeasible(e.getMessage(), e.walkMinutes()), 0));
        } catch (NoSubwayRouteFoundException e) {
            // 경로탐색이 아예 결과를 못 준 이유는 다양하지만, 실제로 가까운 거리라면 이유를
            // 따지지 말고 도보 시간을 추정해서 보여주는 게 사용자에게 더 유용하다 -
            // "운행 종료" 같은 엉뚱한 안내보다 낫다.
            return List.of(new Best(new LastDepartureResult.Infeasible(
                    e.getMessage(), estimateWalkMinutesIfClose(sx, sy, ex, ey)), 0));
        } catch (RuntimeException e) {
            return List.of(new Best(new LastDepartureResult.Infeasible("경로를 찾는 중 문제가 발생했습니다."), 0));
        }

        List<Best> bests = bestOf(pathCandidates, targetArrivalMinutes, date);
        Best latest = bests.get(bests.size() - 1);
        if (targetArrivalMinutes == null || !(latest.result() instanceof LastDepartureResult.Feasible targetFeasible)) {
            return bests;
        }

        // 목표 시각이 밤늦게라 사실상 아무 제약이 안 되면(예: 새벽 2시까지 도착), 역산 결과가
        // 그냥 막차와 똑같이 나온다 - 이 경우 "그 시각까지 도착하려면"이라는 문구가 오해를 살 수
        // 있어서(실제로는 훨씬 일찍 도착함) 화면에서 "이건 그냥 막차입니다"라고 밝혀준다. 이
        // 표시는 "가장 늦게 출발하는 경로"에만 붙인다 - 최적경로(있다면)는 소요시간 기준으로
        // 고른 별개의 경로라 막차 여부 판정이 그대로 적용되지 않는다.
        List<Best> lastTrainBests = bestOf(pathCandidates, null, date);
        Best lastTrain = lastTrainBests.get(lastTrainBests.size() - 1);
        if (lastTrain.result() instanceof LastDepartureResult.Feasible lastTrainFeasible
                && toServiceMinutes(targetFeasible) == toServiceMinutes(lastTrainFeasible)) {
            Best flaggedLatest = new Best(new LastDepartureResult.Feasible(targetFeasible.departureTime(),
                    targetFeasible.nextDay(), targetFeasible.legs(), targetFeasible.finalWalkMinutes(), true,
                    targetFeasible.subwayDirections(), targetFeasible.legBoardServiceMinutes()), latest.fareWon());
            if (bests.size() == 1) {
                return List.of(flaggedLatest);
            }
            return List.of(bests.get(0), flaggedLatest);
        }
        return bests;
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
        List<Best> bests = bestOf(routes, targetArrivalMinutes, date);
        Best latest = bests.get(bests.size() - 1);
        return latest.result() instanceof LastDepartureResult.Feasible feasible
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
     * 경로 후보마다 역산해보고 그중 가장 늦게 출발해도 되는 결과를 고른다 - 막차 모드든 목표
     * 도착시간 모드든 이 앱이 답해야 하는 질문이 "몇 시까지 출발하면 되는지"인 이상, 같은
     * 도착(마감) 조건을 만족하는 후보 중에서는 가장 늦게 출발해도 되는 쪽이 그대로 더 나은
     * 답이다(환승이 하나 더 있어도, 그 경로의 실제 시간표가 허락하는 만큼 이미 여유
     * (SAFE_TRANSFER_MARGIN_MINUTES)를 두고 계산한 값이라 놓칠 위험이 크지 않다).
     * <p>
     * 막차 모드: Google의 "1순위 추천 경로" 하나만 보면, 그 경로 중간에 배차가 뜸한 구간이 껴
     * 있을 때 막차가 실제보다 훨씬 이르게 계산되는 문제가 있었다(이슈 #8 — 가평->신림, 청량리
     * 환승 수인분당선 연장구간처럼 하루 몇 대 안 다니는 구간을 타는 경로가 1순위로 나온 경우).
     * <p>
     * 목표 도착시간 모드: 한때는 "Google이 준 순서 그대로 첫 번째로 성립하는 경로"를 썼다 -
     * "소요시간만 비교"하는 자체 기준을 얹었다가 이상한 경로가 골라지는 문제가 있어서였다. 그런데
     * "가장 늦게 출발해도 되는 후보"는 소요시간 비교와는 다른 기준이고, 오히려 이 앱이 화면에서
     * 이미 약속하는 문구("이 시각까지 출발") 그대로다. 실사용 중 발견(2026-08-30): Google
     * 1순위 후보가 계산해보면 22:54인데, 뒤쪽 후보 중에는 23:37(구글맵이 보여주는 것과
     * 거의 같은 시각)짜리가 있는데도 첫 번째만 보고 넘어가고 있었다 - 그래서 다시 "가장 늦은
     * 후보"로 되돌린다.
     */
    private List<Best> bestOf(List<RouteLegExtractor.ExtractedRoute> pathCandidates, Integer targetArrivalMinutes,
                               LocalDate date) {
        LastDepartureResult.Feasible latest = null;
        int latestFareWon = 0;
        LastDepartureResult.Feasible shortest = null;
        int shortestFareWon = 0;
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
                if (latest == null || isLater(feasible, latest)) {
                    latest = feasible;
                    latestFareWon = route.fareWon();
                }
                if (shortest == null || totalMinutesOf(feasible) < totalMinutesOf(shortest)) {
                    shortest = feasible;
                    shortestFareWon = route.fareWon();
                }
            } else if (fallbackReason == null) {
                fallbackReason = ((LastDepartureResult.Infeasible) result).reason();
            }
        }

        if (latest == null) {
            return List.of(new Best(new LastDepartureResult.Infeasible(
                    fallbackReason != null ? fallbackReason : "가능한 경로를 찾지 못했습니다."), 0));
        }
        // 막차 모드는 항상 하나만(가장 늦은 막차) - "최적경로도 같이" 개념은 목표 도착시간
        // 모드에서만 의미가 있다(막차 모드는 그 자체가 이미 "가장 늦게"가 유일한 목적이다).
        if (targetArrivalMinutes == null || shortest.legs().equals(latest.legs())
                || !isDurationGapSignificant(shortest, latest)) {
            return List.of(new Best(latest, latestFareWon));
        }
        // 사용자 요청: "다른 최적경로랑 소요시간이 30% 이상 차이나면 최적경로랑 늦게 출발 경로
        // 다 띄워줘" - 최적경로(소요시간 최단)와 가장 늦게 출발하는 경로가 서로 다른 경로이고
        // 소요시간 차이가 크면, 어느 쪽이 나은지는 사용자가 고르게 둘 다 보여준다.
        return List.of(new Best(shortest, shortestFareWon), new Best(latest, latestFareWon));
    }

    /** 최적경로 대비 소요시간이 {@link #DURATION_SPLIT_RATIO} 이상 더 걸리면 "크게 차이난다"고 본다. */
    private boolean isDurationGapSignificant(LastDepartureResult.Feasible shortest,
                                              LastDepartureResult.Feasible latest) {
        int shortestMinutes = totalMinutesOf(shortest);
        if (shortestMinutes <= 0) {
            return false;
        }
        int latestMinutes = totalMinutesOf(latest);
        return (latestMinutes - shortestMinutes) / (double) shortestMinutes >= DURATION_SPLIT_RATIO;
    }

    private int totalMinutesOf(LastDepartureResult.Feasible feasible) {
        int legsMinutes = feasible.legs().stream()
                .mapToInt(leg -> leg.rideMinutes() + leg.transferBufferMinutes())
                .sum();
        return legsMinutes + feasible.finalWalkMinutes();
    }

    private boolean isLater(LastDepartureResult.Feasible candidate, LastDepartureResult.Feasible current) {
        return toServiceMinutes(candidate) > toServiceMinutes(current);
    }

    private int toServiceMinutes(LastDepartureResult.Feasible feasible) {
        int minutes = feasible.departureTime().getHour() * 60 + feasible.departureTime().getMinute();
        return feasible.nextDay() ? minutes + MINUTES_PER_DAY : minutes;
    }

    /**
     * @param minutes     서비스일(다이어그램 기준일) 자정을 0으로 하는 목표 시각(분). 새벽대
     *                    재해석이 적용되면 1440 이상이다.
     * @param diagramDate 실제로 시간표를 조회해야 할 날짜(다이어그램 기준일) - 항상 사용자가
     *                    고른 날짜와 같지는 않다({@link #resolveTargetArrivalMinutes} 참고).
     */
    private record ResolvedTarget(int minutes, LocalDate diagramDate) {
    }

    /**
     * 목표 도착 시각과, 실제 시간표 조회에 쓸 "다이어그램 기준일"을 함께 정한다. 지하철/버스
     * 심야 운행은 자정을 넘겨도 전날 시간표(다이어그램)로 취급되는 게 실제 운영 관행이라,
     * 새벽 시간대(EARLY_MORNING_CUTOFF 이전) 목표는 다이어그램 기준일이 리터럴 날짜보다
     * 하루 이를 수 있다.
     * <p>
     * "오늘"과 "다른 날짜"(미래)는 이 새벽대 재해석을 다르게 적용한다:
     * <ul>
     *   <li>오늘: 사용자가 날짜를 직접 고른 게 아니라 그냥 "지금" 기준이므로, 새벽 시각은
     *       "오늘 밤이 지나 내일 새벽"으로 읽는다 - 다이어그램 기준일은 오늘 그대로 두고
     *       시각만 1440을 더한다(기존부터 있던 동작).</li>
     *   <li>미래 날짜(달력에서 명시적으로 고름): "8/30 오전 1시"처럼 날짜·시각을 함께 명시적으로
     *       골랐으므로 리터럴 달력 시각으로 읽어야 한다 - 그 순간을 실제로 지배하는 시간표는
     *       하루 전날(8/29) 밤의 연장이므로, 다이어그램 기준일을 하루 앞당긴다(2026-08-30
     *       실사용 중 발견 - 이걸 안 해서 8/30 새벽 1시 목표가 일요일 시간표로 조회되고 있었다.
     *       실제로는 토요일 심야 시간표를 봐야 한다).
     * </ul>
     * "이미 지난 시각인지"도 오늘 날짜를 고른 경우에만 의미가 있다 - 미래 날짜는 지금이 몇 시든
     * 그 날짜의 그 시각이 아직 안 지났으므로 항상 허용한다(2026-08-30 실사용 중 발견 - 예전엔
     * date를 아예 안 받아서, 미래 날짜에 "오전 10시까지"를 물어도 지금(오후)보다 이르다는
     * 이유로 "이미 지났다"고 잘못 거절하고 있었다).
     */
    private Optional<ResolvedTarget> resolveTargetArrivalMinutes(LocalTime targetArrivalTime, LocalDate date) {
        int targetMinutes = targetArrivalTime.getHour() * 60 + targetArrivalTime.getMinute();
        LocalDate today = LocalDate.now();

        if (date.isAfter(today)) {
            if (targetMinutes < EARLY_MORNING_CUTOFF_MINUTES) {
                return Optional.of(new ResolvedTarget(targetMinutes + MINUTES_PER_DAY, date.minusDays(1)));
            }
            return Optional.of(new ResolvedTarget(targetMinutes, date));
        }
        if (date.isBefore(today)) {
            return Optional.empty(); // 과거 날짜 - UI에서 막고 있지만 방어적으로
        }

        int nowMinutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        if (targetMinutes >= nowMinutes) {
            return Optional.of(new ResolvedTarget(targetMinutes, date));
        }
        if (targetMinutes < EARLY_MORNING_CUTOFF_MINUTES) {
            return Optional.of(new ResolvedTarget(targetMinutes + MINUTES_PER_DAY, date)); // 오늘 밤 자정 넘어서 - 기준일은 그대로
        }
        return Optional.empty(); // 이미 지난 시각
    }

    /**
     * (date, 서비스일 기준 분)을 Google Routes가 요구하는 RFC3339 타임스탬프로 바꾼다.
     * serviceMinutes가 1440 이상이면(다음날 새벽) date+1일로 넘어간다.
     */
    private String toRfc3339(LocalDate date, int serviceMinutes) {
        int dayOffset = Math.floorDiv(serviceMinutes, MINUTES_PER_DAY);
        int minutesOfDay = Math.floorMod(serviceMinutes, MINUTES_PER_DAY);
        LocalDateTime local = date.plusDays(dayOffset).atStartOfDay().plusMinutes(minutesOfDay);
        return local.atZone(SEOUL_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
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

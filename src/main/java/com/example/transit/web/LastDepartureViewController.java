package com.example.transit.web;

import com.example.transit.service.LastDepartureResult;
import com.example.transit.service.LastDepartureService;
import com.example.transit.service.RouteOption;
import com.example.transit.service.StationResolution;
import com.example.transit.service.StationSearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalTime;
import java.util.List;

@Controller
public class LastDepartureViewController {

    private static final int EARLY_MORNING_CUTOFF_MINUTES = 6 * 60;
    private static final int MINUTES_PER_DAY = 24 * 60;

    private final LastDepartureService lastDepartureService;
    private final StationSearchService stationSearchService;

    public LastDepartureViewController(LastDepartureService lastDepartureService,
                                        StationSearchService stationSearchService) {
        this.lastDepartureService = lastDepartureService;
        this.stationSearchService = stationSearchService;
    }

    /**
     * 역 이름으로 출발지/도착지를 입력받는다. 이름이 여러 역과 매칭되면(환승역 등)
     * 후보 목록을 보여주고, 후보를 고르면 그 좌표(originX/Y, destX/Y)를 담아 다시 요청한다.
     * mode=arrival이면 막차 기준이 아니라 targetArrivalTime까지 도착하는 것을 기준으로 역산한다(이슈 #6).
     */
    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String originName,
            @RequestParam(required = false) String destName,
            @RequestParam(required = false) Double originX,
            @RequestParam(required = false) Double originY,
            @RequestParam(required = false) Double destX,
            @RequestParam(required = false) Double destY,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) LocalTime targetArrivalTime,
            Model model) {

        boolean arrivalMode = "arrival".equals(mode);
        model.addAttribute("arrivalMode", arrivalMode);
        model.addAttribute("originName", originName);
        model.addAttribute("destName", destName);
        model.addAttribute("targetArrivalTime", targetArrivalTime);

        if (!StringUtils.hasText(originName) || !StringUtils.hasText(destName)
                || (arrivalMode && targetArrivalTime == null)) {
            return "index";
        }

        StationResolution originResolution = (originX != null && originY != null)
                ? new StationResolution.Resolved(originX, originY, originName)
                : stationSearchService.resolve(originName);

        StationResolution destResolution = (destX != null && destY != null)
                ? new StationResolution.Resolved(destX, destY, destName)
                : stationSearchService.resolve(destName);

        StationResolution.Resolved origin = addResolutionToModel(model, "origin", originResolution);
        StationResolution.Resolved dest = addResolutionToModel(model, "dest", destResolution);

        if (origin == null || dest == null) {
            return "index";
        }

        model.addAttribute("searched", true);
        List<RouteOption> options =
                lastDepartureService.calculateOptions(origin.x(), origin.y(), dest.x(), dest.y(),
                        arrivalMode ? targetArrivalTime : null);

        if (options.isEmpty()) {
            // 세 가지 모두 실패한 경우, 이유를 보여주기 위해 지하철 기준 결과의 사유를 쓴다.
            LastDepartureResult fallback = lastDepartureService.calculateSingle(
                    origin.x(), origin.y(), dest.x(), dest.y(), arrivalMode ? targetArrivalTime : null);
            model.addAttribute("feasible", false);
            model.addAttribute("reason", fallback instanceof LastDepartureResult.Infeasible i
                    ? displayReason(i, arrivalMode)
                    : "가능한 경로를 찾지 못했습니다.");
            return "index";
        }

        model.addAttribute("feasible", true);
        model.addAttribute("routeOptions", options.stream().map(this::toView).toList());
        // "가장 늦게 출발해도 되는 경로"에 배지를 달기 위한 기준값 (동점이면 둘 다 표시된다).
        model.addAttribute("latestDepartureMinutes", options.stream()
                .mapToInt(RouteOption::departureServiceMinutes)
                .max().orElse(-1));
        return "index";
    }

    /**
     * 화면에서 쓰기 쉬운 형태로 펼친다 (예상 도착 시각, 이미 지난 시각 여부처럼 "지금"에 의존하는
     * 값은 계산 결과가 아니라 표시 시점의 관심사라 여기서 만든다).
     */
    private RouteOptionView toView(RouteOption option) {
        boolean alreadyPassed = hasAlreadyPassed(option);
        LocalTime earliestArrival = alreadyPassed
                ? LocalTime.now().plusMinutes(option.totalMinutes()) : null;

        int arrivalMinutes = option.departureServiceMinutes() + option.totalMinutes();
        boolean arrivalNextDay = arrivalMinutes >= MINUTES_PER_DAY;
        int normalizedArrival = arrivalMinutes % MINUTES_PER_DAY;

        return new RouteOptionView(option, alreadyPassed, earliestArrival,
                LocalTime.of(normalizedArrival / 60, normalizedArrival % 60), arrivalNextDay);
    }

    /**
     * @param option              계산 결과
     * @param departureAlreadyPassed 추천 출발 시각이 이미 지났는지
     * @param earliestArrivalTime 지금 출발할 경우 가장 빨리 도착하는 시각 (지난 경우에만)
     * @param expectedArrivalTime 추천 출발 시각에 나설 경우의 도착 시각
     */
    public record RouteOptionView(RouteOption option, boolean departureAlreadyPassed,
                                   LocalTime earliestArrivalTime, LocalTime expectedArrivalTime,
                                   boolean expectedArrivalNextDay) {
    }

    /**
     * 목표 도착시간(arrivalMode) 계산이 Infeasible인 이유는 대부분 "그 시각까지 갈 방법이
     * 없다"는 것 하나로 요약된다 - LastDepartureCalculator/Service가 내부적으로 어느 구간에서
     * 막혔는지 담은 원인 문구는 사용자에게는 불필요하게 기술적이라 통일된 안내로 바꾼다.
     * 단, "목표 도착 시각이 이미 지난 시각" 케이스는 원인이 전혀 다르므로(사용자가 이미 지난
     * 시각을 입력함) 그 메시지는 그대로 둔다.
     */
    private String displayReason(LastDepartureResult.Infeasible infeasible, boolean arrivalMode) {
        boolean targetAlreadyPast = infeasible.reason() != null && infeasible.reason().contains("이미 지난 시각");
        if (arrivalMode && !targetAlreadyPast) {
            return "해당 목적지까지 대중교통 운행이 종료되어 안내가 불가능합니다.";
        }
        return infeasible.reason();
    }

    /**
     * 추천 출발 시각이 이미 지난 시각인지 본다 (예: 막차 계산 결과가 23:30인데 확인하는 시점이
     * 이미 23:50인 경우). 새벽 시간대는 "오늘 자정 넘어서"로 이어지는 서비스일 개념이라
     * targetArrivalTime 처리와 같은 방식(새벽 6시 컷오프)으로 "지금"도 확장해서 비교한다.
     */
    private boolean hasAlreadyPassed(RouteOption option) {
        LocalTime now = LocalTime.now();
        int nowMinutes = now.getHour() * 60 + now.getMinute();
        int serviceNowMinutes = nowMinutes < EARLY_MORNING_CUTOFF_MINUTES ? nowMinutes + MINUTES_PER_DAY : nowMinutes;

        return option.departureServiceMinutes() < serviceNowMinutes;
    }

    /**
     * StationResolution을 화면에서 다루기 쉬운 평평한 속성들로 변환해 모델에 담는다.
     * Thymeleaf에서 sealed interface를 instanceof로 분기하는 대신 문자열 상태값으로 처리하기 위함.
     *
     * @return Resolved인 경우 그 값, 아니면 null
     */
    private StationResolution.Resolved addResolutionToModel(Model model, String prefix, StationResolution resolution) {
        switch (resolution) {
            case StationResolution.Resolved r -> {
                model.addAttribute(prefix + "Status", "RESOLVED");
                model.addAttribute(prefix + "DisplayName", r.displayName());
                model.addAttribute(prefix + "X", r.x());
                model.addAttribute(prefix + "Y", r.y());
                return r;
            }
            case StationResolution.Ambiguous a -> {
                model.addAttribute(prefix + "Status", "AMBIGUOUS");
                model.addAttribute(prefix + "Candidates", a.candidates());
                return null;
            }
            case StationResolution.NotFound n -> {
                model.addAttribute(prefix + "Status", "NOT_FOUND");
                return null;
            }
        }
    }
}

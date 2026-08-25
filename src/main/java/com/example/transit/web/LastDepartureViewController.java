package com.example.transit.web;

import com.example.transit.domain.DayType;
import com.example.transit.domain.KoreanHolidays;
import com.example.transit.service.LastDepartureResult;
import com.example.transit.service.LastDepartureService;
import com.example.transit.service.RealtimeBusArrival;
import com.example.transit.service.RealtimeSubwayArrivalLookup;
import com.example.transit.service.RegionalBusArrivalLookup;
import com.example.transit.service.RouteOption;
import com.example.transit.service.SeoulBusRouteScheduleCatalog;
import com.example.transit.service.StationResolution;
import com.example.transit.service.StationSearchService;
import com.example.transit.service.TaxiFareEstimator;
import com.example.transit.service.TransitLeg;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class LastDepartureViewController {

    private static final int EARLY_MORNING_CUTOFF_MINUTES = 6 * 60;
    private static final int MINUTES_PER_DAY = 24 * 60;
    /** 버스 구간 색 (노선별 색이 있는 지하철과 달리 버스는 한 가지 색으로 통일). */
    private static final String BUS_COLOR = "#3C8A3F";
    /** 위경도 상 약 30m 이내면 "같은 지점을 두 번 고른 것"으로 본다. */
    private static final double SAME_POINT_EPSILON_DEGREES = 0.0003;

    private final LastDepartureService lastDepartureService;
    private final StationSearchService stationSearchService;
    private final LineColorResolver lineColorResolver;
    private final RealtimeSubwayArrivalLookup subwayArrivalLookup;
    private final RegionalBusArrivalLookup busArrivalLookup;
    private final TaxiFareEstimator taxiFareEstimator;
    private final SeoulBusRouteScheduleCatalog seoulBusRouteScheduleCatalog;

    public LastDepartureViewController(LastDepartureService lastDepartureService,
                                        StationSearchService stationSearchService,
                                        LineColorResolver lineColorResolver,
                                        RealtimeSubwayArrivalLookup subwayArrivalLookup,
                                        RegionalBusArrivalLookup busArrivalLookup,
                                        TaxiFareEstimator taxiFareEstimator,
                                        SeoulBusRouteScheduleCatalog seoulBusRouteScheduleCatalog) {
        this.lastDepartureService = lastDepartureService;
        this.stationSearchService = stationSearchService;
        this.lineColorResolver = lineColorResolver;
        this.subwayArrivalLookup = subwayArrivalLookup;
        this.busArrivalLookup = busArrivalLookup;
        this.taxiFareEstimator = taxiFareEstimator;
        this.seoulBusRouteScheduleCatalog = seoulBusRouteScheduleCatalog;
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
            @RequestParam(required = false) String originPoint,
            @RequestParam(required = false) String destPoint,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            Model model) {

        boolean arrivalMode = "arrival".equals(mode);
        LocalDate selectedDate = resolveSelectedDate(targetDate);
        model.addAttribute("arrivalMode", arrivalMode);
        model.addAttribute("originName", originName);
        model.addAttribute("destName", destName);
        model.addAttribute("targetArrivalTime", targetArrivalTime);
        addCalendarToModel(model, selectedDate);

        if (!StringUtils.hasText(originName) || !StringUtils.hasText(destName)
                || (arrivalMode && targetArrivalTime == null)) {
            return "index";
        }

        // 후보 선택 폼은 라디오 하나로 좌표 둘을 넘겨야 해서 "x,y" 한 값으로 온다.
        Point originSelected = Point.parse(originPoint);
        Point destSelected = Point.parse(destPoint);
        if (originSelected != null) {
            originX = originSelected.x();
            originY = originSelected.y();
        }
        if (destSelected != null) {
            destX = destSelected.x();
            destY = destSelected.y();
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

        if (isSamePoint(origin, dest)) {
            // 이 상태로 검색을 진행하면 ODsay가 "출,도착지가 700m이내입니다" 에러를 주고
            // 그게 그대로 "대중교통 운행이 종료되어 안내가 불가능합니다"로 오해를 사는
            // 문구로 이어진다 - 원인이 전혀 다르므로(같은 지점을 골랐을 뿐) 더 명확하게 안내한다.
            model.addAttribute("feasible", false);
            model.addAttribute("reason", "출발지와 도착지가 같습니다. 다른 목적지를 입력해주세요.");
            addTaxiEstimate(model, origin, dest, arrivalMode ? targetArrivalTime : LocalTime.now());
            return "index";
        }

        List<RouteOption> options =
                lastDepartureService.calculateOptions(origin.x(), origin.y(), dest.x(), dest.y(),
                        arrivalMode ? targetArrivalTime : null, selectedDate);

        addTaxiEstimate(model, origin, dest, taxiReferenceTime(arrivalMode, targetArrivalTime, options));

        if (options.isEmpty()) {
            // 세 가지 모두 실패한 경우, 이유를 보여주기 위해 지하철 기준 결과의 사유를 쓴다.
            LastDepartureResult fallback = lastDepartureService.calculate(
                    origin.x(), origin.y(), dest.x(), dest.y(),
                    arrivalMode ? targetArrivalTime : null, selectedDate);
            model.addAttribute("feasible", false);
            if (fallback instanceof LastDepartureResult.Infeasible infeasible && infeasible.walkOnlyMinutes() != null) {
                // 대중교통을 탈 필요가 없을 만큼 가까운 거리 - "운행 종료" 같은 엉뚱한 안내
                // 대신 도보 소요시간을 그대로 보여준다.
                model.addAttribute("walkOnlyMinutes", infeasible.walkOnlyMinutes());
            } else {
                model.addAttribute("reason", fallback instanceof LastDepartureResult.Infeasible i
                        ? displayReason(i, arrivalMode)
                        : "가능한 경로를 찾지 못했습니다.");
            }
            return "index";
        }

        // 실시간 도착정보는 "지금 이 순간"의 값이라 오늘 화면에서만 의미가 있다 (다른날 막차 보기는
        // 미래 시간표 기준 계산이라 실시간 값을 붙이면 오해를 준다).
        boolean showRealtimeArrivals = selectedDate.equals(LocalDate.now());
        model.addAttribute("feasible", true);
        model.addAttribute("routeOptions",
                options.stream().map(o -> toView(o, showRealtimeArrivals, selectedDate)).toList());
        // "가장 늦게 출발해도 되는 경로"에 배지를 달기 위한 기준값 (동점이면 둘 다 표시된다).
        model.addAttribute("latestDepartureMinutes", options.stream()
                .mapToInt(RouteOption::departureServiceMinutes)
                .max().orElse(-1));
        return "index";
    }

    /**
     * 택시 안내는 검색과 추천 경로 사이에 항상 보여준다 - 대중교통 결과가 어떻든(성공/실패
     * 무관) 택시와 바로 비교해볼 수 있게.
     */
    private void addTaxiEstimate(Model model, StationResolution.Resolved origin,
                                  StationResolution.Resolved dest, LocalTime referenceTime) {
        model.addAttribute("taxiFareEstimate",
                taxiFareEstimator.estimate(origin.x(), origin.y(), dest.x(), dest.y(), referenceTime));
    }

    /**
     * 할증·혼잡도는 "지금"이 아니라 실제로 그 이동을 하는 시각 기준이어야 한다.
     * <ul>
     *   <li>출발 시간 계산 탭: 목표 도착 시각</li>
     *   <li>막차 탭: 추천 경로의 막차 출발 시각 - 막차는 대개 밤 늦은 시각이라 지금(예: 낮)
     *       기준으로 계산하면 할증도 안 붙고 혼잡도도 딴판이 된다.</li>
     * </ul>
     * 어느 쪽도 정할 수 없으면(경로를 못 찾은 경우) 지금을 쓴다.
     */
    private LocalTime taxiReferenceTime(boolean arrivalMode, LocalTime targetArrivalTime,
                                         List<RouteOption> options) {
        if (arrivalMode && targetArrivalTime != null) {
            return targetArrivalTime;
        }
        return options.isEmpty() ? LocalTime.now() : options.get(0).departureTime();
    }

    private boolean isSamePoint(StationResolution.Resolved a, StationResolution.Resolved b) {
        return Math.abs(a.x() - b.x()) < SAME_POINT_EPSILON_DEGREES
                && Math.abs(a.y() - b.y()) < SAME_POINT_EPSILON_DEGREES;
    }

    /**
     * 선택 가능한 날짜는 "오늘부터 이번 달 말일까지"로 제한한다. 그 밖의 값(과거 날짜, 다음 달,
     * 형식 오류)은 조용히 오늘로 되돌린다 — 쿼리스트링은 사용자가 직접 고칠 수 있어서
     * 화면에서 못 고르게 막는 것만으로는 부족하다.
     */
    private LocalDate resolveSelectedDate(LocalDate requested) {
        LocalDate today = LocalDate.now();
        if (requested == null || requested.isBefore(today) || requested.isAfter(lastDayOfMonth(today))) {
            return today;
        }
        return requested;
    }

    private LocalDate lastDayOfMonth(LocalDate date) {
        return date.withDayOfMonth(date.lengthOfMonth());
    }

    /**
     * 이번 달 달력을 통째로 모델에 담는다. 공휴일 판별이 서버에만 있으므로(음력 기반 명절 때문에
     * 표로 관리) 날짜 칸을 서버에서 만들어 내려주고, 화면 JS는 열고 닫기만 담당한다.
     */
    private void addCalendarToModel(Model model, LocalDate selectedDate) {
        LocalDate today = LocalDate.now();
        LocalDate firstDay = today.withDayOfMonth(1);
        LocalDate lastDay = lastDayOfMonth(today);

        List<CalendarDay> days = new ArrayList<>();
        // 1일이 무슨 요일인지에 맞춰 앞을 빈 칸으로 채운다 (월요일 시작 기준).
        int leadingBlanks = firstDay.getDayOfWeek().getValue() - 1;
        for (int i = 0; i < leadingBlanks; i++) {
            days.add(CalendarDay.blank());
        }
        for (LocalDate date = firstDay; !date.isAfter(lastDay); date = date.plusDays(1)) {
            DayType dayType = DayType.from(date);
            days.add(new CalendarDay(date, date.getDayOfMonth(), !date.isBefore(today),
                    date.equals(selectedDate), dayType, DayType.displayLabel(date)));
        }

        model.addAttribute("calendarDays", days);
        model.addAttribute("calendarMonth", today.getMonthValue());
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("selectedDateIsToday", selectedDate.equals(today));
        model.addAttribute("selectedDayTypeLabel", DayType.displayLabel(selectedDate));
        model.addAttribute("holidayDataMissing", !KoreanHolidays.isYearCovered(selectedDate));
    }

    /**
     * 달력 한 칸. date가 null이면 1일 앞을 채우는 빈 칸이다.
     *
     * @param selectable 오늘 이후라서 고를 수 있는지 (지난 날짜는 막차를 계산할 의미가 없다)
     */
    public record CalendarDay(LocalDate date, int dayOfMonth, boolean selectable, boolean selected,
                               DayType dayType, String dayTypeLabel) {

        static CalendarDay blank() {
            return new CalendarDay(null, 0, false, false, null, null);
        }

        public boolean isBlank() {
            return date == null;
        }

        public boolean isHoliday() {
            return dayType == DayType.HOLIDAY;
        }

        public boolean isSaturday() {
            return dayType == DayType.SATURDAY;
        }
    }

    /**
     * 화면에서 쓰기 쉬운 형태로 펼친다 (예상 도착 시각, 이미 지난 시각 여부처럼 "지금"에 의존하는
     * 값은 계산 결과가 아니라 표시 시점의 관심사라 여기서 만든다).
     */
    private RouteOptionView toView(RouteOption option, boolean showRealtimeArrivals, LocalDate selectedDate) {
        // 버스 구간의 "예상 대기"(실시간 또는 배차간격/2 추정, estimatedWaitMinutes 참고)를 총
        // 소요시간/도착시각/구간 막대에도 반영해야 앞뒤가 맞는다 - 상세 타임라인에만 대기를
        // 보여주고 이 숫자들은 그대로 두면, "3+13+10=26분"인데 타임라인엔 "대기 13분"까지 있어
        // 산수가 안 맞는 것처럼 보인다는 신고를 받았다(실사용 중 발견). 막차로 잡는 특정
        // 차편(추천 출발 시각)은 그대로 시간표 기준이라 안전성엔 영향 없다 - 그 차편을 탄 뒤의
        // "체감 소요시간"을 더 정확하게 보여주는 것뿐이다.
        List<LegDisplay> legDisplays = legDisplaysOf(option, showRealtimeArrivals);
        int waitMinutesTotal = legDisplays.stream().mapToInt(LegDisplay::waitMinutes).sum();
        int totalMinutes = option.totalMinutes() + waitMinutesTotal;

        boolean alreadyPassed = hasAlreadyPassed(option, selectedDate);
        LocalTime earliestArrival = alreadyPassed
                ? LocalTime.now().plusMinutes(totalMinutes) : null;

        // 추천 출발 시각이 이미 지났으면(alreadyPassed) 총 소요시간 옆에 보여줄 도착 시각도
        // "지금 출발" 기준(earliestArrival)과 같은 값을 써야 한다 - 예전엔 여기서만 지난
        // 추천 출발 시각 기준으로 다시 계산해서, 헤드라인(예: 03:39)과 바로 아래 도착 시각
        // (예: 18:29)이 서로 다른 시나리오를 섞어서 보여주는 바람에 화면이 모순돼 보였다.
        int arrivalMinutes = alreadyPassed
                ? nowServiceMinutes() + totalMinutes
                : option.departureServiceMinutes() + totalMinutes;

        return new RouteOptionView(option, alreadyPassed, earliestArrival,
                toLocalTime(arrivalMinutes), arrivalMinutes >= MINUTES_PER_DAY, totalMinutes, waitMinutesTotal,
                segmentsOf(option), timelineOf(option, legDisplays));
    }

    /** 버스 구간별 실시간 도착정보 + 예상 대기시간을 한 번만 계산해 타임라인/구간막대/총소요시간이 같은 값을 쓰게 한다. */
    private record LegDisplay(List<RealtimeArrivalView> arrivals, int waitMinutes, boolean waitFromRealtime) {
    }

    private List<LegDisplay> legDisplaysOf(RouteOption option, boolean showRealtimeArrivals) {
        List<LegDisplay> result = new ArrayList<>();
        for (TransitLeg leg : option.legs()) {
            List<RealtimeArrivalView> arrivals = realtimeArrivalsFor(leg, showRealtimeArrivals);
            if (!leg.isBus()) {
                result.add(new LegDisplay(arrivals, 0, false));
                continue;
            }
            Optional<RealtimeArrivalView> soonest = arrivals.stream().filter(v -> !v.isStatusOnly()).findFirst();
            if (soonest.isPresent()) {
                int minutes = Math.max(1, (soonest.get().secondsUntilArrival() + 59) / 60);
                result.add(new LegDisplay(arrivals, minutes, true));
                continue;
            }
            int estimated = seoulBusRouteScheduleCatalog.find(leg.busNo())
                    .map(schedule -> Math.max(1, (schedule.intervalMinutes() + 1) / 2))
                    .orElse(0);
            result.add(new LegDisplay(arrivals, estimated, false));
        }
        return result;
    }

    private int nowServiceMinutes() {
        LocalTime now = LocalTime.now();
        return now.getHour() * 60 + now.getMinute();
    }

    /** 모든 승차 구간(지하철/버스)에 실시간 도착정보를 붙인다. */
    private List<RealtimeArrivalView> realtimeArrivalsFor(TransitLeg leg, boolean showRealtimeArrivals) {
        if (!showRealtimeArrivals) {
            return List.of();
        }
        return leg.isBus() ? busRealtimeArrivals(leg) : subwayRealtimeArrivals(leg);
    }

    /**
     * ODsay의 wayCode(1:상행, 2:하행)와 서울시 API의 방향 표기("상행"/"하행")가 일치하는 대부분의
     * 일반 노선에서는 이걸로 실제 타는 방향을 가려낼 수 있다. 2호선처럼 "내선/외선"으로 표기되는
     * 순환선 등 표기 체계가 다른 노선은 아예 안 걸려서(매칭 결과가 없어서) 방향 전체를 보여주는
     * 쪽으로 자연히 폴백된다 - subwayRealtimeArrivals 참고.
     */
    private static final Map<Integer, String> DIRECTION_TEXT_BY_WAY_CODE = Map.of(1, "상행", 2, "하행");

    /**
     * 환승역은 서울시 API 응답 하나에 여러 호선 열차가 섞여 온다(실측 확인: 충무로역이면
     * 3호선/4호선 열차가 같이 옴) - 이 구간이 타는 노선(leg.laneName())과 다른 호선 열차를
     * 섞어서 보여주면 승객이 헷갈린다. 노선을 먼저 걸러낸다.
     * <p>
     * 노선 안에서도 방향(상행/하행)이 갈리는데, wayCode 기준으로 실제 타는 방향만 골라지면
     * 그것만 쓰고, 하나도 안 걸리면(순환선처럼 표기 체계가 다르거나 그 방향에 마침 실시간
     * 열차가 없는 경우) 방향을 가려낼 수 없다고 보고 기존처럼 노선 전체를 보여준다 - 정보가
     * 아예 안 뜨는 것보단 낫다. 어느 쪽이든 방향마다 가장 빠른 2대까지만 골라 실제 승강장
     * 전광판처럼 행선지를 그대로 보여준다.
     */
    private List<RealtimeArrivalView> subwayRealtimeArrivals(TransitLeg leg) {
        String legLineName = lineColorResolver.shortNameOf(leg.laneName());
        List<RealtimeSubwayArrivalLookup.SubwayArrival> sameLine =
                subwayArrivalLookup.findArrivals(leg.stationName()).stream()
                        .filter(arrival -> arrival.secondsUntilArrival() != null)
                        .filter(arrival -> legLineName.equals(arrival.lineName()))
                        .toList();

        String expectedDirection = DIRECTION_TEXT_BY_WAY_CODE.get(leg.wayCode());
        List<RealtimeSubwayArrivalLookup.SubwayArrival> sameDirection = expectedDirection == null
                ? List.of()
                : sameLine.stream().filter(arrival -> expectedDirection.equals(arrival.direction())).toList();
        List<RealtimeSubwayArrivalLookup.SubwayArrival> candidates = sameDirection.isEmpty() ? sameLine : sameDirection;

        Map<String, List<RealtimeSubwayArrivalLookup.SubwayArrival>> byDirection =
                candidates.stream().collect(Collectors.groupingBy(RealtimeSubwayArrivalLookup.SubwayArrival::direction));

        return byDirection.values().stream()
                .peek(arrivals -> arrivals.sort(
                        Comparator.comparing(RealtimeSubwayArrivalLookup.SubwayArrival::secondsUntilArrival)))
                .sorted(Comparator.comparingInt(arrivals -> arrivals.get(0).secondsUntilArrival()))
                .flatMap(arrivals -> arrivals.stream().limit(2))
                .map(this::toRealtimeArrivalView)
                .toList();
    }

    /**
     * 정류장 좌표로 TAGO/경기/인천 순서로 조회한다({@link RegionalBusArrivalLookup} 참고). 지하철과
     * 달리 정류장 하나에 수십 개 노선이 같이 잡히는 게 흔하고(간선/지선/광역/공항버스 등이 한
     * 정류장에 몰림), 그중 환승역처럼 "어느 노선인지 모호"한 경우가 아니라 이 구간에서 탈 노선이
     * 이미 정해져 있으므로(leg.busNo()) 그 노선만 걸러서 보여준다. 관계없는 노선을 다 보여주면
     * 실사용 검증 중 확인한 것처럼 화면이 수십 줄로 길어져서 오히려 못 쓰게 된다.
     */
    private List<RealtimeArrivalView> busRealtimeArrivals(TransitLeg leg) {
        if (leg.stationX() == null || leg.stationY() == null || leg.busNo() == null) {
            return List.of();
        }

        List<RealtimeBusArrival> mine = busArrivalLookup.findArrivals(leg.stationX(), leg.stationY())
                .stream()
                .filter(arrival -> leg.busNo().equals(arrival.routeName()))
                .toList();

        // 오고 있는 버스가 있으면 그것만 (가까운 순 2대까지) 보여준다. 하나도 없으면 왜 없는지를
        // (운행 종료인지 출발대기인지) 알려주는 게 아무것도 안 띄우는 것보다 낫다 - 실사용에서
        // "실시간이 왜 안 뜨지?"로 오해를 샀던 부분이다.
        List<RealtimeArrivalView> arriving = mine.stream()
                .filter(RealtimeBusArrival::isArriving)
                .sorted(Comparator.comparing(RealtimeBusArrival::secondsUntilArrival))
                .limit(2)
                .map(this::toRealtimeArrivalView)
                .toList();
        if (!arriving.isEmpty()) {
            return arriving;
        }
        return mine.stream()
                .filter(arrival -> arrival.statusLabel() != null)
                .limit(1)
                .map(this::toRealtimeArrivalView)
                .toList();
    }

    /** 1분 미만이면 "곧 도착", 그 외엔 "분:초" - 화면 JS가 이 초를 이어받아 매초 카운트다운한다. */
    private RealtimeArrivalView toRealtimeArrivalView(RealtimeSubwayArrivalLookup.SubwayArrival arrival) {
        int seconds = arrival.secondsUntilArrival();
        return new RealtimeArrivalView(arrival.headsign(), etaLabel(seconds), seconds, arrival.isLastTrain());
    }

    private RealtimeArrivalView toRealtimeArrivalView(RealtimeBusArrival arrival) {
        String label = arrival.routeName() != null ? arrival.routeName() + "번" : "버스";
        if (!arrival.isArriving()) {
            // 카운트다운할 값이 없으므로 secondsUntilArrival을 null로 둔다 - 화면 JS는 이 값이
            // 있는 칩만 매초 줄이므로, 상태 문구는 그대로 남는다.
            return new RealtimeArrivalView(label, arrival.statusLabel(), null, false);
        }
        int seconds = arrival.secondsUntilArrival();
        return new RealtimeArrivalView(label, etaLabel(seconds), seconds, false);
    }

    private String etaLabel(int seconds) {
        if (seconds < 60) {
            return "곧 도착";
        }
        return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
    }

    /**
     * 소요시간 비율 막대에 쓸 구간들 (도보는 회색, 승차는 노선 색). 예상 대기시간은 여기 막대에는
     * 안 넣는다 - 도보/승차와 나란히 막대 구간으로 두면 "걷고 타는 과정"처럼 보여 오해를 줄 수
     * 있다는 피드백을 받아, 대신 총 소요시간 옆에 "(대기시간 N분 포함)"으로만 안내한다
     * (toView/RouteOptionView.waitMinutesTotal 참고). 상세 타임라인에는 여전히 별도 행으로 보여준다.
     */
    private List<RouteSegmentView> segmentsOf(RouteOption option) {
        List<RouteSegmentView> segments = new ArrayList<>();
        for (TransitLeg leg : option.legs()) {
            if (leg.transferBufferMinutes() > 0) {
                segments.add(new RouteSegmentView(leg.transferBufferMinutes(), null, true));
            }
            segments.add(new RouteSegmentView(leg.rideMinutes(), colorOf(leg), false));
        }
        if (option.finalWalkMinutes() > 0) {
            segments.add(new RouteSegmentView(option.finalWalkMinutes(), null, true));
        }
        return segments;
    }

    /**
     * 상세보기용 타임라인. 출발 시각에서 시작해 도보/대기/승차/하차 시간을 차례로 더해가며
     * 각 지점의 시각을 만든다 (지도 앱의 세로 경로 안내와 같은 구성).
     * 실시간 도착정보는 지하철·버스 승차 행마다 붙인다.
     * <p>
     * 버스 구간의 승차 시각은 막차 역산 특성상 "그 차편 출발 시각에 정확히 맞춰 걷는다"고
     * 가정해서(즉시 승차, 대기 0분) 계산된다({@link LastDepartureCalculator} 참고) - 실제로는
     * 정류장에서 기다리는 시간이 있다(실사용 중 발견: 화면상 0분으로 보여 혼란을 줌). 그래서
     * 예상 대기시간만큼 실제로 cursor를 밀어서 승차 이후 모든 시각·총 소요시간에 반영한다 -
     * 막차로 잡는 특정 차편(추천 출발 시각) 자체는 여전히 시간표 기준이라 안전성엔 영향 없고,
     * 그 뒤 "체감 소요시간"만 더 정확해진다.
     */
    private List<RouteTimelineRow> timelineOf(RouteOption option, List<LegDisplay> legDisplays) {
        List<RouteTimelineRow> rows = new ArrayList<>();
        int cursor = option.departureServiceMinutes();
        List<TransitLeg> legs = option.legs();

        rows.add(RouteTimelineRow.place(toLocalTime(cursor), "출발"));
        for (int i = 0; i < legs.size(); i++) {
            TransitLeg leg = legs.get(i);
            LegDisplay display = legDisplays.get(i);
            if (leg.transferBufferMinutes() > 0) {
                rows.add(RouteTimelineRow.walk(leg.transferBufferMinutes()));
                cursor += leg.transferBufferMinutes();
            }
            if (display.waitMinutes() > 0) {
                rows.add(RouteTimelineRow.wait(display.waitMinutes(), display.waitFromRealtime()));
                cursor += display.waitMinutes();
            }
            LocalTime boardTime = toLocalTime(cursor);
            cursor += leg.rideMinutes();
            rows.add(new RouteTimelineRow("RIDE", boardTime, toLocalTime(cursor), leg.rideMinutes(),
                    leg.stationName(), leg.endStationName(), lineLabelOf(leg), colorOf(leg), display.arrivals()));
        }
        if (option.finalWalkMinutes() > 0) {
            rows.add(RouteTimelineRow.walk(option.finalWalkMinutes()));
            cursor += option.finalWalkMinutes();
        }
        rows.add(RouteTimelineRow.place(toLocalTime(cursor), "도착"));
        return rows;
    }

    private String lineLabelOf(TransitLeg leg) {
        if (leg.isBus()) {
            return leg.busNo() != null ? leg.busNo() + "번" : "버스";
        }
        return lineColorResolver.shortNameOf(leg.laneName());
    }

    private String colorOf(TransitLeg leg) {
        return leg.isBus() ? BUS_COLOR : lineColorResolver.colorOf(leg.laneName());
    }

    private LocalTime toLocalTime(int serviceMinutes) {
        int normalized = Math.floorMod(serviceMinutes, MINUTES_PER_DAY);
        return LocalTime.of(normalized / 60, normalized % 60);
    }

    /**
     * @param option              계산 결과
     * @param departureAlreadyPassed 추천 출발 시각이 이미 지났는지
     * @param earliestArrivalTime 지금 출발할 경우 가장 빨리 도착하는 시각 (지난 경우에만)
     * @param expectedArrivalTime 추천 출발 시각에 나설 경우의 도착 시각
     * @param totalMinutes        화면에 보여줄 총 소요시간. option.totalMinutes()(시간표 기준)에
     *                            버스 구간 예상 대기시간을 더한 값이라 option 쪽 값과 다를 수 있다 -
     *                            반드시 이 값을 써야 상세 타임라인의 "예상 대기" 줄과 산수가 맞는다.
     * @param waitMinutesTotal    totalMinutes에 포함된 예상 대기시간 합계. 0이면 "(대기시간 포함)"
     *                            안내를 안 보여준다.
     * @param segments            소요시간 비율 막대용 구간들
     * @param timeline            상세보기용 타임라인. 첫 승차 행에 실시간 도착정보가 붙어있다.
     */
    public record RouteOptionView(RouteOption option, boolean departureAlreadyPassed,
                                   LocalTime earliestArrivalTime, LocalTime expectedArrivalTime,
                                   boolean expectedArrivalNextDay, int totalMinutes, int waitMinutesTotal,
                                   List<RouteSegmentView> segments, List<RouteTimelineRow> timeline) {
    }

    /** @param color null이면 도보 구간 */
    public record RouteSegmentView(int minutes, String color, boolean walk) {
    }

    /**
     * @param headsign            "OO행 - OO방면" 형태의 종착지 설명 (실제 승강장 전광판과 같은 표기라
     *                            사용자가 자기 방향을 스스로 알아볼 수 있다)
     * @param etaLabel            초기 렌더링용 "분:초" 또는 "곧 도착" - 이후 화면 JS가 secondsUntilArrival을
     *                            이어받아 매초 직접 카운트다운하며 이 텍스트를 갱신한다. 오고 있는 차가
     *                            없는 버스에서는 대신 상태 문구가 들어간다("운행 종료 · 막차 18:49" 등).
     * @param secondsUntilArrival 도착까지 남은 시간(초). 화면 JS 카운트다운의 시작값이며, 카운트다운할
     *                            대상이 아니면(상태 문구) null이다 - 화면 JS는 이 값이 있는 칩만 줄인다.
     * @param isLastTrain         이 열차가 막차인지
     */
    public record RealtimeArrivalView(String headsign, String etaLabel, Integer secondsUntilArrival,
                                       boolean isLastTrain) {

        /** 카운트다운이 아니라 상태만 알려주는 칩인지 (화면에서 다르게 스타일링한다). */
        public boolean isStatusOnly() {
            return secondsUntilArrival == null;
        }
    }

    /** 후보 선택 라디오가 넘기는 "경도,위도". 형식이 어긋나면 무시하고 이름으로 다시 찾는다. */
    private record Point(double x, double y) {

        static Point parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                return null;
            }
            try {
                return new Point(Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /**
     * @param type              PLACE(출발/도착) | WALK(도보) | WAIT(버스 예상 대기, 참고용) | RIDE(승차~하차)
     * @param time              PLACE는 그 지점 시각, RIDE는 승차 시각
     * @param endTime           RIDE의 하차 시각
     * @param label             PLACE의 "출발"/"도착", WAIT은 실시간 기반이면 "실시간", 배차간격
     *                          추정이면 "예상"
     * @param realtimeArrivals  RIDE 중 첫 승차 행에만 붙는 실시간 지하철 도착정보 (그 외에는 빈 목록)
     */
    public record RouteTimelineRow(String type, LocalTime time, LocalTime endTime, int minutes,
                                    String fromName, String toName, String lineLabel, String color,
                                    List<RealtimeArrivalView> realtimeArrivals) {

        static RouteTimelineRow place(LocalTime time, String label) {
            return new RouteTimelineRow("PLACE", time, null, 0, label, null, null, null, List.of());
        }

        static RouteTimelineRow walk(int minutes) {
            return new RouteTimelineRow("WALK", null, null, minutes, null, null, null, null, List.of());
        }

        /** @param fromRealtime true면 실시간 도착정보 기준, false면 배차간격 절반 추정치 */
        static RouteTimelineRow wait(int minutes, boolean fromRealtime) {
            return new RouteTimelineRow("WAIT", null, null, minutes,
                    fromRealtime ? "실시간" : "예상", null, null, null, List.of());
        }
    }

    /**
     * 목표 도착시간(arrivalMode) 계산이 Infeasible인 이유는 대부분 "그 시각까지 갈 방법이
     * 없다"는 것 하나로 요약된다 - LastDepartureCalculator/Service가 내부적으로 어느 구간에서
     * 막혔는지 담은 원인 문구는 사용자에게는 불필요하게 기술적이라 통일된 안내로 바꾼다.
     * 단, "목표 도착 시각이 이미 지난 시각" 케이스는 원인이 전혀 다르므로(사용자가 이미 지난
     * 시각을 입력함) 그 메시지는 그대로 둔다.
     */
    private String displayReason(LastDepartureResult.Infeasible infeasible, boolean arrivalMode) {
        String reason = infeasible.reason();
        boolean targetAlreadyPast = reason != null && reason.contains("이미 지난 시각");
        // 경로탐색 API가 조회 자체를 못 해준 경우(한도 초과 등)를 "운행 종료"로 바꾸면,
        // 기다린다고 해결되지 않는 상황을 운행 시간 문제로 오해하게 된다 - 그대로 보여준다.
        boolean searchUnavailable = LastDepartureService.ROUTE_SEARCH_UNAVAILABLE_REASON.equals(reason);
        // TAGO가 이 역/노선의 시간표 자체를 안 주는 경우(운행이 끊긴 게 아니라 데이터가 없는 것 -
        // 2026-08-25 실사용 중 발견: 1호선 코레일 구간 역들(창동·광운대·월계·석계 등, TAGO ID가
        // "MTRKR"로 시작)이 TAGO GetSubwaySttnAcctoSchdulList에서 상행/하행 둘 다 0건으로 온다).
        // 이것도 "운행 종료"로 바꾸면 "밤이 늦어 못 간다"고 오해하게 되므로 원인을 구분해서 보여준다.
        boolean noTimetableData = reason != null && reason.contains("운행 정보를 찾을 수 없습니다");
        if (arrivalMode && !targetAlreadyPast && !searchUnavailable && !noTimetableData) {
            return "해당 목적지까지 대중교통 운행이 종료되어 안내가 불가능합니다.";
        }
        if (noTimetableData) {
            return "이 경로 일부 구간의 시간표 정보를 확인할 수 없어 안내가 어렵습니다. 다른 경로나 택시를 이용해주세요.";
        }
        return reason;
    }

    /**
     * 추천 출발 시각이 이미 지난 시각인지 본다 (예: 막차 계산 결과가 23:30인데 확인하는 시점이
     * 이미 23:50인 경우). 새벽 시간대는 "오늘 자정 넘어서"로 이어지는 서비스일 개념이라
     * targetArrivalTime 처리와 같은 방식(새벽 6시 컷오프)으로 "지금"도 확장해서 비교한다.
     * <p>
     * 오늘이 아닌 날짜(달력에서 며칠 뒤를 골라 조회한 경우)는 "이미 지났다"는 개념 자체가
     * 성립하지 않으므로 항상 false다 - 시:분만 비교하면(날짜를 안 보고) 미래 날짜의 계산
     * 결과가 지금 시각보다 이른 시:분이라는 이유만으로 "이미 지났다"고 오판해서, 실제 계산된
     * 막차 시각 대신 엉뚱하게 "지금 출발하면"(현재 시각 + 소요시간) 값을 보여주는 버그가 있었다
     * (예: 오늘 오후에 5일 뒤 막차를 조회했는데 그 결과가 새벽 5시대라 "이미 지남"으로 오판).
     */
    private boolean hasAlreadyPassed(RouteOption option, LocalDate selectedDate) {
        if (!selectedDate.equals(LocalDate.now())) {
            return false;
        }
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

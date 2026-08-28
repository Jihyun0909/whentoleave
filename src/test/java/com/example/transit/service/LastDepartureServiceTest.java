package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.service.client.GoogleRoutesClient;
import com.example.transit.service.client.SeoulBusStopApiClient;
import com.example.transit.service.client.TagoBusRouteDetailApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.GoogleRoutesResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 목표 도착시간이 사실상 아무 제약이 안 돼서 결과가 그냥 막차와 같아지는 경우
 * isLastTrainDeparture가 true로 표시되는지 확인한다. 목표 시각은 항상 23:59로 고정해서
 * (하루 중 가장 늦은 시각이라 "이미 지난 시각" 분기를 절대 안 타므로) 테스트 실행 시각과
 * 무관하게 결정적으로 동작하게 한다.
 * <p>
 * Google Routes 응답은 순수 record라 직접 생성자로 조립하고, TAGO 응답(JsonNode 기반)만
 * ObjectMapper로 만든다. 역 이름→TAGO stationId 해석은 모든 테스트에서 "테스트역"을 그대로
 * stationId로 반환하는 스텁을 쓴다 - LastTrainLookup 페이크가 wayCode를 안 쓰므로 방향 판별
 * 정확도는 이 테스트들과 무관하다.
 */
class LastDepartureServiceTest {

    private static final LocalTime FIXED_TARGET = LocalTime.of(23, 59);
    private final ObjectMapper mapper = new ObjectMapper();
    /** 실제 시드 파일(40MB)을 매 테스트마다 새로 파싱하지 않도록 클래스 전체가 하나만 공유한다 -
     * 이 파일의 테스트들은 headsign을 안 쓰므로(방향 판별은 항상 early return) 이 시드까지
     * 내려가는 경우가 없어 실제 내용은 문제되지 않는다. */
    private static final SeoulSubwayTimetableSeedCatalog SEED_CATALOG = new SeoulSubwayTimetableSeedCatalog();

    @Test
    void 목표시각이_사실상_제약이_안되면_막차와_같은_결과에_플래그를_붙인다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(
                        train(LocalTime.of(22, 0), false),
                        train(LocalTime.of(23, 30), false)
                )
        ));
        LastDepartureService service = newService(5, lookup); // 5분 소요 -> 마감 여유 충분

        LastDepartureResult result = service.calculate(0, 0, 0, 0, FIXED_TARGET);

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 30), feasible.departureTime());
        assertTrue(feasible.isLastTrainDeparture());
    }

    @Test
    void 목표시각이_실제로_제약이_되면_플래그를_붙이지_않는다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(
                        train(LocalTime.of(22, 0), false),
                        train(LocalTime.of(23, 30), false)
                )
        ));
        LastDepartureService service = newService(35, lookup); // 35분 소요 -> 23:30차는 마감을 못 맞춤

        LastDepartureResult result = service.calculate(0, 0, 0, 0, FIXED_TARGET);

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(22, 0), feasible.departureTime());
        assertFalse(feasible.isLastTrainDeparture());
    }

    /**
     * 사용자 피드백(2026-08-30): "다른 날짜 보기"로 미래 날짜를 골라도, 그 목표 시각이 지금(오후 등)
     * 시계보다 이르다는 이유만으로 "이미 지난 시각"으로 잘못 거절되고 있었다 - 실제로는 며칠
     * 뒤의 그 시각이라 전혀 지나지 않았는데도. "지금"보다 1분 이른 시각을 목표로 삼아 재현한다
     * (새벽 6시 이전 컷오프 특례로 우연히 통과하는 걸 피하려고 "지금 - 1분"을 그대로 쓴다 -
     * 테스트가 새벽 6시 정각 부근에 도는 극단적인 경우는 감수한다).
     */
    @Test
    void 미래_날짜를_고르면_현재_시각보다_이른_목표시각도_거절하지_않는다() throws Exception {
        LocalTime targetBeforeNow = LocalTime.now().minusMinutes(1);
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(train(targetBeforeNow.minusMinutes(10), false))
        ));
        LastDepartureService service = newService(5, lookup); // 5분 소요 -> 마감 안에 듦

        LastDepartureResult result = service.calculate(0, 0, 0, 0, targetBeforeNow, tomorrow);

        assertInstanceOf(LastDepartureResult.Feasible.class, result);
    }

    /**
     * 사용자 피드백(2026-08-30): Google Routes 조회에 시각을 아예 안 넘겨서 항상 "지금" 기준으로
     * 계산되고 있었다 - 다른 날짜/목표시각을 물어도 완전히 다른(엉뚱한) 경로가 나오는 원인이었다.
     * 목표 도착시간 모드는 arrivalTime을, 막차 모드는 departureTime을 넘겨야 한다.
     */
    @Test
    void 목표_도착시간_모드는_google에_arrivalTime을_넘기고_막차_모드는_departureTime을_넘긴다() throws Exception {
        String[] captured = new String[2]; // [0]=departureTime, [1]=arrivalTime
        GoogleRoutesClient googleClient = new GoogleRoutesClient("http://dummy", "dummy") {
            @Override
            public GoogleRoutesResponse computeTransitRoutes(double sx, double sy, double ex, double ey,
                                                               List<String> allowedTravelModes,
                                                               String departureTime, String arrivalTime) {
                captured[0] = departureTime;
                captured[1] = arrivalTime;
                return singleLegResponse(5);
            }
        };
        LastTrainLookup lookup = fakeLookup(Map.of("300", List.of(train(LocalTime.of(23, 30), false))));
        LastDepartureService service = new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());

        service.calculate(0, 0, 0, 0, FIXED_TARGET);
        assertNull(captured[0], "목표 도착시간 모드는 departureTime을 보내면 안 됨");
        assertNotNull(captured[1], "목표 도착시간 모드는 arrivalTime을 보내야 함");

        service.calculate(0, 0, 0, 0);
        assertNotNull(captured[0], "막차 모드는 departureTime을 보내야 함");
        assertNull(captured[1], "막차 모드는 arrivalTime을 보내면 안 됨");
    }

    /**
     * 사용자 피드백(2026-08-30): "8/30 오전 1시" 같은 미래 날짜+새벽 시각 조합에서, 실제로 그
     * 순간을 지배하는 시간표는 하루 전날(8/29) 밤의 연장인데도 리터럴 날짜(8/30, 일요일)로
     * 조회되고 있었다 - 실제로는 토요일 심야 시간표를 봐야 하는데 일요일 시간표로 잘못
     * 조회되는 문제였다. 지하철 시간표 조회(LastTrainLookup)에 넘기는 date는 하루 전이어야
     * 하지만, Google에 보내는 arrivalTime은 사용자가 고른 리터럴 시각(8/30 01:00) 그대로여야
     * 한다 - 이 둘이 섞이면 안 된다.
     */
    @Test
    void 미래_날짜_새벽_목표는_전날_시간표를_조회하되_google에는_리터럴_시각을_넘긴다() throws Exception {
        LocalDate[] capturedScheduleDate = new LocalDate[1];
        LastTrainLookup lookup = (stationId, wayCode, date, stationName, laneName) -> {
            capturedScheduleDate[0] = date;
            return List.of(train(LocalTime.of(0, 30), true)); // 다음날 새벽 0:30차
        };
        String[] capturedArrivalTime = new String[1];
        GoogleRoutesClient googleClient = new GoogleRoutesClient("http://dummy", "dummy") {
            @Override
            public GoogleRoutesResponse computeTransitRoutes(double sx, double sy, double ex, double ey,
                                                               List<String> allowedTravelModes,
                                                               String departureTime, String arrivalTime) {
                capturedArrivalTime[0] = arrivalTime;
                return singleLegResponse(5);
            }
        };
        LastDepartureService service = new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());

        LocalDate literalDate = LocalDate.now().plusDays(3);
        LocalDate expectedScheduleDate = literalDate.minusDays(1); // 그 새벽을 실제로 지배하는 다이어그램

        LastDepartureResult result = service.calculate(0, 0, 0, 0, LocalTime.of(1, 0), literalDate);

        assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(expectedScheduleDate, capturedScheduleDate[0]);
        assertTrue(capturedArrivalTime[0].startsWith(literalDate + "T01:00"),
                "Google엔 사용자가 고른 리터럴 날짜·시각을 그대로 보내야 함: " + capturedArrivalTime[0]);
    }

    @Test
    void 막차_모드에서는_플래그가_항상_false다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newService(5, lookup);

        LastDepartureResult result = service.calculate(0, 0, 0, 0);

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertFalse(feasible.isLastTrainDeparture());
    }

    /**
     * "안전 막차" - 환승이 있는 경로에서는 환승마다 여유 버퍼(7분)를 더 요구해서 다시 계산한
     * 더 이른 출발 시각도 RouteOption에 같이 담겨야 한다.
     */
    @Test
    void 막차_모드에서_환승_경로면_안전_막차_시각도_같이_계산된다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "100", List.of(train(LocalTime.of(23, 0), false), train(LocalTime.of(23, 20), false)),
                "200", List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newTransferService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, null, LocalDate.now());

        RouteOption option = options.get(0);
        assertEquals(LocalTime.of(23, 20), option.departureTime());
        assertTrue(option.hasSafeDeparture());
        assertEquals(LocalTime.of(23, 0), option.safeDepartureTime());
    }

    /**
     * 목표 도착시간 모드도 막차 모드의 "안전 막차"와 같은 이유로 환승마다 여유(7분)를 둬야 한다
     * (실사용 중 발견: "내려서 걸은 시간이 다음 환승 열차 출발 시각에 정확히 맞아떨어지는" 결과가
     * 나와서 이상하다는 피드백 - 실제로 그렇게 딱 맞춰 타면 놓치기 딱 좋다). 막차 모드는 "최단
     * 막차"라는 이름 그대로 정확히 맞춰 타는 걸 의도한 거라 그대로 두고, 대신 목표 도착시간
     * 모드의 기본 계산 자체에 여유를 둔다.
     * <p>
     * 역A(leg0) 후보를 1분 간격으로 촘촘히 둬서(양자화 오차 없이) 정확히 7분 차이가 나는지 본다.
     */
    @Test
    void 목표_도착시간_모드도_환승마다_여유시간을_둔다() throws Exception {
        List<SubwaySchedule> denseTrains = new java.util.ArrayList<>();
        for (int m = 0; m <= 29; m++) {
            denseTrains.add(train(LocalTime.of(23, m), false));
        }
        LastTrainLookup lookup = fakeLookup(Map.of(
                "100", denseTrains,
                "200", List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newTransferService(lookup);

        LastDepartureResult result = service.calculate(0, 0, 0, 0, FIXED_TARGET);

        // 역B(200)행 23:30차를 타야 하는데, 환승 도보(3분+기본 여유 2분=5분) + 안전 여유 7분을
        // 두면 역A(100)는 23:30 - 5 - 7 - (역A 소요 5분) = 23:13차를 타야 한다(안전 여유
        // 없이 계산하면 23:20차).
        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 13), feasible.departureTime());
    }

    /**
     * 목표 도착시간 모드(출발 시간 계산 탭)에서는 "안전"이라는 개념을 적용하지 않는다 - 환승이
     * 있는 경로(safe 계산이 실제로 다른 값을 낼 수 있는 경로)로 확인해서, 단순히 "환승이 없어서
     * 안 나온 것"이 아니라 모드 자체 때문에 안 나온다는 걸 확인한다.
     */
    @Test
    void 목표_도착시간_모드에서는_안전_막차_시각을_계산하지_않는다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "100", List.of(train(LocalTime.of(23, 0), false), train(LocalTime.of(23, 20), false)),
                "200", List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newTransferService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertFalse(options.get(0).hasSafeDeparture());
    }

    /**
     * 목표 도착시간 모드에서도 소요시간이 짧은 경로가 맨 위여야 한다. 예전엔 "목표 시각에
     * 가장 가깝게 도착하는" 경로를 우선했는데, 그러면 환승이 많고 오래 걸리는 경로가 단지
     * "딱 맞춰 도착한다"는 이유만으로 더 빠르고 단순한 경로보다 위로 올라오는 문제가 있었다
     * (실사용 중 발견 - 번동->광운대에서 버스 직행 19분보다 2번 환승하는 43분짜리 경로가
     * "가장 빠름"으로 추천됨). 여기서는 5분짜리 경로(23:35 도착, 목표보다 24분 이름)가
     * 50분짜리 경로(23:50 도착, 목표까지 9분)보다 위로 와야 한다.
     */
    @Test
    void 목표_도착시간_모드에서도_소요시간이_짧은_경로가_맨_위다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(train(LocalTime.of(23, 30), false)),
                "400", List.of(train(LocalTime.of(23, 0), false))
        ));
        LastDepartureService service = newPerPathTypeService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertEquals(2, options.size());
        assertEquals(LocalTime.of(23, 30), options.get(0).departureTime()); // 5분 소요, 23:35 도착
        assertEquals(LocalTime.of(23, 0), options.get(1).departureTime()); // 50분 소요, 23:50 도착
    }

    /**
     * bestOf()의 후보 선택 기준은 이 세션 동안 여러 번 바뀌었다: "더 늦게 출발해도 되는 후보"
     * (환승 경로가 이상하게 이김) -> "총 소요시간이 짧은 후보"(그래도 미묘하게 이상함) ->
     * "Google 순서 그대로 1순위"(뒤쪽에 더 늦게 출발 가능한 후보가 있어도 못 봄, 2026-08-30
     * 실사용 중 발견) -> 다시 "더 늦게 출발해도 되는 후보"(단, 환승마다 안전 여유
     * (SAFE_TRANSFER_MARGIN_MINUTES)를 두고 계산하므로, 빠듯한 환승은 애초에 후보에서
     * 걸러진다 - 이 테스트의 환승(S1->S2)이 정확히 그 경우다: 안전 여유 없이는 23:20 출발이
     * 가능하지만, 7분 여유를 더하면 그 환승을 실제로는 놓친다고 보고 Infeasible 처리된다).
     * 그 결과 직행(F)만 유일하게 성립하는 후보로 남아 그대로 골라진다 - "Google 순서를
     * 믿어서"가 아니라 "유일하게 실현 가능해서"다.
     */
    @Test
    void 같은_카테고리_안에서도_google이_1순위로_준_후보를_그대로_고른다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "F", List.of(train(LocalTime.of(23, 0), false)),
                "S1", List.of(train(LocalTime.of(23, 20), false)),
                "S2", List.of(train(LocalTime.of(23, 30), false))
        ));
        GoogleRoutesClient googleClient = googleStub(fastAndSlowResponse());
        LastDepartureService service = new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertEquals(1, options.size()); // S1->S2는 안전 여유를 두면 환승을 놓쳐 Infeasible - 직행(F)만 남음
        assertEquals(LocalTime.of(23, 0), options.get(0).departureTime());
    }

    /** Google이 1순위로 주는 직행(F)과 2순위인 환승(S1->S2, 마감에 더 가깝게 출발) 두 대안을 같이 주는 응답. */
    private GoogleRoutesResponse fastAndSlowResponse() {
        GoogleRoutesResponse.Step fastStep = transitStep(5, "역(F)", "1호선");
        GoogleRoutesResponse.Route fastRoute =
                new GoogleRoutesResponse.Route(List.of(new GoogleRoutesResponse.Leg(List.of(fastStep), 0, "0s")));

        GoogleRoutesResponse.Step slow1 = transitStep(5, "역(S1)", "2호선");
        GoogleRoutesResponse.Step slowWalk = walkStep(3);
        GoogleRoutesResponse.Step slow2 = transitStep(25, "역(S2)", "4호선");
        GoogleRoutesResponse.Route slowRoute = new GoogleRoutesResponse.Route(
                List.of(new GoogleRoutesResponse.Leg(List.of(slow1, slowWalk, slow2), 0, "0s")));

        return new GoogleRoutesResponse(List.of(fastRoute, slowRoute));
    }

    /**
     * 사용자 요청(2026-08-30): "다른 최적경로랑 소요시간이 30% 이상 차이나면 최적경로랑 늦게
     * 출발 경로 다 띄워줘". A(직행, 5분, 22:50 출발)와 B(환승 B1->B2, 20분(=5+15, 환승 도보
     * 버퍼 포함), 23:10 출발 - A보다 20분 늦게 출발해도 되지만 소요시간이 4배(300% 차이, 30%
     * 훨씬 초과))를 같이 주면, 최적경로(A)와 가장 늦게 출발하는 경로(B) 둘 다 나와야 한다 -
     * 안전 여유를 둬도 B의 환승은 여전히 넉넉해서(B1 23:10 vs 마감 23:23) Infeasible이
     * 안 된다.
     */
    @Test
    void 최적경로와_가장_늦은_출발_경로가_소요시간_30퍼센트_이상_차이나면_둘_다_보여준다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "A", List.of(train(LocalTime.of(22, 50), false)),
                "B1", List.of(train(LocalTime.of(23, 10), false)),
                "B2", List.of(train(LocalTime.of(23, 40), false))
        ));
        GoogleRoutesClient googleClient = googleStub(shortAndLongResponse());
        LastDepartureService service = new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertEquals(2, options.size());
        // recommendationOrder는 소요시간 순 - 최적경로(A, 5분)가 먼저, 늦게 출발하는 경로(B, 15분)가 다음.
        assertEquals(LocalTime.of(22, 50), options.get(0).departureTime());
        assertEquals(LocalTime.of(23, 10), options.get(1).departureTime());
    }

    /**
     * 반대로 소요시간 차이가 30% 미만이면 가장 늦게 출발하는 경로 하나만 보여준다 - 어차피
     * 소요시간도 비슷한데 둘 다 보여주면 화면만 복잡해진다. A(5분, 22:50)와 B(6분, 23:00 -
     * A보다 늦게 출발해도 되지만 소요시간 차이는 20%뿐)를 준다.
     */
    @Test
    void 소요시간_차이가_30퍼센트_미만이면_늦게_출발하는_경로만_보여준다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "A", List.of(train(LocalTime.of(22, 50), false)),
                "B", List.of(train(LocalTime.of(23, 0), false))
        ));
        GoogleRoutesResponse.Step aStep = transitStep(5, "역(A)", "1호선");
        GoogleRoutesResponse.Route aRoute =
                new GoogleRoutesResponse.Route(List.of(new GoogleRoutesResponse.Leg(List.of(aStep), 0, "0s")));
        GoogleRoutesResponse.Step bStep = transitStep(6, "역(B)", "2호선");
        GoogleRoutesResponse.Route bRoute =
                new GoogleRoutesResponse.Route(List.of(new GoogleRoutesResponse.Leg(List.of(bStep), 0, "0s")));
        GoogleRoutesClient googleClient = googleStub(new GoogleRoutesResponse(List.of(aRoute, bRoute)));
        LastDepartureService service = new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertEquals(1, options.size());
        assertEquals(LocalTime.of(23, 0), options.get(0).departureTime());
    }

    /** A(직행, 5분)와 B(환승 B1->B2, 15분, A보다 늦게 출발 가능) 두 대안을 같이 주는 응답. */
    private GoogleRoutesResponse shortAndLongResponse() {
        GoogleRoutesResponse.Step aStep = transitStep(5, "역(A)", "1호선");
        GoogleRoutesResponse.Route aRoute =
                new GoogleRoutesResponse.Route(List.of(new GoogleRoutesResponse.Leg(List.of(aStep), 0, "0s")));

        GoogleRoutesResponse.Step b1 = transitStep(5, "역(B1)", "2호선");
        GoogleRoutesResponse.Step bWalk = walkStep(3);
        GoogleRoutesResponse.Step b2 = transitStep(10, "역(B2)", "4호선");
        GoogleRoutesResponse.Route bRoute = new GoogleRoutesResponse.Route(
                List.of(new GoogleRoutesResponse.Leg(List.of(b1, bWalk, b2), 0, "0s")));

        return new GoogleRoutesResponse(List.of(aRoute, bRoute));
    }

    /** 막차 모드(목표 시각 없음)에서는 기존대로 소요시간이 짧은 순이어야 한다. */
    @Test
    void 막차_모드에서는_소요시간이_짧은_경로가_맨_위다() throws Exception {
        LastTrainLookup lookup = fakeLookup(Map.of(
                "300", List.of(train(LocalTime.of(23, 30), false)),
                "400", List.of(train(LocalTime.of(23, 0), false))
        ));
        LastDepartureService service = newPerPathTypeService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, null, LocalDate.now());

        assertEquals(5, options.get(0).totalMinutes());
    }

    /**
     * 경로탐색 결과 자체가 비어 있고(예: 출발지-목적지가 너무 가까워 Google이 대중교통 경로를
     * 안 주는 경우) 거리가 가까우면, "운행 종료" 같은 엉뚱한 안내 대신 좌표 간 직선거리로
     * 도보 시간을 추정해서 보여줘야 한다.
     */
    @Test
    void 경로를_못_찾아도_거리가_가까우면_도보시간을_추정한다() {
        GoogleRoutesClient googleClient = googleStub(new GoogleRoutesResponse(List.of()));
        LastDepartureService service = new LastDepartureService(
                googleClient, newExtractor(), LastDepartureCalculator.subwayOnly(fakeLookup(Map.of())),
                noNightBus());

        // 약 150m 거리
        LastDepartureResult result = service.calculate(127.0276, 37.4979, 127.0290, 37.4985);

        LastDepartureResult.Infeasible infeasible = assertInstanceOf(LastDepartureResult.Infeasible.class, result);
        assertNotNull(infeasible.walkOnlyMinutes());
        assertTrue(infeasible.walkOnlyMinutes() <= 5);
    }

    /** 거리가 멀면(진짜 경로 정보 부족 등) 도보 추정을 하지 않고 원래 실패 사유만 보여준다. */
    @Test
    void 경로를_못_찾고_거리도_멀면_도보시간을_추정하지_않는다() {
        GoogleRoutesClient googleClient = googleStub(new GoogleRoutesResponse(List.of()));
        LastDepartureService service = new LastDepartureService(
                googleClient, newExtractor(), LastDepartureCalculator.subwayOnly(fakeLookup(Map.of())),
                noNightBus());

        // 서울 <-> 부산 정도의 먼 거리
        LastDepartureResult result = service.calculate(127.0, 37.5, 129.0, 35.1);

        LastDepartureResult.Infeasible infeasible = assertInstanceOf(LastDepartureResult.Infeasible.class, result);
        assertNull(infeasible.walkOnlyMinutes());
    }

    /** 심야버스는 별도 API를 타므로 이 테스트들에서는 없는 것으로 둔다. */
    private NightBusRouteFinder noNightBus() {
        return new NightBusRouteFinder(null) {
            @Override
            public List<RouteLegExtractor.ExtractedRoute> find(double sx, double sy, double ex, double ey) {
                return List.of();
            }
        };
    }

    /** 역명→TAGO stationId 해석용 RouteLegExtractor. 항상 "테스트역"류 이름을 그대로 stationId로 돌려준다. */
    private RouteLegExtractor newExtractor() {
        TagoSubwayApiClient subwayClient = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse findStations(String stationName) {
                return stationResponse(stationIdFor(stationName), "1호선");
            }

            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyResponse();
            }
        };
        TagoBusRouteDetailApiClient busClient = new TagoBusRouteDetailApiClient("http://dummy", "dummy");
        TagoCityCodeResolver cityCodeResolver = new TagoCityCodeResolver(null);
        return new RouteLegExtractor(subwayClient, busClient, cityCodeResolver,
                new SeoulBusStopCatalog(new SeoulBusStopApiClient("http://dummy", "")), SEED_CATALOG);
    }

    /** 테스트에서 쓰는 역명은 전부 "역이름(stationId)" 형태로 지어서, 이름만 보고 원하는 stationId를 돌려준다. */
    private String stationIdFor(String stationName) {
        int start = stationName.indexOf('(');
        int end = stationName.indexOf(')');
        return start >= 0 && end > start ? stationName.substring(start + 1, end) : stationName;
    }

    /**
     * 경로 종류(SearchPathType)마다 서로 다른 경로를 주는 스텁 - 정렬 순서를 검증하려면
     * 후보가 둘 이상이어야 하는데, 같은 응답을 주면 중복 제거로 하나만 남기 때문이다.
     * 지하철 전용은 역 300(5분), 그 외는 역 400(50분) 경로가 나온다.
     */
    private LastDepartureService newPerPathTypeService(LastTrainLookup lookup) {
        GoogleRoutesClient googleClient = new GoogleRoutesClient("http://dummy", "dummy") {
            @Override
            public GoogleRoutesResponse computeTransitRoutes(double sx, double sy, double ex, double ey,
                                                               List<String> allowedTravelModes,
                                                               String departureTime, String arrivalTime) {
                return allowedTravelModes != null && allowedTravelModes.contains("SUBWAY")
                        ? singleLegResponse(5, "역(300)")
                        : singleLegResponse(50, "역(400)");
            }
        };
        return new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());
    }

    private LastDepartureService newTransferService(LastTrainLookup lookup) {
        GoogleRoutesClient googleClient = googleStub(twoLegResponse());
        return new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());
    }

    /** 역A(100, 2호선) -[5분]-> 환승도보 3분 -> 역B(200, 4호선) -[5분]->. */
    private GoogleRoutesResponse twoLegResponse() {
        GoogleRoutesResponse.Step first = transitStep(5, "역A(100)", "2호선");
        GoogleRoutesResponse.Step walk = walkStep(3);
        GoogleRoutesResponse.Step second = transitStep(5, "역B(200)", "4호선");
        GoogleRoutesResponse.Leg leg = new GoogleRoutesResponse.Leg(List.of(first, walk, second), 0, "0s");
        return new GoogleRoutesResponse(List.of(new GoogleRoutesResponse.Route(List.of(leg))));
    }

    private LastDepartureService newService(int rideMinutes, LastTrainLookup lookup) {
        GoogleRoutesClient googleClient = googleStub(singleLegResponse(rideMinutes));
        return new LastDepartureService(googleClient, newExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus());
    }

    private GoogleRoutesResponse singleLegResponse(int rideMinutes) {
        return singleLegResponse(rideMinutes, "역(300)");
    }

    private GoogleRoutesResponse singleLegResponse(int rideMinutes, String stationName) {
        GoogleRoutesResponse.Step step = transitStep(rideMinutes, stationName, "1호선");
        GoogleRoutesResponse.Leg leg = new GoogleRoutesResponse.Leg(List.of(step), 0, "0s");
        return new GoogleRoutesResponse(List.of(new GoogleRoutesResponse.Route(List.of(leg))));
    }

    private GoogleRoutesResponse.Step transitStep(int rideMinutes, String departureStopName, String lineNameShort) {
        GoogleRoutesResponse.TransitLine line =
                new GoogleRoutesResponse.TransitLine(lineNameShort, lineNameShort, new GoogleRoutesResponse.Vehicle("SUBWAY"));
        GoogleRoutesResponse.Stop departureStop = new GoogleRoutesResponse.Stop(departureStopName, null);
        GoogleRoutesResponse.StopDetails stopDetails = new GoogleRoutesResponse.StopDetails(departureStop, null, null, null);
        GoogleRoutesResponse.TransitDetails details = new GoogleRoutesResponse.TransitDetails(stopDetails, null, null, line, null);
        return new GoogleRoutesResponse.Step("TRANSIT", 0, (rideMinutes * 60) + "s", details);
    }

    private GoogleRoutesResponse.Step walkStep(int minutes) {
        return new GoogleRoutesResponse.Step("WALK", 0, (minutes * 60) + "s", null);
    }

    private GoogleRoutesClient googleStub(GoogleRoutesResponse response) {
        return new GoogleRoutesClient("http://dummy", "dummy") {
            @Override
            public GoogleRoutesResponse computeTransitRoutes(double sx, double sy, double ex, double ey,
                                                               List<String> allowedTravelModes,
                                                               String departureTime, String arrivalTime) {
                return response;
            }
        };
    }

    /**
     * 이 테스트들은 laneName 값 자체를 검증하지 않으므로, 실제로 어떤 노선명이 오든 매칭되게
     * 흔한 노선명(1/2/4호선)을 전부 같은 stationId로 돌려준다 - 그러지 않으면 twoLegResponse()가
     * 쓰는 "2호선"/"4호선"이 fixedLineName과 안 맞아 지하철역 해석이 조용히 실패한다.
     */
    private TagoBusArrivalResponse stationResponse(String stationId, String lineName) {
        try {
            String json = """
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": { "item": [
                        { "subwayStationId": "%s", "subwayStationName": "테스트역", "subwayRouteName": "%s" },
                        { "subwayStationId": "%s", "subwayStationName": "테스트역", "subwayRouteName": "1호선" },
                        { "subwayStationId": "%s", "subwayStationName": "테스트역", "subwayRouteName": "2호선" },
                        { "subwayStationId": "%s", "subwayStationName": "테스트역", "subwayRouteName": "4호선" }
                      ] } } } }
                    """.formatted(stationId, lineName, stationId, stationId, stationId);
            return mapper.readValue(json, TagoBusArrivalResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TagoBusArrivalResponse emptyResponse() {
        try {
            return mapper.readValue("""
                    { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                      "body": { "items": {} } } }
                    """, TagoBusArrivalResponse.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LastTrainLookup fakeLookup(Map<String, List<SubwaySchedule>> byStation) {
        return (stationId, wayCode, date, stationName, laneName) -> byStation.getOrDefault(stationId, List.of());
    }

    private SubwaySchedule train(LocalTime time, boolean nextDay) {
        return new SubwaySchedule("300", 1, DayType.WEEKDAY, "종착역", time, nextDay, 2);
    }
}

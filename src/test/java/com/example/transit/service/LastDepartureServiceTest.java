package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.junit.jupiter.api.Test;

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
 */
class LastDepartureServiceTest {

    private static final LocalTime FIXED_TARGET = LocalTime.of(23, 59);

    @Test
    void 목표시각이_사실상_제약이_안되면_막차와_같은_결과에_플래그를_붙인다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(
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
    void 목표시각이_실제로_제약이_되면_플래그를_붙이지_않는다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(
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

    @Test
    void 막차_모드에서는_플래그가_항상_false다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(train(LocalTime.of(23, 30), false))
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
    void 막차_모드에서_환승_경로면_안전_막차_시각도_같이_계산된다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(train(LocalTime.of(23, 0), false), train(LocalTime.of(23, 20), false)),
                200, List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newTransferService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, null, LocalDate.now());

        RouteOption option = options.get(0);
        assertEquals(LocalTime.of(23, 20), option.departureTime());
        assertTrue(option.hasSafeDeparture());
        assertEquals(LocalTime.of(23, 0), option.safeDepartureTime());
    }

    /**
     * 목표 도착시간 모드(출발 시간 계산 탭)에서는 "안전"이라는 개념을 적용하지 않는다 - 환승이
     * 있는 경로(safe 계산이 실제로 다른 값을 낼 수 있는 경로)로 확인해서, 단순히 "환승이 없어서
     * 안 나온 것"이 아니라 모드 자체 때문에 안 나온다는 걸 확인한다.
     */
    @Test
    void 목표_도착시간_모드에서는_안전_막차_시각을_계산하지_않는다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(train(LocalTime.of(23, 0), false), train(LocalTime.of(23, 20), false)),
                200, List.of(train(LocalTime.of(23, 30), false))
        ));
        LastDepartureService service = newTransferService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertFalse(options.get(0).hasSafeDeparture());
    }

    /**
     * 목표 도착시간 모드에서는 "목표 시각에 가장 가깝게 도착하는" 경로가 맨 위여야 한다.
     * 소요시간만으로 정렬하면 훨씬 일찍 도착해 하염없이 기다리게 되는 경로가 1순위로 올라온다.
     * 여기서는 5분짜리 빠른 경로(23:35 도착, 목표보다 24분 이름)보다 50분짜리 경로(23:50 도착,
     * 목표까지 9분)가 위로 와야 한다.
     */
    @Test
    void 목표_도착시간_모드에서는_목표에_가장_가깝게_도착하는_경로가_맨_위다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(train(LocalTime.of(23, 30), false)),
                400, List.of(train(LocalTime.of(23, 0), false))
        ));
        LastDepartureService service = newPerPathTypeService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, FIXED_TARGET, LocalDate.now());

        assertEquals(2, options.size());
        assertEquals(LocalTime.of(23, 0), options.get(0).departureTime()); // 23:50 도착 - 목표에 더 가까움
        assertEquals(LocalTime.of(23, 30), options.get(1).departureTime()); // 23:35 도착
    }

    /** 막차 모드(목표 시각 없음)에서는 기존대로 소요시간이 짧은 순이어야 한다. */
    @Test
    void 막차_모드에서는_소요시간이_짧은_경로가_맨_위다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(train(LocalTime.of(23, 30), false)),
                400, List.of(train(LocalTime.of(23, 0), false))
        ));
        LastDepartureService service = newPerPathTypeService(lookup);

        List<RouteOption> options = service.calculateOptions(0, 0, 0, 0, null, LocalDate.now());

        assertEquals(5, options.get(0).totalMinutes());
    }

    /**
     * ODsay는 출발지-목적지가 700m 이내면 경로 자체를 계산하지 않고 에러만 준다(실사용 curl
     * 검증: {"error":{"msg":"출, 도착지가 700m이내입니다.","code":"-98"}}) - result가 없어
     * "경로를 찾지 못했습니다" 취급을 받는다. 이 경우 "운행 종료" 같은 엉뚱한 안내 대신, 좌표
     * 간 직선거리로 도보 시간을 추정해서 보여줘야 한다.
     */
    @Test
    void 경로를_못_찾아도_거리가_가까우면_도보시간을_추정한다() {
        OdsayClient odsayClient = odsayStub(new OdsayPathResponse(null));
        LastDepartureService service = new LastDepartureService(
                odsayClient, new RouteLegExtractor(), LastDepartureCalculator.subwayOnly(fakeLookup(Map.of())),
                noNightBus(odsayClient));

        // 약 150m 거리 (실제 curl 검증에 쓴 좌표 - ODsay가 700m 이내로 판단한 지점)
        LastDepartureResult result = service.calculate(127.0276, 37.4979, 127.0290, 37.4985);

        LastDepartureResult.Infeasible infeasible = assertInstanceOf(LastDepartureResult.Infeasible.class, result);
        assertNotNull(infeasible.walkOnlyMinutes());
        assertTrue(infeasible.walkOnlyMinutes() <= 5);
    }

    /** 거리가 멀면(진짜 경로 정보 부족 등) 도보 추정을 하지 않고 원래 실패 사유만 보여준다. */
    @Test
    void 경로를_못_찾고_거리도_멀면_도보시간을_추정하지_않는다() {
        OdsayClient odsayClient = odsayStub(new OdsayPathResponse(null));
        LastDepartureService service = new LastDepartureService(
                odsayClient, new RouteLegExtractor(), LastDepartureCalculator.subwayOnly(fakeLookup(Map.of())),
                noNightBus(odsayClient));

        // 서울 <-> 부산 정도의 먼 거리
        LastDepartureResult result = service.calculate(127.0, 37.5, 129.0, 35.1);

        LastDepartureResult.Infeasible infeasible = assertInstanceOf(LastDepartureResult.Infeasible.class, result);
        assertNull(infeasible.walkOnlyMinutes());
    }

    /** 심야버스는 별도 API를 타므로 이 테스트들에서는 없는 것으로 둔다. */
    private NightBusRouteFinder noNightBus(OdsayClient odsayClient) {
        return new NightBusRouteFinder(odsayClient) {
            @Override
            public List<RouteLegExtractor.ExtractedRoute> find(double sx, double sy, double ex, double ey) {
                return List.of();
            }
        };
    }

    /**
     * 경로 종류(SearchPathType)마다 서로 다른 경로를 주는 스텁 - 정렬 순서를 검증하려면
     * 후보가 둘 이상이어야 하는데, 같은 응답을 주면 중복 제거로 하나만 남기 때문이다.
     * 지하철 전용은 역 300(5분), 그 외는 역 400(50분) 경로가 나온다.
     */
    private LastDepartureService newPerPathTypeService(LastTrainLookup lookup) {
        OdsayClient odsayClient = new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayPathResponse searchPath(double sx, double sy, double ex, double ey,
                                                 com.example.transit.service.client.SearchPathType pathType) {
                return pathType == com.example.transit.service.client.SearchPathType.SUBWAY_ONLY
                        ? singleLegResponse(5, 300)
                        : singleLegResponse(50, 400);
            }
        };
        return new LastDepartureService(odsayClient, new RouteLegExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus(odsayClient));
    }

    private LastDepartureService newTransferService(LastTrainLookup lookup) {
        OdsayClient odsayClient = odsayStub(twoLegResponse());
        return new LastDepartureService(odsayClient, new RouteLegExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus(odsayClient));
    }

    /** 역A(100, 2호선) -[5분]-> 환승도보 3분 -> 역B(200, 4호선) -[5분]->. */
    private OdsayPathResponse twoLegResponse() {
        return new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, 5, 100, 2, null, "역A",
                                        List.of(new OdsayPathResponse.Lane("2호선"))),
                                new OdsayPathResponse.SubPath(3, 3, null, null, null, null, null),
                                new OdsayPathResponse.SubPath(1, 5, 200, 2, null, "역B",
                                        List.of(new OdsayPathResponse.Lane("4호선")))
                        ))
                ))
        );
    }

    private LastDepartureService newService(int rideMinutes, LastTrainLookup lookup) {
        OdsayClient odsayClient = odsayStub(singleLegResponse(rideMinutes));
        return new LastDepartureService(odsayClient, new RouteLegExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus(odsayClient));
    }

    private OdsayPathResponse singleLegResponse(int rideMinutes) {
        return singleLegResponse(rideMinutes, 300);
    }

    private OdsayPathResponse singleLegResponse(int rideMinutes, int stationId) {
        return new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, rideMinutes, stationId, 1, null, "테스트역",
                                        List.of(new OdsayPathResponse.Lane("1호선")))
                        ))
                ))
        );
    }

    private OdsayClient odsayStub(OdsayPathResponse response) {
        return new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayPathResponse searchPath(double sx, double sy, double ex, double ey,
                                                 com.example.transit.service.client.SearchPathType pathType) {
                return response;
            }
        };
    }

    private LastTrainLookup fakeLookup(Map<Integer, List<SubwaySchedule>> byStation) {
        return (stationId, wayCode, date) -> byStation.getOrDefault(stationId, List.of());
    }

    private SubwaySchedule train(LocalTime time, boolean nextDay) {
        return new SubwaySchedule(300, 1, DayType.WEEKDAY, "종착역", time, nextDay, 2);
    }
}

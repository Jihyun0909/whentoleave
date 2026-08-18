package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayPathResponse;
import org.junit.jupiter.api.Test;

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

    private LastDepartureService newService(int rideMinutes, LastTrainLookup lookup) {
        OdsayClient odsayClient = odsayStub(singleLegResponse(rideMinutes));
        return new LastDepartureService(odsayClient, new RouteLegExtractor(),
                LastDepartureCalculator.subwayOnly(lookup), noNightBus(odsayClient));
    }

    private OdsayPathResponse singleLegResponse(int rideMinutes) {
        return new OdsayPathResponse(
                new OdsayPathResponse.Result(List.of(
                        new OdsayPathResponse.Path(1, List.of(
                                new OdsayPathResponse.SubPath(1, rideMinutes, 300, 1, null, "테스트역",
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

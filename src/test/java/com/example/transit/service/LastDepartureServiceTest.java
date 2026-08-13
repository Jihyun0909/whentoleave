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

    private LastDepartureService newService(int rideMinutes, LastTrainLookup lookup) {
        OdsayClient odsayClient = odsayStub(singleLegResponse(rideMinutes));
        RouteLegExtractor extractor = new RouteLegExtractor();
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);
        // 심야버스는 별도 API를 타므로 이 테스트에서는 없는 것으로 둔다.
        NightBusRouteFinder noNightBus = new NightBusRouteFinder(odsayClient) {
            @Override
            public List<RouteLegExtractor.ExtractedRoute> find(double sx, double sy, double ex, double ey) {
                return List.of();
            }
        };
        return new LastDepartureService(odsayClient, extractor, calculator, noNightBus);
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

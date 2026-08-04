package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwayLastTrain;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastDepartureCalculatorTest {

    private SubwayLastTrain lastTrain(LocalTime time, boolean nextDay) {
        return new SubwayLastTrain(1, 1, DayType.WEEKDAY, "종착역", time, nextDay);
    }

    /** stationId -> 그 역의 막차 후보 목록, 을 그대로 반환하는 람다 페이크. */
    private LastTrainLookup fakeLookup(Map<Integer, List<SubwayLastTrain>> byStation) {
        return (stationId, wayCode) -> byStation.getOrDefault(stationId, List.of());
    }

    @Test
    void 구간이_하나뿐이면_그_역의_막차_시각을_그대로_반환한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(lastTrain(LocalTime.of(23, 45), false))
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        SubwayLeg leg = new SubwayLeg(300, 1, 20, 0);
        LastDepartureResult result = calculator.calculate(List.of(leg));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 45), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }

    @Test
    void 환승이_문제없이_연결되면_첫_구간의_실제_막차_시각을_반환한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(lastTrain(LocalTime.of(23, 0), false)),
                200, List.of(lastTrain(LocalTime.of(23, 30), false))
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        SubwayLeg first = new SubwayLeg(100, 2, 15, 0);
        SubwayLeg second = new SubwayLeg(200, 2, 10, 3);
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 0), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }

    @Test
    void 앞_구간_막차가_다음_환승_마감을_넘기면_Infeasible을_반환한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(lastTrain(LocalTime.of(23, 20), false)), // deadline(23:12)보다 늦음
                200, List.of(lastTrain(LocalTime.of(23, 30), false))
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        SubwayLeg first = new SubwayLeg(100, 2, 15, 0);
        SubwayLeg second = new SubwayLeg(200, 2, 10, 3);
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Infeasible infeasible = assertInstanceOf(LastDepartureResult.Infeasible.class, result);
        assertTrue(infeasible.reason().contains("100"));
    }

    @Test
    void 자정을_넘는_막차도_정상적으로_계산된다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                500, List.of(lastTrain(LocalTime.of(0, 5), true)),   // 다음날 00:05
                400, List.of(lastTrain(LocalTime.of(0, 20), true))   // 다음날 00:20
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        SubwayLeg first = new SubwayLeg(500, 1, 12, 0);
        SubwayLeg second = new SubwayLeg(400, 2, 8, 2);
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(0, 5), feasible.departureTime());
        assertTrue(feasible.nextDay());
    }

    @Test
    void 구간_리스트가_비어있으면_예외를_던진다() {
        LastTrainLookup lookup = fakeLookup(Map.of());
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(List.of()));
    }
}

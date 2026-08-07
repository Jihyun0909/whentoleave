package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwayLastTrain;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LastDepartureCalculatorTest {

    private SubwayLastTrain lastTrain(LocalTime time, boolean nextDay) {
        return lastTrain(time, nextDay, "종착역");
    }

    private SubwayLastTrain lastTrain(LocalTime time, boolean nextDay, String endStationName) {
        return new SubwayLastTrain(1, 1, DayType.WEEKDAY, endStationName, time, nextDay);
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

        SubwayLeg leg = new SubwayLeg(300, 1, 20, 0, Set.of());
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

        SubwayLeg first = new SubwayLeg(100, 2, 15, 0, Set.of());
        SubwayLeg second = new SubwayLeg(200, 2, 10, 3, Set.of());
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

        SubwayLeg first = new SubwayLeg(100, 2, 15, 0, Set.of());
        SubwayLeg second = new SubwayLeg(200, 2, 10, 3, Set.of());
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

        SubwayLeg first = new SubwayLeg(500, 1, 12, 0, Set.of());
        SubwayLeg second = new SubwayLeg(400, 2, 8, 2, Set.of());
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

    /**
     * 실사용 검증 중 발견한 버그의 회귀 테스트 (수유->압구정, 충무로 환승, 이슈 #4).
     * 충무로역 막차 후보 중 "약수"행(00:57)이 시각상 가장 늦지만, 노선 순서상 약수는
     * 압구정보다 앞이라 그 열차로는 압구정에 못 간다. earlierStopNames에 "약수"가
     * 포함되어 있으면 그 후보를 빼고, 실제로 압구정까지 가는 후보 중 가장 늦은
     * "도곡"(00:34)을 골라야 한다.
     */
    @Test
    void 단축운행으로_도착역_전에_끊기는_막차_후보는_제외한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                331, List.of(
                        lastTrain(LocalTime.of(0, 6), true, "오금"),
                        lastTrain(LocalTime.of(0, 24), true, "수서"),
                        lastTrain(LocalTime.of(0, 34), true, "도곡"),
                        lastTrain(LocalTime.of(0, 57), true, "약수") // 압구정보다 앞이라 못 감
                )
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        Set<String> earlierThanApgujeong = Set.of("충무로", "동대입구", "약수");
        SubwayLeg leg = new SubwayLeg(331, 2, 5, 0, earlierThanApgujeong);

        LastDepartureResult result = calculator.calculate(List.of(leg));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(0, 34), feasible.departureTime()); // 00:57(약수)이 아니라 00:34(도곡)
        assertTrue(feasible.nextDay());
    }

    /**
     * 또 다른 실사용 검증 회귀 테스트 (이슈 #4). 가장 늦은 후보(서울역행 00:35)가
     * 환승 마감을 못 맞추더라도, 그보다 이른 다른 후보(금정행 23:27)가 마감을
     * 맞출 수 있으면 그걸 써야 한다. "가장 늦은 후보 하나만 보고 포기"하면 안 된다.
     */
    @Test
    void 가장_늦은_후보가_마감을_못_맞춰도_그보다_이른_후보가_맞으면_그걸_쓴다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(
                        lastTrain(LocalTime.of(23, 27), false, "금정"),  // deadline(23:35) 이내
                        lastTrain(LocalTime.of(0, 35), true, "서울역")   // 더 늦지만 환승을 놓침
                ),
                200, List.of(lastTrain(LocalTime.of(0, 10), true, "종착역"))
        ));
        LastDepartureCalculator calculator = new LastDepartureCalculator(lookup);

        SubwayLeg first = new SubwayLeg(100, 2, 10, 0, Set.of());
        SubwayLeg second = new SubwayLeg(200, 2, 10, 5, Set.of());
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 27), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }
}

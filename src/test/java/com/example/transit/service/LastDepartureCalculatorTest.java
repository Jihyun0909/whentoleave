package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
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

    private SubwaySchedule lastTrain(LocalTime time, boolean nextDay) {
        return lastTrain(time, nextDay, "종착역", 2);
    }

    private SubwaySchedule lastTrain(LocalTime time, boolean nextDay, String endStationName) {
        return lastTrain(time, nextDay, endStationName, 2);
    }

    private SubwaySchedule lastTrain(LocalTime time, boolean nextDay, String endStationName, int firstLastFlag) {
        return new SubwaySchedule(1, 1, DayType.WEEKDAY, endStationName, time, nextDay, firstLastFlag);
    }

    /** stationId -> 그 역의 시간표 후보 목록, 을 그대로 반환하는 람다 페이크. */
    private LastTrainLookup fakeLookup(Map<Integer, List<SubwaySchedule>> byStation) {
        return (stationId, wayCode, date) -> byStation.getOrDefault(stationId, List.of());
    }

    @Test
    void 구간이_하나뿐이면_그_역의_막차_시각을_그대로_반환한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(lastTrain(LocalTime.of(23, 45), false))
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg leg = TransitLeg.subway(300, 1, 20, 0, Set.of());
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
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(100, 2, 15, 0, Set.of());
        TransitLeg second = TransitLeg.subway(200, 2, 10, 3, Set.of());
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
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(100, 2, 15, 0, Set.of());
        TransitLeg second = TransitLeg.subway(200, 2, 10, 3, Set.of());
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
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(500, 1, 12, 0, Set.of());
        TransitLeg second = TransitLeg.subway(400, 2, 8, 2, Set.of());
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(0, 5), feasible.departureTime());
        assertTrue(feasible.nextDay());
    }

    /**
     * 출발지를 주소로 입력한 경우, 그 주소에서 첫 승차역까지 걷는 시간(legs.get(0)의
     * transferBufferMinutes)이 계산에 반영되어야 한다 - 승차 시각이 아니라 "출발지에서
     * 나서야 하는 시각"을 보여줘야 하기 때문이다.
     */
    @Test
    void 첫_구간_승차역까지_걷는_시간도_출발_시각에서_빼야_한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(lastTrain(LocalTime.of(23, 45), false))
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg leg = TransitLeg.subway(300, 1, 20, 12, Set.of()); // 출발지->첫 승차역 도보 12분
        LastDepartureResult result = calculator.calculate(List.of(leg));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 33), feasible.departureTime()); // 23:45 - 12분
    }

    @Test
    void 구간_리스트가_비어있으면_예외를_던진다() {
        LastTrainLookup lookup = fakeLookup(Map.of());
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

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
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        Set<String> earlierThanApgujeong = Set.of("충무로", "동대입구", "약수");
        TransitLeg leg = TransitLeg.subway(331, 2, 5, 0, earlierThanApgujeong);

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
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(100, 2, 10, 0, Set.of());
        TransitLeg second = TransitLeg.subway(200, 2, 10, 5, Set.of());
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 27), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }

    /**
     * 이슈 #5의 회귀 테스트. 실제 수유(414)->압구정(충무로 환승) 케이스에서 확인한 실제 값 그대로:
     * 공식 막차(firstLastFlag=2)인 사당행 24:21은 마감(00:14)을 7분 차로 못 맞추지만,
     * 막차로 태그되진 않은(firstLastFlag=0) 23:59 사당행은 맞는다. "공식 막차만" 보면
     * 이 열차를 후보에서 아예 놓치고 훨씬 이른 금정행(23:27)까지 건너뛰게 된다 —
     * firstLastFlag와 무관하게 전체 시간표에서 마감을 만족하는 가장 늦은 열차를 찾아야 한다.
     */
    @Test
    void 공식_막차가_아니어도_마감을_만족하면_후보로_쓴다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                414, List.of(
                        lastTrain(LocalTime.of(22, 47), false, "오이도", 2),
                        lastTrain(LocalTime.of(23, 3), false, "안산", 2),
                        lastTrain(LocalTime.of(23, 27), false, "금정", 2),
                        lastTrain(LocalTime.of(23, 59), false, "사당", 0), // 공식 막차 아님, 그러나 마감 안에 듦
                        lastTrain(LocalTime.of(0, 21), true, "사당", 2),   // 공식 막차, 그러나 마감을 7분 차로 놓침
                        lastTrain(LocalTime.of(0, 35), true, "서울역", 2)
                ),
                331, List.of(lastTrain(LocalTime.of(0, 34), true, "도곡", 2))
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(414, 2, 19, 0, Set.of());
        TransitLeg second = TransitLeg.subway(331, 2, 5, 1, Set.of());
        LastDepartureResult result = calculator.calculate(List.of(first, second));

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(23, 59), feasible.departureTime()); // 23:27이 아니라 23:59
        assertFalse(feasible.nextDay());
    }

    /**
     * 이슈 #6 — 목표 도착시간 역산. 막차와 무관하게, 그 시각까지 도착 가능한
     * 가장 늦은 후보를 찾아야 한다. 22:50 타면 20분 뒤 23:10 도착이라 23:00 마감을
     * 넘기므로, 그보다 이른 22:30(23:00 도착 전에 여유 있게 도착)이 답이어야 한다.
     */
    @Test
    void 목표_도착시간을_주면_그_시각_이내에_도착하는_가장_늦은_후보를_고른다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(
                        lastTrain(LocalTime.of(22, 30), false),
                        lastTrain(LocalTime.of(22, 50), false),
                        lastTrain(LocalTime.of(23, 10), false)
                )
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg leg = TransitLeg.subway(300, 1, 20, 0, Set.of()); // 20분 소요
        int targetArrivalMinutes = 23 * 60; // 23:00까지 도착

        LastDepartureResult result = calculator.calculate(List.of(leg), targetArrivalMinutes);

        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(22, 30), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }

    @Test
    void 목표_도착시간까지_어떤_열차로도_도착할_수_없으면_Infeasible을_반환한다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                300, List.of(lastTrain(LocalTime.of(22, 30), false))
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg leg = TransitLeg.subway(300, 1, 20, 0, Set.of());
        int targetArrivalMinutes = 6 * 60; // 06:00까지 도착 - 이 시간표엔 그렇게 이른 열차가 없음

        LastDepartureResult result = calculator.calculate(List.of(leg), targetArrivalMinutes);

        assertInstanceOf(LastDepartureResult.Infeasible.class, result);
    }

    @Test
    void 목표_도착시간_역산도_환승_구간까지_제대로_전파된다() {
        LastTrainLookup lookup = fakeLookup(Map.of(
                100, List.of(
                        lastTrain(LocalTime.of(20, 0), false),
                        lastTrain(LocalTime.of(20, 20), false),
                        lastTrain(LocalTime.of(20, 40), false)
                ),
                200, List.of(
                        lastTrain(LocalTime.of(20, 50), false),
                        lastTrain(LocalTime.of(21, 10), false)
                )
        ));
        LastDepartureCalculator calculator = LastDepartureCalculator.subwayOnly(lookup);

        TransitLeg first = TransitLeg.subway(100, 2, 15, 0, Set.of());  // 15분 소요
        TransitLeg second = TransitLeg.subway(200, 2, 10, 5, Set.of()); // 10분 소요, 환승버퍼 5분
        int targetArrivalMinutes = 21 * 60 + 30; // 21:30까지 도착

        LastDepartureResult result = calculator.calculate(List.of(first, second), targetArrivalMinutes);

        // second leg deadline = 21:30-10분 = 21:20 -> 21:10 선택
        // first leg required = 21:10-5분(버퍼) = 21:05, deadline = 21:05-15분 = 20:50 -> 20:40 선택
        LastDepartureResult.Feasible feasible = assertInstanceOf(LastDepartureResult.Feasible.class, result);
        assertEquals(LocalTime.of(20, 40), feasible.departureTime());
        assertFalse(feasible.nextDay());
    }
}

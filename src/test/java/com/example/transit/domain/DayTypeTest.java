package com.example.transit.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 지하철·버스 시간표가 평일/토요일/공휴일로 갈리므로, 날짜 -> 요일유형 판별이 틀리면
 * 막차 시각이 통째로 틀어진다. 특히 공휴일은 음력 명절 때문에 계산이 아니라 표로 관리한다.
 */
class DayTypeTest {

    @Test
    void 평일은_WEEKDAY다() {
        // 2026-08-13은 목요일
        assertEquals(DayType.WEEKDAY, DayType.from(LocalDate.of(2026, 8, 13)));
    }

    @Test
    void 토요일은_SATURDAY다() {
        // 2026-08-22는 토요일 (공휴일 아님)
        assertEquals(DayType.SATURDAY, DayType.from(LocalDate.of(2026, 8, 22)));
    }

    @Test
    void 일요일은_HOLIDAY다() {
        // 2026-08-23은 일요일
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 8, 23)));
    }

    @Test
    void 평일에_있는_공휴일은_HOLIDAY다() {
        // 2026-10-09 한글날 (금요일)
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 10, 9)));
    }

    /**
     * 공휴일이 토요일과 겹치면 토요일 시간표가 아니라 공휴일 시간표를 써야 한다
     * (공휴일 쪽이 더 적게 다녀서, 이쪽으로 잡아야 막차를 놓치지 않는다).
     */
    @Test
    void 토요일과_겹친_공휴일은_SATURDAY가_아니라_HOLIDAY다() {
        // 2026-08-15 광복절은 토요일
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 8, 15)));
    }

    @Test
    void 대체공휴일도_HOLIDAY다() {
        // 광복절(토)의 대체공휴일인 2026-08-17 월요일
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 8, 17)));
    }

    @Test
    void 공휴일_표에_없는_연도는_주말만_반영한다() {
        assertEquals(DayType.WEEKDAY, DayType.from(LocalDate.of(2030, 1, 1))); // 신정이지만 표에 없음
        assertEquals(false, KoreanHolidays.isYearCovered(LocalDate.of(2030, 1, 1)));
    }

    /**
     * from()의 시간표 분류(HOLIDAY)와 달리, 화면 표시용 라벨은 평범한 일요일과 지정된
     * 공휴일을 구분해야 한다 - 안 그러면 그냥 일요일을 국경일로 착각하게 된다.
     */
    @Test
    void 평범한_일요일의_표시라벨은_일요일이다() {
        // 2026-08-23은 일요일이지만 공휴일 표에는 없음
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 8, 23)));
        assertEquals("일요일", DayType.displayLabel(LocalDate.of(2026, 8, 23)));
    }

    @Test
    void 공휴일과_겹친_일요일의_표시라벨은_공휴일이다() {
        // 2026-03-01 삼일절은 일요일
        assertEquals(DayType.HOLIDAY, DayType.from(LocalDate.of(2026, 3, 1)));
        assertEquals("공휴일", DayType.displayLabel(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void 평일_표시라벨은_from과_같다() {
        assertEquals("평일", DayType.displayLabel(LocalDate.of(2026, 8, 13)));
    }

    @Test
    void 토요일_표시라벨은_from과_같다() {
        assertEquals("토요일", DayType.displayLabel(LocalDate.of(2026, 8, 22)));
    }
}

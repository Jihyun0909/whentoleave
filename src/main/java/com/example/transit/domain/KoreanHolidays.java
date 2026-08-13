package com.example.transit.domain;

import java.time.LocalDate;
import java.time.Month;
import java.util.Set;

/**
 * 대한민국 공휴일(대체공휴일 포함) 표.
 * <p>
 * 지하철·버스 시간표는 평일/토요일/공휴일로 나뉘는데, 설날·추석·부처님오신날은 음력이라
 * 날짜를 계산으로 구할 수 없다. 그래서 표로 들고 있는다.
 * <p>
 * <b>한계:</b> 해마다 갱신해야 한다. 표에 없는 연도는 주말만 공휴일로 처리하므로
 * (조용히 틀리는 대신) 평일 시간표로 계산된다. 제대로 하려면 공공데이터포털
 * "특일 정보" API를 붙여 연 단위로 받아오는 게 맞지만, API 키가 하나 더 필요하고
 * 이 앱은 당월 날짜만 선택할 수 있어서 지금은 표로 충분하다고 판단했다.
 */
public final class KoreanHolidays {

    private static final Set<LocalDate> HOLIDAYS = Set.of(
            // 2026
            LocalDate.of(2026, Month.JANUARY, 1),      // 신정
            LocalDate.of(2026, Month.FEBRUARY, 16),    // 설날 연휴
            LocalDate.of(2026, Month.FEBRUARY, 17),    // 설날
            LocalDate.of(2026, Month.FEBRUARY, 18),    // 설날 연휴
            LocalDate.of(2026, Month.MARCH, 1),        // 삼일절 (일)
            LocalDate.of(2026, Month.MARCH, 2),        // 삼일절 대체공휴일
            LocalDate.of(2026, Month.MAY, 5),          // 어린이날
            LocalDate.of(2026, Month.MAY, 24),         // 부처님오신날 (일)
            LocalDate.of(2026, Month.MAY, 25),         // 부처님오신날 대체공휴일
            LocalDate.of(2026, Month.JUNE, 6),         // 현충일 (토, 대체공휴일 대상 아님)
            LocalDate.of(2026, Month.AUGUST, 15),      // 광복절 (토)
            LocalDate.of(2026, Month.AUGUST, 17),      // 광복절 대체공휴일
            LocalDate.of(2026, Month.SEPTEMBER, 24),   // 추석 연휴
            LocalDate.of(2026, Month.SEPTEMBER, 25),   // 추석
            LocalDate.of(2026, Month.SEPTEMBER, 26),   // 추석 연휴
            LocalDate.of(2026, Month.OCTOBER, 3),      // 개천절 (토)
            LocalDate.of(2026, Month.OCTOBER, 5),      // 개천절 대체공휴일
            LocalDate.of(2026, Month.OCTOBER, 9),      // 한글날
            LocalDate.of(2026, Month.DECEMBER, 25)     // 성탄절
    );

    /** 표에 들어있는 연도. 이 범위 밖은 공휴일 판별을 신뢰할 수 없다. */
    private static final Set<Integer> COVERED_YEARS = Set.of(2026);

    private KoreanHolidays() {
    }

    public static boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date);
    }

    /** 이 날짜의 공휴일 판별을 신뢰할 수 있는지. 화면에서 "공휴일 정보 없음"을 알릴 때 쓴다. */
    public static boolean isYearCovered(LocalDate date) {
        return COVERED_YEARS.contains(date.getYear());
    }
}

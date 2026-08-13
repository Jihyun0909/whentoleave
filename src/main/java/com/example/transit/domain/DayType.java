package com.example.transit.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

public enum DayType {
    WEEKDAY,
    SATURDAY,
    HOLIDAY;

    /**
     * 공휴일이 토요일과 겹치면 공휴일(=일요일) 시간표가 우선이다. 지하철·버스 모두 토요일과
     * 공휴일 시간표가 다르고, 공휴일 쪽이 더 적게 다니기 때문에 이쪽으로 잡아야 안전하다.
     */
    public static DayType from(LocalDate date) {
        if (KoreanHolidays.isHoliday(date)) {
            return HOLIDAY;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            return HOLIDAY;
        }
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return SATURDAY;
        }
        return WEEKDAY;
    }

    public String label() {
        return switch (this) {
            case WEEKDAY -> "평일";
            case SATURDAY -> "토요일";
            case HOLIDAY -> "공휴일";
        };
    }

    /**
     * 화면 표시용 라벨. from()은 시간표 조회를 위해 일요일을 공휴일과 같은 칸(HOLIDAY)으로
     * 묶지만(지하철·버스 모두 실제로 같은 시간표를 쓰므로 이건 맞다), 평범한 일요일을 화면에
     * "공휴일"이라고 보여주면 국경일로 착각하게 만든다. 그래서 표시용으로만 일요일과 지정된
     * 공휴일을 구분한다 — 시간표 계산에는 영향을 주지 않는다.
     */
    public static String displayLabel(LocalDate date) {
        if (KoreanHolidays.isHoliday(date)) {
            return HOLIDAY.label();
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            return "일요일";
        }
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return SATURDAY.label();
        }
        return WEEKDAY.label();
    }
}

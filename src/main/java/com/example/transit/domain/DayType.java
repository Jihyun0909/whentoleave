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
}

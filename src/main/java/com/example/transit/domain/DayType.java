package com.example.transit.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

public enum DayType {
    WEEKDAY,
    SATURDAY,
    HOLIDAY;

    /**
     * 공휴일 판별은 하지 않는다 (공공데이터 연동 전까지는 일요일만 HOLIDAY로 취급).
     * 공휴일 API 연동은 이후 별도로 붙인다.
     */
    public static DayType from(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            return HOLIDAY;
        }
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return SATURDAY;
        }
        return WEEKDAY;
    }
}

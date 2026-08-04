package com.example.transit.service;

import java.time.LocalTime;

/**
 * ODsay는 자정 넘은 시각을 "24:35"처럼 24시 초과로 표기한다.
 * hour가 24 이상이면 24를 빼고 다음날(next_day) 여부를 함께 반환한다.
 */
public final class OdsayTimeParser {

    private OdsayTimeParser() {
    }

    public static ParsedTime parse(String raw) {
        String[] parts = raw.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);

        boolean nextDay = hour >= 24;
        if (nextDay) {
            hour -= 24;
        }
        return new ParsedTime(LocalTime.of(hour, minute), nextDay);
    }

    public record ParsedTime(LocalTime time, boolean nextDay) {
    }
}

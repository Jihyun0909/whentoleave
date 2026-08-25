package com.example.transit.service;

import java.time.LocalTime;

/**
 * TAGO는 자정 넘은 시각을 ODsay처럼 "24:35" 같은 24시 초과 표기로 주지 않고, 그냥 "00시"대로
 * 감아서 준다. 실제 응답(2026-08-20 라이브 테스트, 강남역 2호선 평일 상행)을 보면
 * {@code depTime}이 "000030"(00:00:30), "000930", ... "004630" 순으로 먼저 나오고 그 다음에야
 * "053600"(05:36:00)이 나온다 - 지하철 첫차는 보통 05시 전후이므로, 앞의 00시대 항목들은
 * 그날의 첫차가 아니라 전날 첫차부터 이어지는 "익일 새벽 막차"다.
 * <p>
 * 그래서 hour가 {@link #EARLY_MORNING_CUTOFF_HOUR} 미만이면 "다음날(nextDay)"로 본다 -
 * LastDepartureService의 EARLY_MORNING_CUTOFF_MINUTES(6시)보다는 보수적으로 4시로 잡았다
 * (지하철 최초 첫차가 대개 05시대라 4시 이전에 실제로 운행하는 열차는 없다고 봐도 안전하다).
 */
public final class TagoTimeParser {

    private static final int EARLY_MORNING_CUTOFF_HOUR = 4;

    private TagoTimeParser() {
    }

    /** @param raw "HHMMSS" 6자리 문자열 (예: "053600", "000030") - 지하철 시간표(GetSubwaySttnAcctoSchdulList)용. */
    public static ParsedTime parse(String raw) {
        return parseHourMinute(raw);
    }

    /**
     * @param raw "HHMM" 4자리 문자열 또는 그걸 숫자로 받은 값의 문자열 표현(예: "0500", "2320") -
     *            버스 노선 첫차/막차(getRouteInfoIem의 startvehicletime/endvehicletime)용.
     *            둘 다 앞 4자리만 HH/MM으로 읽으므로 같은 메서드로 처리한다.
     */
    public static ParsedTime parseHourMinute(String raw) {
        String padded = raw.length() < 4 ? "0".repeat(4 - raw.length()) + raw : raw;
        int hour = Integer.parseInt(padded.substring(0, 2));
        int minute = Integer.parseInt(padded.substring(2, 4));

        boolean nextDay = hour < EARLY_MORNING_CUTOFF_HOUR;
        return new ParsedTime(LocalTime.of(hour, minute), nextDay);
    }

    public record ParsedTime(LocalTime time, boolean nextDay) {
    }
}

package com.example.transit.service;

import com.example.transit.domain.DayType;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seed/seoul-subway-timetable.csv(data.seoul.go.kr id 527, 2026-08-27 다운로드) 적재·조회를
 * 검증한다. 실제 시드 파일을 그대로 읽으므로, 파일이 갱신되면 이 테스트의 기댓값도 같이
 * 바뀌어야 한다.
 */
class SeoulSubwayTimetableSeedCatalogTest {

    private final SeoulSubwayTimetableSeedCatalog catalog = new SeoulSubwayTimetableSeedCatalog();

    /** 사당역 4호선 상행 평일 05:32:30차(진접행) - 원본 CSV 행으로 직접 확인한 값. */
    @Test
    void 역명과_호선으로_시간표를_찾는다() {
        List<SeoulSubwayTimetableSeedCatalog.Entry> entries =
                catalog.find("사당", "4호선", 1, DayType.WEEKDAY);

        assertTrue(entries.stream().anyMatch(e ->
                e.departureTime().equals(LocalTime.of(5, 32, 30)) && "진접".equals(e.endStationName())));
    }

    /** Google이 "수도권4호선"처럼 접두어를 붙여줘도, 숫자만 뽑아 CSV의 "04호선"과 자리수 무시하고 비교한다. */
    @Test
    void 노선명_표기가_달라도_숫자만_비교한다() {
        List<SeoulSubwayTimetableSeedCatalog.Entry> withPrefix = catalog.find("사당", "수도권4호선", 1, DayType.WEEKDAY);
        List<SeoulSubwayTimetableSeedCatalog.Entry> plain = catalog.find("사당", "4호선", 1, DayType.WEEKDAY);

        assertEquals(plain, withPrefix);
        assertFalse(plain.isEmpty());
    }

    /** 자정을 넘는 시각("24:xx:xx")은 다음날로 정규화되어야 한다. */
    @Test
    void 자정_넘는_시각은_다음날로_정규화한다() {
        List<SeoulSubwayTimetableSeedCatalog.Entry> entries =
                catalog.find("영등포구청", "5호선", 1, DayType.WEEKDAY);

        assertTrue(entries.stream().anyMatch(e ->
                e.nextDay() && e.departureTime().equals(LocalTime.of(0, 7, 10)) && "방화".equals(e.endStationName())));
    }

    /** 진접선(4호선 연장) 구간도 역명 그대로 커버된다 - TAGO/서울교통공사 API 둘 다 비어 있는 구간. */
    @Test
    void 진접선_연장구간도_찾을_수_있다() {
        List<SeoulSubwayTimetableSeedCatalog.Entry> entries =
                catalog.find("진접", "4호선", 1, DayType.WEEKDAY);

        assertFalse(entries.isEmpty());
    }

    @Test
    void 역명이_없으면_빈_목록을_반환한다() {
        assertTrue(catalog.find("존재하지않는역이름", "4호선", 1, DayType.WEEKDAY).isEmpty());
    }

    @Test
    void 호선을_못_뽑으면_빈_목록을_반환한다() {
        assertTrue(catalog.find("사당", "분당선", 1, DayType.WEEKDAY).isEmpty());
    }

    @Test
    void null_입력은_빈_목록을_반환한다() {
        assertTrue(catalog.find(null, "4호선", 1, DayType.WEEKDAY).isEmpty());
        assertTrue(catalog.find("사당", null, 1, DayType.WEEKDAY).isEmpty());
    }
}

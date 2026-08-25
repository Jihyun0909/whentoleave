package com.example.transit.service;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * seed/seoul-bus-route-schedule.csv(TOPIS 2026년 7월 인가현황 기준) 적재·조회를 검증한다.
 * 실제 시드 파일을 그대로 읽으므로, 파일이 갱신되면 이 테스트의 기댓값도 같이 바뀌어야 한다.
 */
class SeoulBusRouteScheduleCatalogTest {

    private final SeoulBusRouteScheduleCatalog catalog = new SeoulBusRouteScheduleCatalog();

    @Test
    void 노선번호로_첫차_막차_배차간격을_찾는다() {
        Optional<SeoulBusRouteScheduleCatalog.RouteSchedule> result = catalog.find("146");

        SeoulBusRouteScheduleCatalog.RouteSchedule schedule = result.orElseThrow();
        assertEquals(LocalTime.of(4, 5), schedule.firstTime());
        assertEquals(LocalTime.of(23, 0), schedule.lastTime());
        assertFalse(schedule.lastTimeNextDay());
        assertEquals(12, schedule.intervalMinutes());
    }

    /** 자정을 넘는 막차(예: 00:xx)는 "다음날 그 시각"으로 표시돼야 역산 계산이 꼬이지 않는다. */
    @Test
    void 막차가_자정을_넘으면_다음날로_표시한다() {
        SeoulBusRouteScheduleCatalog.RouteSchedule schedule = catalog.find("201").orElseThrow();

        assertEquals(LocalTime.of(0, 0), schedule.lastTime());
        assertTrue(schedule.lastTimeNextDay());
    }

    @Test
    void 없는_노선번호는_빈_값을_반환한다() {
        assertTrue(catalog.find("존재하지않는노선").isEmpty());
    }

    @Test
    void null_노선번호도_빈_값을_반환한다() {
        assertTrue(catalog.find(null).isEmpty());
    }
}

package com.example.transit.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 좌표 -> 서울/TAGO/경기/인천 순서로 시도하는 지역 라우팅을 검증한다. 각 지역의 정류소 매칭·도착정보
 * 조회는 전부 함수형 인터페이스라 람다 페이크로 조합해서, 우선순위와 폴백 조건만 확인한다.
 */
class RegionalBusArrivalLookupTest {

    private static final RealtimeBusArrival SAMPLE = new RealtimeBusArrival("140", 180, 3, "서울70사1234");
    private static final RealtimeSeoulBusArrivalLookup NO_SEOUL = (x, y) -> List.of();

    /**
     * 서울을 맨 앞에 두는 이유의 회귀 테스트. TAGO는 서울 시내버스를 커버하지 않으면서도 그 자리를
     * 지나는 경기/인천 광역버스 정류소는 돌려주기 때문에, 서울을 뒤에 두면 엉뚱한 노선이 먼저 걸린다.
     */
    @Test
    void 서울에서_찾으면_나머지_지역은_부르지_않는다() {
        int[] tagoCalls = {0};

        RegionalBusArrivalLookup lookup = new RegionalBusArrivalLookup(
                (x, y) -> List.of(SAMPLE),
                (x, y) -> {
                    tagoCalls[0]++;
                    return Optional.empty();
                },
                (cityCode, nodeId) -> List.of(),
                (x, y) -> Optional.empty(),
                stationId -> List.of(),
                (x, y) -> Optional.empty(),
                stationId -> List.of());

        List<RealtimeBusArrival> result = lookup.findArrivals(127.0, 37.5);

        assertEquals(List.of(SAMPLE), result);
        assertEquals(0, tagoCalls[0]);
    }

    @Test
    void TAGO에서_찾으면_경기_인천은_부르지_않는다() {
        int[] gyeonggiCalls = {0};
        int[] incheonCalls = {0};

        RegionalBusArrivalLookup lookup = new RegionalBusArrivalLookup(
                NO_SEOUL,
                (x, y) -> Optional.of(new TagoBusStopLookup.TagoStop("25", "12345")),
                (cityCode, nodeId) -> List.of(SAMPLE),
                (x, y) -> {
                    gyeonggiCalls[0]++;
                    return Optional.empty();
                },
                stationId -> List.of(),
                (x, y) -> {
                    incheonCalls[0]++;
                    return Optional.empty();
                },
                stationId -> List.of());

        List<RealtimeBusArrival> result = lookup.findArrivals(127.0, 37.5);

        assertEquals(List.of(SAMPLE), result);
        assertEquals(0, gyeonggiCalls[0]);
        assertEquals(0, incheonCalls[0]);
    }

    @Test
    void TAGO가_빈_목록이면_경기로_폴백한다() {
        RegionalBusArrivalLookup lookup = new RegionalBusArrivalLookup(
                NO_SEOUL,
                (x, y) -> Optional.of(new TagoBusStopLookup.TagoStop("25", "12345")),
                (cityCode, nodeId) -> List.of(),
                (x, y) -> Optional.of("gg-station-1"),
                stationId -> List.of(SAMPLE),
                (x, y) -> Optional.empty(),
                stationId -> List.of());

        List<RealtimeBusArrival> result = lookup.findArrivals(127.0, 37.5);

        assertEquals(List.of(SAMPLE), result);
    }

    @Test
    void TAGO_경기_다_빈_목록이면_인천으로_폴백한다() {
        RegionalBusArrivalLookup lookup = new RegionalBusArrivalLookup(
                NO_SEOUL,
                (x, y) -> Optional.empty(),
                (cityCode, nodeId) -> List.of(),
                (x, y) -> Optional.empty(),
                stationId -> List.of(),
                (x, y) -> Optional.of("ic-station-1"),
                stationId -> List.of(SAMPLE));

        List<RealtimeBusArrival> result = lookup.findArrivals(127.0, 37.5);

        assertEquals(List.of(SAMPLE), result);
    }

    @Test
    void 넷_다_못_찾으면_빈_목록을_준다() {
        RegionalBusArrivalLookup lookup = new RegionalBusArrivalLookup(
                NO_SEOUL,
                (x, y) -> Optional.empty(),
                (cityCode, nodeId) -> List.of(),
                (x, y) -> Optional.empty(),
                stationId -> List.of(),
                (x, y) -> Optional.empty(),
                stationId -> List.of());

        assertTrue(lookup.findArrivals(127.0, 37.5).isEmpty());
    }
}

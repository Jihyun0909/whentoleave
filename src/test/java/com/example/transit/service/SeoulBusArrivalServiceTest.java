package com.example.transit.service;

import com.example.transit.service.client.SeoulBusApiClient;
import com.example.transit.service.client.SeoulBusStopApiClient;
import com.example.transit.service.client.dto.SeoulBusArrivalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서울시 버스도착정보 응답을 공통 형태로 바꾸는 부분을 검증한다. 응답 스키마는 2026-08-18
 * 실제 키로 확인한 값(수유역.강북구청)을 기준으로 만들었다.
 */
class SeoulBusArrivalServiceTest {

    private static final double STOP_X = 127.0258703828;
    private static final double STOP_Y = 37.6382895216;

    @Test
    void 첫번째_두번째_도착버스를_각각_한_건으로_펼친다() {
        SeoulBusArrivalService service = serviceWith(okResponse(
                item("148", 145, "서울70사1234", 307, "서울70사5678")));

        List<RealtimeBusArrival> result = service.findArrivals(STOP_X, STOP_Y);

        assertEquals(2, result.size());
        assertEquals("148", result.get(0).routeName());
        assertEquals(145, result.get(0).secondsUntilArrival());
        assertEquals(307, result.get(1).secondsUntilArrival());
    }

    /**
     * 운행종료·출발대기 버스는 남은 시간이 0으로 온다. 그대로 카운트다운에 넣으면 화면에서
     * "곧 도착"으로 보여 오지도 않는 버스를 기다리게 되므로, 도착 예정이 아니라 상태로 분류한다.
     */
    @Test
    void 남은_시간이_0인_버스는_도착예정이_아니라_상태로_분류한다() {
        SeoulBusArrivalService service = serviceWith(okResponse(
                item("120", 93, "서울70사1111", 0, null)));

        List<RealtimeBusArrival> result = service.findArrivals(STOP_X, STOP_Y);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isArriving());
        assertEquals(93, result.get(0).secondsUntilArrival());
    }

    /** 운행 시간이 끝난 노선은 막차 시각까지 같이 알려줘야 판단이 된다. */
    @Test
    void 운행종료면_막차_시각을_함께_알려준다() {
        SeoulBusArrivalService service = serviceWith(okResponse(
                item("8101", 0, null, 0, null, "운행종료", "20260820081000", "10")));

        List<RealtimeBusArrival> result = service.findArrivals(STOP_X, STOP_Y);

        assertEquals(1, result.size());
        assertFalse(result.get(0).isArriving());
        assertEquals("운행 종료 · 막차 08:10", result.get(0).statusLabel());
    }

    /** 운행 시간인데 아직 출발 전이면 배차간격을 같이 알려준다. */
    @Test
    void 출발대기면_배차간격을_함께_알려준다() {
        SeoulBusArrivalService service = serviceWith(okResponse(
                item("6102", 0, null, 0, null, "출발대기", "20260820184900", "40")));

        List<RealtimeBusArrival> result = service.findArrivals(STOP_X, STOP_Y);

        assertEquals(1, result.size());
        assertEquals("출발대기 · 배차 40분", result.get(0).statusLabel());
    }

    /** 심야 노선처럼 배차간격이 0(의미 없는 값)이면 그 부분은 빼고 상태만 알려준다. */
    @Test
    void 배차간격이_0이면_상태만_알려준다() {
        SeoulBusArrivalService service = serviceWith(okResponse(
                item("새벽A148", 0, null, 0, null, "출발대기", "20260821034700", "0")));

        List<RealtimeBusArrival> result = service.findArrivals(STOP_X, STOP_Y);

        assertEquals("출발대기", result.get(0).statusLabel());
    }

    /** headerCd "4"(결과 없음)는 실패가 아니라 빈 목록이다. */
    @Test
    void 결과가_없으면_빈_목록을_준다() {
        SeoulBusArrivalService service = serviceWith(new SeoulBusArrivalResponse(
                new SeoulBusArrivalResponse.MsgHeader("결과가 없습니다.", "4", 0), null));

        assertTrue(service.findArrivals(STOP_X, STOP_Y).isEmpty());
    }

    @Test
    void 조회가_실패해도_예외_대신_빈_목록을_준다() {
        SeoulBusApiClient failing = new SeoulBusApiClient("http://dummy", "dummy-key") {
            @Override
            public SeoulBusArrivalResponse findArrivals(String stopId) {
                throw new IllegalStateException("API 실패");
            }
        };
        SeoulBusArrivalService service = new SeoulBusArrivalService(catalogWithStop(), failing);

        assertTrue(service.findArrivals(STOP_X, STOP_Y).isEmpty());
    }

    /** 근처에 서울 정류소가 없으면(경기/인천 지역 좌표 등) 도착정보 API를 부르지 않는다. */
    @Test
    void 근처_정류소가_없으면_도착정보를_조회하지_않는다() {
        SeoulBusApiClient neverCalled = new SeoulBusApiClient("http://dummy", "dummy-key") {
            @Override
            public SeoulBusArrivalResponse findArrivals(String stopId) {
                throw new AssertionError("정류소를 못 찾으면 도착정보를 조회하면 안 됨");
            }
        };
        SeoulBusArrivalService service = new SeoulBusArrivalService(catalogWithStop(), neverCalled);

        // 부산 좌표 - 서울 정류소 목록에서 200m 이내로 잡힐 리 없다.
        assertTrue(service.findArrivals(129.0756, 35.1796).isEmpty());
    }

    @Test
    void 키가_없으면_아무것도_조회하지_않는다() {
        SeoulBusApiClient noKey = new SeoulBusApiClient("http://dummy", "") {
            @Override
            public SeoulBusArrivalResponse findArrivals(String stopId) {
                throw new AssertionError("키가 없으면 조회하면 안 됨");
            }
        };
        SeoulBusArrivalService service = new SeoulBusArrivalService(catalogWithStop(), noKey);

        assertTrue(service.findArrivals(STOP_X, STOP_Y).isEmpty());
    }

    private SeoulBusArrivalService serviceWith(SeoulBusArrivalResponse response) {
        SeoulBusApiClient client = new SeoulBusApiClient("http://dummy", "dummy-key") {
            @Override
            public SeoulBusArrivalResponse findArrivals(String stopId) {
                return response;
            }
        };
        return new SeoulBusArrivalService(catalogWithStop(), client);
    }

    /** 수유역.강북구청 한 곳만 들고 있는 정류소 카탈로그 페이크. */
    private SeoulBusStopCatalog catalogWithStop() {
        SeoulBusStopApiClient stopClient = new SeoulBusStopApiClient("http://dummy", "dummy-key") {
            @Override
            public tools.jackson.databind.JsonNode findStops(int startIndex, int endIndex) {
                String json = """
                        { "busStopLocationXyInfo": { "list_total_count": 1, "row": [
                          { "STOPS_NO": "108000378", "STOPS_NM": "수유역.강북구청",
                            "XCRD": "127.0258703828", "YCRD": "37.6382895216", "NODE_ID": "09013" }
                        ] } }
                        """;
                return new tools.jackson.databind.ObjectMapper().readTree(json);
            }
        };
        return new SeoulBusStopCatalog(stopClient);
    }

    private SeoulBusArrivalResponse okResponse(SeoulBusArrivalResponse.Item... items) {
        return new SeoulBusArrivalResponse(
                new SeoulBusArrivalResponse.MsgHeader("정상적으로 처리되었습니다.", "0", items.length),
                new SeoulBusArrivalResponse.MsgBody(List.of(items)));
    }

    private SeoulBusArrivalResponse.Item item(String routeName, int seconds1, String plate1,
                                               int seconds2, String plate2) {
        return item(routeName, seconds1, plate1, seconds2, plate2, "곧 도착", "20260820224400", "8");
    }

    private SeoulBusArrivalResponse.Item item(String routeName, int seconds1, String plate1,
                                               int seconds2, String plate2,
                                               String arrmsg1, String lastTm, String term) {
        return new SeoulBusArrivalResponse.Item(routeName, "09013", "수유역.강북구청",
                "20260820040100", lastTm, term,
                seconds1, arrmsg1, plate1, "0", seconds2, arrmsg1, plate2, "0");
    }
}

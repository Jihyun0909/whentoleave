package com.example.transit.service;

import com.example.transit.service.client.SeoulSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실시간 지하철 도착정보 조회/변환 검증. 서울 열린데이터광장 응답의 정확한 필드명은 아직
 * 실제 키로 확인되지 않았으므로, SeoulSubwayArrivalResponse에 적어둔 문서 기준 필드명으로
 * 응답을 만들어 매핑 로직이 의도대로 동작하는지를 확인한다.
 */
class SeoulSubwayArrivalServiceTest {

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() {
        CountingClient client = new CountingClient("", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 역이름이_비어있으면_API를_부르지_않는다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals(" ");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 응답을_도착정보_목록으로_변환한다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());

        RealtimeSubwayArrivalLookup.SubwayArrival first = result.get(0);
        assertEquals("상행", first.direction());
        assertEquals("성수", first.destinationStationName());
        assertEquals("성수행 - 신설동방면", first.headsign());
        assertEquals("전역 도착", first.arrivalMessage());
        assertEquals(97, first.secondsUntilArrival());
        assertFalse(first.isLastTrain());

        RealtimeSubwayArrivalLookup.SubwayArrival second = result.get(1);
        assertTrue(second.isLastTrain());
    }

    @Test
    void 도착시간이_숫자가_아니면_null로_준다() {
        SeoulSubwayArrivalResponse response = new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1002", "상행", "성수행", "강남",
                        "전역 도착", "성수", "전역 도착", "신설동", "1", "0", "2026-08-14 10:00:00")));
        CountingClient client = new CountingClient("dummy-key", response);
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(client);

        List<RealtimeSubwayArrivalLookup.SubwayArrival> result = service.findArrivals("강남");

        assertNull(result.get(0).secondsUntilArrival());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        SeoulSubwayApiClient failing = new SeoulSubwayApiClient("http://dummy", "dummy-key") {
            @Override
            public SeoulSubwayArrivalResponse findArrivals(String stationName) {
                throw new IllegalStateException("API 실패");
            }
        };
        SeoulSubwayArrivalService service = new SeoulSubwayArrivalService(failing);

        assertTrue(service.findArrivals("강남").isEmpty());
    }

    private SeoulSubwayArrivalResponse sampleResponse() {
        return new SeoulSubwayArrivalResponse(null, List.of(
                new SeoulSubwayArrivalResponse.Arrival("1002", "상행", "성수행 - 신설동방면", "강남",
                        "97", "성수", "전역 도착", "신설동", "1", "0", "2026-08-14 10:00:00"),
                new SeoulSubwayArrivalResponse.Arrival("1002", "하행", "역삼행", "강남",
                        "180", "역삼", "3분 후 (당역 종료)", "역삼", "0", "1", "2026-08-14 10:00:00")));
    }

    private static class CountingClient extends SeoulSubwayApiClient {
        private final SeoulSubwayArrivalResponse response;
        private int calls;

        CountingClient(String apiKey, SeoulSubwayArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public SeoulSubwayArrivalResponse findArrivals(String stationName) {
            calls++;
            return response;
        }
    }
}

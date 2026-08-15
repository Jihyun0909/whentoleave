package com.example.transit.service;

import com.example.transit.service.client.IncheonBusApiClient;
import com.example.transit.service.client.dto.IncheonBusArrivalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인천 BIMS 버스 도착정보 조회/변환 검증.
 * 2026-08-14 라이브 테스트로 확인한 실제 응답 스키마(XML, 항목당 버스 1대) 기준.
 */
class IncheonBusArrivalServiceTest {

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() {
        CountingClient client = new CountingClient("", sampleResponse());
        IncheonBusArrivalService service = new IncheonBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("54321");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 항목별_버스_한_대씩을_도착정보로_변환한다() {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        IncheonBusArrivalService service = new IncheonBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("54321");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());
        assertEquals("165000001", result.get(0).routeName());
        assertEquals(120, result.get(0).secondsUntilArrival());
        assertEquals(3, result.get(0).remainingStopCount());
        assertEquals("인천70가1234", result.get(0).plateNo());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        IncheonBusApiClient failing = new IncheonBusApiClient("http://dummy", "dummy-key") {
            @Override
            public IncheonBusArrivalResponse findArrivals(String bstopId) {
                throw new IllegalStateException("API 실패");
            }
        };
        IncheonBusArrivalService service = new IncheonBusArrivalService(failing);

        assertTrue(service.findArrivals("54321").isEmpty());
    }

    private IncheonBusArrivalResponse sampleResponse() {
        return new IncheonBusArrivalResponse(List.of(
                new IncheonBusArrivalResponse.Item("54321", "165000001", "인천70가1234", 3, 120, false),
                new IncheonBusArrivalResponse.Item("54321", "165000002", "인천71나5678", 10, 600, true)));
    }

    private static class CountingClient extends IncheonBusApiClient {
        private final IncheonBusArrivalResponse response;
        private int calls;

        CountingClient(String apiKey, IncheonBusArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public IncheonBusArrivalResponse findArrivals(String bstopId) {
            calls++;
            return response;
        }
    }
}

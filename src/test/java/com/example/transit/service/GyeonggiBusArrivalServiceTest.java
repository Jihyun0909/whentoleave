package com.example.transit.service;

import com.example.transit.service.client.GyeonggiBusApiClient;
import com.example.transit.service.client.dto.RegionalBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GBIS 버스 도착정보 조회/변환 검증. 정확한 응답 스키마는 실제 키로 확인되지 않았으므로,
 * RegionalBusArrivalResponse 클래스 주석에 적어둔 문서 기준 필드명으로 응답을 만들어
 * predictTime1/2를 각각 별도 도착정보로 펼치는 로직과 -1(정보없음) 필터링을 확인한다.
 */
class GyeonggiBusArrivalServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() throws Exception {
        CountingClient client = new CountingClient("", responseWithArrayList());
        GyeonggiBusArrivalService service = new GyeonggiBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("12345");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 한_노선의_두_버스를_각각_도착정보로_펼친다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", responseWithArrayList());
        GyeonggiBusArrivalService service = new GyeonggiBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("12345");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());
        assertEquals("720", result.get(0).routeName());
        assertEquals(180, result.get(0).secondsUntilArrival()); // 3분 -> 180초
        assertEquals("720", result.get(1).routeName());
        assertEquals(900, result.get(1).secondsUntilArrival()); // 15분 -> 900초
    }

    @Test
    void predictTime가_음수면_해당_버스는_제외한다() throws Exception {
        RegionalBusArrivalResponse response = mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0, "resultMessage": "SUCCESS" },
                  "msgBody": { "busArrivalList":
                    { "routeName": "720", "predictTime1": -1, "predictTime2": 15,
                      "locationNo1": 0, "locationNo2": 8, "plateNo1": null, "plateNo2": "경기71아5678" }
                  } } }
                """, RegionalBusArrivalResponse.class);
        CountingClient client = new CountingClient("dummy-key", response);
        GyeonggiBusArrivalService service = new GyeonggiBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("12345");

        assertEquals(1, result.size());
        assertEquals(900, result.get(0).secondsUntilArrival());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        GyeonggiBusApiClient failing = new GyeonggiBusApiClient("http://dummy", "dummy-key") {
            @Override
            public RegionalBusArrivalResponse findArrivals(String stationId) {
                throw new IllegalStateException("API 실패");
            }
        };
        GyeonggiBusArrivalService service = new GyeonggiBusArrivalService(failing);

        assertTrue(service.findArrivals("12345").isEmpty());
    }

    private RegionalBusArrivalResponse responseWithArrayList() throws Exception {
        return mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0, "resultMessage": "SUCCESS" },
                  "msgBody": { "busArrivalList":
                    { "routeName": "720", "predictTime1": 3, "predictTime2": 15,
                      "locationNo1": 2, "locationNo2": 8,
                      "plateNo1": "경기70바1234", "plateNo2": "경기71아5678" }
                  } } }
                """, RegionalBusArrivalResponse.class);
    }

    private static class CountingClient extends GyeonggiBusApiClient {
        private final RegionalBusArrivalResponse response;
        private int calls;

        CountingClient(String apiKey, RegionalBusArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public RegionalBusArrivalResponse findArrivals(String stationId) {
            calls++;
            return response;
        }
    }
}

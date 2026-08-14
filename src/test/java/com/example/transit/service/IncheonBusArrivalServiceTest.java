package com.example.transit.service;

import com.example.transit.service.client.IncheonBusApiClient;
import com.example.transit.service.client.dto.RegionalBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인천 BIMS 버스 도착정보 조회/변환 검증. 경기(GBIS)와 같은 스키마를 가정한 구현이라
 * 테스트도 GyeonggiBusArrivalServiceTest와 같은 형태의 응답으로 검증한다.
 */
class IncheonBusArrivalServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() throws Exception {
        CountingClient client = new CountingClient("", sampleResponse());
        IncheonBusArrivalService service = new IncheonBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("54321");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 한_노선의_두_버스를_각각_도착정보로_펼친다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", sampleResponse());
        IncheonBusArrivalService service = new IncheonBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("54321");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());
        assertEquals("583", result.get(0).routeName());
        assertEquals(120, result.get(0).secondsUntilArrival());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        IncheonBusApiClient failing = new IncheonBusApiClient("http://dummy", "dummy-key") {
            @Override
            public RegionalBusArrivalResponse findArrivals(String stationId) {
                throw new IllegalStateException("API 실패");
            }
        };
        IncheonBusArrivalService service = new IncheonBusArrivalService(failing);

        assertTrue(service.findArrivals("54321").isEmpty());
    }

    private RegionalBusArrivalResponse sampleResponse() throws Exception {
        return mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0, "resultMessage": "SUCCESS" },
                  "msgBody": { "busArrivalList":
                    { "routeName": "583", "predictTime1": 2, "predictTime2": 20,
                      "locationNo1": 1, "locationNo2": 10,
                      "plateNo1": "인천70가1234", "plateNo2": "인천71나5678" }
                  } } }
                """, RegionalBusArrivalResponse.class);
    }

    private static class CountingClient extends IncheonBusApiClient {
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

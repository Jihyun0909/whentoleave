package com.example.transit.service;

import com.example.transit.service.client.IncheonStationApiClient;
import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncheonStationFinderTest {

    private static final double X = 126.7052;
    private static final double Y = 37.4563;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_값을_준다() throws Exception {
        CountingClient client = new CountingClient("", rowsInNamedField());
        IncheonStationFinder finder = new IncheonStationFinder(client);

        Optional<String> result = finder.findNearestStationId(X, Y);

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 이름_붙은_필드_안의_배열에서도_가장_가까운_정류소를_찾는다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", rowsInNamedField());
        IncheonStationFinder finder = new IncheonStationFinder(client);

        assertEquals(Optional.of("54321"), finder.findNearestStationId(X, Y));
        assertEquals(1, client.calls);
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_값을_준다() {
        IncheonStationApiClient failing = new IncheonStationApiClient("http://dummy", "dummy-key") {
            @Override
            public RegionalBusStationResponse findStationsNearPosition(double x, double y) {
                throw new IllegalStateException("API 실패");
            }
        };
        IncheonStationFinder finder = new IncheonStationFinder(failing);

        assertTrue(finder.findNearestStationId(X, Y).isEmpty());
    }

    private RegionalBusStationResponse rowsInNamedField() throws Exception {
        return mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0 }, "msgBody": { "busStationAroundList": [
                    { "stationId": "54321", "stationName": "가까운정류장", "x": "126.7053", "y": "37.4564" },
                    { "stationId": "11111", "stationName": "먼정류장", "x": "126.9000", "y": "37.6000" }
                  ] } } }
                """, RegionalBusStationResponse.class);
    }

    private static class CountingClient extends IncheonStationApiClient {
        private final RegionalBusStationResponse response;
        private int calls;

        CountingClient(String apiKey, RegionalBusStationResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public RegionalBusStationResponse findStationsNearPosition(double x, double y) {
            calls++;
            return response;
        }
    }
}

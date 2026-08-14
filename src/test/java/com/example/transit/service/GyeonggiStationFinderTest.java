package com.example.transit.service;

import com.example.transit.service.client.GyeonggiStationApiClient;
import com.example.transit.service.client.dto.RegionalBusStationResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GyeonggiStationFinderTest {

    private static final double X = 127.0276;
    private static final double Y = 37.4979;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_값을_준다() throws Exception {
        CountingClient client = new CountingClient("", rowsInNamedField());
        GyeonggiStationFinder finder = new GyeonggiStationFinder(client);

        Optional<String> result = finder.findNearestStationId(X, Y);

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 이름_붙은_필드_안의_배열에서도_가장_가까운_정류소를_찾는다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", rowsInNamedField());
        GyeonggiStationFinder finder = new GyeonggiStationFinder(client);

        Optional<String> result = finder.findNearestStationId(X, Y);

        assertEquals(1, client.calls);
        assertEquals(Optional.of("12345"), result);
    }

    @Test
    void msgBody가_직접_배열이어도_찾는다() throws Exception {
        RegionalBusStationResponse response = mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0 }, "msgBody": [
                    { "stationId": "99999", "x": "127.0277", "y": "37.4980" }
                  ] } }
                """, RegionalBusStationResponse.class);
        CountingClient client = new CountingClient("dummy-key", response);
        GyeonggiStationFinder finder = new GyeonggiStationFinder(client);

        assertEquals(Optional.of("99999"), finder.findNearestStationId(X, Y));
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_값을_준다() {
        GyeonggiStationApiClient failing = new GyeonggiStationApiClient("http://dummy", "dummy-key") {
            @Override
            public RegionalBusStationResponse findStationsNearPosition(double x, double y) {
                throw new IllegalStateException("API 실패");
            }
        };
        GyeonggiStationFinder finder = new GyeonggiStationFinder(failing);

        assertTrue(finder.findNearestStationId(X, Y).isEmpty());
    }

    private RegionalBusStationResponse rowsInNamedField() throws Exception {
        return mapper.readValue("""
                { "response": { "msgHeader": { "resultCode": 0 }, "msgBody": { "busStationAroundList": [
                    { "stationId": "12345", "stationName": "가까운정류장", "x": "127.0277", "y": "37.4980" },
                    { "stationId": "67890", "stationName": "먼정류장", "x": "127.2000", "y": "37.7000" }
                  ] } } }
                """, RegionalBusStationResponse.class);
    }

    private static class CountingClient extends GyeonggiStationApiClient {
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

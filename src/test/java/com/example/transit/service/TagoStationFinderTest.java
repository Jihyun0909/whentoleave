package com.example.transit.service;

import com.example.transit.service.client.TagoStationApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TagoStationFinderTest {

    private static final double X = 127.0276;
    private static final double Y = 37.4979;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_값을_준다() throws Exception {
        CountingClient client = new CountingClient("", rowsJson());
        TagoStationFinder finder = new TagoStationFinder(client);

        Optional<TagoBusStopLookup.TagoStop> result = finder.findNearestStop(X, Y);

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 가장_가까운_정류소의_지역코드와_노드아이디를_돌려준다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", rowsJson());
        TagoStationFinder finder = new TagoStationFinder(client);

        Optional<TagoBusStopLookup.TagoStop> result = finder.findNearestStop(X, Y);

        assertTrue(result.isPresent());
        assertEquals("25", result.get().cityCode());
        assertEquals("GGB123", result.get().nodeId());
    }

    @Test
    void 지역코드_필드가_없으면_빈_값을_준다() throws Exception {
        TagoBusArrivalResponse response = mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE" },
                  "body": { "items": { "item":
                    { "nodeid": "GGB123", "nodenm": "정류장", "gpslong": "127.0277", "gpslati": "37.4980" }
                  } } } }
                """, TagoBusArrivalResponse.class);
        CountingClient client = new CountingClient("dummy-key", response);
        TagoStationFinder finder = new TagoStationFinder(client);

        assertTrue(finder.findNearestStop(X, Y).isEmpty());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_값을_준다() {
        TagoStationApiClient failing = new TagoStationApiClient("http://dummy", "dummy-key") {
            @Override
            public TagoBusArrivalResponse findStationsNearPosition(double x, double y) {
                throw new IllegalStateException("API 실패");
            }
        };
        TagoStationFinder finder = new TagoStationFinder(failing);

        assertTrue(finder.findNearestStop(X, Y).isEmpty());
    }

    private TagoBusArrivalResponse rowsJson() throws Exception {
        return mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE" },
                  "body": { "items": { "item": [
                    { "nodeid": "GGB123", "nodenm": "가까운정류장", "citycode": "25",
                      "gpslong": "127.0277", "gpslati": "37.4980" },
                    { "nodeid": "GGB999", "nodenm": "먼정류장", "citycode": "25",
                      "gpslong": "127.2000", "gpslati": "37.7000" }
                  ] } } } }
                """, TagoBusArrivalResponse.class);
    }

    private static class CountingClient extends TagoStationApiClient {
        private final TagoBusArrivalResponse response;
        private int calls;

        CountingClient(String apiKey, TagoBusArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public TagoBusArrivalResponse findStationsNearPosition(double x, double y) {
            calls++;
            return response;
        }
    }
}

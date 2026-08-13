package com.example.transit.service;

import com.example.transit.service.client.SeoulOpenApiClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서울시 정류소 매칭 로직 검증. 실제 서울 열린데이터광장 응답 형태(정확한 필드명)는 아직
 * 키로 검증되지 못했으므로, 클래스 주석에 적어둔 필드명 후보 중 하나를 쓰는 응답으로 테스트한다 -
 * "여러 후보 이름을 시도하는" 방어적 파싱이 실제로 동작하는지가 이 테스트의 핵심이다.
 */
class SeoulStationFinderTest {

    private static final double X = 127.0276;
    private static final double Y = 37.4979;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_값을_준다() throws Exception {
        CountingClient client = new CountingClient("", rowsJson());
        SeoulStationFinder finder = new SeoulStationFinder(client);

        Optional<RealtimeStationFinder.StationMatch> result = finder.findNearestStation(X, Y);

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 여러_정류소_중_가장_가까운_곳을_고른다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", rowsJson());
        SeoulStationFinder finder = new SeoulStationFinder(client);

        Optional<RealtimeStationFinder.StationMatch> result = finder.findNearestStation(X, Y);

        assertTrue(result.isPresent());
        assertEquals("23155", result.get().stationId()); // 좌표가 더 가까운 쪽
        assertEquals("강남역", result.get().stationName());
    }

    @Test
    void 알아보는_필드가_하나도_없으면_빈_값을_준다() throws Exception {
        JsonNode unknownShape = mapper.readTree("""
                { "someService": { "row": [ { "unknownField": "x" } ] } }
                """);
        CountingClient client = new CountingClient("dummy-key", unknownShape);
        SeoulStationFinder finder = new SeoulStationFinder(client);

        Optional<RealtimeStationFinder.StationMatch> result = finder.findNearestStation(X, Y);

        assertTrue(result.isEmpty());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_값을_준다() {
        SeoulOpenApiClient failing = new SeoulOpenApiClient("http://dummy", "svc", "dummy-key") {
            @Override
            public JsonNode findStationsNearPosition(double x, double y) {
                throw new IllegalStateException("API 실패");
            }
        };
        SeoulStationFinder finder = new SeoulStationFinder(failing);

        assertTrue(finder.findNearestStation(X, Y).isEmpty());
    }

    private JsonNode rowsJson() throws Exception {
        // X/Y_CODE 필드명 후보를 쓴다. 강남역이 더 가깝고, 먼 정류소도 하나 섞어 최근접 선택을 검증한다.
        return mapper.readTree("""
                { "busStationLocationXyInfo": { "row": [
                    { "STOPS_NO": "23155", "STOPS_NM": "강남역", "X_CODE": "127.0277", "Y_CODE": "37.4980" },
                    { "STOPS_NO": "23999", "STOPS_NM": "먼정류장", "X_CODE": "127.1000", "Y_CODE": "37.6000" }
                ] } }
                """);
    }

    private static class CountingClient extends SeoulOpenApiClient {
        private final JsonNode response;
        private int calls;

        CountingClient(String apiKey, JsonNode response) {
            super("http://dummy", "busStationLocationXyInfo", apiKey);
            this.response = response;
        }

        @Override
        public JsonNode findStationsNearPosition(double x, double y) {
            calls++;
            return response;
        }
    }
}

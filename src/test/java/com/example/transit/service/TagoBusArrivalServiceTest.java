package com.example.transit.service;

import com.example.transit.service.client.TagoBusApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TAGO 버스 도착정보 조회/변환 검증. 정확한 응답 스키마는 실제 키로 확인되지 않았으므로,
 * TagoBusArrivalResponse 클래스 주석에 적어둔 문서 기준 필드명으로 응답을 만들어
 * item이 배열/단일 객체 두 경우 모두 잘 정규화되는지를 확인한다.
 */
class TagoBusArrivalServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 키가_없으면_API를_부르지_않고_빈_목록을_준다() throws Exception {
        CountingClient client = new CountingClient("", responseWithArrayItems());
        TagoBusArrivalService service = new TagoBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("25", "12345");

        assertTrue(result.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void 여러_건이면_배열로_와도_전부_변환한다() throws Exception {
        CountingClient client = new CountingClient("dummy-key", responseWithArrayItems());
        TagoBusArrivalService service = new TagoBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("25", "12345");

        assertEquals(1, client.calls);
        assertEquals(2, result.size());
        assertEquals("146", result.get(0).routeName());
        assertEquals(97, result.get(0).secondsUntilArrival());
        assertEquals(2, result.get(0).remainingStopCount());
    }

    @Test
    void 한_건이면_단일_객체로_와도_변환한다() throws Exception {
        TagoBusArrivalResponse response = mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE" },
                  "body": { "items": { "item":
                    { "routeno": "740", "arrtime": "210", "arrprevstationcnt": "5" }
                  } } } }
                """, TagoBusArrivalResponse.class);
        CountingClient client = new CountingClient("dummy-key", response);
        TagoBusArrivalService service = new TagoBusArrivalService(client);

        List<RealtimeBusArrival> result = service.findArrivals("25", "12345");

        assertEquals(1, result.size());
        assertEquals("740", result.get(0).routeName());
        assertEquals(210, result.get(0).secondsUntilArrival());
    }

    @Test
    void API_호출이_실패해도_예외_대신_빈_목록을_준다() {
        TagoBusApiClient failing = new TagoBusApiClient("http://dummy", "dummy-key") {
            @Override
            public TagoBusArrivalResponse findArrivals(String cityCode, String nodeId) {
                throw new IllegalStateException("API 실패");
            }
        };
        TagoBusArrivalService service = new TagoBusArrivalService(failing);

        assertTrue(service.findArrivals("25", "12345").isEmpty());
    }

    private TagoBusArrivalResponse responseWithArrayItems() throws Exception {
        return mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE" },
                  "body": { "items": { "item": [
                    { "routeno": "146", "arrtime": "97", "arrprevstationcnt": "2" },
                    { "routeno": "360", "arrtime": "310", "arrprevstationcnt": "6" }
                  ] } } } }
                """, TagoBusArrivalResponse.class);
    }

    private static class CountingClient extends TagoBusApiClient {
        private final TagoBusArrivalResponse response;
        private int calls;

        CountingClient(String apiKey, TagoBusArrivalResponse response) {
            super("http://dummy", apiKey);
            this.response = response;
        }

        @Override
        public TagoBusArrivalResponse findArrivals(String cityCode, String nodeId) {
            calls++;
            return response;
        }
    }
}

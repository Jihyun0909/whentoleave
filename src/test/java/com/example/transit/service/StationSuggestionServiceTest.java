package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 자동완성 후보 정렬/중복제거/개수 제한 로직을 확인한다. ODsay가 이미 부분 검색을 해주므로
 * ("건대" 입력해도 "건대입구" 포함) 여기서는 그 결과를 다듬는 부분만 검증한다.
 */
class StationSuggestionServiceTest {

    @Test
    void 결과를_가나다순으로_정렬한다() {
        StationSuggestionService service = serviceReturning(List.of(
                new OdsayStationSearchResponse.Station("수원", 2, 127.0, 37.0, "수도권1호선"),
                new OdsayStationSearchResponse.Station("수유", 1, 127.0, 37.0, "수도권4호선")
        ));

        List<StationCandidate> result = service.suggest("수유");

        assertEquals(List.of("수원", "수유"), result.stream().map(StationCandidate::stationName).toList());
    }

    @Test
    void 완전히_같은_후보는_중복제거한다() {
        StationSuggestionService service = serviceReturning(List.of(
                new OdsayStationSearchResponse.Station("건대입구", 1, 127.0, 37.0, "수도권2호선"),
                new OdsayStationSearchResponse.Station("건대입구", 1, 127.0, 37.0, "수도권2호선")
        ));

        List<StationCandidate> result = service.suggest("건대");

        assertEquals(1, result.size());
    }

    @Test
    void 빈_문자열이면_ODsay를_호출하지_않고_빈_목록을_반환한다() {
        StationSuggestionService service = new StationSuggestionService(new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayStationSearchResponse searchStation(String stationName) {
                throw new AssertionError("빈 쿼리로는 호출되면 안 됨");
            }
        });

        assertTrue(service.suggest("  ").isEmpty());
    }

    /**
     * 자동완성은 타이핑마다 호출돼서 ODsay 일일 한도를 가장 많이 먹는다(실제로 한도를 소진함).
     * 한 글자는 후보가 너무 넓어 쓸모도 적으므로 아예 호출하지 않는다.
     */
    @Test
    void 한_글자_쿼리는_ODsay를_호출하지_않는다() {
        StationSuggestionService service = new StationSuggestionService(new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayStationSearchResponse searchStation(String stationName) {
                throw new AssertionError("한 글자로는 호출되면 안 됨");
            }
        });

        assertTrue(service.suggest("수").isEmpty());
    }

    @Test
    void 결과가_많으면_최대_10개까지만_반환한다() {
        List<OdsayStationSearchResponse.Station> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> new OdsayStationSearchResponse.Station("역" + i, i, 127.0, 37.0, "수도권1호선"))
                .toList();
        StationSuggestionService service = serviceReturning(many);

        assertEquals(10, service.suggest("역이").size());
    }

    @Test
    void 수도권_노선이_아니면_자동완성에서_제외한다() {
        StationSuggestionService service = serviceReturning(List.of(
                new OdsayStationSearchResponse.Station("수유", 1, 127.0, 37.0, "수도권4호선"),
                new OdsayStationSearchResponse.Station("수로왕릉", 2, 128.0, 35.0, "부산-김해경전철")
        ));

        List<StationCandidate> result = service.suggest("수유");

        assertEquals(List.of("수유"), result.stream().map(StationCandidate::stationName).toList());
    }

    private StationSuggestionService serviceReturning(List<OdsayStationSearchResponse.Station> stations) {
        OdsayClient stub = new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayStationSearchResponse searchStation(String stationName) {
                return new OdsayStationSearchResponse(new OdsayStationSearchResponse.Result(stations));
            }
        };
        return new StationSuggestionService(stub);
    }
}

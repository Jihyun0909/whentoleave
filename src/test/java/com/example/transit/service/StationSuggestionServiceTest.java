package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 자동완성 후보 정렬/중복제거/개수 제한/카테고리·지역 필터링 로직을 확인한다. VWorld는 한 번의
 * 장소검색 호출로 지하철역·일반장소가 섞여서 오므로, 역 후보(category="지하철역")를 우선
 * 추리고 없으면 나머지를 장소 후보로 대신 보여주는 흐름을 검증한다.
 */
class StationSuggestionServiceTest {

    private static final String SUBWAY_CATEGORY = "철도시설 > 철도/지하철 > 지하철역";
    private static final String NON_SUBWAY_CATEGORY = "금융기관 > 은행 > 시중은행";

    @Test
    void 결과를_가나다순으로_정렬한다() {
        StationSuggestionService service = serviceReturning(List.of(
                subwayItem("수원역", "서울특별시", 127.0, 37.0),
                subwayItem("수유역", "서울특별시", 127.0, 37.0)
        ));

        List<StationCandidate> result = service.suggest("수유");

        assertEquals(List.of("수원역", "수유역"), result.stream().map(StationCandidate::stationName).toList());
    }

    @Test
    void 완전히_같은_후보는_중복제거한다() {
        StationSuggestionService service = serviceReturning(List.of(
                subwayItem("건대입구역", "서울특별시", 127.0, 37.0),
                subwayItem("건대입구역", "서울특별시", 127.0, 37.0)
        ));

        List<StationCandidate> result = service.suggest("건대");

        assertEquals(1, result.size());
    }

    @Test
    void 빈_문자열이면_아무것도_호출하지_않고_빈_목록을_반환한다() {
        StationSuggestionService service = serviceWith(query -> {
            throw new AssertionError("빈 쿼리로는 호출되면 안 됨");
        });

        assertTrue(service.suggest("  ").isEmpty());
    }

    /**
     * 자동완성은 타이핑마다 호출된다. 한 글자는 VWorld 전체 장소 대상이라 후보가 너무 넓어
     * 쓸모도 적으므로 아예 호출하지 않는다.
     */
    @Test
    void 한_글자_쿼리는_아무것도_호출하지_않는다() {
        StationSuggestionService service = serviceWith(query -> {
            throw new AssertionError("한 글자로는 호출되면 안 됨");
        });

        assertTrue(service.suggest("수").isEmpty());
    }

    @Test
    void 결과가_많으면_최대_10개까지만_반환한다() {
        List<VWorldSearchResponse.Item> many = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> subwayItem("역" + i + "역", "서울특별시", 127.0, 37.0))
                .toList();
        StationSuggestionService service = serviceReturning(many);

        assertEquals(10, service.suggest("역이").size());
    }

    @Test
    void 수도권_밖이면_자동완성에서_제외한다() {
        StationSuggestionService service = serviceReturning(List.of(
                subwayItem("수유역", "서울특별시 강북구", 127.0, 37.0),
                subwayItem("수영역", "부산광역시 수영구", 128.0, 35.0)
        ));

        List<StationCandidate> result = service.suggest("수유");

        assertEquals(List.of("수유역"), result.stream().map(StationCandidate::stationName).toList());
    }

    /** category가 "지하철역"이 아닌 항목은 역 후보에서 제외되고, 역 후보가 하나도 없을 때만 장소 후보로 쓰인다. */
    @Test
    void 역_이름으로_못_찾으면_장소_검색_결과로_대체한다() {
        StationSuggestionService service = serviceReturning(List.of(
                item("(주)신한은행(신한은행본점)", NON_SUBWAY_CATEGORY, "서울특별시 중구 태평로2가 120", "126.9745", "37.5611")
        ));

        List<StationCandidate> result = service.suggest("신한은행 본점");

        assertEquals(1, result.size());
        assertEquals("(주)신한은행(신한은행본점)", result.get(0).stationName());
        assertEquals(126.9745, result.get(0).x());
        assertEquals(37.5611, result.get(0).y());
    }

    /** 같은 응답 안에 역 후보와 일반 장소가 섞여 있으면, 장소는 무시하고 역 후보만 보여준다. */
    @Test
    void 역_후보가_있으면_장소_후보는_섞이지_않는다() {
        StationSuggestionService service = serviceReturning(List.of(
                subwayItem("수유역", "서울특별시", 127.0, 37.0),
                item("수유동주민센터", NON_SUBWAY_CATEGORY, "서울특별시 강북구", "127.0", "37.0")
        ));

        List<StationCandidate> result = service.suggest("수유");

        assertEquals(1, result.size());
        assertEquals("수유역", result.get(0).stationName());
    }

    @Test
    void 역도_장소도_못_찾으면_빈_목록을_반환한다() {
        StationSuggestionService service = serviceWith(query -> notFoundResponse());

        assertTrue(service.suggest("존재하지않는곳").isEmpty());
    }

    private StationSuggestionService serviceReturning(List<VWorldSearchResponse.Item> items) {
        return serviceWith(query -> new VWorldSearchResponse(
                new VWorldSearchResponse.Response("OK", new VWorldSearchResponse.Result(items))));
    }

    private StationSuggestionService serviceWith(Function<String, VWorldSearchResponse> handler) {
        VWorldGeocoderClient stub = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldSearchResponse searchPlace(String query, int size) {
                return handler.apply(query);
            }
        };
        return new StationSuggestionService(stub);
    }

    private VWorldSearchResponse.Item subwayItem(String title, String addressText, double x, double y) {
        return item(title, SUBWAY_CATEGORY, addressText, String.valueOf(x), String.valueOf(y));
    }

    private VWorldSearchResponse.Item item(String title, String category, String addressText, String x, String y) {
        return new VWorldSearchResponse.Item("id-" + title, title, category,
                new VWorldSearchResponse.Address(null, null, addressText, null),
                new VWorldSearchResponse.Point(x, y));
    }

    private VWorldSearchResponse notFoundResponse() {
        return new VWorldSearchResponse(new VWorldSearchResponse.Response("NOT_FOUND", null));
    }
}

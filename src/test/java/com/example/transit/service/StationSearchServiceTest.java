package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 이슈 #7 회귀 테스트. 역 이름으로 못 찾은 경우에만 주소 검색(VWorld 지오코더)으로 폴백하는지 확인한다.
 */
class StationSearchServiceTest {

    private static final String SUBWAY_CATEGORY = "철도시설 > 철도/지하철 > 지하철역";

    @Test
    void 역이름으로_찾으면_주소_검색은_시도하지_않는다() {
        VWorldGeocoderClient searchClient = placeSearchStub(List.of(
                item("수유(강북구청)역", 127.025, 37.637)
        ));
        VWorldGeocoderClient explodingGeocoder = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                throw new AssertionError("역 이름으로 찾아진 경우 주소 검색이 호출되면 안 됨");
            }
        };
        StationSearchService service = new StationSearchService(
                searchClient, new StationCandidateResolver(), new AddressSearchService(explodingGeocoder));

        StationResolution result = service.resolve("수유");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("수유(강북구청)역", resolved.displayName());
    }

    @Test
    void 역이름으로_못찾으면_주소_검색으로_폴백한다() {
        VWorldGeocoderClient searchClient = placeSearchStub(List.of());
        VWorldGeocoderClient geocoderClient = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response(
                        "OK",
                        new VWorldGeocoderResponse.Result(new VWorldGeocoderResponse.Point("127.028", "37.498")),
                        new VWorldGeocoderResponse.Refined("서울 강남구 테헤란로 1")));
            }
        };
        StationSearchService service = new StationSearchService(
                searchClient, new StationCandidateResolver(), new AddressSearchService(geocoderClient));

        StationResolution result = service.resolve("서울 강남구 테헤란로 1");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 테헤란로 1", resolved.displayName());
        assertEquals(127.028, resolved.x());
        assertEquals(37.498, resolved.y());
    }

    /**
     * "수유역"처럼 뒤에 "역"을 붙여도, VWorld가 "수유(강북구청)역"으로 찾아준 결과가
     * StationCandidateResolver의 정규화("역" 접미사 제거 후 비교)로 잘 매칭돼서 주소 폴백으로
     * 새지 않아야 한다.
     */
    @Test
    void 역을_붙여_입력해도_주소가_아니라_역으로_찾는다() {
        VWorldGeocoderClient searchClient = placeSearchStub(List.of(
                item("수유(강북구청)역", 127.025473, 37.637828)
        ));
        VWorldGeocoderClient explodingGeocoder = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                throw new AssertionError("역으로 찾아져야 하므로 주소 검색이 호출되면 안 됨");
            }
        };
        StationSearchService service = new StationSearchService(
                searchClient, new StationCandidateResolver(), new AddressSearchService(explodingGeocoder));

        StationResolution result = service.resolve("수유역");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(127.025473, resolved.x());
    }

    @Test
    void 역이름_주소_둘다_못찾으면_NotFound를_반환한다() {
        VWorldGeocoderClient searchClient = placeSearchStub(List.of());
        VWorldGeocoderClient geocoderClient = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("NOT_FOUND", null, null));
            }
        };
        StationSearchService service = new StationSearchService(
                searchClient, new StationCandidateResolver(), new AddressSearchService(geocoderClient));

        StationResolution result = service.resolve("존재하지않는곳");

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    private VWorldGeocoderClient placeSearchStub(List<VWorldSearchResponse.Item> items) {
        return new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldSearchResponse searchPlace(String query, int size) {
                return new VWorldSearchResponse(new VWorldSearchResponse.Response("OK",
                        new VWorldSearchResponse.Result(items)));
            }
        };
    }

    private VWorldSearchResponse.Item item(String title, double x, double y) {
        return new VWorldSearchResponse.Item("id-" + title, title, SUBWAY_CATEGORY, null,
                new VWorldSearchResponse.Point(String.valueOf(x), String.valueOf(y)));
    }
}

package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 이슈 #7 회귀 테스트. 역 이름으로 못 찾은 경우에만 주소 검색(VWorld)으로 폴백하는지 확인한다.
 */
class StationSearchServiceTest {

    @Test
    void 역이름으로_찾으면_주소_검색은_시도하지_않는다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of(
                        new OdsayStationSearchResponse.Station("수유", 414, 127.025, 37.637, "4호선")
                ))));
        VWorldGeocoderClient explodingVWorld = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                throw new AssertionError("역 이름으로 찾아진 경우 주소 검색이 호출되면 안 됨");
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(explodingVWorld));

        StationResolution result = service.resolve("수유");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("수유 (4호선)", resolved.displayName());
    }

    @Test
    void 역이름으로_못찾으면_주소_검색으로_폴백한다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of())));
        VWorldGeocoderClient vWorldClient = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response(
                        "OK",
                        new VWorldGeocoderResponse.Result(new VWorldGeocoderResponse.Point("127.028", "37.498")),
                        new VWorldGeocoderResponse.Refined("서울 강남구 테헤란로 1")));
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(vWorldClient));

        StationResolution result = service.resolve("서울 강남구 테헤란로 1");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 테헤란로 1", resolved.displayName());
        assertEquals(127.028, resolved.x());
        assertEquals(37.498, resolved.y());
    }

    /**
     * "수유역"처럼 뒤에 "역"을 붙이면 ODsay 검색이 아무것도 못 찾아서(저장된 이름은
     * "수유(강북구청)"), 주소 폴백으로 넘어가 엉뚱한 "수유역 두산위브 아파트" 좌표로
     * 잡히던 버그의 회귀 테스트. "역"을 뗀 이름으로 재검색해서 역으로 찾아야 한다.
     */
    @Test
    void 역을_붙여_입력해도_주소가_아니라_역으로_찾는다() {
        OdsayClient odsayClient = new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayStationSearchResponse searchStation(String stationName) {
                if ("수유".equals(stationName)) {
                    return new OdsayStationSearchResponse(new OdsayStationSearchResponse.Result(List.of(
                            new OdsayStationSearchResponse.Station("수유(강북구청)", 414, 127.025473, 37.637828, "4호선")
                    )));
                }
                return new OdsayStationSearchResponse(new OdsayStationSearchResponse.Result(List.of()));
            }
        };
        VWorldGeocoderClient explodingVWorld = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                throw new AssertionError("역으로 찾아져야 하므로 주소 검색이 호출되면 안 됨");
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(explodingVWorld));

        StationResolution result = service.resolve("수유역");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(127.025473, resolved.x());
    }

    @Test
    void 역이름_주소_둘다_못찾으면_NotFound를_반환한다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of())));
        VWorldGeocoderClient vWorldClient = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("NOT_FOUND", null, null));
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(vWorldClient));

        StationResolution result = service.resolve("존재하지않는곳");

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    private OdsayClient odsayStub(OdsayStationSearchResponse response) {
        return new OdsayClient("http://dummy", "dummy") {
            @Override
            public OdsayStationSearchResponse searchStation(String stationName) {
                return response;
            }
        };
    }
}

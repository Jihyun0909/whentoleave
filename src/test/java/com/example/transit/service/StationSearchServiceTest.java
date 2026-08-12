package com.example.transit.service;

import com.example.transit.service.client.KakaoLocalClient;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.KakaoAddressSearchResponse;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 이슈 #7 회귀 테스트. 역 이름으로 못 찾은 경우에만 주소 검색(카카오)으로 폴백하는지 확인한다.
 */
class StationSearchServiceTest {

    @Test
    void 역이름으로_찾으면_주소_검색은_시도하지_않는다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of(
                        new OdsayStationSearchResponse.Station("수유", 414, 127.025, 37.637, "4호선")
                ))));
        KakaoLocalClient explodingKakao = new KakaoLocalClient("dummy") {
            @Override
            public KakaoAddressSearchResponse searchAddress(String query) {
                throw new AssertionError("역 이름으로 찾아진 경우 주소 검색이 호출되면 안 됨");
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(explodingKakao));

        StationResolution result = service.resolve("수유");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("수유 (4호선)", resolved.displayName());
    }

    @Test
    void 역이름으로_못찾으면_주소_검색으로_폴백한다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of())));
        KakaoLocalClient kakaoClient = new KakaoLocalClient("dummy") {
            @Override
            public KakaoAddressSearchResponse searchAddress(String query) {
                return new KakaoAddressSearchResponse(List.of(
                        new KakaoAddressSearchResponse.Document("서울 강남구 테헤란로 1", "127.028", "37.498")));
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(kakaoClient));

        StationResolution result = service.resolve("서울 강남구 테헤란로 1");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 테헤란로 1", resolved.displayName());
        assertEquals(127.028, resolved.x());
        assertEquals(37.498, resolved.y());
    }

    @Test
    void 역이름_주소_둘다_못찾으면_NotFound를_반환한다() {
        OdsayClient odsayClient = odsayStub(new OdsayStationSearchResponse(
                new OdsayStationSearchResponse.Result(List.of())));
        KakaoLocalClient kakaoClient = new KakaoLocalClient("dummy") {
            @Override
            public KakaoAddressSearchResponse searchAddress(String query) {
                return new KakaoAddressSearchResponse(List.of());
            }
        };
        StationSearchService service = new StationSearchService(
                odsayClient, new StationCandidateResolver(), new AddressSearchService(kakaoClient));

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

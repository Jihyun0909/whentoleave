package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressSearchServiceTest {

    @Test
    void 도로명_주소로_찾아지면_지번으로_재시도하지_않는다() {
        AddressSearchService service = serviceWith((address, type) -> {
            if ("road".equals(type)) {
                return okGeocodeResponse("127.028", "37.498", "서울 강남구 테헤란로 1");
            }
            throw new AssertionError("도로명으로 찾아지면 지번 재시도하면 안 됨");
        });

        StationResolution result = service.resolve("서울 강남구 테헤란로 1");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 테헤란로 1", resolved.displayName());
        assertEquals(127.028, resolved.x());
        assertEquals(37.498, resolved.y());
    }

    @Test
    void 도로명으로_못찾으면_지번으로_재시도한다() {
        AddressSearchService service = serviceWith((address, type) ->
                "road".equals(type) ? notFoundGeocodeResponse() : okGeocodeResponse("127.03", "37.5", "서울 강남구 대치동 1"));

        StationResolution result = service.resolve("서울 강남구 대치동 1번지");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 대치동 1", resolved.displayName());
    }

    @Test
    void 지오코더_주소검색_다_못찾으면_장소검색으로_넘어간다() {
        AddressSearchService service = serviceWith(
                (address, type) -> notFoundGeocodeResponse(),
                (query, category) -> notFoundSearchResponse(),
                query -> okSearchResponse("126.9745", "37.5611", "서울특별시 중구 태평로2가 120", "(주)신한은행(신한은행본점)"));

        StationResolution result = service.resolve("신한은행 본점");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals(126.9745, resolved.x());
        assertEquals(37.5611, resolved.y());
    }

    @Test
    void 주소검색으로_찾아지면_장소검색은_시도하지_않는다() {
        AddressSearchService service = serviceWith(
                (address, type) -> notFoundGeocodeResponse(),
                (query, category) -> "PARCEL".equals(category)
                        ? okSearchResponse("127.03", "37.5", "서울 강남구 대치동 1", "대치동 1")
                        : notFoundSearchResponse(),
                query -> {
                    throw new AssertionError("주소검색으로 찾아지면 장소검색은 시도하면 안 됨");
                });

        StationResolution result = service.resolve("대치동 1");

        StationResolution.Resolved resolved = assertInstanceOf(StationResolution.Resolved.class, result);
        assertEquals("서울 강남구 대치동 1", resolved.displayName());
    }

    @Test
    void 셋_다_못찾으면_NotFound를_반환한다() {
        AddressSearchService service = serviceWith(
                (address, type) -> notFoundGeocodeResponse(),
                (query, category) -> notFoundSearchResponse(),
                query -> notFoundSearchResponse());

        StationResolution result = service.resolve("존재하지않는주소");

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    @Test
    void status가_ERROR면_못찾은_것으로_취급한다() {
        AddressSearchService service = serviceWith(
                (address, type) -> new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("ERROR", null, null)),
                (query, category) -> notFoundSearchResponse(),
                query -> notFoundSearchResponse());

        StationResolution result = service.resolve("서울 강남구 아무동");

        assertInstanceOf(StationResolution.NotFound.class, result);
    }

    /**
     * 실사용 검증 중 발견한 버그의 회귀 테스트. "파인빌"처럼 숫자나 시/도/구/동 같은 행정단위가
     * 전혀 없는 순수 이름은 완전한 주소로 볼 근거가 없는데, 지오코더는 이런 입력에도 전국에
     * 같은 이름의 건물 중 하나를 임의로 "OK"로 확정해버렸다(예: 전국 1,000개 넘는 "파인빌" 중
     * 과천시 한 곳). 이런 입력은 지오코더를 아예 건너뛰고 후보 목록을 낼 수 있는 검색으로만
     * 찾아야 한다.
     */
    @Test
    void 행정단위가_없는_건물명만_입력하면_지오코더를_건너뛴다() {
        AddressSearchService service = serviceWith(
                (address, type) -> {
                    throw new AssertionError("행정단위 없는 건물명은 지오코더를 시도하면 안 됨");
                },
                (query, category) -> notFoundSearchResponse(),
                query -> okSearchResponse("126.9", "37.4", "경기도 과천시 어딘가", "파인빌"));

        StationResolution result = service.resolve("파인빌");

        assertInstanceOf(StationResolution.Resolved.class, result);
    }

    /**
     * 실사용 검증 중 발견한 버그의 회귀 테스트. 서로 다른 지역(수원/과천/광주 등)에 같은 이름의
     * 건물이 여럿 있는데 첫 번째 결과만 조용히 확정해버려서 엉뚱한 곳으로 안내됐다. 위치가
     * 서로 뚜렷이 다른 후보가 여럿이면 역 이름 검색처럼 후보 선택 화면을 보여줘야 한다.
     */
    @Test
    void 이름은_같지만_위치가_다른_후보가_여럿이면_선택_화면을_보여준다() {
        AddressSearchService service = serviceWith(
                (address, type) -> {
                    throw new AssertionError("건물명만 입력하면 지오코더를 시도하면 안 됨");
                },
                (query, category) -> notFoundSearchResponse(),
                query -> multiSearchResponse(
                        item("127.03", "37.28", "경기도 수원시 팔달구 우만동", "파인빌"),
                        item("127.03", "37.46", "경기도 과천시 주암동", "파인빌"),
                        item("126.85", "37.70", "경기도 고양시 덕양구 관산동", "파인빌")));

        StationResolution result = service.resolve("파인빌");

        StationResolution.Ambiguous ambiguous = assertInstanceOf(StationResolution.Ambiguous.class, result);
        assertEquals(3, ambiguous.candidates().size());
    }

    /**
     * 같은 건물/단지 안의 동/호 차이처럼 좌표가 사실상 같은(약 300m 이내) 후보들은 굳이 고르게
     * 하지 않고 하나로 합쳐 바로 확정한다.
     */
    @Test
    void 좌표가_사실상_같은_후보들은_하나로_합쳐진다() {
        AddressSearchService service = serviceWith(
                (address, type) -> {
                    throw new AssertionError("건물명만 입력하면 지오코더를 시도하면 안 됨");
                },
                (query, category) -> notFoundSearchResponse(),
                query -> multiSearchResponse(
                        item("127.1783096", "37.348032", "경기도 광주시 오포로171번길 17-190", "파인빌 101동"),
                        item("127.1783048", "37.348184", "경기도 광주시 오포로171번길 17-188", "파인빌 102동"),
                        item("127.1783056", "37.348337", "경기도 광주시 오포로171번길 17-186", "파인빌 103동")));

        StationResolution result = service.resolve("파인빌");

        assertInstanceOf(StationResolution.Resolved.class, result);
    }

    private AddressSearchService serviceWith(BiFunction<String, String, VWorldGeocoderResponse> geocodeHandler) {
        return serviceWith(geocodeHandler, (query, category) -> notFoundSearchResponse(), query -> notFoundSearchResponse());
    }

    private AddressSearchService serviceWith(BiFunction<String, String, VWorldGeocoderResponse> geocodeHandler,
                                              BiFunction<String, String, VWorldSearchResponse> searchAddressHandler,
                                              Function<String, VWorldSearchResponse> searchPlaceHandler) {
        VWorldGeocoderClient stub = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return geocodeHandler.apply(address, type);
            }

            @Override
            public VWorldSearchResponse searchAddress(String query, String category) {
                return searchAddressHandler.apply(query, category);
            }

            @Override
            public VWorldSearchResponse searchPlace(String query) {
                return searchPlaceHandler.apply(query);
            }
        };
        return new AddressSearchService(stub);
    }

    private VWorldGeocoderResponse okGeocodeResponse(String x, String y, String refinedText) {
        return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response(
                "OK",
                new VWorldGeocoderResponse.Result(new VWorldGeocoderResponse.Point(x, y)),
                new VWorldGeocoderResponse.Refined(refinedText)));
    }

    private VWorldGeocoderResponse notFoundGeocodeResponse() {
        return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("NOT_FOUND", null, null));
    }

    private VWorldSearchResponse okSearchResponse(String x, String y, String parcel, String title) {
        return multiSearchResponse(item(x, y, parcel, title));
    }

    private VWorldSearchResponse.Item item(String x, String y, String parcel, String title) {
        return new VWorldSearchResponse.Item(
                "id", title, null, new VWorldSearchResponse.Address(null, null, null, parcel),
                new VWorldSearchResponse.Point(x, y));
    }

    private VWorldSearchResponse multiSearchResponse(VWorldSearchResponse.Item... items) {
        return new VWorldSearchResponse(new VWorldSearchResponse.Response("OK",
                new VWorldSearchResponse.Result(List.of(items))));
    }

    private VWorldSearchResponse notFoundSearchResponse() {
        return new VWorldSearchResponse(new VWorldSearchResponse.Response("NOT_FOUND", null));
    }
}

package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Optional<StationResolution.Resolved> result = service.resolve("아무 주소");

        assertTrue(result.isPresent());
        assertEquals("서울 강남구 테헤란로 1", result.get().displayName());
        assertEquals(127.028, result.get().x());
        assertEquals(37.498, result.get().y());
    }

    @Test
    void 도로명으로_못찾으면_지번으로_재시도한다() {
        AddressSearchService service = serviceWith((address, type) ->
                "road".equals(type) ? notFoundGeocodeResponse() : okGeocodeResponse("127.03", "37.5", "서울 강남구 대치동 1"));

        Optional<StationResolution.Resolved> result = service.resolve("아무 지번 주소");

        assertTrue(result.isPresent());
        assertEquals("서울 강남구 대치동 1", result.get().displayName());
    }

    @Test
    void 지오코더_주소검색_다_못찾으면_장소검색으로_넘어간다() {
        AddressSearchService service = serviceWith(
                (address, type) -> notFoundGeocodeResponse(),
                (query, category) -> notFoundSearchResponse(),
                query -> okSearchResponse("126.9745", "37.5611", "서울특별시 중구 태평로2가 120", "(주)신한은행(신한은행본점)"));

        Optional<StationResolution.Resolved> result = service.resolve("신한은행 본점");

        assertTrue(result.isPresent());
        assertEquals(126.9745, result.get().x());
        assertEquals(37.5611, result.get().y());
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

        Optional<StationResolution.Resolved> result = service.resolve("대치동 1");

        assertTrue(result.isPresent());
        assertEquals("서울 강남구 대치동 1", result.get().displayName());
    }

    @Test
    void 셋_다_못찾으면_빈_Optional을_반환한다() {
        AddressSearchService service = serviceWith(
                (address, type) -> notFoundGeocodeResponse(),
                (query, category) -> notFoundSearchResponse(),
                query -> notFoundSearchResponse());

        assertTrue(service.resolve("존재하지않는주소").isEmpty());
    }

    @Test
    void status가_ERROR면_못찾은_것으로_취급한다() {
        AddressSearchService service = serviceWith(
                (address, type) -> new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("ERROR", null, null)),
                (query, category) -> notFoundSearchResponse(),
                query -> notFoundSearchResponse());

        assertTrue(service.resolve("아무 주소").isEmpty());
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
        VWorldSearchResponse.Item item = new VWorldSearchResponse.Item(
                "id", title, new VWorldSearchResponse.Address(null, null, null, parcel),
                new VWorldSearchResponse.Point(x, y));
        return new VWorldSearchResponse(new VWorldSearchResponse.Response("OK",
                new VWorldSearchResponse.Result(List.of(item))));
    }

    private VWorldSearchResponse notFoundSearchResponse() {
        return new VWorldSearchResponse(new VWorldSearchResponse.Response("NOT_FOUND", null));
    }
}

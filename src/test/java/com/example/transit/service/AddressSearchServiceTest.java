package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressSearchServiceTest {

    @Test
    void 도로명_주소로_찾아지면_지번으로_재시도하지_않는다() {
        AddressSearchService service = serviceWith((address, type) -> {
            if ("road".equals(type)) {
                return okResponse("127.028", "37.498", "서울 강남구 테헤란로 1");
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
                "road".equals(type) ? notFoundResponse() : okResponse("127.03", "37.5", "서울 강남구 대치동 1"));

        Optional<StationResolution.Resolved> result = service.resolve("아무 지번 주소");

        assertTrue(result.isPresent());
        assertEquals("서울 강남구 대치동 1", result.get().displayName());
    }

    @Test
    void 둘_다_못찾으면_빈_Optional을_반환한다() {
        AddressSearchService service = serviceWith((address, type) -> notFoundResponse());

        assertTrue(service.resolve("존재하지않는주소").isEmpty());
    }

    @Test
    void status가_ERROR면_못찾은_것으로_취급한다() {
        AddressSearchService service = serviceWith((address, type) ->
                new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("ERROR", null, null)));

        assertTrue(service.resolve("아무 주소").isEmpty());
    }

    private AddressSearchService serviceWith(BiFunction<String, String, VWorldGeocoderResponse> handler) {
        VWorldGeocoderClient stub = new VWorldGeocoderClient("dummy") {
            @Override
            public VWorldGeocoderResponse geocode(String address, String type) {
                return handler.apply(address, type);
            }
        };
        return new AddressSearchService(stub);
    }

    private VWorldGeocoderResponse okResponse(String x, String y, String refinedText) {
        return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response(
                "OK",
                new VWorldGeocoderResponse.Result(new VWorldGeocoderResponse.Point(x, y)),
                new VWorldGeocoderResponse.Refined(refinedText)));
    }

    private VWorldGeocoderResponse notFoundResponse() {
        return new VWorldGeocoderResponse(new VWorldGeocoderResponse.Response("NOT_FOUND", null, null));
    }
}

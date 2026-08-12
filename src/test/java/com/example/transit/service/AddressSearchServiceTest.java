package com.example.transit.service;

import com.example.transit.service.client.KakaoLocalClient;
import com.example.transit.service.client.dto.KakaoAddressSearchResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AddressSearchServiceTest {

    @Test
    void 카카오_검색결과가_있으면_첫번째_결과로_Resolved를_반환한다() {
        KakaoLocalClient stubClient = stubReturning(new KakaoAddressSearchResponse(List.of(
                new KakaoAddressSearchResponse.Document("서울 강남구 테헤란로 1", "127.028", "37.498"),
                new KakaoAddressSearchResponse.Document("서울 강남구 테헤란로 2", "127.029", "37.499")
        )));
        AddressSearchService service = new AddressSearchService(stubClient);

        Optional<StationResolution.Resolved> result = service.resolve("아무 주소");

        assertTrue(result.isPresent());
        assertEquals("서울 강남구 테헤란로 1", result.get().displayName());
        assertEquals(127.028, result.get().x());
        assertEquals(37.498, result.get().y());
    }

    @Test
    void 검색결과가_없으면_빈_Optional을_반환한다() {
        KakaoLocalClient stubClient = stubReturning(new KakaoAddressSearchResponse(List.of()));
        AddressSearchService service = new AddressSearchService(stubClient);

        assertTrue(service.resolve("존재하지않는주소").isEmpty());
    }

    private KakaoLocalClient stubReturning(KakaoAddressSearchResponse response) {
        return new KakaoLocalClient("dummy") {
            @Override
            public KakaoAddressSearchResponse searchAddress(String query) {
                return response;
            }
        };
    }
}

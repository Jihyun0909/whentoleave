package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StationSearchService {

    /** VWorld는 이름 전용 검색이 아니라 일반 장소검색이라 무관한 결과가 많이 섞인다 - 그 안에서
     * 지하철역 카테고리를 걸러내려면 기본 5건보다 넉넉하게 받아야 한다. */
    private static final int STATION_SEARCH_SIZE = 20;

    private final VWorldGeocoderClient vWorldGeocoderClient;
    private final StationCandidateResolver resolver;
    private final AddressSearchService addressSearchService;

    public StationSearchService(VWorldGeocoderClient vWorldGeocoderClient, StationCandidateResolver resolver,
                                 AddressSearchService addressSearchService) {
        this.vWorldGeocoderClient = vWorldGeocoderClient;
        this.resolver = resolver;
        this.addressSearchService = addressSearchService;
    }

    /**
     * 역 이름으로 먼저 찾고, 못 찾으면 주소로 보고 지오코딩한다 (이슈 #7).
     * 역 이름이 여러 건(환승역)으로 걸리는 경우는 기존과 동일하게 후보 선택 화면을 보여준다 -
     * 주소 폴백은 역 이름 검색이 정말 아무것도 못 찾았을 때만 시도한다.
     */
    public StationResolution resolve(String name) {
        StationResolution byStationName = searchByStationName(name);

        if (!(byStationName instanceof StationResolution.NotFound)) {
            return byStationName;
        }
        StationResolution byAddress = addressSearchService.resolve(name);
        return byAddress instanceof StationResolution.NotFound ? byStationName : byAddress;
    }

    private StationResolution searchByStationName(String name) {
        VWorldSearchResponse.Response body;
        try {
            body = vWorldGeocoderClient.searchPlace(name, STATION_SEARCH_SIZE).response();
        } catch (RuntimeException e) {
            return new StationResolution.NotFound(name);
        }
        List<VWorldSearchResponse.Item> items =
                body == null || body.result() == null ? null : body.result().items();
        return resolver.resolve(name, items);
    }
}

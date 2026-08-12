package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StationSearchService {

    private final OdsayClient odsayClient;
    private final StationCandidateResolver resolver;
    private final AddressSearchService addressSearchService;

    public StationSearchService(OdsayClient odsayClient, StationCandidateResolver resolver,
                                 AddressSearchService addressSearchService) {
        this.odsayClient = odsayClient;
        this.resolver = resolver;
        this.addressSearchService = addressSearchService;
    }

    /**
     * 역 이름으로 먼저 찾고, 못 찾으면 주소로 보고 지오코딩한다 (이슈 #7).
     * 역 이름이 여러 건(환승역)으로 걸리는 경우는 기존과 동일하게 후보 선택 화면을 보여준다 -
     * 주소 폴백은 역 이름 검색이 정말 아무것도 못 찾았을 때만 시도한다.
     */
    public StationResolution resolve(String name) {
        OdsayStationSearchResponse response = odsayClient.searchStation(name);
        List<OdsayStationSearchResponse.Station> stations =
                response.result() == null ? null : response.result().station();
        StationResolution byStationName = resolver.resolve(name, stations);
        if (!(byStationName instanceof StationResolution.NotFound)) {
            return byStationName;
        }
        return addressSearchService.resolve(name)
                .<StationResolution>map(resolved -> resolved)
                .orElse(byStationName);
    }
}

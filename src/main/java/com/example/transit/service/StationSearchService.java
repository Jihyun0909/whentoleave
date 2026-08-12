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
     * <p>
     * "수유역"처럼 뒤에 "역"을 붙여 입력하는 경우, ODsay 검색 자체가 아무것도 못 찾으므로
     * (ODsay에는 "수유(강북구청)"으로 저장돼 있음) "역"을 뗀 이름으로 한 번 더 검색한다.
     * 이걸 안 하면 "수유역"이 역이 아니라 주소로 폴백돼서 엉뚱한 아파트 좌표로 잡힌다.
     */
    public StationResolution resolve(String name) {
        StationResolution byStationName = searchByStationName(name);

        if (byStationName instanceof StationResolution.NotFound && name.length() > 1 && name.trim().endsWith("역")) {
            String withoutSuffix = name.trim().substring(0, name.trim().length() - 1);
            StationResolution retried = searchByStationName(withoutSuffix);
            if (!(retried instanceof StationResolution.NotFound)) {
                return retried;
            }
        }

        if (!(byStationName instanceof StationResolution.NotFound)) {
            return byStationName;
        }
        return addressSearchService.resolve(name)
                .<StationResolution>map(resolved -> resolved)
                .orElse(byStationName);
    }

    private StationResolution searchByStationName(String name) {
        OdsayStationSearchResponse response = odsayClient.searchStation(name);
        List<OdsayStationSearchResponse.Station> stations =
                response.result() == null ? null : response.result().station();
        return resolver.resolve(name, stations);
    }
}

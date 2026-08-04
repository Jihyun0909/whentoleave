package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StationSearchService {

    private final OdsayClient odsayClient;
    private final StationCandidateResolver resolver;

    public StationSearchService(OdsayClient odsayClient, StationCandidateResolver resolver) {
        this.odsayClient = odsayClient;
        this.resolver = resolver;
    }

    public StationResolution resolve(String stationName) {
        OdsayStationSearchResponse response = odsayClient.searchStation(stationName);
        List<OdsayStationSearchResponse.Station> stations =
                response.result() == null ? null : response.result().station();
        return resolver.resolve(stationName, stations);
    }
}

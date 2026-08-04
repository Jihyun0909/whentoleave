package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ODsay searchStation 응답(부분 검색 결과)에서, 검색어와 이름이 정확히 일치하는
 * 역만 골라 하나로 좁혀지는지 판단하는 순수 로직.
 * (예: "강남"으로 검색하면 "강남", "강남구청", "강남대"가 다 나오는데 "강남구청"/"강남대"는 제외해야 함)
 */
@Component
public class StationCandidateResolver {

    public StationResolution resolve(String queryName, List<OdsayStationSearchResponse.Station> stations) {
        List<StationCandidate> exactMatches = (stations == null ? List.<OdsayStationSearchResponse.Station>of() : stations)
                .stream()
                .filter(station -> queryName.equals(station.stationName()))
                .filter(station -> station.x() != null && station.y() != null)
                .map(station -> new StationCandidate(station.stationName(), station.laneName(),
                        station.x(), station.y()))
                .toList();

        if (exactMatches.isEmpty()) {
            return new StationResolution.NotFound(queryName);
        }
        if (exactMatches.size() == 1) {
            StationCandidate only = exactMatches.get(0);
            String displayName = only.stationName() + " (" + only.laneName() + ")";
            return new StationResolution.Resolved(only.x(), only.y(), displayName);
        }
        return new StationResolution.Ambiguous(exactMatches);
    }
}

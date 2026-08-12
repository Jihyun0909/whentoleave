package com.example.transit.service;

import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ODsay searchStation 응답(부분 검색 결과)에서, 검색어와 이름이 실질적으로 일치하는
 * 역만 골라 하나로 좁혀지는지 판단하는 순수 로직.
 * <p>
 * 비교 전 "괄호와 그 안 내용"을 뒤에서 제거하고 비교한다:
 * - "수유(강북구청)"으로 저장된 역을 "수유"로 검색해도 찾을 수 있어야 함
 * - 그러면서도 "강남"으로 검색했을 때 "강남구청"/"강남대"까지 걸리면 안 됨
 *   (이 둘은 괄호가 없는 별개의 역명이라 정규화해도 "강남"과 달라서 걸러짐)
 * <p>
 * 원래 검색어로 못 찾으면, 검색어 끝에 "역"이 붙어 있는 경우에 한해 그걸 뗀 이름으로
 * 한 번 더 시도한다("수유역" -> "수유"). "서울역"처럼 "역"이 실제 역명의 일부인 경우는
 * 원래 검색어로 이미 찾아지므로 이 폴백까지 갈 일이 없다.
 */
@Component
public class StationCandidateResolver {

    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    public StationResolution resolve(String queryName, List<OdsayStationSearchResponse.Station> stations) {
        List<StationCandidate> exactMatches = findExactMatches(queryName, stations);

        if (exactMatches.isEmpty()) {
            String normalizedQuery = normalize(queryName);
            if (normalizedQuery.length() > 1 && normalizedQuery.endsWith("역")) {
                exactMatches = findExactMatches(normalizedQuery.substring(0, normalizedQuery.length() - 1), stations);
            }
        }

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

    private List<StationCandidate> findExactMatches(String queryName, List<OdsayStationSearchResponse.Station> stations) {
        String normalizedQuery = normalize(queryName);
        return (stations == null ? List.<OdsayStationSearchResponse.Station>of() : stations)
                .stream()
                .filter(station -> normalizedQuery.equals(normalize(station.stationName())))
                .filter(station -> station.x() != null && station.y() != null)
                .map(station -> new StationCandidate(station.stationName(), station.laneName(),
                        station.x(), station.y()))
                .toList();
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return TRAILING_PARENTHETICAL.matcher(name.trim()).replaceAll("").trim();
    }
}

package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 입력 중인 역 이름 자동완성 후보를 준다 (예: "수" -> 수유, 수원...). ODsay의 searchStation은
 * 원래 부분/포함 검색이라("건대" -> "건대입구"도 걸림) 그대로 재사용하면 된다 - StationSearchService처럼
 * "정확히 일치하는 것만" 걸러내지 않는 게 차이점이다. 검색 빈도 데이터가 없어 인기순은 못 주고
 * 가나다순으로 정렬한다.
 * <p>
 * ODsay searchStation은 전국 역을 다 뒤지는데(부산-김해경전철 등), 이 앱은 수도권 지하철
 * 막차 계산이 목적이라 수도권 노선이 아닌 결과는 걸러낸다 (실사용 검증 중 "수" 검색 시
 * 전국 각지 "수OO" 역들에 밀려 정작 수유/수원이 자동완성에 안 뜨는 문제를 발견함).
 */
@Service
public class StationSuggestionService {

    private static final int MAX_SUGGESTIONS = 10;
    /** 자동완성을 시작할 최소 글자 수. ODsay 호출량을 줄이기 위한 하한. */
    private static final int MIN_QUERY_LENGTH = 2;

    /** ODsay 응답에서 "수도권" 접두어 없이 오는 수도권 전철 노선들 (실측 확인: 경의중앙선). */
    private static final Set<String> KNOWN_NON_PREFIXED_SEOUL_LINES = Set.of(
            "경의중앙선", "공항철도", "신분당선", "경춘선", "서해선",
            "우이신설선", "신림선", "인천1호선", "인천2호선",
            "김포골드라인", "의정부경전철", "에버라인"
    );

    private final OdsayClient odsayClient;

    public StationSuggestionService(OdsayClient odsayClient) {
        this.odsayClient = odsayClient;
    }

    public List<StationCandidate> suggest(String query) {
        // 한 글자로는 후보가 너무 넓어 쓸모가 적은데(“수” -> 전국 수십 개) 자동완성은 타이핑마다
        // 호출돼서 ODsay 일일 한도를 가장 많이 먹는다. 두 글자부터 검색한다.
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        OdsayStationSearchResponse response = odsayClient.searchStation(query);
        List<OdsayStationSearchResponse.Station> stations =
                response.result() == null ? null : response.result().station();
        if (stations == null) {
            return List.of();
        }
        return stations.stream()
                .filter(s -> s.stationName() != null && s.x() != null && s.y() != null)
                .filter(s -> isSeoulAreaLine(s.laneName()))
                .map(s -> new StationCandidate(s.stationName(), s.laneName(), s.x(), s.y()))
                .distinct()
                .sorted(Comparator.comparing(StationCandidate::stationName)
                        .thenComparing(c -> c.laneName() == null ? "" : c.laneName()))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private boolean isSeoulAreaLine(String laneName) {
        return laneName != null
                && (laneName.startsWith("수도권") || KNOWN_NON_PREFIXED_SEOUL_LINES.contains(laneName));
    }
}

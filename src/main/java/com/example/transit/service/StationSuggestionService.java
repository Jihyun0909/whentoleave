package com.example.transit.service;

import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.OdsayStationSearchResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
 * <p>
 * 역 이름으로 하나도 못 찾으면 VWorld 장소(POI) 검색으로 대체한다 - "신한은행 본점",
 * "수원SK스카이뷰아파트"처럼 역이 아니라 상호/건물명을 입력하는 경우도 자동완성에 뜨게
 * 하기 위해서다(AddressSearchService의 최종 폴백과 같은 이유). 역 후보가 이미 있으면
 * 장소 검색은 호출하지 않는다 - 타이핑마다 API를 두 번씩 부르지 않기 위해서다.
 */
@Service
public class StationSuggestionService {

    private static final int MAX_SUGGESTIONS = 10;
    /** 자동완성을 시작할 최소 글자 수. ODsay 호출량을 줄이기 위한 하한. */
    private static final int MIN_QUERY_LENGTH = 2;
    private static final String STATUS_OK = "OK";

    /** ODsay 응답에서 "수도권" 접두어 없이 오는 수도권 전철 노선들 (실측 확인: 경의중앙선). */
    private static final Set<String> KNOWN_NON_PREFIXED_SEOUL_LINES = Set.of(
            "경의중앙선", "공항철도", "신분당선", "경춘선", "서해선",
            "우이신설선", "신림선", "인천1호선", "인천2호선",
            "김포골드라인", "의정부경전철", "에버라인"
    );

    private final OdsayClient odsayClient;
    private final VWorldGeocoderClient vWorldGeocoderClient;

    public StationSuggestionService(OdsayClient odsayClient, VWorldGeocoderClient vWorldGeocoderClient) {
        this.odsayClient = odsayClient;
        this.vWorldGeocoderClient = vWorldGeocoderClient;
    }

    public List<StationCandidate> suggest(String query) {
        // 한 글자로는 후보가 너무 넓어 쓸모가 적은데(“수” -> 전국 수십 개) 자동완성은 타이핑마다
        // 호출돼서 ODsay 일일 한도를 가장 많이 먹는다. 두 글자부터 검색한다.
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        List<StationCandidate> stationCandidates = suggestStations(query);
        if (!stationCandidates.isEmpty()) {
            return stationCandidates;
        }
        return suggestPlaces(query);
    }

    private List<StationCandidate> suggestStations(String query) {
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

    private List<StationCandidate> suggestPlaces(String query) {
        VWorldSearchResponse.Response body;
        try {
            body = vWorldGeocoderClient.searchPlace(query).response();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (body == null || !STATUS_OK.equals(body.status()) || body.result() == null
                || body.result().items() == null) {
            return List.of();
        }
        return body.result().items().stream()
                .map(this::toCandidate)
                .filter(Objects::nonNull)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private StationCandidate toCandidate(VWorldSearchResponse.Item item) {
        if (item.title() == null || item.title().isBlank() || item.point() == null) {
            return null;
        }
        try {
            return new StationCandidate(item.title(), addressLine(item),
                    Double.parseDouble(item.point().x()), Double.parseDouble(item.point().y()));
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** 드롭다운 두 번째 줄에 보여줄 주소. 역 후보의 laneName 자리를 대신 채운다. */
    private String addressLine(VWorldSearchResponse.Item item) {
        if (item.address() == null) {
            return null;
        }
        if (item.address().road() != null && !item.address().road().isBlank()) {
            return item.address().road();
        }
        return item.address().parcel();
    }
}

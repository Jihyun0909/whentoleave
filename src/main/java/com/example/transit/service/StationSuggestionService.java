package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 입력 중인 역 이름 자동완성 후보를 준다 (예: "수" -> 수유, 수원...).
 * <p>
 * VWorld는 역 이름 전용 검색이 아니라 일반 장소검색이라 무관한 결과가 훨씬 많이 섞여 온다
 * (예: "수유" 검색 시 기상관측소·출판사 등이 지하철역보다 먼저 나옴 - 2026-08-20 라이브 확인).
 * 그래서 넉넉한 개수를 받아({@link #SEARCH_SIZE}) {@code category}가 "지하철역"인 것만 추린다.
 * 검색 빈도 데이터가 없어 인기순은 못 주고 가나다순으로 정렬한다.
 * <p>
 * 이 앱은 수도권 지하철 막차 계산이 목적이라, 수도권 밖 동명 역(예: 부산/대구 지하철)이
 * 섞여 나오는 걸 막기 위해 주소에 수도권 지역명이 포함된 것만 남긴다.
 * <p>
 * 역 이름으로 하나도 못 찾으면(주소/상호 등을 입력한 경우) 같은 응답의 나머지 결과를
 * 장소(POI) 후보로 대신 보여준다 - "신한은행 본점", "수원SK스카이뷰아파트"처럼 역이 아닌
 * 입력도 자동완성에 뜨게 하기 위해서다.
 */
@Service
public class StationSuggestionService {

    private static final int MAX_SUGGESTIONS = 10;
    private static final int SEARCH_SIZE = 30;
    /** 자동완성을 시작할 최소 글자 수. 한 글자는 후보가 너무 넓어(VWorld 전체 장소 대상) 쓸모가 적다. */
    private static final int MIN_QUERY_LENGTH = 2;
    private static final String STATUS_OK = "OK";
    private static final String SUBWAY_CATEGORY_MARKER = "지하철역";
    private static final List<String> METRO_AREA_MARKERS = List.of("서울특별시", "경기도", "인천광역시");

    private final VWorldGeocoderClient vWorldGeocoderClient;

    public StationSuggestionService(VWorldGeocoderClient vWorldGeocoderClient) {
        this.vWorldGeocoderClient = vWorldGeocoderClient;
    }

    public List<StationCandidate> suggest(String query) {
        if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
            return List.of();
        }

        List<VWorldSearchResponse.Item> items = search(query);
        List<StationCandidate> stationCandidates = stationCandidates(items);
        if (!stationCandidates.isEmpty()) {
            return stationCandidates;
        }
        return placeCandidates(items);
    }

    private List<VWorldSearchResponse.Item> search(String query) {
        VWorldSearchResponse.Response body;
        try {
            body = vWorldGeocoderClient.searchPlace(query, SEARCH_SIZE).response();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (body == null || !STATUS_OK.equals(body.status()) || body.result() == null
                || body.result().items() == null) {
            return List.of();
        }
        return body.result().items();
    }

    private List<StationCandidate> stationCandidates(List<VWorldSearchResponse.Item> items) {
        return items.stream()
                .filter(item -> item.category() != null && item.category().contains(SUBWAY_CATEGORY_MARKER))
                .filter(item -> item.title() != null && item.point() != null
                        && item.point().x() != null && item.point().y() != null)
                .filter(this::isInMetroArea)
                .map(this::toStationCandidate)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(StationCandidate::stationName))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private boolean isInMetroArea(VWorldSearchResponse.Item item) {
        String addressText = addressLine(item);
        return addressText != null && METRO_AREA_MARKERS.stream().anyMatch(addressText::contains);
    }

    private StationCandidate toStationCandidate(VWorldSearchResponse.Item item) {
        try {
            return new StationCandidate(item.title(), addressLine(item),
                    Double.parseDouble(item.point().x()), Double.parseDouble(item.point().y()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<StationCandidate> placeCandidates(List<VWorldSearchResponse.Item> items) {
        return items.stream()
                .map(this::toPlaceCandidate)
                .filter(Objects::nonNull)
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private StationCandidate toPlaceCandidate(VWorldSearchResponse.Item item) {
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

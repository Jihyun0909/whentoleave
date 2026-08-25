package com.example.transit.service;

import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * VWorld 장소검색(searchPlace) 결과에서, 검색어와 이름이 실질적으로 일치하는 지하철역만
 * 골라 하나로 좁혀지는지 판단하는 순수 로직.
 * <p>
 * VWorld는 역 이름 전용 검색이 아니라 일반 장소검색이라, "수유"로 검색하면 기상관측소·출판사
 * 등 무관한 결과가 훨씬 많이 섞여 온다(2026-08-20 라이브 확인) - 그래서 호출하는 쪽
 * (StationSearchService)이 {@code category}가 "지하철역"을 포함하는 항목만 넘겨줘야 한다.
 * 여기서는 그렇게 걸러진 후보들 중 이름이 정확히 일치하는지만 판단한다.
 * <p>
 * ODsay는 역명에 "역"을 안 붙였지만(예: "수유(강북구청)") VWorld는 붙여서 준다(예:
 * "수유(강북구청)역", "강남역") - 그래서 정규화 순서가 ODsay 시절과 다르다: <b>먼저 끝의 "역"을
 * 떼고, 그다음 괄호를 뗀다</b>(괄호가 "역" 앞에 오므로). "서울역"처럼 "역"이 실제 역명의 일부인
 * 경우도, 검색어("서울역"이든 "서울"이든)가 똑같이 정규화되므로 문제없이 일치한다.
 */
@Component
public class StationCandidateResolver {

    private static final String SUBWAY_CATEGORY_MARKER = "지하철역";
    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    public StationResolution resolve(String queryName, List<VWorldSearchResponse.Item> items) {
        List<StationCandidate> exactMatches = findExactMatches(queryName, items);

        if (exactMatches.isEmpty()) {
            return new StationResolution.NotFound(queryName);
        }
        if (exactMatches.size() == 1) {
            StationCandidate only = exactMatches.get(0);
            return new StationResolution.Resolved(only.x(), only.y(), only.stationName());
        }
        return new StationResolution.Ambiguous(exactMatches);
    }

    private List<StationCandidate> findExactMatches(String queryName, List<VWorldSearchResponse.Item> items) {
        String normalizedQuery = normalize(queryName);
        return (items == null ? List.<VWorldSearchResponse.Item>of() : items)
                .stream()
                .filter(item -> isSubwayStation(item.category()))
                .filter(item -> normalizedQuery.equals(normalize(item.title())))
                .filter(item -> item.point() != null && item.point().x() != null && item.point().y() != null)
                .map(this::toCandidate)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private StationCandidate toCandidate(VWorldSearchResponse.Item item) {
        try {
            return new StationCandidate(item.title(), null,
                    Double.parseDouble(item.point().x()), Double.parseDouble(item.point().y()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isSubwayStation(String category) {
        return category != null && category.contains(SUBWAY_CATEGORY_MARKER);
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        String withoutStationSuffix = trimmed.endsWith("역") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return TRAILING_PARENTHETICAL.matcher(withoutStationSuffix).replaceAll("").trim();
    }
}

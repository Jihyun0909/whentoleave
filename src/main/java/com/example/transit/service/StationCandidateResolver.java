package com.example.transit.service;

import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    /**
     * 이 정도 위경도 차이(약 10m) 이내면 VWorld가 같은 POI를 중복으로 준 것으로 보고 하나로
     * 합친다. AddressSearchService의 300m 기준(다른 동/호 등 실제로 다른 지점을 합침)과는
     * 목적이 다르다 - 여기서는 진짜 서로 다른 위치(강남역처럼 출입구가 여러 곳인 환승역 등,
     * 보통 수십~수백 m 떨어짐)까지 합치면 안 되고, 부동소수점 수준으로 거의 같은 좌표만 걸러야
     * 한다 - 실제 약 180m 떨어진 강남역 두 출입구는 계속 Ambiguous로 남아야 한다.
     */
    private static final double CLUSTER_EPSILON_DEGREES = 0.0001;

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
        List<StationCandidate> candidates = (items == null ? List.<VWorldSearchResponse.Item>of() : items)
                .stream()
                .filter(item -> isSubwayStation(item.category()))
                .filter(item -> normalizedQuery.equals(normalize(item.title())))
                .filter(item -> item.point() != null && item.point().x() != null && item.point().y() != null)
                .map(this::toCandidate)
                .filter(java.util.Objects::nonNull)
                .toList();
        return dedupeByLocation(candidates);
    }

    /**
     * VWorld가 같은 역을 좌표만 미세하게(소수점 7자리류) 다른 여러 항목으로 중복해서 주는
     * 경우가 있다(2026-08-25 실사용 중 발견 - "압구정로데오역"). 위치가 사실상 같으면 먼저
     * 나온 것만 남긴다.
     */
    private List<StationCandidate> dedupeByLocation(List<StationCandidate> candidates) {
        List<StationCandidate> distinct = new ArrayList<>();
        for (StationCandidate candidate : candidates) {
            boolean nearExisting = distinct.stream().anyMatch(existing ->
                    Math.abs(existing.x() - candidate.x()) < CLUSTER_EPSILON_DEGREES
                            && Math.abs(existing.y() - candidate.y()) < CLUSTER_EPSILON_DEGREES);
            if (!nearExisting) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private StationCandidate toCandidate(VWorldSearchResponse.Item item) {
        try {
            return new StationCandidate(item.title(), addressLine(item),
                    Double.parseDouble(item.point().x()), Double.parseDouble(item.point().y()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 후보 구분용 부가 설명. VWorld는 역 후보 사이에 노선(호선) 정보를 안 주므로(장소검색이라
     * "지하철역" 카테고리까지만 나온다 - 2026-08-25 실사용 중 발견, 홍대입구역이 2호선/공항철도
     * 승강장으로 서로 다른 역사인데도 구분할 표시가 없어 후보 두 개가 똑같이 보였다), 대신
     * 주소로 구분한다(도로명 우선, 없으면 지번). {@link StationSuggestionService#addressLine}과
     * 동일 패턴.
     */
    private static String addressLine(VWorldSearchResponse.Item item) {
        if (item.address() == null) {
            return null;
        }
        if (item.address().road() != null && !item.address().road().isBlank()) {
            return item.address().road();
        }
        return item.address().parcel();
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

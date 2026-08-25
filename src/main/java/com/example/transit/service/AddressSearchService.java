package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 주소/장소 문자열을 좌표로 바꾼다(VWorld). 역 이름 검색이 실패했을 때의 폴백으로 쓰인다 (이슈 #7).
 * ODsay의 경로탐색(searchPubTransPathT)이 임의의 좌표를 받아 가까운 역까지의 도보 구간까지
 * 알아서 계산해주므로, 여기서는 "가장 가까운 역"을 직접 찾을 필요 없이 좌표만 넘기면 된다.
 * <p>
 * 완전한 주소 형태(숫자나 시/도/군/구/동/로/길 같은 행정단위가 포함됨)인지에 따라 경로가 갈린다:
 * <ol>
 *   <li>완전한 주소면 지오코더(/req/address)부터 시도한다 - 가장 정확하다.</li>
 *   <li>그 외(상호/건물명만 입력, 예: "파인빌", "신한은행 본점")는 지오코더를 아예 건너뛴다.
 *       지오코더는 "결과 하나"만 주는 API라 후보를 보여줄 수가 없는데, 실사용 검증 중 "파인빌"
 *       하나만 입력해도 지오코더가 (전국에 같은 이름의 건물이 1,000개 넘게 있는데도) 그중
 *       임의의 하나를 "OK"로 확정해버려서 완전히 엉뚱한 지역으로 안내되는 문제를 발견했다.</li>
 *   <li>두 경우 다 실패하면(또는 처음부터 건너뛰면) 주소 검색(/req/search, type=ADDRESS) ->
 *       장소 검색(/req/search, type=PLACE) 순으로 시도한다. 이 두 단계는 결과가 여러 건일 수
 *       있는 검색이라, 서로 다른 위치의 후보가 여럿이면(예: "파인빌"이 수원/과천/광주 등 여러
 *       곳에 있음) 역 이름 검색과 똑같이 후보 선택 화면을 보여준다 - 조용히 하나를 골라버리지
 *       않는다. 같은 건물/단지 안의 동/호 차이처럼 사실상 같은 위치로 뭉치는 후보들은 하나로
 *       합쳐서, 진짜 위치가 다른 경우에만 고르게 한다.</li>
 * </ol>
 */
@Service
public class AddressSearchService {

    private static final String STATUS_OK = "OK";
    /** 이 정도 위경도 차이(약 300m) 이내면 "사실상 같은 위치"로 보고 후보를 하나로 합친다. */
    private static final double CLUSTER_EPSILON_DEGREES = 0.003;
    /** 완전한 주소로 볼 수 있는 최소 근거 - 숫자(지번/도로명 번호) 또는 행정단위 글자. */
    private static final Pattern ADDRESS_LIKE = Pattern.compile("[0-9시도군구읍면동리로길가]");

    private final VWorldGeocoderClient vWorldGeocoderClient;

    public AddressSearchService(VWorldGeocoderClient vWorldGeocoderClient) {
        this.vWorldGeocoderClient = vWorldGeocoderClient;
    }

    public StationResolution resolve(String address) {
        Optional<StationResolution> result = looksLikeCompleteAddress(address)
                ? geocode(address, "road")
                        .or(() -> geocode(address, "parcel"))
                        .or(() -> searchByAddress(address, "PARCEL"))
                        .or(() -> searchByAddress(address, "ROAD"))
                        .or(() -> searchByPlace(address))
                : searchByAddress(address, "PARCEL")
                        .or(() -> searchByAddress(address, "ROAD"))
                        .or(() -> searchByPlace(address));
        return result.orElse(new StationResolution.NotFound(address));
    }

    private boolean looksLikeCompleteAddress(String address) {
        return address != null && ADDRESS_LIKE.matcher(address).find();
    }

    /** 완전한 주소를 좌표로 바꾼다. 결과를 하나만 주는 API라 후보 선택은 못 만든다. */
    private Optional<StationResolution> geocode(String address, String type) {
        VWorldGeocoderResponse.Response body;
        try {
            body = vWorldGeocoderClient.geocode(address, type).response();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (body == null || !STATUS_OK.equals(body.status())
                || body.result() == null || body.result().point() == null) {
            return Optional.empty();
        }
        VWorldGeocoderResponse.Point point = body.result().point();
        String displayName = body.refined() != null && body.refined().text() != null
                ? body.refined().text() : address;
        return toResolved(point.x(), point.y(), displayName);
    }

    /** 부분 주소도 찾아주는 검색. */
    private Optional<StationResolution> searchByAddress(String query, String category) {
        return resolveFromItems(() -> vWorldGeocoderClient.searchAddress(query, category), query);
    }

    /** 상호/건물명으로 찾는 장소(POI) 검색. */
    private Optional<StationResolution> searchByPlace(String query) {
        return resolveFromItems(() -> vWorldGeocoderClient.searchPlace(query), query);
    }

    private Optional<StationResolution> resolveFromItems(Supplier<VWorldSearchResponse> call, String query) {
        VWorldSearchResponse.Response body;
        try {
            body = call.get().response();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (body == null || !STATUS_OK.equals(body.status()) || body.result() == null) {
            return Optional.empty();
        }
        List<VWorldSearchResponse.Item> items = body.result().items();
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }

        List<VWorldSearchResponse.Item> distinctLocations = dedupeByLocation(items);
        if (distinctLocations.size() == 1) {
            VWorldSearchResponse.Item only = distinctLocations.get(0);
            if (only.point() == null) {
                return Optional.empty();
            }
            return toResolved(only.point().x(), only.point().y(), displayNameOf(only, query));
        }

        List<StationCandidate> candidates = distinctLocations.stream()
                .map(item -> toCandidate(item, query))
                .filter(Objects::nonNull)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (candidates.size() == 1) {
            StationCandidate only = candidates.get(0);
            return Optional.of(new StationResolution.Resolved(only.x(), only.y(), only.stationName()));
        }
        return Optional.of(new StationResolution.Ambiguous(candidates));
    }

    /**
     * 좌표가 서로 가까운(약 300m 이내) 후보는 "같은 곳"으로 보고 하나만 남긴다 - 같은 건물/
     * 단지의 동/호 차이 같은 걸로 매번 후보를 고르게 하지 않기 위함. 먼저 나온 것을 남긴다
     * (VWorld가 이미 관련도순으로 정렬해서 준다고 가정).
     */
    private List<VWorldSearchResponse.Item> dedupeByLocation(List<VWorldSearchResponse.Item> items) {
        List<VWorldSearchResponse.Item> distinct = new ArrayList<>();
        for (VWorldSearchResponse.Item item : items) {
            if (item.point() == null) {
                continue;
            }
            double x = parseOrNaN(item.point().x());
            double y = parseOrNaN(item.point().y());
            boolean nearExisting = distinct.stream().anyMatch(existing -> {
                double ex = parseOrNaN(existing.point().x());
                double ey = parseOrNaN(existing.point().y());
                return Math.abs(ex - x) < CLUSTER_EPSILON_DEGREES && Math.abs(ey - y) < CLUSTER_EPSILON_DEGREES;
            });
            if (!nearExisting) {
                distinct.add(item);
            }
        }
        return distinct;
    }

    private double parseOrNaN(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException | NullPointerException e) {
            return Double.NaN;
        }
    }

    /** 후보가 여럿일 때 서로 구분할 수 있는 형태 - 이름은 같을 수 있어(예: "파인빌") 주소로 구분한다. */
    private StationCandidate toCandidate(VWorldSearchResponse.Item item, String fallback) {
        if (item.point() == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(item.point().x());
            double y = Double.parseDouble(item.point().y());
            String name = item.title() != null && !item.title().isBlank() ? item.title() : fallback;
            return new StationCandidate(name, addressOf(item), x, y);
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    private String addressOf(VWorldSearchResponse.Item item) {
        if (item.address() == null) {
            return "";
        }
        if (item.address().road() != null && !item.address().road().isBlank()) {
            return item.address().road();
        }
        return item.address().parcel() != null ? item.address().parcel() : "";
    }

    /** 후보가 하나로 확정될 때의 표시명 - 주소(지번/도로명)를 우선하고, 없으면 이름을 쓴다. */
    private String displayNameOf(VWorldSearchResponse.Item item, String fallback) {
        if (item.address() != null) {
            if (item.address().parcel() != null && !item.address().parcel().isBlank()) {
                return item.address().parcel();
            }
            if (item.address().road() != null && !item.address().road().isBlank()) {
                return item.address().road();
            }
        }
        return item.title() != null && !item.title().isBlank() ? item.title() : fallback;
    }

    private Optional<StationResolution> toResolved(String x, String y, String displayName) {
        try {
            return Optional.of(new StationResolution.Resolved(
                    Double.parseDouble(x), Double.parseDouble(y), displayName));
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
    }
}

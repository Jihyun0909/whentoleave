package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 주소 문자열을 좌표로 바꾼다(VWorld). 역 이름 검색이 실패했을 때의 폴백으로 쓰인다 (이슈 #7).
 * ODsay의 경로탐색(searchPubTransPathT)이 임의의 좌표를 받아 가까운 역까지의 도보 구간까지
 * 알아서 계산해주므로, 여기서는 "가장 가까운 역"을 직접 찾을 필요 없이 좌표만 넘기면 된다.
 * <p>
 * 두 단계로 찾는다:
 * <ol>
 *   <li>지오코더(/req/address) — 완전한 주소면 가장 정확하다</li>
 *   <li>검색(/req/search) — "번동 463-68"처럼 시/도/구가 빠진 입력은 지오코더가 못 찾는데,
 *       카카오맵/네이버지도처럼 대충 입력해도 되게 하려면 이 폴백이 필요하다</li>
 * </ol>
 * 각 단계에서 지번(parcel)과 도로명(road)을 모두 시도한다 — 입력이 어느 형태인지 알 수 없어서다.
 */
@Service
public class AddressSearchService {

    private static final String STATUS_OK = "OK";

    private final VWorldGeocoderClient vWorldGeocoderClient;

    public AddressSearchService(VWorldGeocoderClient vWorldGeocoderClient) {
        this.vWorldGeocoderClient = vWorldGeocoderClient;
    }

    public Optional<StationResolution.Resolved> resolve(String address) {
        return geocode(address, "road")
                .or(() -> geocode(address, "parcel"))
                .or(() -> search(address, "PARCEL"))
                .or(() -> search(address, "ROAD"));
    }

    /** 완전한 주소를 좌표로 바꾼다. 주소 형태가 조금이라도 어긋나면 NOT_FOUND가 난다. */
    private Optional<StationResolution.Resolved> geocode(String address, String type) {
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

    /** 부분 주소도 찾아주는 검색. 여러 건이 나오면 가장 정확하다고 판단된 첫 번째를 쓴다. */
    private Optional<StationResolution.Resolved> search(String query, String category) {
        VWorldSearchResponse.Response body;
        try {
            body = vWorldGeocoderClient.searchAddress(query, category).response();
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
        VWorldSearchResponse.Item first = items.get(0);
        if (first.point() == null) {
            return Optional.empty();
        }
        return toResolved(first.point().x(), first.point().y(), displayNameOf(first, query));
    }

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

    private Optional<StationResolution.Resolved> toResolved(String x, String y, String displayName) {
        try {
            return Optional.of(new StationResolution.Resolved(
                    Double.parseDouble(x), Double.parseDouble(y), displayName));
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
    }
}

package com.example.transit.service;

import com.example.transit.service.client.VWorldGeocoderClient;
import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 주소 문자열을 좌표로 바꾼다(VWorld 지오코더). 역 이름 검색이 실패했을 때의 폴백으로
 * 쓰인다 (이슈 #7). ODsay의 경로탐색(searchPubTransPathT)이 임의의 좌표를 받아 가까운
 * 역까지의 도보 구간까지 알아서 계산해주므로, 여기서는 "가장 가까운 역"을 직접 찾을
 * 필요 없이 좌표만 넘기면 된다.
 */
@Service
public class AddressSearchService {

    private static final String STATUS_OK = "OK";

    private final VWorldGeocoderClient vWorldGeocoderClient;

    public AddressSearchService(VWorldGeocoderClient vWorldGeocoderClient) {
        this.vWorldGeocoderClient = vWorldGeocoderClient;
    }

    /** 도로명 주소로 먼저 시도하고, 못 찾으면 지번 주소로 재시도한다 (입력이 어느 형태인지 미리 알 수 없음). */
    public Optional<StationResolution.Resolved> resolve(String address) {
        Optional<StationResolution.Resolved> byRoad = geocode(address, "road");
        if (byRoad.isPresent()) {
            return byRoad;
        }
        return geocode(address, "parcel");
    }

    private Optional<StationResolution.Resolved> geocode(String address, String type) {
        VWorldGeocoderResponse.Response body = vWorldGeocoderClient.geocode(address, type).response();
        if (body == null || !STATUS_OK.equals(body.status())
                || body.result() == null || body.result().point() == null) {
            return Optional.empty();
        }
        VWorldGeocoderResponse.Point point = body.result().point();
        String displayName = body.refined() != null && body.refined().text() != null
                ? body.refined().text() : address;
        return Optional.of(new StationResolution.Resolved(
                Double.parseDouble(point.x()), Double.parseDouble(point.y()), displayName));
    }
}

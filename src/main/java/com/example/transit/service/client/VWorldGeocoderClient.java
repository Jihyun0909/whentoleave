package com.example.transit.service.client;

import com.example.transit.service.client.dto.VWorldGeocoderResponse;
import com.example.transit.service.client.dto.VWorldSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** VWorld(국토교통부) 지오코더 API를 호출한다. 인증키는 쿼리 파라미터로 받는다. */
@Component
public class VWorldGeocoderClient {

    private static final String GEOCODE_URL = "https://api.vworld.kr/req/address";
    private static final String SEARCH_URL = "https://api.vworld.kr/req/search";

    private final RestClient restClient;
    private final String apiKey;

    public VWorldGeocoderClient(@Value("${vworld.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.apiKey = apiKey;
    }

    /**
     * @param type "road"(도로명) 또는 "parcel"(지번). 어느 형태의 주소인지 미리 알 수 없으므로
     *             호출하는 쪽(AddressSearchService)에서 하나 실패하면 다른 타입으로 재시도한다.
     */
    public VWorldGeocoderResponse geocode(String address, String type) {
        URI uri = URI.create(GEOCODE_URL
                + "?service=address&request=GetCoord&version=2.0&crs=epsg:4326"
                + "&type=" + type
                + "&address=" + encode(address)
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldGeocoderResponse.class);
    }

    /**
     * 주소 검색. 지오코더와 달리 완전한 주소가 아니어도 찾아준다("번동 463-68"처럼
     * 시/도/구가 빠진 입력). 여러 건이 나올 수 있어 호출하는 쪽에서 첫 번째를 쓴다.
     *
     * @param category "PARCEL"(지번) 또는 "ROAD"(도로명)
     */
    public VWorldSearchResponse searchAddress(String query, String category) {
        URI uri = URI.create(SEARCH_URL
                + "?service=search&request=search&version=2.0&crs=EPSG:4326&format=json&errorformat=json"
                + "&size=5&type=ADDRESS"
                + "&category=" + category
                + "&query=" + encode(query)
                + "&key=" + encode(apiKey));
        return restClient.get().uri(uri).retrieve().body(VWorldSearchResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

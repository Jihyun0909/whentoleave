package com.example.transit.service.client;

import com.example.transit.service.client.dto.VWorldGeocoderResponse;
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

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

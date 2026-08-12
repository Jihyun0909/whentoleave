package com.example.transit.service.client;

import com.example.transit.service.client.dto.KakaoAddressSearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 카카오 로컬 API(주소 검색)를 호출한다. ODsay와 달리 인증을 쿼리 파라미터가 아니라
 * Authorization 헤더("KakaoAK {REST API 키}")로 받는다.
 */
@Component
public class KakaoLocalClient {

    private static final String ADDRESS_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json";

    private final RestClient restClient;
    private final String apiKey;

    public KakaoLocalClient(@Value("${kakao.api-key}") String apiKey) {
        this.restClient = RestClient.create();
        this.apiKey = apiKey;
    }

    public KakaoAddressSearchResponse searchAddress(String query) {
        URI uri = URI.create(ADDRESS_SEARCH_URL + "?query=" + encode(query));
        return restClient.get()
                .uri(uri)
                .header("Authorization", "KakaoAK " + apiKey)
                .retrieve()
                .body(KakaoAddressSearchResponse.class);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

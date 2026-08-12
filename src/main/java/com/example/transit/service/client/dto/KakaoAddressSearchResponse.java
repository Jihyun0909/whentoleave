package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 카카오 로컬 API 주소 검색(/v2/local/search/address.json) 응답.
 * x/y는 문자열로 내려온다 (x=경도, y=위도 - ODsay의 SX/SY와 같은 좌표계).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoAddressSearchResponse(List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            @JsonProperty("address_name") String addressName,
            String x,
            String y
    ) {
    }
}

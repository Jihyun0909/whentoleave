package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * VWorld 검색(/req/search) 응답.
 * <p>
 * 지오코더(/req/address)는 "서울특별시 강북구 번동 463-68"처럼 완전한 주소만 변환해준다.
 * 반면 이 검색 API는 "번동 463-68"처럼 앞부분(시/도/구)이 빠진 주소도 찾아준다 —
 * 카카오맵/네이버지도에서 하는 것처럼 대충 입력해도 되게 하려면 이쪽이 필요하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VWorldSearchResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String status, Result result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Item> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id, String title, Address address, Point point) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String zipcode, String category, String road, String parcel) {
    }

    /** x=경도, y=위도. 문자열로 내려온다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(String x, String y) {
    }
}

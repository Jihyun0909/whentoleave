package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * VWorld(국토교통부) 역지오코더(좌표 -> 주소) 응답.
 * <p>
 * <b>2026-08-20 라이브 테스트로 확인:</b> {@code type=road}(도로명)는 도로에서 살짝만 벗어나도
 * NOT_FOUND가 나는 경우가 있었다(강남역 좌표로 실측). {@code type=parcel}(지번)이 더 안정적으로
 * 응답을 준다 - 시/군/구 이름만 필요한 용도(TAGO cityCode 매칭)라면 parcel로 충분하다.
 * <p>
 * 정방향 지오코더({@link VWorldGeocoderResponse})와 달리 {@code result}가 배열로 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VWorldReverseGeocodeResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String status, List<Result> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(String text, Structure structure) {
    }

    /**
     * @param level1 시/도 (예: "서울특별시", "경기도")
     * @param level2 시/군/구 (예: "강남구", "수원시") - TAGO cityCode 매칭에 주로 쓰는 필드.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Structure(String level1, String level2, String level3) {
    }
}

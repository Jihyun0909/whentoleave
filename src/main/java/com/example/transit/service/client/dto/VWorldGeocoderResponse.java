package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * VWorld(국토교통부) 지오코더(주소 -> 좌표) 응답.
 * status: "OK"(성공) / "NOT_FOUND"(결과 없음) / "ERROR".
 * point.x=경도, point.y=위도 (ODsay의 SX/SY와 같은 좌표계) - 둘 다 문자열로 내려온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VWorldGeocoderResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(String status, Result result, Refined refined) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Point point) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(String x, String y) {
    }

    /** 정제된(표준화된) 주소 텍스트. 화면 표시용. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Refined(String text) {
    }
}

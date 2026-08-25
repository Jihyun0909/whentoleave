package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 서울교통공사 SearchLastTrainTimeByIDService(openAPI.seoul.go.kr:8088) 응답.
 * <p>
 * 2026-08-25 라이브 테스트로 확인. TAGO가 시간표를 아예 안 주는 서울교통공사 운영 구간
 * 역(사당·종합운동장·창동 등, 실사용 중 발견)의 보완용으로 쓴다. 이름과 달리 "막차 한 건"이
 * 아니라 요청한 개수(start~end)만큼 막차부터 거슬러 올라간 여러 건을 준다 - TAGO의
 * GetSubwaySttnAcctoSchdulList와 같은 용도로 쓸 수 있다.
 * <p>
 * <b>주의:</b> 서울교통공사가 운영하는 1~9호선 구간만 커버한다. 코레일 위탁 구간(4호선
 * 진접선 방면 등)은 이 API 범위 밖이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulSubwayLastTrainResponse(
        @JsonProperty("SearchLastTrainTimeByIDService") Body body) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(@JsonProperty("list_total_count") Integer listTotalCount,
                        @JsonProperty("RESULT") Result result,
                        @JsonProperty("row") List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@JsonProperty("CODE") String code, @JsonProperty("MESSAGE") String message) {
    }

    /**
     * @param stationName 역명 (표시/검증용)
     * @param subwayEnd   이 열차의 종착역 이름
     * @param leftTime    출발 시각. "24:56:30"처럼 24시를 넘는 표기가 온다(ODsay와 동일 컨벤션 -
     *                    TagoTimeParser가 쓰는 TAGO 자체 포맷과는 다르다).
     * @param weekTag     "1"=평일, "2"=토요일, "3"=일요일/공휴일(공식 문서 기준, 평일만 라이브 확인).
     * @param inOutTag    "1"=상행, "2"=하행 - 우리 wayCode(1/2)와 값이 동일하다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(@JsonProperty("STATION_CD") String stationCode,
                       @JsonProperty("STATION_NM") String stationName,
                       @JsonProperty("SUBWAYENAME") String subwayEnd,
                       @JsonProperty("LEFTTIME") String leftTime,
                       @JsonProperty("WEEK_TAG") String weekTag,
                       @JsonProperty("INOUT_TAG") String inOutTag) {
    }
}

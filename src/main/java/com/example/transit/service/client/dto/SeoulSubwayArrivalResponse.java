package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 서울시 지하철 실시간 도착정보(swopenAPI.seoul.go.kr, realtimeStationArrival) 응답.
 * <p>
 * 필드명은 서울 열린데이터광장에 공개된 문서 기준으로 적었다. 이 프로젝트는 실제 발급받은 키로
 * 아직 호출해보지 못했으니, 처음 연동할 때 응답을 한 번 찍어서 필드명이 맞는지 확인해야 한다.
 * 알 수 없는 필드는 {@code ignoreUnknown}으로 무시하므로 필드가 추가되는 정도는 안전하다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulSubwayArrivalResponse(ErrorMessage errorMessage, List<Arrival> realtimeArrivalList) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorMessage(Integer status, String code, String message) {
    }

    /**
     * @param subwayId    호선 ID (서울시 자체 코드체계 - ODsay의 stationId와는 다른 체계라 직접 매칭에는 못 쓴다)
     * @param updnLine    방향 표시. 호선마다 "상행/하행", "내선/외선" 등 표기가 달라 값 자체를 그대로 표시용으로 쓴다.
     * @param trainLineNm 종착지 방면 설명 (예: "성수행 - 신설동방면")
     * @param statnNm     조회 대상 역 이름
     * @param barvlDt     도착 예정까지 남은 시간(초)
     * @param bstatnNm    해당 열차의 종착역 이름
     * @param arvlMsg2    도착 메시지 (예: "전역 도착", "3분 후 (당역 종료)")
     * @param arvlMsg3    도착 메시지에 나오는 위치/방면 요약
     * @param arvlCd      도착 코드 (0:진입 1:도착 2:출발 3:전역출발 4:전역진입 5:전역도착 99:운행중)
     * @param lstcarAt    막차 여부 ("1"이면 막차)
     * @param recptnDt    이 데이터를 수신한 시각
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Arrival(String subwayId, String updnLine, String trainLineNm, String statnNm,
                           String barvlDt, String bstatnNm, String arvlMsg2, String arvlMsg3,
                           String arvlCd, String lstcarAt, String recptnDt) {
    }
}

package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * 경기도 버스정보시스템(GBIS)·인천 버스정보시스템(BIMS) 버스도착정보 응답.
 * <p>
 * <b>주의 - 인천 쪽은 특히 검증이 안 됐다.</b> 인천이 경기도와 같은 벤더 시스템을 쓴다는
 * 전제로 동일한 응답 스키마를 가정해 이 DTO를 같이 쓰고 있다 - 실제 인천 키로 호출해보고
 * 스키마가 다르면 인천 전용 DTO로 분리해야 한다.
 * <p>
 * {@code busArrivalList}도 결과가 1건이면 배열이 아니라 단일 객체로 올 수 있어 {@link JsonNode}로
 * 받아 서비스 계층에서 방어적으로 정규화한다. 항목(item) 안의 주요 필드(GBIS 문서 기준):
 * <ul>
 *   <li>{@code routeName} - 노선번호 (표시용)</li>
 *   <li>{@code predictTime1}/{@code predictTime2} - 첫 번째/두 번째 버스의 도착예정시간(분).
 *       -1이면 운행 정보 없음.</li>
 *   <li>{@code locationNo1}/{@code locationNo2} - 몇 번째 전 정류장에 있는지</li>
 *   <li>{@code plateNo1}/{@code plateNo2} - 차량 번호판</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegionalBusArrivalResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(MsgHeader msgHeader, MsgBody msgBody) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgHeader(Integer resultCode, String resultMessage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgBody(JsonNode busArrivalList) {
    }
}

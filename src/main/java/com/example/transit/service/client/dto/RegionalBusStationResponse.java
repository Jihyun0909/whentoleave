package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * 경기도 버스정보시스템(GBIS)·인천 버스정보시스템(BIMS) 정류소 좌표기반 조회 응답.
 * <p>
 * <b>주의 - 실제 키로 검증되지 않았다.</b> {@code msgBody} 안에 정류소 목록이 어떤 이름의
 * 필드로 오는지(예: {@code busStationAroundList}) 확정할 수 없어서, {@code msgBody}를 통째로
 * {@link JsonNode}로 받아 서비스 계층({@code GyeonggiStationFinder}/{@code IncheonStationFinder})에서
 * "객체 안에서 배열 필드를 찾는" 방어적 방식으로 정류소 목록을 뽑아낸다.
 * <p>
 * 인천은 경기도와 같은 벤더 시스템을 쓴다는 전제로 이 DTO를 같이 쓰고 있다 - RegionalBusArrivalResponse와
 * 같은 가정, 같은 검증 필요.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegionalBusStationResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(JsonNode msgHeader, JsonNode msgBody) {
    }
}

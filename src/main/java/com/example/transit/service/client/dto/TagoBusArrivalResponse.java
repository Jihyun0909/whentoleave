package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

/**
 * TAGO(국가대중교통정보센터, data.go.kr) 정류소별 버스 도착예정정보 응답.
 * <p>
 * 공공데이터포털의 XML 기반 API를 JSON으로 변환한 응답이라, 결과가 1건일 때 {@code item}이
 * 배열이 아니라 단일 객체로 오는 경우가 흔하다(잘 알려진 quirk). 그래서 item은 고정 타입이 아니라
 * {@link JsonNode}로 받아 {@code TagoBusArrivalService}에서 배열/단일 객체 둘 다 방어적으로 처리한다.
 * <p>
 * <b>주의 - 실제 키로 호출 검증을 못 했다.</b> 필드명은 공공데이터포털에 공개된 문서 기준의
 * 추정값이다. item 안의 주요 필드(문서 기준):
 * <ul>
 *   <li>{@code routeno} - 노선번호 (표시용)</li>
 *   <li>{@code arrtime} - 도착예정시간(초)</li>
 *   <li>{@code arrprevstationcnt} - 도착예정 남은 정류장수</li>
 *   <li>{@code vehicletp} - 차량유형 (저상/일반 등)</li>
 *   <li>{@code nodenm} - 정류소명</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TagoBusArrivalResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Header header, Body body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Header(String resultCode, String resultMsg) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(JsonNode items) {
    }
}

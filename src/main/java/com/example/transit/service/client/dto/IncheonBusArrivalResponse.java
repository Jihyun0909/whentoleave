package com.example.transit.service.client.dto;

import java.util.List;

/**
 * 인천 버스정보시스템(BIMS) 버스도착정보 응답.
 * <p>
 * 2026-08-14 라이브 테스트로 확인: 경기(GBIS)와 벤더가 다르고, {@code resultType=json}을 넣어도
 * 실제로는 XML만 돌려주는 별난 시스템이다({@code resultType} 파라미터 자체가 없으면 무조건
 * {@code HTTP_ERROR}가 나서, 값과 무관하게 이 파라미터의 "존재"만 필요한 것으로 보인다). 그래서
 * {@link com.example.transit.service.client.IncheonBusApiClient}가 Jackson이 아니라 DOM으로 직접
 * 파싱해서 이 레코드를 채운다.
 * <p>
 * 노선 번호/이름 필드가 이 응답에 없다 - {@code routeId}는 인천 BIMS 내부 노선 ID일 뿐, 사람이 보는
 * 노선번호(예: "584")가 아니다. 실제 노선번호가 필요하면 별도 노선 조회 API로 routeId -> 노선번호
 * 매핑을 추가해야 한다(아직 미구현).
 *
 * @param items 정류소에 도착 예정인 버스 목록 (실제 XML 태그명은 대문자 - 파싱 시 소문자 필드로 정규화)
 */
public record IncheonBusArrivalResponse(List<Item> items) {

    /**
     * @param bstopId               조회한 정류소 ID (BSTOPID)
     * @param routeId               인천 BIMS 내부 노선 ID, 표시용 노선번호 아님 (ROUTEID)
     * @param busNumPlate           차량 번호판 (BUS_NUM_PLATE)
     * @param restStopCount         도착까지 남은 정류장 수 (REST_STOP_COUNT)
     * @param arrivalEstimateSeconds 도착 예정까지 남은 시간(초) - 원본이 이미 초 단위라 분->초 환산 불필요 (ARRIVALESTIMATETIME)
     * @param lastBus               막차 여부 ("1"이면 막차) (LASTBUSYN)
     */
    public record Item(String bstopId, String routeId, String busNumPlate, Integer restStopCount,
                        Integer arrivalEstimateSeconds, boolean lastBus) {
    }
}

package com.example.transit.service;

import java.util.List;

/**
 * 역 이름으로 실시간 지하철 도착정보를 조회한다. 보조 기능이라 실패해도 예외 대신 빈 목록을 준다 -
 * 기존 시간표 기반 막차 계산에는 영향을 주면 안 된다.
 * <p>
 * 실제 구현({@code SeoulSubwayArrivalService})과 분리해두면, 이 데이터를 쓰는 쪽을
 * Mockito 같은 목킹 프레임워크 없이 람다 페이크로 테스트할 수 있다.
 */
@FunctionalInterface
public interface RealtimeSubwayArrivalLookup {

    List<SubwayArrival> findArrivals(String stationName);

    /**
     * @param lineName               노선 이름 (예: "4호선", "수인분당선"). 환승역은 한 응답에 여러 노선
     *                                열차가 섞여 오므로, TransitLeg가 실제로 타는 노선만 걸러내는 데
     *                                쓴다. 서울시 API가 커버하지 않는 노선(다른 사업자 운영 구간)이거나
     *                                매핑표에 없는 코드면 null - 이 경우 호출하는 쪽에서 제외해야 한다.
     * @param direction              방향 표시 원문 (호선마다 "상행/하행", "내선/외선" 등 표기가 달라 그대로 노출한다)
     * @param destinationStationName 이 열차의 종착역 이름 - TransitLeg의 laneName/endStationName과
     *                                맞춰 어느 방향 열차인지 가리는 데 쓸 수 있다.
     * @param headsign               "OO행 - OO방면" 형태의 종착지 설명 (표시용)
     * @param arrivalMessage         도착 메시지 (예: "전역 도착", "3분 후 (당역 종료)")
     * @param secondsUntilArrival    도착까지 남은 시간(초). 알 수 없으면 null.
     * @param isLastTrain            이 열차가 막차인지 여부
     */
    record SubwayArrival(String lineName, String direction, String destinationStationName, String headsign,
                          String arrivalMessage, Integer secondsUntilArrival, boolean isLastTrain) {
    }
}

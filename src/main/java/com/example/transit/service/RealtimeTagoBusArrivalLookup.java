package com.example.transit.service;

import java.util.List;

/**
 * TAGO(국가대중교통정보센터, data.go.kr) 정류소별 버스 도착예정정보 조회.
 * 실패해도 예외 대신 빈 목록을 준다 - 보조 기능이라 기존 계산에 영향을 주면 안 된다.
 * <p>
 * 실제 구현({@code TagoBusArrivalService})과 분리해두면, 이 데이터를 쓰는 쪽을
 * Mockito 없이 람다 페이크로 테스트할 수 있다.
 *
 * @see RealtimeBusArrival
 */
@FunctionalInterface
public interface RealtimeTagoBusArrivalLookup {

    /**
     * @param cityCode TAGO 자체 지역코드 (예: 서울=25 - 정확한 값은 공공데이터포털 문서로 확인 필요)
     * @param nodeId   TAGO 자체 정류소 ID (Seoul ARS ID, ODsay stationId와는 다른 체계)
     */
    List<RealtimeBusArrival> findArrivals(String cityCode, String nodeId);
}

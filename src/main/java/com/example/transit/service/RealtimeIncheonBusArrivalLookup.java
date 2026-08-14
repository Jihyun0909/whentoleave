package com.example.transit.service;

import java.util.List;

/**
 * 인천 버스정보시스템(BIMS) 정류소별 버스 도착정보 조회.
 * 실패해도 예외 대신 빈 목록을 준다 - 보조 기능이라 기존 계산에 영향을 주면 안 된다.
 *
 * @see RealtimeBusArrival
 */
@FunctionalInterface
public interface RealtimeIncheonBusArrivalLookup {

    /** @param stationId 인천 BIMS 자체 정류소 ID */
    List<RealtimeBusArrival> findArrivals(String stationId);
}

package com.example.transit.service;

import java.util.Optional;

/**
 * 좌표(승차 정류장) -> GBIS 자체 정류소 ID 매칭.
 * 실패해도 예외 대신 빈 값을 준다 - 보조 기능이라 기존 계산에 영향을 주면 안 된다.
 */
@FunctionalInterface
public interface GyeonggiBusStopLookup {

    Optional<String> findStationId(double stationX, double stationY);
}

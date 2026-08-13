package com.example.transit.service;

import java.util.Optional;

/**
 * 버스 구간의 승차 정류장 좌표로, 실시간 도착정보 조회에 쓸 정류소 ID를 얻는다.
 * 시간표 기반 막차 계산(BusDepartureLookup)과는 완전히 별개 경로다 - 이건 "오늘, 지금"
 * 화면에 실시간 도착정보를 보조로 보여주기 위한 것이라, 실패해도 기존 막차 계산에는
 * 영향을 주면 안 된다(그래서 예외 대신 빈 Optional로 실패를 표현한다).
 */
@FunctionalInterface
public interface RealtimeBusStopLookup {

    /** 정류소를 못 찾았거나(정보 없음) 조회에 실패하면 빈 값. */
    Optional<String> findStationId(double stationX, double stationY);
}

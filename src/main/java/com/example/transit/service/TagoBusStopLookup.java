package com.example.transit.service;

import java.util.Optional;

/**
 * 좌표(승차 정류장) -> TAGO 자체 정류소 식별자(cityCode+nodeId) 매칭.
 * 실패해도 예외 대신 빈 값을 준다 - 보조 기능이라 기존 계산에 영향을 주면 안 된다.
 */
@FunctionalInterface
public interface TagoBusStopLookup {

    Optional<TagoStop> findStop(double stationX, double stationY);

    /** @param cityCode TAGO 자체 지역코드, @param nodeId TAGO 자체 정류소 ID */
    record TagoStop(String cityCode, String nodeId) {
    }
}

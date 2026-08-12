package com.example.transit.service;

import java.util.List;

/**
 * 버스 구간의 승차 정류장에서 "탈 수 있는 차편들의 출발 시각"(서비스일 기준 분) 후보를 준다.
 * 지하철의 LastTrainLookup에 대응하며, 마찬가지로 실제 구현(BusDepartureCacheService)과
 * 분리해두어 계산 로직을 목킹 없이 페이크로 테스트할 수 있게 한다.
 */
@FunctionalInterface
public interface BusDepartureLookup {

    /** 후보가 없으면 빈 목록. 오름차순일 필요는 없다. */
    List<Integer> departureServiceMinutes(TransitLeg leg);
}

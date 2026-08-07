package com.example.transit.service;

import com.example.transit.domain.SubwaySchedule;

import java.util.List;

/**
 * 역×방향의 시간표 후보 목록(그 역/방향/요일유형의 전체 시간표)을 조회한다.
 * SubwayScheduleCacheService의 실제 구현과 분리해두면, LastDepartureCalculator를
 * Mockito 같은 목킹 프레임워크 없이 람다 페이크로 테스트할 수 있다.
 */
@FunctionalInterface
public interface LastTrainLookup {
    List<SubwaySchedule> getLastTrains(int stationId, int wayCode);
}

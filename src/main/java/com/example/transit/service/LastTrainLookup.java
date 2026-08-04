package com.example.transit.service;

import com.example.transit.domain.SubwayLastTrain;

import java.util.List;

/**
 * 역×방향의 막차 후보 목록을 조회한다.
 * SubwayScheduleCacheService의 실제 구현과 분리해두면, LastDepartureCalculator를
 * Mockito 같은 목킹 프레임워크 없이 람다 페이크로 테스트할 수 있다.
 */
@FunctionalInterface
public interface LastTrainLookup {
    List<SubwayLastTrain> getLastTrains(int stationId, int wayCode);
}

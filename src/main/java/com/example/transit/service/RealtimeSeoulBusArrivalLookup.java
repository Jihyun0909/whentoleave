package com.example.transit.service;

import java.util.List;

/**
 * 좌표로 서울시 정류장의 실시간 버스 도착정보를 조회한다.
 * <p>
 * 다른 지역 조회들과 같은 이유로 인터페이스를 따로 둔다 - 실제 구현
 * ({@link SeoulBusArrivalService})은 외부 API를 두 번(정류소 목록, 도착정보) 타는데,
 * 이걸 쓰는 쪽({@link RegionalBusArrivalLookup})은 목킹 프레임워크 없이 람다 페이크로
 * 지역 우선순위만 검증할 수 있어야 한다.
 */
@FunctionalInterface
public interface RealtimeSeoulBusArrivalLookup {

    List<RealtimeBusArrival> findArrivals(double stationX, double stationY);
}

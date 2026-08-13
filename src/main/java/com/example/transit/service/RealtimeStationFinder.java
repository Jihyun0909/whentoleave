package com.example.transit.service;

import java.util.Optional;

/**
 * 좌표로 실시간 도착정보 API 쪽의 정류소를 찾는다.
 * <p>
 * ODsay 정류장 좌표와 실시간 도착정보 제공처(예: 서울시)의 정류소 ID 체계가 서로 달라서
 * 필요한 매칭 작업이다. 실제 구현({@code SeoulStationFinder})과 분리해두면, 이 매칭을
 * 쓰는 캐시 로직({@link SeoulBusStopCacheService})을 목킹 없이 람다 페이크로 테스트할 수 있다
 * (LastTrainLookup/BusDepartureLookup과 같은 이유).
 */
@FunctionalInterface
public interface RealtimeStationFinder {

    /** 이 좌표에서 가장 가까운 정류소. 매칭 실패(정보 없음, API 호출 실패 등)면 빈 값. */
    Optional<StationMatch> findNearestStation(double x, double y);

    record StationMatch(String stationId, String stationName, int distanceMeters) {
    }
}

package com.example.transit.service;

/**
 * 출발지-목적지가 대중교통을 탈 필요 없이 걸어서 갈 수 있을 만큼 가까운 경우 던진다.
 * ODsay가 이런 경로를 도보(trafficType=3) 구간만으로 응답하는데, 그러면 지하철/버스 구간이
 * 하나도 없어 {@link NoSubwayRouteFoundException}과 같은 "경로 없음" 취급을 받기 쉽다 -
 * 그 결과 "대중교통 운행이 종료되어 안내 불가"라는 엉뚱한 안내가 나가는 문제가 있어(실사용
 * 검증 중 발견: 도보 몇 분 거리인 두 지점을 검색한 경우) 별도 타입으로 구분한다.
 */
public class WalkOnlyRouteException extends NoSubwayRouteFoundException {

    private final int walkMinutes;

    public WalkOnlyRouteException(int walkMinutes) {
        super("대중교통 없이 도보로 이동 가능한 거리입니다.");
        this.walkMinutes = walkMinutes;
    }

    public int walkMinutes() {
        return walkMinutes;
    }
}

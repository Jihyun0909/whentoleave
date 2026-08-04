package com.example.transit.service;

/**
 * 지하철 전용 경로를 찾지 못했거나(경로 없음), 경로에 지하철이 아닌 구간(버스 등)이
 * 섞여 있어서(SearchPathType=1로 요청했음에도) 신뢰할 수 없는 경우 던진다.
 */
public class NoSubwayRouteFoundException extends RuntimeException {
    public NoSubwayRouteFoundException(String message) {
        super(message);
    }
}

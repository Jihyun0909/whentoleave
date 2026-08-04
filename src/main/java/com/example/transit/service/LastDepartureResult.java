package com.example.transit.service;

import java.time.LocalTime;

public sealed interface LastDepartureResult {

    /**
     * @param departureTime 첫 구간 승차역에서 타야 하는 막차 출발 시각
     * @param nextDay       departureTime이 "다음날 이 시각"을 의미하는지 여부
     */
    record Feasible(LocalTime departureTime, boolean nextDay) implements LastDepartureResult {
    }

    /**
     * @param reason 계산이 불가능한 이유 (예: 막차 정보 없음, 환승 연결 실패)
     */
    record Infeasible(String reason) implements LastDepartureResult {
    }
}

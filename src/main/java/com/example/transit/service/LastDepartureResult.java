package com.example.transit.service;

import java.time.LocalTime;
import java.util.List;

public sealed interface LastDepartureResult {

    /**
     * @param departureTime 첫 구간 승차역에서 타야 하는 막차 출발 시각
     * @param nextDay       departureTime이 "다음날 이 시각"을 의미하는지 여부
     * @param legs          이 결과를 계산할 때 쓴 실제 경로(정순). 화면에 "역 호선 → 도보 N분 → 역 호선"처럼
     *                      경로를 보여주기 위한 표시용이며, 계산 자체에는 이미 반영되어 있어 다시 쓰지 않는다.
     */
    record Feasible(LocalTime departureTime, boolean nextDay, List<SubwayLeg> legs) implements LastDepartureResult {
    }

    /**
     * @param reason 계산이 불가능한 이유 (예: 막차 정보 없음, 환승 연결 실패)
     */
    record Infeasible(String reason) implements LastDepartureResult {
    }
}

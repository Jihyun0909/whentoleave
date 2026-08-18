package com.example.transit.service;

import java.time.LocalTime;
import java.util.List;

public sealed interface LastDepartureResult {

    /**
     * @param departureTime        첫 구간 승차역에서 타야 하는 막차 출발 시각
     * @param nextDay               departureTime이 "다음날 이 시각"을 의미하는지 여부
     * @param legs                  이 결과를 계산할 때 쓴 실제 경로(정순). 화면에 "역 호선 → 도보 N분 → 역 호선"처럼
     *                              경로를 보여주기 위한 표시용이며, 계산 자체에는 이미 반영되어 있어 다시 쓰지 않는다.
     * @param finalWalkMinutes      마지막 지하철 하차역에서 실제 목적지까지 걸어야 하는 시간(분). 0이면 하차역이 곧 목적지.
     * @param isLastTrainDeparture  목표 도착시간 역산(arrival mode) 결과가 실제로는 그냥 막차와 같은 경우 true.
     *                              목표 시각이 밤늦게라 사실상 아무 제약이 안 되는 경우(예: 새벽 2시까지 도착)
     *                              화면에서 "이건 그냥 막차 안내입니다"라고 밝혀주기 위함. 막차 모드(목표 시각 없음)에서는
     *                              항상 false다(그 모드 자체가 이미 막차 기준이라 알려줄 의미가 없음).
     */
    record Feasible(LocalTime departureTime, boolean nextDay, List<TransitLeg> legs, int finalWalkMinutes,
                     boolean isLastTrainDeparture) implements LastDepartureResult {
    }

    /**
     * @param reason         계산이 불가능한 이유 (예: 막차 정보 없음, 환승 연결 실패)
     * @param walkOnlyMinutes 출발지-목적지가 대중교통 없이 걸어갈 수 있을 만큼 가까워서 계산
     *                        자체가 성립하지 않는 경우의 도보 소요시간(분). 그 외의 모든 실패
     *                        사유에서는 null - 화면에서 이 값의 유무로 "도보 몇 분이면 충분해요"
     *                        안내와 일반 실패 사유 안내를 구분한다.
     */
    record Infeasible(String reason, Integer walkOnlyMinutes) implements LastDepartureResult {
        public Infeasible(String reason) {
            this(reason, null);
        }
    }
}

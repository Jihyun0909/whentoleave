package com.example.transit.service;

/**
 * 경로 탐색 결과에서 추출한 지하철 구간 하나.
 *
 * @param stationId              이 구간의 승차역 ID
 * @param wayCode                방향 (1:상행, 2:하행)
 * @param rideMinutes             이 구간의 탑승 소요시간(분)
 * @param transferBufferMinutes  이 구간에 타기 위해 필요한 환승/도보 시간(분).
 *                               첫 구간에서는 계산에 쓰이지 않는다(직전 구간이 없으므로).
 */
public record SubwayLeg(int stationId, int wayCode, int rideMinutes, int transferBufferMinutes) {
}

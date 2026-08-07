package com.example.transit.service;

import java.util.Set;

/**
 * 경로 탐색 결과에서 추출한 지하철 구간 하나.
 *
 * @param stationId              이 구간의 승차역 ID
 * @param wayCode                방향 (1:상행, 2:하행)
 * @param rideMinutes             이 구간의 탑승 소요시간(분)
 * @param transferBufferMinutes  이 구간에 타기 위해 필요한 환승/도보 시간(분).
 *                               첫 구간에서는 계산에 쓰이지 않는다(직전 구간이 없으므로).
 * @param earlierStopNames       이 구간의 도착역보다 "앞서" 있는 정차역 이름들.
 *                               막차 후보 중 목적지가 여기 포함되면, 그 열차는 도착역에
 *                               닿기 전에 끊기는 단축운행 열차이므로 후보에서 제외해야 한다.
 */
public record SubwayLeg(int stationId, int wayCode, int rideMinutes, int transferBufferMinutes,
                         Set<String> earlierStopNames) {
}

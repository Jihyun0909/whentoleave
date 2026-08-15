package com.example.transit.service;

/**
 * 지역별 실시간 버스 도착정보의 공통 결과 형태 (TAGO/경기/인천 모두 이 형태로 정규화한다).
 *
 * @param routeName           버스 노선 번호/이름 (표시용)
 * @param secondsUntilArrival 도착까지 남은 시간(초). 경기/인천 원본은 분 단위라 60배 해서 맞춘다.
 * @param remainingStopCount  이 버스가 몇 정류장 전에 있는지. 제공되지 않으면 null.
 * @param plateNo             차량 번호판 (표시용). 제공되지 않으면 null.
 */
public record RealtimeBusArrival(String routeName, Integer secondsUntilArrival,
                                  Integer remainingStopCount, String plateNo) {
}

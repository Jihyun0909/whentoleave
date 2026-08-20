package com.example.transit.service;

/**
 * 지역별 실시간 버스 도착정보의 공통 결과 형태 (서울/TAGO/경기/인천 모두 이 형태로 정규화한다).
 *
 * @param routeName           버스 노선 번호/이름 (표시용)
 * @param secondsUntilArrival 도착까지 남은 시간(초). 경기/인천 원본은 분 단위라 60배 해서 맞춘다.
 *                            오고 있는 버스가 없으면(운행종료·출발대기) null이고, 대신
 *                            statusLabel에 그 사유가 담긴다.
 * @param remainingStopCount  이 버스가 몇 정류장 전에 있는지. 제공되지 않으면 null.
 * @param plateNo             차량 번호판 (표시용). 제공되지 않으면 null.
 * @param statusLabel         카운트다운을 보여줄 수 없을 때의 상태 문구(예: "운행 종료 · 막차 18:49",
 *                            "출발대기 · 배차 40분"). 정상적으로 오고 있으면 null.
 */
public record RealtimeBusArrival(String routeName, Integer secondsUntilArrival,
                                  Integer remainingStopCount, String plateNo,
                                  String statusLabel) {

    /** 도착 시간을 아는 버스 (카운트다운 대상). */
    public static RealtimeBusArrival arriving(String routeName, Integer secondsUntilArrival,
                                               Integer remainingStopCount, String plateNo) {
        return new RealtimeBusArrival(routeName, secondsUntilArrival, remainingStopCount, plateNo, null);
    }

    /** 오고 있는 버스가 없어 상태만 알려주는 경우 (운행종료/출발대기). */
    public static RealtimeBusArrival status(String routeName, String statusLabel) {
        return new RealtimeBusArrival(routeName, null, null, null, statusLabel);
    }

    public boolean isArriving() {
        return secondsUntilArrival != null;
    }
}

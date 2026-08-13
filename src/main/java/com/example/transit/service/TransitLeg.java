package com.example.transit.service;

import java.util.List;
import java.util.Set;

/**
 * 경로 탐색 결과에서 추출한 대중교통 구간 하나 (지하철 또는 버스).
 *
 * @param mode                   지하철/버스. 어느 쪽이냐에 따라 "이 정류장의 출발 시각 후보"를
 *                               구하는 방법이 완전히 달라진다 (지하철=실제 시간표, 버스=배차 기반 추정).
 * @param stationId              이 구간의 승차역/정류장 ID
 * @param wayCode                방향 (1:상행, 2:하행). 버스는 노선 자체가 방향이라 안 쓴다.
 * @param rideMinutes            이 구간의 탑승 소요시간(분)
 * @param transferBufferMinutes  이 구간에 타기 위해 필요한 환승/도보 시간(분).
 *                               첫 구간에서는 출발지에서 승차 지점까지 걷는 시간을 의미한다.
 * @param earlierStopNames       이 구간의 도착역보다 "앞서" 있는 정차역 이름들. 막차 후보 중
 *                               목적지가 여기 포함되면 단축운행 열차이므로 후보에서 제외한다(지하철 전용).
 * @param stationName            승차역/정류장 이름 (표시용)
 * @param endStationName         하차역/정류장 이름 (표시용). 어디서 내려야 하는지가 환승 경로에서는
 *                               승차역만큼 중요한 정보라 같이 담는다.
 * @param laneName               노선 이름 (표시용). 지하철은 "4호선", 버스는 "간선" 같은 노선종류.
 * @param busIds                 이 구간을 지나는 버스 노선 ID들. ODsay는 한 구간에 탈 수 있는 버스를
 *                               여러 개 준다("120, 130, 140 중 아무거나") — 그래서 이 구간의 막차는
 *                               그중 <b>가장 늦은 노선</b> 기준으로 봐야 한다.
 * @param busNo                  대표 버스 번호 (표시용, 예: "120"). 버스 구간에서만 값이 있다.
 * @param distanceMeters         이 구간의 이동 거리(m). rideMinutes와 함께 평균 속도를 구해,
 *                               "기점에서 승차 정류장까지 오는 시간"을 추정하는 데 쓴다.
 * @param stationX               승차 정류장의 경도, @param stationY 위도. 버스 구간에서만 값이 있다
 *                               (지하철은 stationID로 바로 시간표를 조회해서 좌표가 필요 없다).
 *                               실시간 도착정보 조회용 정류소를 좌표로 매칭하는 데 쓴다.
 */
public record TransitLeg(TransitMode mode, int stationId, int wayCode, int rideMinutes,
                          int transferBufferMinutes, Set<String> earlierStopNames,
                          String stationName, String endStationName, String laneName,
                          List<Integer> busIds, String busNo, int distanceMeters,
                          Double stationX, Double stationY) {

    /** 지하철 구간 (표시용 이름 포함). */
    public static TransitLeg subway(int stationId, int wayCode, int rideMinutes, int transferBufferMinutes,
                                     Set<String> earlierStopNames, String stationName, String endStationName,
                                     String laneName) {
        return new TransitLeg(TransitMode.SUBWAY, stationId, wayCode, rideMinutes, transferBufferMinutes,
                earlierStopNames, stationName, endStationName, laneName, List.of(), null, 0, null, null);
    }

    /** 표시용 이름 없이 계산 로직만 테스트할 때 쓰는 지하철 구간 생성자. */
    public static TransitLeg subway(int stationId, int wayCode, int rideMinutes, int transferBufferMinutes,
                                     Set<String> earlierStopNames) {
        return subway(stationId, wayCode, rideMinutes, transferBufferMinutes, earlierStopNames, null, null, null);
    }

    public static TransitLeg bus(int stationId, int rideMinutes, int transferBufferMinutes,
                                  String stationName, String endStationName, String laneName,
                                  List<Integer> busIds, String busNo, int distanceMeters,
                                  Double stationX, Double stationY) {
        return new TransitLeg(TransitMode.BUS, stationId, 0, rideMinutes, transferBufferMinutes,
                Set.of(), stationName, endStationName, laneName, List.copyOf(busIds), busNo, distanceMeters,
                stationX, stationY);
    }

    /** 정류장 좌표를 모르는(테스트 등) 경우용. */
    public static TransitLeg bus(int stationId, int rideMinutes, int transferBufferMinutes,
                                  String stationName, String endStationName, String laneName,
                                  List<Integer> busIds, String busNo, int distanceMeters) {
        return bus(stationId, rideMinutes, transferBufferMinutes, stationName, endStationName, laneName,
                busIds, busNo, distanceMeters, null, null);
    }

    public boolean isBus() {
        return mode == TransitMode.BUS;
    }
}

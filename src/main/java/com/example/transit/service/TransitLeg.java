package com.example.transit.service;

import java.util.List;
import java.util.Set;

/**
 * 경로 탐색 결과에서 추출한 대중교통 구간 하나 (지하철 또는 버스).
 *
 * @param mode                   지하철/버스. 어느 쪽이냐에 따라 "이 정류장의 출발 시각 후보"를
 *                               구하는 방법이 완전히 달라진다 (지하철=실제 시간표, 버스=배차 기반 추정).
 * @param stationId              이 구간의 승차역/정류장 ID. TAGO 자체 ID 체계(문자열)를 쓴다
 *                               (예: 지하철 "MTRS12222", 버스 정류소 ARS ID 등) - ODsay의 정수 ID가 아니다.
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
 * @param busIds                 이 구간을 지나는 버스 노선 ID(TAGO routeId)들. Google Routes는 보통
 *                               구간당 노선 하나만 주지만, ODsay 시절처럼 여러 개("120, 130, 140 중
 *                               아무거나")가 들어올 수도 있는 구조는 유지한다 — 이 경우 막차는
 *                               그중 <b>가장 늦은 노선</b> 기준으로 봐야 한다.
 * @param busNo                  대표 버스 번호 (표시용, 예: "120"). 버스 구간에서만 값이 있다.
 * @param distanceMeters         이 구간의 이동 거리(m). rideMinutes와 함께 평균 속도를 구해,
 *                               "기점에서 승차 정류장까지 오는 시간"을 추정하는 데 쓴다.
 * @param stationX               승차 정류장의 경도, @param stationY 위도. 버스 구간에서만 값이 있다
 *                               (지하철은 stationID로 바로 시간표를 조회해서 좌표가 필요 없다).
 *                               실시간 도착정보 조회용 정류소를 좌표로 매칭하는 데 쓴다.
 * @param cityCode               busIds(TAGO routeId)를 해석하는 데 필요한 TAGO 자체 지역코드
 *                               (예: 수원시=31010). TAGO 버스노선정보 API는 routeId 조회에도
 *                               cityCode가 같이 필요하다. 버스 구간에서만 값이 있다.
 * @param googleDepartureTime    Google Routes가 이 버스 구간에 준 예정 출발 시각(RFC3339,
 *                               예: "2026-08-29T14:37:02Z"). 실시간 GPS 추적이 아니라 Google 자체
 *                               정적 시간표 기반 추정치라 "실시간은 안 쓴다"는 원칙과 안 부딪힌다.
 *                               서울 버스는 TAGO에 정류장별 시간표가 없어 기점 막차 시각을 그대로
 *                               써왔는데(승차 정류장까지 오는 시간을 못 빼서 실제보다 훨씬 이르게
 *                               나옴 - 2026-08-30 실사용 중 발견, 구글맵과 최대 73분 차이), 이
 *                               값을 배차간격 후보 목록에 추가 후보로 얹어서 보정한다
 *                               (BusDepartureCacheService 참고). 지하철 구간은 null.
 */
public record TransitLeg(TransitMode mode, String stationId, int wayCode, int rideMinutes,
                          int transferBufferMinutes, Set<String> earlierStopNames,
                          String stationName, String endStationName, String laneName,
                          List<String> busIds, String busNo, int distanceMeters,
                          Double stationX, Double stationY, String cityCode, String googleDepartureTime) {

    /** 지하철 구간 (표시용 이름 포함). */
    public static TransitLeg subway(String stationId, int wayCode, int rideMinutes, int transferBufferMinutes,
                                     Set<String> earlierStopNames, String stationName, String endStationName,
                                     String laneName) {
        return new TransitLeg(TransitMode.SUBWAY, stationId, wayCode, rideMinutes, transferBufferMinutes,
                earlierStopNames, stationName, endStationName, laneName, List.of(), null, 0, null, null, null, null);
    }

    /** 표시용 이름 없이 계산 로직만 테스트할 때 쓰는 지하철 구간 생성자. */
    public static TransitLeg subway(String stationId, int wayCode, int rideMinutes, int transferBufferMinutes,
                                     Set<String> earlierStopNames) {
        return subway(stationId, wayCode, rideMinutes, transferBufferMinutes, earlierStopNames, null, null, null);
    }

    public static TransitLeg bus(String stationId, int rideMinutes, int transferBufferMinutes,
                                  String stationName, String endStationName, String laneName,
                                  List<String> busIds, String busNo, int distanceMeters,
                                  Double stationX, Double stationY, String cityCode, String googleDepartureTime) {
        return new TransitLeg(TransitMode.BUS, stationId, 0, rideMinutes, transferBufferMinutes,
                Set.of(), stationName, endStationName, laneName, List.copyOf(busIds), busNo, distanceMeters,
                stationX, stationY, cityCode, googleDepartureTime);
    }

    /** 정류장 좌표/지역코드를 모르는(테스트 등) 경우용. */
    public static TransitLeg bus(String stationId, int rideMinutes, int transferBufferMinutes,
                                  String stationName, String endStationName, String laneName,
                                  List<String> busIds, String busNo, int distanceMeters) {
        return bus(stationId, rideMinutes, transferBufferMinutes, stationName, endStationName, laneName,
                busIds, busNo, distanceMeters, null, null, null, null);
    }

    public boolean isBus() {
        return mode == TransitMode.BUS;
    }
}

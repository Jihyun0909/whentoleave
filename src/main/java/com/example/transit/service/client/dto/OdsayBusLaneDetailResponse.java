package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ODsay busLaneDetail(버스 노선 상세) 응답 중 막차 추정에 필요한 필드만 매핑한다.
 * <p>
 * 지하철의 searchSubwaySchedule과 달리 <b>정류장별 시간표가 없다</b>. 노선 단위로
 * "기점 기준 첫차/막차"와 요일별 배차간격, 그리고 정류장별 기점으로부터의 누적거리만
 * 주기 때문에, 승차 정류장의 출발 시각은 이 값들로 추정할 수밖에 없다
 * (BusDepartureEstimator 참고).
 * <p>
 * 시간/간격 값이 문자열로 내려온다("23:10", "6"). 간격은 "1회 -"처럼 숫자가 아닌 경우도
 * 있어서 파싱에 실패할 수 있으니 쓰는 쪽에서 방어해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayBusLaneDetailResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            String busNo,
            String busFirstTime,
            String busLastTime,
            String busInterval,
            String bus_Interval_Week,
            String bus_Interval_Sat,
            String bus_Interval_Sun,
            Integer busTotalDistance,
            List<Station> station
    ) {
    }

    /**
     * @param stationDistance 기점에서 이 정류장까지의 누적 거리(m). 승차 정류장까지 오는 데
     *                        걸리는 시간을 추정하는 데 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(Integer idx, Integer stationID, String stationName, Integer stationDistance) {
    }
}

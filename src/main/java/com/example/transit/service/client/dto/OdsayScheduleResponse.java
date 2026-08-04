package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ODsay searchSubwaySchedule 응답 중 우리가 실제로 쓰는 필드만 매핑한다.
 * (laneName, prevOBJ, nextOBJ 등 나머지 필드는 무시)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayScheduleResponse(ScheduleResult result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleResult(
            Integer stationID,
            String stationName,
            DaySchedule weekdaySchedule,
            DaySchedule saturdaySchedule,
            DaySchedule holidaySchedule
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DaySchedule(List<TrainEntry> up, List<TrainEntry> down) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrainEntry(
            Integer subwayClass,
            String departureTime,
            String startStationName,
            String endStationName,
            Integer firstLastFlag
    ) {
    }
}

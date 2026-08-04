package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayStationSearchResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Station> station) {
    }

    /**
     * stationName은 부분/포함 검색 결과라, "정확히 일치하는 이름"만 걸러 쓰는 건
     * 이 DTO를 쓰는 쪽(StationCandidateResolver)의 책임이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(
            String stationName,
            Integer stationID,
            Double x,
            Double y,
            String laneName
    ) {
    }
}

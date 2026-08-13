package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ODsay searchBusLane(버스 노선 검색) 응답. 심야버스(N버스) 목록을 한 번에 받아오는 데 쓴다
 * (busNo=N으로 검색하면 서울 N버스가 전부 나온다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayBusLaneSearchResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Lane> lane) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(
            String busNo,
            Integer busID,
            String busCityName,
            String busStartPoint,
            String busEndPoint,
            String busFirstTime,
            String busLastTime,
            String busInterval
    ) {
    }
}

package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ODsay searchPubTransPathT 응답 중 우리가 실제로 쓰는 필드만 매핑한다.
 * SearchPathType=1(지하철 전용)으로 요청하므로 subPath에는 지하철(trafficType=1)과
 * 도보(trafficType=3) 구간만 나오는 것을 전제로 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayPathResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Path> path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(Integer pathType, List<SubPath> subPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            Integer trafficType,
            Integer sectionTime,
            Integer startID,
            Integer wayCode,
            PassStopList passStopList,
            String startName,
            List<Lane> lane
    ) {
    }

    /** 이 구간이 속한 노선 정보. 화면에 "O호선"처럼 보여주는 표시용으로만 쓴다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name) {
    }

    /**
     * 이 구간이 실제로 정차하는 역들을 순서대로 담는다. 막차 후보 중 "이 구간의
     * 도착역보다 앞에서 끊기는(단축운행) 열차"를 걸러내는 데 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PassStopList(List<Station> stations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(String stationName) {
    }
}

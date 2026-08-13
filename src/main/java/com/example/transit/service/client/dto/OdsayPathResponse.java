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
    public record Path(Integer pathType, List<SubPath> subPath, Info info) {

        public Path(Integer pathType, List<SubPath> subPath) {
            this(pathType, subPath, null);
        }
    }

    /** @param payment 이 경로의 총 요금(원). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(Integer payment) {
    }

    /**
     * @param distance 이 구간의 이동 거리(m). 버스 구간에서 sectionTime과 함께 평균 속도를 구해,
     *                 "기점에서 승차 정류장까지 오는 데 걸리는 시간"을 추정하는 데 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            Integer trafficType,
            Integer sectionTime,
            Integer startID,
            Integer wayCode,
            PassStopList passStopList,
            String startName,
            List<Lane> lane,
            Integer distance,
            String endName
    ) {
        /** 버스 필드가 필요 없는(지하철 전용) 테스트/기존 코드용. */
        public SubPath(Integer trafficType, Integer sectionTime, Integer startID, Integer wayCode,
                        PassStopList passStopList, String startName, List<Lane> lane) {
            this(trafficType, sectionTime, startID, wayCode, passStopList, startName, lane, null, null);
        }
    }

    /**
     * 이 구간이 속한 노선 정보. 지하철은 name("수도권 4호선")만 쓰고,
     * 버스는 busNo(노선번호)와 busID(노선 상세/막차 조회용 키)를 쓴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busNo, Integer busID, Integer type) {

        public Lane(String name) {
            this(name, null, null, null);
        }
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

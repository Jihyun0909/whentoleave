package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ODsay searchPubTransPathT 응답 중 우리가 실제로 쓰는 필드만 매핑한다.
 * SearchPathType=1(지하철 전용)으로 요청하므로 subPath에는 지하철(trafficType=1)과
 * 도보(trafficType=3) 구간만 나오는 것을 전제로 한다.
 * <p>
 * 실패해도 HTTP 200에 {@code error} 필드만 담아 주므로(예: 일일 호출 한도 초과 429),
 * result가 비어있다는 것만으로 "경로가 없다"고 단정하면 안 된다 - 그러면 API 장애가
 * "대중교통 운행 종료"로 잘못 안내된다(실사용 중 실제로 발생).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayPathResponse(Result result, List<Error> error) {

    /** 테스트/기존 코드용 - 정상 응답만 만들 때. */
    public OdsayPathResponse(Result result) {
        this(result, null);
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    /** @param code ODsay 오류 코드 (예: "429" 일일 호출 한도 초과) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code, String message) {
    }

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
     * @param startX   승차 정류장의 경도, @param startY 위도. 버스 구간에서 실시간 도착정보
     *                 조회용 정류소(서울시 arsId 등)를 좌표로 매칭하는 데 쓴다(지하철은 stationID로
     *                 바로 조회하므로 안 씀).
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
            String endName,
            Double startX,
            Double startY
    ) {
        /** 버스 필드가 필요 없는(지하철 전용) 테스트/기존 코드용. */
        public SubPath(Integer trafficType, Integer sectionTime, Integer startID, Integer wayCode,
                        PassStopList passStopList, String startName, List<Lane> lane) {
            this(trafficType, sectionTime, startID, wayCode, passStopList, startName, lane, null, null, null, null);
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

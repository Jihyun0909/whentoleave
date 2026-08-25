package com.example.transit.service.client;

/** 경로탐색 모드(지하철 전용/버스 전용/전체). LastDepartureService가 Google Routes의 allowedTravelModes로 매핑한다. */
public enum SearchPathType {

    ALL(0, "지하철+버스"),
    SUBWAY_ONLY(1, "지하철"),
    BUS_ONLY(2, "버스");

    private final int code;
    private final String label;

    SearchPathType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    /** 화면에 "지하철 / 지하철+버스 / 버스"로 보여줄 이름. */
    public String label() {
        return label;
    }
}

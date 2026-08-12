package com.example.transit.service.client;

/** ODsay searchPubTransPathT의 SearchPathType 파라미터. */
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

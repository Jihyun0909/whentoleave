package com.example.transit.service;

/**
 * @param laneName 화면 표시용 노선명. ODsay는 "수도권 4호선"/"수도권 수인.분당선"처럼 주는데,
 *                 이 앱은 수도권 전용이라 접두어가 정보를 더하지 않고 자리만 차지해서 떼고 저장한다.
 */
public record StationCandidate(String stationName, String laneName, double x, double y) {

    public StationCandidate {
        laneName = shortLaneName(laneName);
    }

    private static String shortLaneName(String laneName) {
        if (laneName == null) {
            return null;
        }
        return laneName.replace("수도권", "").replace(".", "").trim();
    }
}

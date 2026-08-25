package com.example.transit.service;

/**
 * @param laneName 화면 표시용 부가 설명(후보를 서로 구분하는 용도). 예전 ODsay는 "수도권 4호선"
 *                 같은 실제 노선명을 줬지만, VWorld(현재 지오코더)는 장소검색이라 노선 정보가
 *                 아예 없다 - 대신 주소(도로명 우선, 없으면 지번)를 넣어 후보를 구분한다
 *                 (예: 홍대입구역처럼 이름은 같지만 서로 다른 역사인 경우).
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

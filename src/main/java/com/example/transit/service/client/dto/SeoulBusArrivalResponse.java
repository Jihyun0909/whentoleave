package com.example.transit.service.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 서울시 버스도착정보(ws.bus.go.kr, arrive/getLowArrInfoByStId) 응답 중 실제로 쓰는 필드만 매핑한다.
 * <p>
 * 응답 항목이 100개 가까이 되는데(예측 계수, 혼잡도, 다음 정류소 ID 등) 우리는 "무슨 노선이
 * 몇 초 뒤 오는가"만 필요해서 그 부분만 남겼다. 오퍼레이션 이름에 Low(저상)가 들어가지만
 * 실제로는 그 정류장의 모든 노선을 돌려준다(2026-08-18 라이브 확인: 수유역.강북구청 21개 노선).
 *
 * @param msgHeader 처리 결과. headerCd "0"이 정상, "4"는 결과 없음.
 * @param msgBody   도착정보 목록. 결과가 없으면 itemList가 null이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeoulBusArrivalResponse(MsgHeader msgHeader, MsgBody msgBody) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgHeader(String headerMsg, String headerCd, Integer itemCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MsgBody(List<Item> itemList) {
    }

    /**
     * 한 노선의 도착정보. 첫 번째/두 번째 도착 예정 버스가 같은 항목에 1/2 접미사로 들어있다.
     *
     * @param rtNm       노선 번호 (예: "148")
     * @param busRouteId 노선 고유 ID. <b>순환 노선은 같은 정류장을 노선 한 바퀴 안에 두 번(다른
     *                    staOrd로) 지나는 경우가 있는데, busRouteId는 두 항목에서 동일하다</b>
     *                    (2026-08-25 실사용 중 발견: 1218번이 "강북구청사거리"를 staOrd 15/83
     *                    두 번 지남 - 노선번호만 보고 두 항목을 합치면 서로 다른 바퀴 지점의
     *                    버스를 섞어서 보여주게 된다). 이 필드로 중복을 걸러야 한다
     *                    ({@link com.example.transit.service.SeoulBusArrivalService} 참고).
     * @param firstTm    이 노선의 첫차 시각 ("20260820040100" - yyyyMMddHHmmss)
     * @param lastTm     이 노선의 막차 시각. 오고 있는 버스가 없을 때 "막차 몇 시였는지" 안내에 쓴다.
     * @param term       배차간격(분). 심야 노선 등은 0으로 오기도 한다.
     * @param traTime1   첫 번째 버스 도착까지 남은 시간(초). 운행종료/출발대기면 0이 온다 - 단,
     *                   진짜로 정류소에 진입 중인 버스도 GPS 갱신 시점에 따라 0으로 올 수 있다
     *                   (2026-08-25 실사용 중 발견: "곧 도착"인데 화면에서 사라진다는 신고).
     *                   이 경우는 isArrive1로 구분해야 한다.
     * @param arrmsg1    첫 번째 버스 도착 안내 문구 (예: "2분25초후[1번째 전]", "곧 도착", "운행종료")
     * @param plainNo1   첫 번째 버스 차량번호
     * @param isLast1    첫 번째 버스가 막차인지 ("1"이면 막차)
     * @param isArrive1  첫 번째 버스가 정류소 도착임박 상태인지("1"이면 임박) - traTime1이 0이어도
     *                   이 값이 "1"이면 "출발대기"가 아니라 "곧 도착"으로 봐야 한다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String rtNm, String busRouteId, String arsId, String stNm,
                        String firstTm, String lastTm, String term,
                        Integer traTime1, String arrmsg1, String plainNo1, String isLast1, String isArrive1,
                        Integer traTime2, String arrmsg2, String plainNo2, String isLast2, String isArrive2) {
    }
}

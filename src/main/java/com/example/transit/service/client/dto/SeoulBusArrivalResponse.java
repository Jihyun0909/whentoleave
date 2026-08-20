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
     * @param rtNm      노선 번호 (예: "148")
     * @param traTime1  첫 번째 버스 도착까지 남은 시간(초). 운행종료/출발대기면 0이 온다.
     * @param arrmsg1   첫 번째 버스 도착 안내 문구 (예: "2분25초후[1번째 전]", "곧 도착", "운행종료")
     * @param plainNo1  첫 번째 버스 차량번호
     * @param isLast1   첫 번째 버스가 막차인지 ("1"이면 막차)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String rtNm, String arsId, String stNm,
                        Integer traTime1, String arrmsg1, String plainNo1, String isLast1,
                        Integer traTime2, String arrmsg2, String plainNo2, String isLast2) {
    }
}

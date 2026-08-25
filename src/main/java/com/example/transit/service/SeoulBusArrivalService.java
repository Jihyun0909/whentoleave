package com.example.transit.service;

import com.example.transit.service.client.SeoulBusApiClient;
import com.example.transit.service.client.dto.SeoulBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서울시 실시간 버스 도착정보를 조회해 지역 공통 형태({@link RealtimeBusArrival})로 바꾼다.
 * <p>
 * 좌표 -> 정류소 ID는 {@link SeoulBusStopCatalog}가, 도착정보 조회는 {@link SeoulBusApiClient}가 맡는다.
 * 한 노선에 대해 첫 번째/두 번째 도착 버스가 같은 항목에 1/2 접미사로 들어있어 두 건으로 펼친다.
 * 다른 지역 서비스와 마찬가지로 실패는 예외 대신 빈 목록으로 돌려준다(보조 기능).
 */
@Service
public class SeoulBusArrivalService implements RealtimeSeoulBusArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(SeoulBusArrivalService.class);

    /** 정상 처리 코드. "4"는 결과 없음이라 예외가 아니라 빈 목록으로 취급한다. */
    private static final String HEADER_CODE_OK = "0";

    private final SeoulBusStopCatalog stopCatalog;
    private final SeoulBusApiClient client;

    public SeoulBusArrivalService(SeoulBusStopCatalog stopCatalog, SeoulBusApiClient client) {
        this.stopCatalog = stopCatalog;
        this.client = client;
    }

    @Override
    public List<RealtimeBusArrival> findArrivals(double stationX, double stationY) {
        if (!client.isConfigured()) {
            return List.of();
        }
        return stopCatalog.findNearest(stationX, stationY)
                .map(stop -> {
                    log.debug("서울 버스 실시간 조회: 요청좌표=({},{}) -> 매칭 정류소 stopId={} name={}",
                            stationX, stationY, stop.stopId(), stop.name());
                    return findArrivals(stop.stopId());
                })
                .orElse(List.of());
    }

    private List<RealtimeBusArrival> findArrivals(String stopId) {
        SeoulBusArrivalResponse response;
        try {
            response = client.findArrivals(stopId);
        } catch (RuntimeException e) {
            log.debug("서울 버스 실시간 도착정보 조회 실패 stopId={}", stopId, e);
            return List.of();
        }
        if (response == null || response.msgHeader() == null
                || !HEADER_CODE_OK.equals(response.msgHeader().headerCd())
                || response.msgBody() == null || response.msgBody().itemList() == null) {
            return List.of();
        }

        List<RealtimeBusArrival> arrivals = new ArrayList<>();
        java.util.Set<String> seenRouteIds = new java.util.HashSet<>();
        for (SeoulBusArrivalResponse.Item item : response.msgBody().itemList()) {
            if (item.rtNm() == null) {
                continue;
            }
            // 순환 노선은 같은 정류장을 한 바퀴 안에 두 번 지나는 항목이 따로 온다(Item 참고) -
            // busRouteId가 같으면 노선 순서상 먼저 오는 항목(응답이 staOrd 오름차순이라 처음
            // 만나는 쪽)만 쓰고, 뒤에 오는 바퀴는 무시한다. 둘 다 합치면 서로 다른 바퀴 지점의
            // 버스를 "곧 오는 버스 두 대"처럼 섞어서 보여주게 된다.
            if (item.busRouteId() != null && !seenRouteIds.add(item.busRouteId())) {
                continue;
            }
            boolean added = addIfArriving(arrivals, item.rtNm(), item.traTime1(), item.plainNo1());
            added |= addIfArriving(arrivals, item.rtNm(), item.traTime2(), item.plainNo2());
            if (!added) {
                arrivals.add(RealtimeBusArrival.status(item.rtNm(), statusLabelOf(item)));
            }
        }
        return arrivals;
    }

    /**
     * 운행종료·출발대기인 버스는 남은 시간이 0으로 온다. 그걸 그대로 카운트다운에 넣으면 화면에서
     * "곧 도착"으로 보여 실제로는 오지 않는 버스를 기다리게 만들므로, 여기서는 담지 않고
     * 호출하는 쪽이 상태 문구로 대신 보여준다.
     *
     * @return 실제로 담았으면 true
     */
    private boolean addIfArriving(List<RealtimeBusArrival> arrivals, String routeName,
                                   Integer seconds, String plateNo) {
        if (seconds == null || seconds <= 0) {
            return false;
        }
        arrivals.add(RealtimeBusArrival.arriving(routeName, seconds, null, plateNo));
        return true;
    }

    /**
     * 오고 있는 버스가 없을 때 그 이유를 문구로 만든다. 사유 자체는 API가 arrmsg로 알려주지만
     * ("운행종료"/"출발대기") 그것만으로는 "언제쯤 오는지"를 알 수 없어서, 같이 오는 노선
     * 시간표(첫차/막차/배차간격)를 붙여 실제로 판단할 수 있게 한다.
     */
    private String statusLabelOf(SeoulBusArrivalResponse.Item item) {
        String message = item.arrmsg1() == null ? "" : item.arrmsg1().trim();
        if (message.contains("운행종료")) {
            String lastTime = formatTime(item.lastTm());
            return lastTime == null ? "운행 종료" : "운행 종료 · 막차 " + lastTime;
        }
        if (message.contains("출발대기")) {
            Integer term = intervalMinutes(item.term());
            return term == null ? "출발대기" : "출발대기 · 배차 " + term + "분";
        }
        return message.isEmpty() ? "정보 없음" : message;
    }

    /** 원본은 "20260820184900"(yyyyMMddHHmmss) 형태라 시:분만 잘라 쓴다. */
    private String formatTime(String yyyyMMddHHmmss) {
        if (yyyyMMddHHmmss == null || yyyyMMddHHmmss.length() < 12) {
            return null;
        }
        return yyyyMMddHHmmss.substring(8, 10) + ":" + yyyyMMddHHmmss.substring(10, 12);
    }

    /** 심야 노선처럼 배차간격이 0으로 오는 경우가 있어(의미 없는 값) 그건 없는 것으로 본다. */
    private Integer intervalMinutes(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        try {
            int minutes = Integer.parseInt(term.trim());
            return minutes > 0 ? minutes : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.example.transit.service;

import com.example.transit.service.client.SeoulBusApiClient;
import com.example.transit.service.client.dto.SeoulBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        Map<String, SeoulBusArrivalResponse.Item> bestByKey = new LinkedHashMap<>();
        for (SeoulBusArrivalResponse.Item item : response.msgBody().itemList()) {
            if (item.rtNm() == null) {
                continue;
            }
            // 순환 노선은 같은 정류장을 한 바퀴 안에 두 번 지나는 항목이 따로 온다(Item 참고) -
            // busRouteId가 같으면 노선 번호만으로는 구분이 안 되니 이걸로 묶어서 그중 하나만
            // 써야 한다("곧 오는 버스 두 대"처럼 서로 다른 바퀴 지점의 버스를 섞어 보여주면
            // 안 된다). 처음엔 응답이 staOrd 오름차순이라 먼저 나오는 쪽이 항상 더 가깝다고
            // 가정했는데, 실제로는 그렇지 않다(2026-08-25 실사용 중 발견: 1218번이 앞쪽 바퀴
            // 지점에서는 아직 배차 전(출발대기)인데 뒤쪽 바퀴 지점에는 이미 다음 버스가 18분
            // 거리로 오고 있는 경우가 있었다 - 카카오맵엔 도착정보가 뜨는데 우리는 "출발대기"만
            // 보여준 원인). 그래서 순서가 아니라 "실제로 오고 있는 버스가 있는 쪽"을 우선하고,
            // 둘 다 있으면 더 가까운 쪽을 남긴다.
            String key = item.busRouteId() != null ? item.busRouteId() : item.rtNm();
            bestByKey.merge(key, item, this::preferSoonerArrival);
        }

        List<RealtimeBusArrival> arrivals = new ArrayList<>();
        for (SeoulBusArrivalResponse.Item item : bestByKey.values()) {
            boolean added = addIfArriving(arrivals, item.rtNm(), item.traTime1(), item.plainNo1(), item.isArrive1());
            added |= addIfArriving(arrivals, item.rtNm(), item.traTime2(), item.plainNo2(), item.isArrive2());
            if (!added) {
                arrivals.add(RealtimeBusArrival.status(item.rtNm(), statusLabelOf(item)));
            }
        }
        return arrivals;
    }

    /** 같은 노선(busRouteId)의 두 바퀴 지점 항목 중, 실제로 오고 있는 버스가 있는 쪽 - 있으면 더 가까운 쪽을 남긴다. */
    private SeoulBusArrivalResponse.Item preferSoonerArrival(SeoulBusArrivalResponse.Item a,
                                                               SeoulBusArrivalResponse.Item b) {
        int aSoonest = soonestArrivalSeconds(a);
        int bSoonest = soonestArrivalSeconds(b);
        return aSoonest <= bSoonest ? a : b;
    }

    /** 이 항목에서 실제로 오고 있는 차편 중 가장 빠른 남은 시간(초). 오는 차가 없으면 Integer.MAX_VALUE. */
    private int soonestArrivalSeconds(SeoulBusArrivalResponse.Item item) {
        int best = Integer.MAX_VALUE;
        if (isUsableArrival(item.traTime1(), item.isArrive1())) {
            best = Math.min(best, Math.max(item.traTime1(), 0));
        }
        if (isUsableArrival(item.traTime2(), item.isArrive2())) {
            best = Math.min(best, Math.max(item.traTime2(), 0));
        }
        return best;
    }

    private boolean isUsableArrival(Integer seconds, String isArrive) {
        return seconds != null && (seconds > 0 || "1".equals(isArrive));
    }

    /**
     * 운행종료·출발대기인 버스는 남은 시간이 0으로 온다. 그걸 그대로 카운트다운에 넣으면 화면에서
     * "곧 도착"으로 보여 실제로는 오지 않는 버스를 기다리게 만들므로, 여기서는 담지 않고
     * 호출하는 쪽이 상태 문구로 대신 보여준다.
     * <p>
     * 예외: 정류소에 진짜로 진입 중인 버스도 GPS 갱신 시점에 따라 남은 시간이 0으로 오는 경우가
     * 있다(2026-08-25 실사용 중 발견 - 카카오맵엔 "곧 도착"으로 뜨는데 우리 화면에선 그 버스
     * 정보가 통째로 사라짐). isArrive가 "1"(도착임박)이면 남은 시간이 0이어도 "출발대기"가
     * 아니라 실제로 오고 있는 버스이므로 담아야 한다 - 0초는 etaLabel에서 "곧 도착"으로 표시된다.
     *
     * @return 실제로 담았으면 true
     */
    private boolean addIfArriving(List<RealtimeBusArrival> arrivals, String routeName,
                                   Integer seconds, String plateNo, String isArrive) {
        boolean arriving = "1".equals(isArrive);
        if (seconds == null || (seconds <= 0 && !arriving)) {
            return false;
        }
        arrivals.add(RealtimeBusArrival.arriving(routeName, Math.max(seconds, 0), null, plateNo));
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

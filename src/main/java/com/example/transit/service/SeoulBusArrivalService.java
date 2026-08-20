package com.example.transit.service;

import com.example.transit.service.client.SeoulBusApiClient;
import com.example.transit.service.client.dto.SeoulBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
                .map(stop -> findArrivals(stop.stopId()))
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
        for (SeoulBusArrivalResponse.Item item : response.msgBody().itemList()) {
            addIfArriving(arrivals, item.rtNm(), item.traTime1(), item.plainNo1());
            addIfArriving(arrivals, item.rtNm(), item.traTime2(), item.plainNo2());
        }
        return arrivals;
    }

    /**
     * 운행종료·출발대기인 버스는 남은 시간이 0으로 오는데, 그걸 그대로 두면 화면에서 "곧 도착"으로
     * 보여 실제로는 오지 않는 버스를 기다리게 만든다. 실제로 오고 있는(남은 시간이 있는) 것만 담는다.
     */
    private void addIfArriving(List<RealtimeBusArrival> arrivals, String routeName,
                                Integer seconds, String plateNo) {
        if (routeName == null || seconds == null || seconds <= 0) {
            return;
        }
        arrivals.add(new RealtimeBusArrival(routeName, seconds, null, plateNo));
    }
}

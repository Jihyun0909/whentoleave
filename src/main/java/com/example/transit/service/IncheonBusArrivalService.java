package com.example.transit.service;

import com.example.transit.service.client.IncheonBusApiClient;
import com.example.transit.service.client.dto.IncheonBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link IncheonBusApiClient}로 인천 BIMS 실시간 버스 도착정보를 조회해 {@link RealtimeBusArrival}로
 * 변환한다. 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다.
 * <p>
 * 인천 응답에는 표시용 노선번호가 없어 {@code routeId}(내부 ID)를 그대로 routeName 자리에 넣는다 -
 * 화면에 실제 노선번호를 보여주려면 별도 노선 조회 API로 매핑을 추가해야 한다(TODO).
 */
@Service
public class IncheonBusArrivalService implements RealtimeIncheonBusArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(IncheonBusArrivalService.class);

    private final IncheonBusApiClient client;

    public IncheonBusArrivalService(IncheonBusApiClient client) {
        this.client = client;
    }

    @Override
    public List<RealtimeBusArrival> findArrivals(String stationId) {
        if (!client.isConfigured() || stationId == null || stationId.isBlank()) {
            return List.of();
        }
        try {
            return toArrivals(client.findArrivals(stationId));
        } catch (RuntimeException e) {
            log.debug("인천 버스 도착정보 조회 실패 stationId={}", stationId, e);
            return List.of();
        }
    }

    private List<RealtimeBusArrival> toArrivals(IncheonBusArrivalResponse response) {
        if (response == null || response.items() == null) {
            return List.of();
        }
        return response.items().stream()
                .filter(item -> item.arrivalEstimateSeconds() != null)
                .map(item -> new RealtimeBusArrival(item.routeId(), item.arrivalEstimateSeconds(),
                        item.restStopCount(), item.busNumPlate()))
                .toList();
    }
}

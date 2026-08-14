package com.example.transit.service;

import com.example.transit.service.client.SeoulSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link SeoulSubwayApiClient}로 실시간 지하철 도착정보를 조회해 도메인 형태로 변환한다.
 * 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다(SubwayScheduleCacheService와 달리
 * 정적 시간표가 아니다).
 */
@Service
public class SeoulSubwayArrivalService implements RealtimeSubwayArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(SeoulSubwayArrivalService.class);

    private final SeoulSubwayApiClient client;

    public SeoulSubwayArrivalService(SeoulSubwayApiClient client) {
        this.client = client;
    }

    @Override
    public List<SubwayArrival> findArrivals(String stationName) {
        if (!client.isConfigured() || stationName == null || stationName.isBlank()) {
            return List.of();
        }
        try {
            return toArrivals(client.findArrivals(stationName));
        } catch (RuntimeException e) {
            log.debug("서울시 지하철 실시간 도착정보 조회 실패 station={}", stationName, e);
            return List.of();
        }
    }

    private List<SubwayArrival> toArrivals(SeoulSubwayArrivalResponse response) {
        if (response == null || response.realtimeArrivalList() == null) {
            return List.of();
        }
        return response.realtimeArrivalList().stream().map(this::toArrival).toList();
    }

    private SubwayArrival toArrival(SeoulSubwayArrivalResponse.Arrival arrival) {
        return new SubwayArrival(
                arrival.updnLine(),
                arrival.bstatnNm(),
                arrival.trainLineNm(),
                arrival.arvlMsg2(),
                parseSeconds(arrival.barvlDt()),
                "1".equals(arrival.lstcarAt()));
    }

    private Integer parseSeconds(String barvlDt) {
        if (barvlDt == null) {
            return null;
        }
        try {
            return Integer.parseInt(barvlDt);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

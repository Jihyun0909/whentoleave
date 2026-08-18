package com.example.transit.service;

import com.example.transit.service.client.SeoulSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * {@link SeoulSubwayApiClient}로 실시간 지하철 도착정보를 조회해 도메인 형태로 변환한다.
 * 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다(SubwayScheduleCacheService와 달리
 * 정적 시간표가 아니다).
 */
@Service
public class SeoulSubwayArrivalService implements RealtimeSubwayArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(SeoulSubwayArrivalService.class);

    /**
     * 서울시 응답의 subwayId(자체 호선 코드) -> 화면/LineColorResolver와 같은 형식의 짧은
     * 노선명. 환승역은 한 응답에 여러 호선 열차가 섞여 오는데(예: 충무로역이면 1003/1004가
     * 같이 옴 - 실측 확인) 이 역이 어느 노선의 도착정보인지 구분할 방법이 이것뿐이다. 서울
     * 열린데이터광장 문서 기준 코드이며, 여기 없는 코드(인천/김포/용인 등 다른 사업자 노선)는
     * 애초에 이 API가 커버하지 않아 매핑할 필요가 없다.
     */
    private static final Map<String, String> LINE_NAMES_BY_SUBWAY_ID = Map.ofEntries(
            Map.entry("1001", "1호선"),
            Map.entry("1002", "2호선"),
            Map.entry("1003", "3호선"),
            Map.entry("1004", "4호선"),
            Map.entry("1005", "5호선"),
            Map.entry("1006", "6호선"),
            Map.entry("1007", "7호선"),
            Map.entry("1008", "8호선"),
            Map.entry("1009", "9호선"),
            Map.entry("1063", "경의중앙선"),
            Map.entry("1065", "공항철도"),
            Map.entry("1067", "경춘선"),
            Map.entry("1075", "수인분당선"),
            Map.entry("1077", "신분당선"),
            Map.entry("1092", "우이신설선"),
            Map.entry("1093", "서해선")
    );

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
                LINE_NAMES_BY_SUBWAY_ID.get(arrival.subwayId()),
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

package com.example.transit.service;

import com.example.transit.service.client.SeoulSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link SeoulSubwayApiClient}로 실시간 지하철 도착정보를 조회해 도메인 형태로 변환한다.
 * 조회 시점마다 바뀌는 데이터라 DB 캐시를 두지 않는다(SubwayScheduleCacheService와 달리
 * 정적 시간표가 아니다).
 */
@Service
public class SeoulSubwayArrivalService implements RealtimeSubwayArrivalLookup {

    private static final Logger log = LoggerFactory.getLogger(SeoulSubwayArrivalService.class);

    /** "수유(강북구청)"처럼 뒤에 붙는 부역명 괄호 - 서울시 API는 이걸 빼야 인식한다. */
    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

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
        String queryName = toSeoulStationName(stationName);
        try {
            return toArrivals(client.findArrivals(queryName));
        } catch (RuntimeException e) {
            log.debug("서울시 지하철 실시간 도착정보 조회 실패 station={}", queryName, e);
            return List.of();
        }
    }

    /**
     * ODsay는 부역명을 괄호로 붙여서 주는데("수유(강북구청)"), 서울시 API는 순수 역명만
     * 인식해서 괄호째로 넘기면 "해당하는 데이터가 없습니다"로 아무것도 안 나온다(실측 확인:
     * "수유(강북구청)"은 빈 응답, "수유"는 정상). 실시간 정보가 조용히 사라지던 원인이라
     * 조회 전에 뒤쪽 괄호를 떼어낸다.
     */
    private String toSeoulStationName(String stationName) {
        return TRAILING_PARENTHETICAL.matcher(stationName.trim()).replaceAll("").trim();
    }

    private List<SubwayArrival> toArrivals(SeoulSubwayArrivalResponse response) {
        if (response == null || response.realtimeArrivalList() == null) {
            return List.of();
        }
        return response.realtimeArrivalList().stream().map(this::toArrival).toList();
    }

    /** "[6]번째 전역 (의정부)"처럼 몇 번째 전역인지만 알려주고 실제 남은 시간은 아직 계산 전인 메시지. */
    private static final Pattern STATIONS_AWAY_PATTERN = Pattern.compile("^\\[\\d+\\]번째 전역");

    private SubwayArrival toArrival(SeoulSubwayArrivalResponse.Arrival arrival) {
        return new SubwayArrival(
                LINE_NAMES_BY_SUBWAY_ID.get(arrival.subwayId()),
                arrival.updnLine(),
                arrival.bstatnNm(),
                arrival.trainLineNm(),
                arrival.arvlMsg2(),
                parseSeconds(arrival.barvlDt(), arrival.arvlMsg2()),
                "1".equals(arrival.lstcarAt()));
    }

    /**
     * arvlCd "99"(운행중)는 "아직 역에 안 왔다"는 뜻일 뿐 흔한 정상 상태라, 이 코드 자체로
     * barvlDt 신뢰 여부를 가릴 수 없다(대부분의 열차가 이 코드이고, barvlDt도 "4분 30초 후"
     * 같은 실제 메시지와 정확히 일치하는 진짜 값이다 - 처음엔 이 코드를 통째로 못 믿게 했다가
     * 지하철 실시간 정보가 전부 사라지는 회귀를 만들었다).
     * <p>
     * 진짜 문제는 따로 있었다: 몇몇 열차는 아직 남은 시간 계산 전이라 barvlDt가 의미없는 "0"으로
     * 오면서, 안내 메시지도 시간이 아니라 "[6]번째 전역 (의정부)"처럼 역 개수만 알려준다(실사용
     * 중 발견 - 창동역 인천행 하행). 그런 경우만 신뢰하지 않는다.
     */
    private Integer parseSeconds(String barvlDt, String arvlMsg2) {
        if (barvlDt == null || (arvlMsg2 != null && STATIONS_AWAY_PATTERN.matcher(arvlMsg2).find())) {
            return null;
        }
        try {
            return Integer.parseInt(barvlDt);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

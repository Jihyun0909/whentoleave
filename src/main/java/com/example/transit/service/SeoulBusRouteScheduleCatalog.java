package com.example.transit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 서울 시내버스 노선별 첫차/막차/배차간격을 정적 시드 데이터에서 찾아준다.
 * <p>
 * TAGO는 서울 시내버스를 아예 커버하지 않고(2026-08-18 라이브 확인), 대체 가능한 실시간 API도
 * 다 막혀 있다(서울 열린데이터광장의 "서울시 버스 노선 정보 조회"·"서울시 버스노선별 경유정류소
 * 목록정보"는 2026-08-25 확인 결과 전부 서비스 종료, ws.bus.go.kr 노선정보 API는 신규 키 발급이
 * 막혀 기존 키로도 401). 대신 TOPIS가 매달 공개하는 "버스 운행노선 현황"(인가현황) 다운로드
 * 파일에서 노선번호·첫차시각·막차시각·배차간격(최소~최대)을 뽑아 시드로 넣었다
 * ({@code seed/seoul-bus-route-schedule.csv}, 2026년 7월 인가현황 기준, 430개 노선).
 * <p>
 * <b>알려진 한계:</b>
 * <ul>
 *   <li>노선 전체(기점) 기준 첫차/막차다. 승차 정류장까지 오는 데 걸리는 시간(offset)은 반영하지
 *       않는다 - 정류장별 순서 데이터가 없어서 계산할 수 없다. 그래서 실제보다 이르게(=안전하게)
 *       추정된다({@link com.example.transit.service.client.TagoBusRouteDetailApiClient}와 달리
 *       기점 기준 그대로 쓴다).</li>
 *   <li>순환 노선(예: "01A"/"01B") 등 이 파일에서 방향별로 노선번호가 갈리는 경우, Google이 주는
 *       노선번호(예: "01")와 정확히 일치하지 않아 못 찾을 수 있다. 심야버스도 같은 이유로
 *       ("N13상계"/"N13송파"처럼 방향 접미사가 붙어서 옴) 이 표에서는 안 찾는다 - 심야버스는
 *       {@link NightBusRouteFinder}가 별도로 처리한다.</li>
 *   <li>배차간격은 최대값을 쓴다(최소가 아니라) - 배차가 촘촘하다고 낙관적으로 가정하는 것보다
 *       뜸하다고 보수적으로 가정하는 쪽이 막차를 놓치지 않는다.</li>
 *   <li>월별로 갱신되는 파일의 한 시점 스냅샷이라, 시간이 지나면 실제와 어긋날 수 있다. 정기
 *       갱신 자동화는 안 돼 있다(TOPIS 다운로드가 정식 API가 아니라 매달 사람이 다시 받아야 함).</li>
 * </ul>
 */
@Component
public class SeoulBusRouteScheduleCatalog {

    private static final Logger log = LoggerFactory.getLogger(SeoulBusRouteScheduleCatalog.class);
    private static final String SEED_PATH = "seed/seoul-bus-route-schedule.csv";
    /** TagoTimeParser와 동일한 컷오프 - 새벽 4시 이전 막차 시각은 "다음날 그 시각"으로 본다. */
    private static final int NEXT_DAY_HOUR_CUTOFF = 4;

    private final Map<String, RouteSchedule> byRouteNo;

    public SeoulBusRouteScheduleCatalog() {
        this.byRouteNo = load();
    }

    public record RouteSchedule(LocalTime firstTime, LocalTime lastTime, boolean lastTimeNextDay,
                                 int intervalMinutes) {
    }

    public Optional<RouteSchedule> find(String routeNo) {
        return routeNo == null ? Optional.empty() : Optional.ofNullable(byRouteNo.get(routeNo));
    }

    private Map<String, RouteSchedule> load() {
        Map<String, RouteSchedule> parsed = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(SEED_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            while ((line = reader.readLine()) != null) {
                parseLine(line).ifPresent(entry -> parsed.put(entry.routeNo(), entry.schedule()));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("서울 버스 노선 시드 데이터 적재 실패 - 서울 버스 막차 계산이 이번엔 안 됨", e);
            return Map.of();
        }
        log.info("서울 버스 노선 시드 {}건 적재 완료", parsed.size());
        return Map.copyOf(parsed);
    }

    private record RouteEntry(String routeNo, RouteSchedule schedule) {
    }

    private Optional<RouteEntry> parseLine(String line) {
        String[] cols = line.split(",", -1);
        if (cols.length != 4) {
            return Optional.empty();
        }
        try {
            String routeNo = cols[0];
            LocalTime firstTime = LocalTime.parse(cols[1]);
            LocalTime lastTime = LocalTime.parse(cols[2]);
            int interval = Integer.parseInt(cols[3]);
            boolean nextDay = lastTime.getHour() < NEXT_DAY_HOUR_CUTOFF;
            return Optional.of(new RouteEntry(routeNo, new RouteSchedule(firstTime, lastTime, nextDay, interval)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}

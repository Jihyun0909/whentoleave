package com.example.transit.service;

import com.example.transit.domain.DayType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서울교통공사가 공개한 "역코드로 지하철 열차 시간표 검색" 전수 데이터(data.seoul.go.kr, 서울
 * 데이터 허브, dataset id 527, 2026-08-27 다운로드 - 1~9호선 열차별 전 역 통과시각, 42만여 행)에서
 * 역×호선×방향×요일유형의 전체 시간표를 찾아준다.
 * <p>
 * TAGO와 서울교통공사 자체 API({@link com.example.transit.service.client.SeoulSubwayLastTrainApiClient})가
 * 둘 다 시간표를 안 주는 역(사당·종합운동장·창동 등, 2026-08-25 실사용 중 발견)의 마지막 보완
 * 단계로 {@link SubwayScheduleCacheService}가 이 카탈로그를 쓴다. 이 데이터는 station ID가 TAGO의
 * subwayStationId 체계와도, 서울교통공사 STATION_CD 체계와도 다른 자체 코드(sbwy_sttcd)를 쓰기
 * 때문에(2026-08-27 라이브 확인 - 사당 STATION_CD=0226이지만 이 데이터의 사당 코드는 0433),
 * 코드가 아니라 <b>역명 + 호선번호</b>로 매칭한다.
 * <p>
 * <b>알려진 한계:</b>
 * <ul>
 *   <li>서울교통공사 운영 1~9호선만 커버한다(코레일 위탁 구간·분당선·신분당선·경의중앙선 등
 *       숫자가 아닌 노선명은 안 다룬다). 진접선(4호선 연장) 구간은 "진접" 등 역명이 그대로
 *       실려 있어 커버된다(2026-08-27 확인).</li>
 *   <li>2020-06-17 기준 정적 스냅샷이다. 이후 다이아 개정(배차 변경 등)은 반영되지 않는다 -
 *       정기 갱신 자동화는 안 돼 있다(공식 API가 아니라 사람이 다시 받아야 함).</li>
 *   <li>순환 노선(2호선 등)은 상행/하행 대신 외선/내선 개념이라 방향값(goup_godw_div)이 실제
 *       "그 방향으로 도는 열차"만 가려줄 뿐, 출발역=도착역(한 바퀴) 표기가 그대로 나온다 - 기존
 *       TAGO 기반 로직과 동일한 한계다.</li>
 * </ul>
 */
@Component
public class SeoulSubwayTimetableSeedCatalog {

    private static final Logger log = LoggerFactory.getLogger(SeoulSubwayTimetableSeedCatalog.class);
    private static final String SEED_PATH = "seed/seoul-subway-timetable.csv";
    private static final int EXPECTED_COLUMNS = 18;
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final Map<String, List<Entry>> byKey;

    public SeoulSubwayTimetableSeedCatalog() {
        this.byKey = load();
    }

    /** @param endStationName 이 열차의 최종 종착역명(표시용 - earlierStopNames 비교엔 안 쓴다, 이 시드는 단축운행 정보가 없다). */
    public record Entry(LocalTime departureTime, boolean nextDay, String endStationName) {
    }

    /**
     * @param stationName 역명(예: "사당"). Google/TAGO가 준 이름을 그대로 넘기면 된다.
     * @param laneName    노선 표시명(예: "수도권4호선", "4호선"). 숫자만 뽑아 CSV의 "OO호선"
     *                    표기와 자리수 무시하고 비교한다.
     * @param wayCode     1=상행, 2=하행 (TAGO/서울교통공사 API와 동일 컨벤션 - 2026-08-27 라이브
     *                    데이터로 확인: goup_godw_div가 그대로 이 값과 일치한다).
     */
    public List<Entry> find(String stationName, String laneName, int wayCode, DayType dayType) {
        Integer lineNo = lineNumberOf(laneName);
        if (stationName == null || lineNo == null || dayType == null) {
            return List.of();
        }
        return byKey.getOrDefault(key(stationName, lineNo, wayCode, dayType), List.of());
    }

    private Integer lineNumberOf(String laneName) {
        if (laneName == null) {
            return null;
        }
        Matcher matcher = DIGITS.matcher(laneName);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private String key(String stationName, int lineNo, int wayCode, DayType dayType) {
        return stationName + "|" + lineNo + "|" + wayCode + "|" + dayType;
    }

    private Map<String, List<Entry>> load() {
        Map<String, List<Entry>> parsed = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(SEED_PATH).getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine(); // 한글 헤더
            reader.readLine(); // 영문 필드명 헤더
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (parseLine(line, parsed)) {
                    count++;
                }
            }
            for (List<Entry> entries : parsed.values()) {
                entries.sort(Comparator.comparing(Entry::nextDay).thenComparing(Entry::departureTime));
            }
            log.info("서울교통공사 지하철 시간표 시드 {}건 적재 완료 ({}개 역×호선×방향×요일 조합)",
                    count, parsed.size());
            return Map.copyOf(parsed);
        } catch (IOException | RuntimeException e) {
            log.warn("서울교통공사 지하철 시간표 시드 적재 실패 - 이번엔 이 보완 없이 동작", e);
            return Map.of();
        }
    }

    private boolean parseLine(String line, Map<String, List<Entry>> parsed) {
        String[] cols = line.split(",", -1);
        if (cols.length != EXPECTED_COLUMNS) {
            return false;
        }
        try {
            String route = cols[1];
            String stationName = cols[4];
            String destStationName = cols[11];
            DayType dayType = toDayType(cols[12]);
            int wayCode = Integer.parseInt(cols[13]);
            Integer lineNo = lineNumberOf(route);
            if (dayType == null || lineNo == null || stationName.isEmpty()) {
                return false;
            }
            ParsedTime departure = parseTime(cols[7]);
            if (departure == null) {
                return false;
            }
            parsed.computeIfAbsent(key(stationName, lineNo, wayCode, dayType), k -> new ArrayList<>())
                    .add(new Entry(departure.time(), departure.nextDay(), destStationName));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private DayType toDayType(String day) {
        return switch (day) {
            case "1" -> DayType.WEEKDAY;
            case "2" -> DayType.SATURDAY;
            case "3" -> DayType.HOLIDAY; // 일요일 - DayType.from()도 일요일을 HOLIDAY로 묶는다.
            default -> null;
        };
    }

    private record ParsedTime(LocalTime time, boolean nextDay) {
    }

    /** "HH:MM:SS" (자정 넘으면 ODsay처럼 "24:xx:xx"류 24시 초과 표기 - 서울교통공사 자체 API와 동일). */
    private ParsedTime parseTime(String raw) {
        String[] parts = raw.split(":");
        if (parts.length < 2) {
            return null;
        }
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        int second = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
        boolean nextDay = hour >= 24;
        return new ParsedTime(LocalTime.of(nextDay ? hour - 24 : hour, minute, second), nextDay);
    }
}

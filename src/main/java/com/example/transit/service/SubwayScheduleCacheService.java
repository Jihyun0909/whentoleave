package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.repository.SubwayScheduleRepository;
import com.example.transit.service.client.SeoulSubwayLastTrainApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayLastTrainResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

/**
 * 역×방향×요일유형별 시간표를 캐싱한다.
 * <p>
 * 예전엔 "공식 막차(firstLastFlag=2/3)"만 저장했었는데, 그러면 막차로 태그되진
 * 않았지만 특정 환승 마감엔 여전히 맞는 열차를 후보에서 아예 놓치는 문제가 있었다
 * (이슈 #5). 그래서 지금은 응답에 있는 항목을 전부 저장하고, "마감을 만족하는
 * 가장 늦은 열차"를 계산 시점(LastDepartureCalculator)에서 찾는다.
 * <p>
 * TAGO는 firstLastFlag 같은 막차 표시 필드를 안 주므로(ODsay와 달리), 이 값은 항상 null로 저장한다
 * ({@link SubwaySchedule#getFirstLastFlag()}는 원래도 참고용일 뿐 계산 로직에서는 안 쓴다).
 * <p>
 * 실제 요청이 들어온 역만 그날 처음 조회될 때 캐싱하는 lazy cache-aside 방식을 쓴다
 * (전국 역을 매일 배치로 긁으면 무료 API 호출 한도를 캐싱 자체로 소진하기 때문 - TAGO는
 * 하루 10,000건이라 ODsay 때보다는 훨씬 여유롭지만, 굳이 배치로 긁을 이유는 없다).
 * <p>
 * 주의: 지금은 동시 요청 시 캐시 미스가 겹치면 TAGO를 중복 호출할 수 있다(캐시 스탬피드) -
 * ODsay 시절과 동일한 알려진 한계로, 별도 확장 기능에서 락으로 해결할 예정이다.
 * <p>
 * <b>TAGO 데이터 공백 보완(2026-08-25 실사용 중 발견):</b> TAGO가 시간표를 아예 안 주는
 * 서울교통공사 운영 구간 역이 꽤 있다(사당·종합운동장·창동·낙성대·신촌·신도림·잠실·
 * 동대문역사문화공원 등, 실제 조회로 확인). TAGO가 빈 목록을 주면 서울교통공사 자체 API
 * ({@link SeoulSubwayLastTrainApiClient})로 한 번 더 시도하고, 그마저 비어 있으면(예: 코레일
 * 위탁 구간인 4호선 진접선) 서울교통공사 전수 시간표 정적 시드
 * ({@link SeoulSubwayTimetableSeedCatalog})로 마지막으로 한 번 더 시도한다. 이 마지막 단계는
 * 역명+호선으로 찾으므로 stationName/laneName이 없으면(예: 테스트) 건너뛴다.
 */
@Service
public class SubwayScheduleCacheService implements LastTrainLookup {

    private static final Logger log = LoggerFactory.getLogger(SubwayScheduleCacheService.class);

    private static final String WAY_CODE_UP = "U";
    private static final String WAY_CODE_DOWN = "D";
    /** 막차부터 거슬러 올라가며 받을 최대 행 수 - TAGO 쪽(SCHEDULE_MAX_ROWS)과 동일 기준. */
    private static final int SEOUL_FALLBACK_MAX_ROWS = 300;
    /** TAGO subwayStationId 뒷자리의 역번호(예: "MTRS12226" -> "226")를 뽑아낸다. */
    private static final Pattern TRAILING_STATION_NUMBER = Pattern.compile("(\\d{3})$");

    private final SubwayScheduleRepository repository;
    private final TagoSubwayApiClient tagoSubwayApiClient;
    private final SeoulSubwayLastTrainApiClient seoulSubwayLastTrainApiClient;
    private final SeoulSubwayTimetableSeedCatalog seedCatalog;

    public SubwayScheduleCacheService(SubwayScheduleRepository repository, TagoSubwayApiClient tagoSubwayApiClient,
                                       SeoulSubwayLastTrainApiClient seoulSubwayLastTrainApiClient,
                                       SeoulSubwayTimetableSeedCatalog seedCatalog) {
        this.repository = repository;
        this.tagoSubwayApiClient = tagoSubwayApiClient;
        this.seoulSubwayLastTrainApiClient = seoulSubwayLastTrainApiClient;
        this.seedCatalog = seedCatalog;
    }

    @Override
    @Transactional
    public List<SubwaySchedule> getLastTrains(String stationId, int wayCode, LocalDate date,
                                               String stationName, String laneName) {
        DayType dayType = DayType.from(date);

        if (repository.existsByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType)) {
            return repository.findByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType);
        }

        List<SubwaySchedule> fetched = fetchAndCache(stationId, wayCode, dayType);
        if (!fetched.isEmpty()) {
            return fetched;
        }
        return fromSeed(stationId, stationName, laneName, wayCode, dayType);
    }

    /** 위 두 단계(TAGO, 서울교통공사 API)가 모두 빈 목록을 준 역의 마지막 보완 - DB에 캐싱하지
     * 않는다(정적 시드라 매번 다시 조회해도 비용이 없다 - 인메모리 Map 조회일 뿐). */
    private List<SubwaySchedule> fromSeed(String stationId, String stationName, String laneName,
                                           int wayCode, DayType dayType) {
        List<SeoulSubwayTimetableSeedCatalog.Entry> entries = seedCatalog.find(stationName, laneName, wayCode, dayType);
        return entries.stream()
                .map(entry -> new SubwaySchedule(stationId, wayCode, dayType, entry.endStationName(),
                        entry.departureTime(), entry.nextDay(), null))
                .toList();
    }

    private List<SubwaySchedule> fetchAndCache(String stationId, int wayCode, DayType dayType) {
        TagoBusArrivalResponse response = tagoSubwayApiClient.fetchSchedule(
                stationId, toUpDownTypeCode(wayCode), toDailyTypeCode(dayType));

        List<JsonNode> tagoItems = items(response);
        if (!tagoItems.isEmpty()) {
            List<SubwaySchedule> entries = new ArrayList<>();
            for (JsonNode item : tagoItems) {
                entries.add(repository.save(toEntity(stationId, wayCode, dayType, item)));
            }
            return entries;
        }
        return fetchAndCacheFromSeoul(stationId, wayCode, dayType);
    }

    private SubwaySchedule toEntity(String stationId, int wayCode, DayType dayType, JsonNode item) {
        TagoTimeParser.ParsedTime parsedTime = TagoTimeParser.parse(text(item, "depTime"));
        return new SubwaySchedule(
                stationId, wayCode, dayType,
                text(item, "endSubwayStationNm"),
                parsedTime.time(),
                parsedTime.nextDay(),
                null
        );
    }

    /** TAGO가 빈 목록을 준 역만 서울교통공사 자체 API로 재시도한다. */
    private List<SubwaySchedule> fetchAndCacheFromSeoul(String stationId, int wayCode, DayType dayType) {
        Optional<String> stationCode = seoulStationCodeOf(stationId);
        if (stationCode.isEmpty() || !seoulSubwayLastTrainApiClient.isConfigured()) {
            return List.of();
        }
        try {
            SeoulSubwayLastTrainResponse response = seoulSubwayLastTrainApiClient.findSchedule(
                    stationCode.get(), toWeekTag(dayType), String.valueOf(wayCode), SEOUL_FALLBACK_MAX_ROWS);
            List<SeoulSubwayLastTrainResponse.Row> rows = response == null || response.body() == null
                    || response.body().rows() == null ? List.of() : response.body().rows();
            List<SubwaySchedule> entries = new ArrayList<>();
            for (SeoulSubwayLastTrainResponse.Row row : rows) {
                toEntity(stationId, wayCode, dayType, row).ifPresent(entity -> entries.add(repository.save(entity)));
            }
            return entries;
        } catch (RuntimeException e) {
            log.debug("서울교통공사 막차시간표 폴백 실패 stationId={} wayCode={}", stationId, wayCode, e);
            return List.of();
        }
    }

    private Optional<SubwaySchedule> toEntity(String stationId, int wayCode, DayType dayType,
                                               SeoulSubwayLastTrainResponse.Row row) {
        if (row.leftTime() == null) {
            return Optional.empty();
        }
        ParsedSeoulTime parsedTime = parseSeoulLeftTime(row.leftTime());
        if (parsedTime == null) {
            return Optional.empty();
        }
        return Optional.of(new SubwaySchedule(
                stationId, wayCode, dayType,
                row.subwayEnd(),
                parsedTime.time(),
                parsedTime.nextDay(),
                null
        ));
    }

    /** "MTRS12226" -> "0226". 코레일 위탁 구간(MTRKR류 접두사) 등 이 대응이 안 맞는 경우는 못 찾은 걸로 본다. */
    private Optional<String> seoulStationCodeOf(String tagoStationId) {
        if (tagoStationId == null) {
            return Optional.empty();
        }
        Matcher matcher = TRAILING_STATION_NUMBER.matcher(tagoStationId);
        return matcher.find() ? Optional.of("0" + matcher.group(1)) : Optional.empty();
    }

    private String toWeekTag(DayType dayType) {
        return switch (dayType) {
            case WEEKDAY -> "1";
            case SATURDAY -> "2";
            case HOLIDAY -> "3";
        };
    }

    /** "24:56:30"처럼 24시를 넘는 표기(ODsay와 동일 컨벤션, TAGO의 "HHMMSS" 포맷과는 다르다). */
    private ParsedSeoulTime parseSeoulLeftTime(String raw) {
        String[] parts = raw.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            boolean nextDay = hour >= 24;
            return new ParsedSeoulTime(LocalTime.of(nextDay ? hour - 24 : hour, minute, second), nextDay);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private record ParsedSeoulTime(LocalTime time, boolean nextDay) {
    }

    private String toUpDownTypeCode(int wayCode) {
        return wayCode == 1 ? WAY_CODE_UP : WAY_CODE_DOWN;
    }

    private String toDailyTypeCode(DayType dayType) {
        return switch (dayType) {
            case WEEKDAY -> "01";
            case SATURDAY -> "02";
            case HOLIDAY -> "03";
        };
    }

    /** item은 결과가 1건이면 단일 객체로, 여러 건이면 배열로 온다 - 둘 다 리스트로 정규화한다. */
    private List<JsonNode> items(TagoBusArrivalResponse response) {
        if (response == null || response.response() == null || response.response().body() == null) {
            return List.of();
        }
        JsonNode itemsNode = response.response().body().items();
        if (itemsNode == null || itemsNode.isNull()) {
            return List.of();
        }
        JsonNode item = itemsNode.get("item");
        if (item == null || item.isNull()) {
            return List.of();
        }
        if (item.isArray()) {
            return item.isEmpty() ? List.of() : StreamSupport.stream(item.spliterator(), false).toList();
        }
        return List.of(item);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}

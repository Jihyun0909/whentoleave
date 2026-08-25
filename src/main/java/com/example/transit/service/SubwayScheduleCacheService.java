package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.repository.SubwayScheduleRepository;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 */
@Service
public class SubwayScheduleCacheService implements LastTrainLookup {

    private static final String WAY_CODE_UP = "U";
    private static final String WAY_CODE_DOWN = "D";

    private final SubwayScheduleRepository repository;
    private final TagoSubwayApiClient tagoSubwayApiClient;

    public SubwayScheduleCacheService(SubwayScheduleRepository repository, TagoSubwayApiClient tagoSubwayApiClient) {
        this.repository = repository;
        this.tagoSubwayApiClient = tagoSubwayApiClient;
    }

    @Override
    @Transactional
    public List<SubwaySchedule> getLastTrains(String stationId, int wayCode, LocalDate date) {
        DayType dayType = DayType.from(date);

        if (repository.existsByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType)) {
            return repository.findByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType);
        }

        return fetchAndCache(stationId, wayCode, dayType);
    }

    private List<SubwaySchedule> fetchAndCache(String stationId, int wayCode, DayType dayType) {
        TagoBusArrivalResponse response = tagoSubwayApiClient.fetchSchedule(
                stationId, toUpDownTypeCode(wayCode), toDailyTypeCode(dayType));

        List<SubwaySchedule> entries = new ArrayList<>();
        for (JsonNode item : items(response)) {
            entries.add(repository.save(toEntity(stationId, wayCode, dayType, item)));
        }
        return entries;
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

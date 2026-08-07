package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.repository.SubwayScheduleRepository;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayScheduleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 역×방향×요일유형별 시간표를 캐싱한다.
 * <p>
 * 처음엔 "공식 막차(firstLastFlag=2/3)"만 저장했었는데, 그러면 막차로 태그되진
 * 않았지만 특정 환승 마감엔 여전히 맞는 열차를 후보에서 아예 놓치는 문제가 있었다
 * (이슈 #5 — 예: 공식 막차가 마감을 몇 분 차로 못 맞추는데, 그 앞의 비공식 열차는
 * 맞는 경우). 그래서 지금은 응답에 있는 항목을 전부 저장하고, "마감을 만족하는
 * 가장 늦은 열차"를 계산 시점(LastDepartureCalculator)에서 찾는다.
 * API 호출 횟수는 그대로다 — 이미 받는 응답에서 더 많은 항목을 저장할 뿐이다.
 * <p>
 * 수도권 700여개 역을 매일 전부 배치로 긁으면 무료 API 호출 한도를 캐싱 자체로 소진하므로,
 * 실제 요청이 들어온 역만 그날 처음 조회될 때 캐싱하는 lazy cache-aside 방식을 쓴다.
 * <p>
 * 주의: 지금은 동시 요청 시 캐시 미스가 겹치면 ODsay를 중복 호출할 수 있다.
 * 이 문제(캐시 스탬피드)는 별도 확장 기능(docs/future-features-concurrency.md 기능 1)에서
 * 락으로 해결할 예정이며, 지금 단계에서는 의도적으로 단순하게 둔다.
 */
@Service
public class SubwayScheduleCacheService implements LastTrainLookup {

    private final SubwayScheduleRepository repository;
    private final OdsayClient odsayClient;

    public SubwayScheduleCacheService(SubwayScheduleRepository repository, OdsayClient odsayClient) {
        this.repository = repository;
        this.odsayClient = odsayClient;
    }

    @Override
    @Transactional
    public List<SubwaySchedule> getLastTrains(int stationId, int wayCode) {
        DayType dayType = DayType.from(LocalDate.now());

        if (repository.existsByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType)) {
            return repository.findByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType);
        }

        return fetchAndCache(stationId, wayCode, dayType);
    }

    private List<SubwaySchedule> fetchAndCache(int stationId, int wayCode, DayType dayType) {
        OdsayScheduleResponse response = odsayClient.fetchSubwaySchedule(stationId, wayCode);
        OdsayScheduleResponse.DaySchedule daySchedule = pickDaySchedule(response.result(), dayType);
        List<OdsayScheduleResponse.TrainEntry> entries = pickDirection(daySchedule, wayCode);

        return entries.stream()
                .map(entry -> toEntity(stationId, wayCode, dayType, entry))
                .map(repository::save)
                .toList();
    }

    private OdsayScheduleResponse.DaySchedule pickDaySchedule(
            OdsayScheduleResponse.ScheduleResult result, DayType dayType) {
        return switch (dayType) {
            case WEEKDAY -> result.weekdaySchedule();
            case SATURDAY -> result.saturdaySchedule();
            case HOLIDAY -> result.holidaySchedule();
        };
    }

    private List<OdsayScheduleResponse.TrainEntry> pickDirection(
            OdsayScheduleResponse.DaySchedule daySchedule, int wayCode) {
        List<OdsayScheduleResponse.TrainEntry> entries = (wayCode == 1) ? daySchedule.up() : daySchedule.down();
        return entries == null ? List.of() : entries;
    }

    private SubwaySchedule toEntity(int stationId, int wayCode, DayType dayType,
                                     OdsayScheduleResponse.TrainEntry entry) {
        OdsayTimeParser.ParsedTime parsedTime = OdsayTimeParser.parse(entry.departureTime());
        return new SubwaySchedule(
                stationId, wayCode, dayType,
                entry.endStationName(),
                parsedTime.time(),
                parsedTime.nextDay(),
                entry.firstLastFlag()
        );
    }
}

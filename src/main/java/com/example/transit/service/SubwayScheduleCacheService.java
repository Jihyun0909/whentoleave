package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwayLastTrain;
import com.example.transit.repository.SubwayLastTrainRepository;
import com.example.transit.service.client.OdsayClient;
import com.example.transit.service.client.dto.OdsayScheduleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 역×방향×요일유형별 막차 시각을 캐싱한다.
 * <p>
 * 수도권 700여개 역을 매일 전부 배치로 긁으면 무료 API 호출 한도를 캐싱 자체로 소진하므로,
 * 실제 요청이 들어온 역만 그날 처음 조회될 때 캐싱하는 lazy cache-aside 방식을 쓴다.
 * <p>
 * 주의: 지금은 동시 요청 시 캐시 미스가 겹치면 ODsay를 중복 호출할 수 있다.
 * 이 문제(캐시 스탬피드)는 별도 확장 기능(docs/future-features-concurrency.md 기능 1)에서
 * 락으로 해결할 예정이며, 지금 단계에서는 의도적으로 단순하게 둔다.
 */
@Service
public class SubwayScheduleCacheService {

    private final SubwayLastTrainRepository repository;
    private final OdsayClient odsayClient;

    public SubwayScheduleCacheService(SubwayLastTrainRepository repository, OdsayClient odsayClient) {
        this.repository = repository;
        this.odsayClient = odsayClient;
    }

    @Transactional
    public List<SubwayLastTrain> getLastTrains(int stationId, int wayCode) {
        DayType dayType = DayType.from(LocalDate.now());

        if (repository.existsByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType)) {
            return repository.findByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType);
        }

        return fetchAndCache(stationId, wayCode, dayType);
    }

    private List<SubwayLastTrain> fetchAndCache(int stationId, int wayCode, DayType dayType) {
        OdsayScheduleResponse response = odsayClient.fetchSubwaySchedule(stationId, wayCode);
        OdsayScheduleResponse.DaySchedule daySchedule = pickDaySchedule(response.result(), dayType);
        List<OdsayScheduleResponse.TrainEntry> entries = pickDirection(daySchedule, wayCode);

        return entries.stream()
                .filter(entry -> entry.firstLastFlag() != null
                        && (entry.firstLastFlag() == 2 || entry.firstLastFlag() == 3))
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

    private SubwayLastTrain toEntity(int stationId, int wayCode, DayType dayType,
                                      OdsayScheduleResponse.TrainEntry entry) {
        OdsayTimeParser.ParsedTime parsedTime = OdsayTimeParser.parse(entry.departureTime());
        return new SubwayLastTrain(
                stationId, wayCode, dayType,
                entry.endStationName(),
                parsedTime.time(),
                parsedTime.nextDay()
        );
    }
}

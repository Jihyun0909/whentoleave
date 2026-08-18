package com.example.transit.repository;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * (bus_id, station_id, day_type) 유니크 제약 회귀 테스트.
 * <p>
 * 이 제약이 없으면 캐시 미스 경합으로 같은 키에 두 행이 들어갈 수 있고, 그러면
 * {@link BusStopDepartureRepository#findByBusIdAndStationIdAndDayType}(Optional 반환, 즉 최대
 * 한 행을 기대)가 이후 조회에서 {@code NonUniqueResultException}을 던져 500이 난다(실제 발생).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BusStopDepartureRepositoryTest {

    @Autowired
    private BusStopDepartureRepository repository;

    @Test
    void 같은_버스_정류장_요일유형_조합은_두번째_저장에서_유니크_제약_위반이_난다() {
        repository.saveAndFlush(departure(1500, 80821, DayType.WEEKDAY, "05:00", "23:00"));

        BusStopDeparture duplicate = departure(1500, 80821, DayType.WEEKDAY, "05:10", "23:10");
        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(duplicate));
    }

    @Test
    void 버스나_정류장이나_요일유형_중_하나라도_다르면_함께_저장된다() {
        repository.saveAndFlush(departure(1500, 80821, DayType.WEEKDAY, "05:00", "23:00"));
        repository.saveAndFlush(departure(1600, 80821, DayType.WEEKDAY, "05:00", "23:00"));
        repository.saveAndFlush(departure(1500, 90000, DayType.WEEKDAY, "05:00", "23:00"));
        repository.saveAndFlush(departure(1500, 80821, DayType.SATURDAY, "05:00", "23:00"));

        assertEquals(4, repository.count());
    }

    private BusStopDeparture departure(int busId, int stationId, DayType dayType, String first, String last) {
        return new BusStopDeparture(
                busId, stationId, dayType,
                LocalTime.parse(first), false,
                LocalTime.parse(last), false,
                15, "120");
    }
}

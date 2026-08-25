package com.example.transit.repository;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusStopDepartureRepository extends JpaRepository<BusStopDeparture, Long> {

    /**
     * 이 키로는 행이 하나만 있는 게 정상이고 유니크 제약도 걸려 있지만, 조회는 굳이
     * "가장 최근 것 하나"로 한다 - 제약이 붙기 전에 이미 중복이 쌓인 DB에서는 단순
     * Optional 조회가 NonUniqueResultException으로 500을 내기 때문이다(실제 발생).
     * 이렇게 두면 중복 정리 전이라도 서비스가 멈추지 않는다.
     */
    Optional<BusStopDeparture> findFirstByBusIdAndStationIdAndDayTypeOrderByIdDesc(
            String busId, String stationId, DayType dayType
    );
}

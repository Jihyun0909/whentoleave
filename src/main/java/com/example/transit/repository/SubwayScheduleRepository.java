package com.example.transit.repository;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubwayScheduleRepository extends JpaRepository<SubwaySchedule, Long> {

    List<SubwaySchedule> findByStationIdAndWayCodeAndDayType(
            Integer stationId, Integer wayCode, DayType dayType
    );

    boolean existsByStationIdAndWayCodeAndDayType(
            Integer stationId, Integer wayCode, DayType dayType
    );
}

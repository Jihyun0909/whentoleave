package com.example.transit.repository;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwayLastTrain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SubwayLastTrainRepository extends JpaRepository<SubwayLastTrain, Long> {

    List<SubwayLastTrain> findByStationIdAndWayCodeAndDayType(
            Integer stationId, Integer wayCode, DayType dayType
    );

    boolean existsByStationIdAndWayCodeAndDayType(
            Integer stationId, Integer wayCode, DayType dayType
    );
}

package com.example.transit.repository;

import com.example.transit.domain.BusStopDeparture;
import com.example.transit.domain.DayType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusStopDepartureRepository extends JpaRepository<BusStopDeparture, Long> {

    Optional<BusStopDeparture> findByBusIdAndStationIdAndDayType(
            Integer busId, Integer stationId, DayType dayType
    );
}

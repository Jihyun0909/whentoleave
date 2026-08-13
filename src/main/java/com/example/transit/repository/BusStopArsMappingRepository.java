package com.example.transit.repository;

import com.example.transit.domain.BusStopArsMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusStopArsMappingRepository extends JpaRepository<BusStopArsMapping, Long> {

    Optional<BusStopArsMapping> findByCoordKey(String coordKey);
}

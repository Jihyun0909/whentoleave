package com.example.transit.repository;

import com.example.transit.domain.RegionalBusStopMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RegionalBusStopMappingRepository extends JpaRepository<RegionalBusStopMapping, Long> {

    Optional<RegionalBusStopMapping> findByProviderAndCoordKey(String provider, String coordKey);
}

package com.example.transit.repository;

import com.example.transit.domain.TaxiRide;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxiRideRepository extends JpaRepository<TaxiRide, Long> {

    Optional<TaxiRide> findByIdAndUserId(Long id, Long userId);

    List<TaxiRide> findByUserIdOrderByIdDesc(Long userId);
}

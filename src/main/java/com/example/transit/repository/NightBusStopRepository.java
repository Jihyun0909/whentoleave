package com.example.transit.repository;

import com.example.transit.domain.NightBusStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NightBusStopRepository extends JpaRepository<NightBusStop, Long> {

    List<NightBusStop> findByBusNoOrderBySequenceAsc(String busNo);

    @Query("select distinct s.busNo from NightBusStop s")
    List<String> findDistinctBusNos();
}

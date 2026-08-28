package com.example.transit.repository;

import com.example.transit.domain.Partner;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    List<Partner> findByActiveTrue();

    boolean existsByName(String name);
}

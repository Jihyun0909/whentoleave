package com.example.transit.api;

import com.example.transit.api.dto.StationSearchResponse;
import com.example.transit.service.StationSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StationSearchController {

    private final StationSearchService stationSearchService;

    public StationSearchController(StationSearchService stationSearchService) {
        this.stationSearchService = stationSearchService;
    }

    @GetMapping("/api/v1/stations")
    public StationSearchResponse search(@RequestParam String name) {
        return StationSearchResponse.from(stationSearchService.resolve(name));
    }
}

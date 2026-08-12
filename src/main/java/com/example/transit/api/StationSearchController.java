package com.example.transit.api;

import com.example.transit.api.dto.StationSearchResponse;
import com.example.transit.service.StationCandidate;
import com.example.transit.service.StationSearchService;
import com.example.transit.service.StationSuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StationSearchController {

    private final StationSearchService stationSearchService;
    private final StationSuggestionService stationSuggestionService;

    public StationSearchController(StationSearchService stationSearchService,
                                    StationSuggestionService stationSuggestionService) {
        this.stationSearchService = stationSearchService;
        this.stationSuggestionService = stationSuggestionService;
    }

    @GetMapping("/api/v1/stations")
    public StationSearchResponse search(@RequestParam String name) {
        return StationSearchResponse.from(stationSearchService.resolve(name));
    }

    /** 입력 중인 역 이름 자동완성용. 프론트에서 디바운스해서 호출한다. */
    @GetMapping("/api/v1/stations/suggest")
    public List<StationCandidate> suggest(@RequestParam String query) {
        return stationSuggestionService.suggest(query);
    }
}

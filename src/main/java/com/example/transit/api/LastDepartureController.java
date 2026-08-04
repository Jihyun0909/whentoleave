package com.example.transit.api;

import com.example.transit.api.dto.LastDepartureResponse;
import com.example.transit.service.LastDepartureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LastDepartureController {

    private final LastDepartureService lastDepartureService;

    public LastDepartureController(LastDepartureService lastDepartureService) {
        this.lastDepartureService = lastDepartureService;
    }

    /**
     * 좌표 기반 임시 인터페이스. 역 이름 검색/자동완성은 후속 작업(이슈 #2 스코프 제외 항목).
     */
    @GetMapping("/api/v1/last-departure")
    public LastDepartureResponse lastDeparture(
            @RequestParam double sx, @RequestParam double sy,
            @RequestParam double ex, @RequestParam double ey) {
        return LastDepartureResponse.from(lastDepartureService.calculate(sx, sy, ex, ey));
    }
}

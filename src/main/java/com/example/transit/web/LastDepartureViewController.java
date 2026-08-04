package com.example.transit.web;

import com.example.transit.service.LastDepartureResult;
import com.example.transit.service.LastDepartureService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LastDepartureViewController {

    private final LastDepartureService lastDepartureService;

    public LastDepartureViewController(LastDepartureService lastDepartureService) {
        this.lastDepartureService = lastDepartureService;
    }

    /**
     * 좌표 기반 임시 화면. 역 이름 검색/자동완성은 후속 작업(이슈 #2 스코프 제외 항목).
     */
    @GetMapping("/")
    public String index(
            @RequestParam(required = false) Double sx,
            @RequestParam(required = false) Double sy,
            @RequestParam(required = false) Double ex,
            @RequestParam(required = false) Double ey,
            Model model) {

        if (sx != null && sy != null && ex != null && ey != null) {
            LastDepartureResult result = lastDepartureService.calculate(sx, sy, ex, ey);
            model.addAttribute("searched", true);

            switch (result) {
                case LastDepartureResult.Feasible f -> {
                    model.addAttribute("feasible", true);
                    model.addAttribute("departureTime", f.departureTime());
                    model.addAttribute("nextDay", f.nextDay());
                }
                case LastDepartureResult.Infeasible i -> {
                    model.addAttribute("feasible", false);
                    model.addAttribute("reason", i.reason());
                }
            }
        }

        return "index";
    }
}

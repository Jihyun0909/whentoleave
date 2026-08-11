package com.example.transit.web;

import com.example.transit.service.LastDepartureResult;
import com.example.transit.service.LastDepartureService;
import com.example.transit.service.StationResolution;
import com.example.transit.service.StationSearchService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalTime;

@Controller
public class LastDepartureViewController {

    private final LastDepartureService lastDepartureService;
    private final StationSearchService stationSearchService;

    public LastDepartureViewController(LastDepartureService lastDepartureService,
                                        StationSearchService stationSearchService) {
        this.lastDepartureService = lastDepartureService;
        this.stationSearchService = stationSearchService;
    }

    /**
     * 역 이름으로 출발지/도착지를 입력받는다. 이름이 여러 역과 매칭되면(환승역 등)
     * 후보 목록을 보여주고, 후보를 고르면 그 좌표(originX/Y, destX/Y)를 담아 다시 요청한다.
     * mode=arrival이면 막차 기준이 아니라 targetArrivalTime까지 도착하는 것을 기준으로 역산한다(이슈 #6).
     */
    @GetMapping("/")
    public String index(
            @RequestParam(required = false) String originName,
            @RequestParam(required = false) String destName,
            @RequestParam(required = false) Double originX,
            @RequestParam(required = false) Double originY,
            @RequestParam(required = false) Double destX,
            @RequestParam(required = false) Double destY,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) LocalTime targetArrivalTime,
            Model model) {

        boolean arrivalMode = "arrival".equals(mode);
        model.addAttribute("arrivalMode", arrivalMode);
        model.addAttribute("originName", originName);
        model.addAttribute("destName", destName);
        model.addAttribute("targetArrivalTime", targetArrivalTime);

        if (!StringUtils.hasText(originName) || !StringUtils.hasText(destName)
                || (arrivalMode && targetArrivalTime == null)) {
            return "index";
        }

        StationResolution originResolution = (originX != null && originY != null)
                ? new StationResolution.Resolved(originX, originY, originName)
                : stationSearchService.resolve(originName);

        StationResolution destResolution = (destX != null && destY != null)
                ? new StationResolution.Resolved(destX, destY, destName)
                : stationSearchService.resolve(destName);

        StationResolution.Resolved origin = addResolutionToModel(model, "origin", originResolution);
        StationResolution.Resolved dest = addResolutionToModel(model, "dest", destResolution);

        if (origin == null || dest == null) {
            return "index";
        }

        LastDepartureResult result = arrivalMode
                ? lastDepartureService.calculate(origin.x(), origin.y(), dest.x(), dest.y(), targetArrivalTime)
                : lastDepartureService.calculate(origin.x(), origin.y(), dest.x(), dest.y());
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

        return "index";
    }

    /**
     * StationResolution을 화면에서 다루기 쉬운 평평한 속성들로 변환해 모델에 담는다.
     * Thymeleaf에서 sealed interface를 instanceof로 분기하는 대신 문자열 상태값으로 처리하기 위함.
     *
     * @return Resolved인 경우 그 값, 아니면 null
     */
    private StationResolution.Resolved addResolutionToModel(Model model, String prefix, StationResolution resolution) {
        switch (resolution) {
            case StationResolution.Resolved r -> {
                model.addAttribute(prefix + "Status", "RESOLVED");
                model.addAttribute(prefix + "DisplayName", r.displayName());
                model.addAttribute(prefix + "X", r.x());
                model.addAttribute(prefix + "Y", r.y());
                return r;
            }
            case StationResolution.Ambiguous a -> {
                model.addAttribute(prefix + "Status", "AMBIGUOUS");
                model.addAttribute(prefix + "Candidates", a.candidates());
                return null;
            }
            case StationResolution.NotFound n -> {
                model.addAttribute(prefix + "Status", "NOT_FOUND");
                return null;
            }
        }
    }
}

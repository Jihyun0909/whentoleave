package com.example.transit.api;

import com.example.transit.api.dto.settlement.SettlementResponse;
import com.example.transit.api.dto.settlement.SettlementRunRequest;
import com.example.transit.api.dto.settlement.SettlementRunResponse;
import com.example.transit.repository.SettlementRepository;
import com.example.transit.service.settlement.SettlementLauncher;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 운영자 전용. SecurityConfig에서 {@code /api/v1/admin/**} → ROLE_ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/settlements")
public class AdminSettlementController {

    private final SettlementLauncher settlementLauncher;
    private final SettlementRepository settlements;

    public AdminSettlementController(SettlementLauncher settlementLauncher, SettlementRepository settlements) {
        this.settlementLauncher = settlementLauncher;
        this.settlements = settlements;
    }

    /** 정산 배치를 지금 실행한다. 본문 생략 시 어제 날짜, 전체 제휴사. */
    @PostMapping("/run")
    public SettlementRunResponse run(@RequestBody(required = false) SettlementRunRequest request) {
        LocalDate date = request != null && request.settlementDate() != null
                ? request.settlementDate()
                : LocalDate.now().minusDays(1);
        Long partnerId = request != null ? request.partnerId() : null;
        return SettlementRunResponse.from(settlementLauncher.run(date, partnerId));
    }

    @GetMapping
    public List<SettlementResponse> list(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return settlements.findAllByOrderByIdDesc(PageRequest.of(page, size))
                .map(SettlementResponse::from).getContent();
    }
}

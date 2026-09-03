package com.example.transit.api;

import com.example.transit.api.dto.settlement.SettlementResponse;
import com.example.transit.repository.SettlementRepository;
import com.example.transit.service.auth.AuthenticatedUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 제휴사 관리자 전용. SecurityConfig에서 {@code /api/v1/partner/**} → ROLE_PARTNER_ADMIN.
 * 항상 토큰에 담긴 {@code partnerId}의 정산만 돌려준다(다른 제휴사 것은 볼 수 없다).
 */
@RestController
public class PartnerSettlementController {

    private final SettlementRepository settlements;

    public PartnerSettlementController(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    @GetMapping("/api/v1/partner/settlements")
    public List<SettlementResponse> mySettlements(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        if (user.partnerId() == null) {
            return List.of();
        }
        return settlements.findByPartnerIdOrderByIdDesc(user.partnerId(), PageRequest.of(page, size))
                .map(SettlementResponse::from).getContent();
    }
}

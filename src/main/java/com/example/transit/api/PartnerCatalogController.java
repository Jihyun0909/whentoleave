package com.example.transit.api;

import com.example.transit.api.dto.ride.PartnerResponse;
import com.example.transit.repository.PartnerRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 이용 요청 시 고를 수 있는 (가상) 제휴사 목록. 인증된 사용자면 조회 가능.
 * 경로가 {@code /api/v1/partners}라 SecurityConfig의 {@code /api/v1/partner/**}
 * (PARTNER_ADMIN 전용, 단수형)와 겹치지 않는다.
 */
@RestController
public class PartnerCatalogController {

    private final PartnerRepository partners;

    public PartnerCatalogController(PartnerRepository partners) {
        this.partners = partners;
    }

    @GetMapping("/api/v1/partners")
    public List<PartnerResponse> activePartners() {
        return partners.findByActiveTrue().stream().map(PartnerResponse::from).toList();
    }
}

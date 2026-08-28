package com.example.transit.api;

import com.example.transit.api.dto.ride.PartnerResponse;
import com.example.transit.domain.Partner;
import com.example.transit.repository.PartnerRepository;
import com.example.transit.service.ride.RideException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 운영자 전용 제휴사 관리. {@code /api/v1/admin/**} → ROLE_ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/partners")
public class AdminPartnerController {

    private final PartnerRepository partners;

    public AdminPartnerController(PartnerRepository partners) {
        this.partners = partners;
    }

    @GetMapping
    public List<PartnerResponse> all() {
        return partners.findAll().stream().map(PartnerResponse::from).toList();
    }

    @PostMapping("/{id}/activate")
    @Transactional
    public PartnerResponse activate(@PathVariable long id) {
        return PartnerResponse.from(setActive(id, true));
    }

    @PostMapping("/{id}/deactivate")
    @Transactional
    public PartnerResponse deactivate(@PathVariable long id) {
        return PartnerResponse.from(setActive(id, false));
    }

    private Partner setActive(long id, boolean active) {
        Partner partner = partners.findById(id)
                .orElseThrow(() -> new RideException(RideException.Reason.PARTNER_NOT_AVAILABLE, "제휴사를 찾을 수 없습니다"));
        if (active) {
            partner.activate();
        } else {
            partner.deactivate();
        }
        return partners.save(partner);
    }
}

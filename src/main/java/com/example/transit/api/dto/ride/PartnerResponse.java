package com.example.transit.api.dto.ride;

import com.example.transit.domain.Partner;

public record PartnerResponse(
        long id,
        String name,
        int commissionBasisPoints
) {
    public static PartnerResponse from(Partner partner) {
        return new PartnerResponse(partner.getId(), partner.getName(), partner.getCommissionBasisPoints());
    }
}

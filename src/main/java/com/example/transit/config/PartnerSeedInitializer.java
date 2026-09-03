package com.example.transit.config;

import com.example.transit.domain.Partner;
import com.example.transit.repository.PartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 가상 제휴 택시회사 시드. 실제 제휴 심사·사업자 등록이 없으므로, 이용/정산을 붙일 대상이
 * 최소한 존재하도록 기동 시 한 번 채운다(이미 있으면 건너뜀).
 */
@Component
public class PartnerSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PartnerSeedInitializer.class);

    private final PartnerRepository partners;

    public PartnerSeedInitializer(PartnerRepository partners) {
        this.partners = partners;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (partners.count() > 0) {
            return;
        }
        List<Partner> seed = List.of(
                new Partner("모범택시협동조합", 1500, true),   // 15.00%
                new Partner("가온모빌리티", 1200, true),        // 12.00%
                new Partner("한빛콜택시", 1000, false)          // 비활성 - 이용 불가 케이스용
        );
        partners.saveAll(seed);
        log.info("제휴사 시드 {}건 적재", seed.size());
    }
}

package com.example.transit.config;

import com.example.transit.service.settlement.SettlementLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;

/**
 * 매일 새벽 어제 거래를 일괄 정산한다. {@code app.settlement.scheduler-enabled=false}면 등록되지 않는다
 * (테스트·수동 운영).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.settlement.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final SettlementLauncher settlementLauncher;

    public SchedulingConfig(SettlementLauncher settlementLauncher) {
        this.settlementLauncher = settlementLauncher;
    }

    @Scheduled(cron = "${app.settlement.cron:0 0 3 * * *}")
    public void runDailySettlement() {
        LocalDate target = LocalDate.now().minusDays(1);
        log.info("일일 정산 배치 시작: {}", target);
        var result = settlementLauncher.run(target);
        log.info("일일 정산 배치 종료: status={}, 확정 {}건, 실패 {}건",
                result.status(), result.settledPartners(), result.failedPartners());
    }
}

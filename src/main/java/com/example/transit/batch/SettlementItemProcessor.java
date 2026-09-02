package com.example.transit.batch;

import com.example.transit.service.settlement.SettlementDraft;
import com.example.transit.service.settlement.SettlementService;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 제휴사 id 하나 → 그 제휴사의 정산안({@link SettlementDraft}). 정산할 게 없으면 {@code null}을
 * 돌려줘 라이터로 안 넘어가게 하고, 정산 불가면 {@code SettlementException}으로 스킵된다.
 * <p>
 * 람다 대신 클래스로 둔 이유: {@code @Bean} 메서드가 인터페이스({@code ItemProcessor})를 반환하면
 * Spring Batch가 "리스너 애노테이션 스캔 안 함" WARN을 남긴다. 구상 클래스면 안 남는다.
 */
@Component
@StepScope
public class SettlementItemProcessor implements ItemProcessor<Long, SettlementDraft> {

    private final SettlementService settlementService;
    private final LocalDate settlementDate;
    private final Long jobExecutionId;

    public SettlementItemProcessor(
            SettlementService settlementService,
            @Value("#{jobParameters['" + SettlementBatchConfig.PARAM_SETTLEMENT_DATE + "']}") LocalDate settlementDate,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.settlementService = settlementService;
        this.settlementDate = settlementDate;
        this.jobExecutionId = jobExecutionId;
    }

    @Nullable
    @Override
    public SettlementDraft process(Long partnerId) {
        return settlementService.calculate(partnerId, settlementDate, settlementDate, jobExecutionId);
    }
}

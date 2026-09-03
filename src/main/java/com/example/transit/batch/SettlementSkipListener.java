package com.example.transit.batch;

import com.example.transit.service.settlement.SettlementDraft;
import com.example.transit.service.settlement.SettlementException;
import com.example.transit.service.settlement.SettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 스텝이 {@link SettlementException}을 skip으로 넘길 때, 해당 제휴사의 이번 회차를
 * {@code FAILED}로 기록한다. 롤백된 청크와 별개의 트랜잭션으로 커밋된다
 * ({@link SettlementService#recordFailure}가 {@code REQUIRES_NEW}).
 */
@Component
@StepScope
public class SettlementSkipListener implements SkipListener<Long, SettlementDraft> {

    private static final Logger log = LoggerFactory.getLogger(SettlementSkipListener.class);

    private final SettlementService settlementService;
    private final LocalDate settlementDate;
    private final Long jobExecutionId;

    public SettlementSkipListener(SettlementService settlementService,
                                  @Value("#{jobParameters['settlementDate']}") LocalDate settlementDate,
                                  @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        this.settlementService = settlementService;
        this.settlementDate = settlementDate;
        this.jobExecutionId = jobExecutionId;
    }

    @Override
    public void onSkipInProcess(Long partnerId, Throwable t) {
        record(partnerId, t);
    }

    @Override
    public void onSkipInWrite(SettlementDraft item, Throwable t) {
        record(item.partnerId(), t);
    }

    private void record(long partnerId, Throwable t) {
        log.warn("정산 실패 - 제휴사 {} FAILED 기록. 사유: {}", partnerId, t.getMessage());
        settlementService.recordFailure(partnerId, settlementDate, settlementDate,
                t.getMessage(), jobExecutionId);
    }
}

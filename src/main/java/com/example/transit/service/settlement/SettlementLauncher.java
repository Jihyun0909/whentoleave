package com.example.transit.service.settlement;

import com.example.transit.batch.SettlementBatchConfig;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 정산 배치를 트리거한다. 관리자 API와 스케줄러가 이걸 부른다.
 * <p>
 * 같은 날짜로 여러 번 돌려도 안전하다 - 도메인 레벨에서 {@code Settlement} 유니크 +
 * {@code DONE} 스킵으로 멱등하다. 매 실행은 {@code run.id}가 달라 새 JobInstance가 된다.
 */
@Service
public class SettlementLauncher {

    private final JobOperator jobOperator;
    private final Job partnerSettlementJob;

    public SettlementLauncher(JobOperator jobOperator, Job partnerSettlementJob) {
        this.jobOperator = jobOperator;
        this.partnerSettlementJob = partnerSettlementJob;
    }

    public SettlementRunResult run(LocalDate settlementDate) {
        return run(settlementDate, null);
    }

    /**
     * @param partnerId 지정하면 그 제휴사만 정산한다(재정산 등). null이면 전체.
     */
    public SettlementRunResult run(LocalDate settlementDate, Long partnerId) {
        if (settlementDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("미래 날짜는 정산할 수 없습니다: " + settlementDate);
        }
        var builder = new JobParametersBuilder()
                .addLocalDate(SettlementBatchConfig.PARAM_SETTLEMENT_DATE, settlementDate)
                .addLong("run.id", System.currentTimeMillis());
        if (partnerId != null) {
            builder.addLong(SettlementBatchConfig.PARAM_PARTNER_ID, partnerId);
        }
        var params = builder.toJobParameters();
        try {
            JobExecution execution = jobOperator.start(partnerSettlementJob, params);
            long skipped = execution.getStepExecutions().stream()
                    .mapToLong(se -> se.getSkipCount()).sum();
            long written = execution.getStepExecutions().stream()
                    .mapToLong(se -> se.getWriteCount()).sum();
            return new SettlementRunResult(
                    execution.getId(),
                    execution.getStatus().toString(),
                    execution.getExitStatus().getExitCode(),
                    (int) written,
                    (int) skipped);
        } catch (Exception e) {
            throw new IllegalStateException("정산 배치 실행 실패: " + e.getMessage(), e);
        }
    }
}

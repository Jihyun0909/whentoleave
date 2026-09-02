package com.example.transit.batch;

import com.example.transit.domain.PaymentStatus;
import com.example.transit.repository.PaymentRepository;
import com.example.transit.service.settlement.SettlementDraft;
import com.example.transit.service.settlement.SettlementException;
import com.example.transit.service.settlement.SettlementService;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 제휴사 일일 수수료 정산 배치.
 * <p>
 * chunk 크기 1 = 제휴사 하나가 곧 트랜잭션 하나. 한 제휴사에서 {@link SettlementException}이 나면
 * 그 청크만 롤백되고(정산행·분개·결제 마킹이 하나도 안 남는다), {@link SettlementSkipListener}가
 * 별도 트랜잭션으로 {@code FAILED}를 남긴 뒤 다음 제휴사로 넘어간다.
 */
@Configuration
public class SettlementBatchConfig {

    public static final String JOB_NAME = "partnerSettlementJob";
    public static final String PARAM_SETTLEMENT_DATE = "settlementDate";
    /** 선택: 지정하면 그 제휴사 하나만 정산한다(없으면 미정산 결제가 있는 전체). */
    public static final String PARAM_PARTNER_ID = "partnerId";

    @Bean
    public Job partnerSettlementJob(JobRepository jobRepository, Step settlePartnersStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(settlePartnersStep)
                .build();
    }

    @Bean
    public Step settlePartnersStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                   ListItemReader<Long> settlementPartnerReader,
                                   ItemProcessor<Long, SettlementDraft> settlementProcessor,
                                   ItemWriter<SettlementDraft> settlementWriter,
                                   SettlementSkipListener skipListener) {
        return new StepBuilder("settlePartnersStep", jobRepository)
                .<Long, SettlementDraft>chunk(1)
                .transactionManager(transactionManager)
                .reader(settlementPartnerReader)
                .processor(settlementProcessor)
                .writer(settlementWriter)
                .faultTolerant()
                .skip(SettlementException.class)
                .skipLimit(Integer.MAX_VALUE)
                .skipListener(skipListener)
                .build();
    }

    /**
     * 정산 대상 제휴사 id들을 읽는다(건수가 적어 리스트로 한 번에). {@code partnerId} 잡 파라미터가
     * 있으면 그 하나만, 없으면 해당 기간에 미정산 결제가 있는 전체.
     */
    @Bean
    @StepScope
    public ListItemReader<Long> settlementPartnerReader(
            @Value("#{jobParameters['" + PARAM_SETTLEMENT_DATE + "']}") LocalDate settlementDate,
            @Value("#{jobParameters['" + PARAM_PARTNER_ID + "']}") Long partnerId,
            PaymentRepository payments) {
        if (partnerId != null) {
            return new ListItemReader<>(List.of(partnerId));
        }
        LocalDateTime from = settlementDate.atStartOfDay();
        LocalDateTime to = settlementDate.plusDays(1).atStartOfDay();
        return new ListItemReader<>(payments.findPartnerIdsWithUnsettledPayments(PaymentStatus.PAID, from, to));
    }

    @Bean
    @StepScope
    public ItemProcessor<Long, SettlementDraft> settlementProcessor(
            @Value("#{jobParameters['" + PARAM_SETTLEMENT_DATE + "']}") LocalDate settlementDate,
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId,
            SettlementService settlementService) {
        return partnerId -> settlementService.calculate(partnerId, settlementDate, settlementDate, jobExecutionId);
    }

    @Bean
    public ItemWriter<SettlementDraft> settlementWriter(SettlementService settlementService) {
        return chunk -> {
            for (SettlementDraft draft : chunk) {
                settlementService.commit(draft);
            }
        };
    }
}

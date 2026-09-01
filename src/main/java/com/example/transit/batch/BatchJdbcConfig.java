package com.example.transit.batch;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.context.annotation.Configuration;

/**
 * JDBC 기반(영속) JobRepository를 쓴다.
 * <ul>
 *   <li>{@code @EnableBatchProcessing} - Batch의 {@code BatchRegistrar}를 import한다. 그리고 Boot의
 *       {@code BatchAutoConfiguration}(인메모리 JobRepository)이 물러난다
 *       ({@code @ConditionalOnMissingBean(annotation = EnableBatchProcessing.class)}).</li>
 *   <li>{@code @EnableJdbcJobRepository} - {@code BatchRegistrar}가 이 애노테이션을 보고
 *       {@code dataSource}/{@code transactionManager} 빈으로 JDBC JobRepository를 등록한다.</li>
 * </ul>
 * 덕분에 JobExecution·StepExecution·skip/write 카운트가 {@code BATCH_*} 테이블에 남아 실행 이력
 * 조회와 재시작이 가능하다({@code BATCH_*} 테이블은 {@link BatchSchemaInitializer}가 생성).
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository
public class BatchJdbcConfig {
}

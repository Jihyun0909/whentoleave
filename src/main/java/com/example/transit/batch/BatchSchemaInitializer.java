package com.example.transit.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Spring Batch 6 / Boot 4는 배치 스키마를 자동 생성하지 않고(예전 {@code spring.batch.jdbc.*}
 * 속성도 없어졌다), 기본 JobRepository도 인메모리다. {@link BatchJdbcConfig}로 JDBC JobRepository를
 * 쓰기로 했으니 {@code BATCH_*} 테이블도 여기서 만든다 - 없을 때 한 번만.
 * <p>
 * eager {@code @Component}라 컨텍스트 초기화 중(= 어떤 Job이 실행되기 훨씬 전)에 돈다.
 */
@Component
public class BatchSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(BatchSchemaInitializer.class);

    public BatchSchemaInitializer(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            String platform = product.contains("postgresql") ? "postgresql"
                    : product.contains("h2") ? "h2"
                    : null;
            if (platform == null) {
                log.warn("배치 스키마 자동 생성을 건너뜀 - 지원 안 하는 DB: {}", product);
                return;
            }
            if (batchTablesExist(connection)) {
                return;
            }
            new ResourceDatabasePopulator(
                    new ClassPathResource("org/springframework/batch/core/schema-" + platform + ".sql"))
                    .execute(dataSource);
            log.info("Spring Batch 메타테이블 생성 완료 (platform={})", platform);
        }
    }

    private static boolean batchTablesExist(Connection connection) throws SQLException {
        for (String name : new String[] {"BATCH_JOB_INSTANCE", "batch_job_instance"}) {
            try (ResultSet rs = connection.getMetaData().getTables(null, null, name, new String[] {"TABLE"})) {
                if (rs.next()) {
                    return true;
                }
            }
        }
        return false;
    }
}

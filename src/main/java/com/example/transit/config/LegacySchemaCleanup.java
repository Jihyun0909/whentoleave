package com.example.transit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code ddl-auto=update}가 못 하는 스키마 정리를 기동 시 한 번 한다(전부 {@code IF EXISTS}라 멱등).
 * <ol>
 *   <li>옛 {@code check} 제약: enum을 {@code @Enumerated(STRING)}로 매핑했을 때 Hibernate가 만든
 *       {@code check (col in (...))}. 모든 enum을 {@code @Converter}(varchar)로 바꿨지만 이미
 *       만들어진 DB엔 남아 있어, enum에 값을 추가하면 새 값 INSERT가 깨진다.</li>
 *   <li>떨어져 나간 컬럼/제약: 엔티티에서 없앴는데 {@code ddl-auto=update}가 DROP은 안 하는 것들
 *       (예: {@code settlement.updated_at} NOT NULL - append-only로 바꾸며 제거).</li>
 * </ol>
 * Postgres에서만 의미가 있다. 새 DB·테스트(H2 create-drop)에는 애초에 없어서 no-op.
 * 스키마가 더 커지면 Flyway로 옮기는 게 맞다.
 */
@Component
@Order(-100)
public class LegacySchemaCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacySchemaCleanup.class);

    /** 옛 enum 매핑이 붙였던 제약 이름들 ({@code <table>_<column>_check}, 옛 유니크). */
    private static final List<String> DROP_CONSTRAINTS = List.of(
            "app_user|app_user_role_check",
            "taxi_ride|taxi_ride_status_check",
            "payment|payment_status_check",
            "settlement|settlement_status_check",
            "settlement|uk_settlement_partner_period",
            "settlement|uk_settlement_partner_period_job",
            "audit_log|audit_log_event_check",
            "ledger_transaction|ledger_transaction_type_check",
            "ledger_entry|ledger_entry_direction_check",
            "ledger_account|ledger_account_kind_check",
            "ledger_account|ledger_account_owner_type_check",
            "bus_stop_departure|bus_stop_departure_day_type_check",
            "subway_schedule|subway_schedule_day_type_check");

    /** 엔티티에서 없앴지만 남아 있는 컬럼 ({@code <table>|<column>}). */
    private static final List<String> DROP_COLUMNS = List.of(
            "settlement|updated_at");

    public LegacySchemaCleanup(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")) {
                return;
            }
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        for (String entry : DROP_CONSTRAINTS) {
            String[] parts = entry.split("\\|");
            run(jdbc, "ALTER TABLE IF EXISTS " + parts[0] + " DROP CONSTRAINT IF EXISTS " + parts[1]);
        }
        for (String entry : DROP_COLUMNS) {
            String[] parts = entry.split("\\|");
            run(jdbc, "ALTER TABLE IF EXISTS " + parts[0] + " DROP COLUMN IF EXISTS " + parts[1]);
        }
    }

    private static void run(JdbcTemplate jdbc, String ddl) {
        try {
            jdbc.execute(ddl);
        } catch (RuntimeException e) {
            log.warn("레거시 스키마 정리 실패 [{}]: {}", ddl, e.getMessage());
        }
    }
}

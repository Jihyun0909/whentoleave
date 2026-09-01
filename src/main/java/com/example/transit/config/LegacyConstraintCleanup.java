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
 * {@code ddl-auto=update}는 enum을 {@code @Enumerated(STRING)}로 매핑했을 때 Hibernate가 만든
 * {@code check (col in (...))} 제약을 갱신하지 못한다 - enum에 값을 추가하면 새 값 INSERT가 깨진다.
 * 그래서 모든 enum을 {@code @Converter}(그냥 varchar)로 바꿨는데, <b>이미 만들어진 DB</b>에는 옛
 * check 제약이 그대로 남아 있다. 이 컴포넌트가 기동 시 그 제약들을 떨어낸다({@code DROP ... IF EXISTS}).
 * <p>
 * Postgres에서만, 한 번만 의미가 있다(멱등). 새로 만든 DB나 테스트(H2 create-drop)에는 애초에
 * 없어서 no-op. 스키마가 더 커지면 Flyway로 옮기는 게 맞다.
 */
@Component
@Order(-100)
public class LegacyConstraintCleanup {

    private static final Logger log = LoggerFactory.getLogger(LegacyConstraintCleanup.class);

    /** {@code <table>.<constraint>} — Hibernate가 옛 매핑에서 붙였던 이름들. */
    private static final List<String[]> LEGACY_CONSTRAINTS = List.of(
            new String[] {"app_user", "app_user_role_check"},
            new String[] {"taxi_ride", "taxi_ride_status_check"},
            new String[] {"payment", "payment_status_check"},
            new String[] {"settlement", "settlement_status_check"},
            new String[] {"settlement", "uk_settlement_partner_period"},
            new String[] {"settlement", "uk_settlement_partner_period_job"},
            new String[] {"audit_log", "audit_log_event_check"},
            new String[] {"ledger_transaction", "ledger_transaction_type_check"},
            new String[] {"ledger_entry", "ledger_entry_direction_check"},
            new String[] {"ledger_account", "ledger_account_kind_check"},
            new String[] {"ledger_account", "ledger_account_owner_type_check"},
            new String[] {"bus_stop_departure", "bus_stop_departure_day_type_check"},
            new String[] {"subway_schedule", "subway_schedule_day_type_check"});

    public LegacyConstraintCleanup(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")) {
                return;
            }
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        int dropped = 0;
        for (String[] tableAndConstraint : LEGACY_CONSTRAINTS) {
            try {
                jdbc.execute("ALTER TABLE IF EXISTS " + tableAndConstraint[0]
                        + " DROP CONSTRAINT IF EXISTS " + tableAndConstraint[1]);
                dropped++;
            } catch (RuntimeException e) {
                log.warn("레거시 제약 제거 실패({} / {}): {}", tableAndConstraint[0], tableAndConstraint[1], e.getMessage());
            }
        }
        log.debug("레거시 enum check/unique 제약 정리 {}건 시도", dropped);
    }
}

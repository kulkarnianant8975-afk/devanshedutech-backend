package com.devanshedutech.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Removes the CHECK constraints Hibernate generates for enum columns.
 *
 * <p>Hibernate writes a constraint listing an enum's values when it first creates the column,
 * and {@code ddl-auto: update} never revisits it. Adding a value to an enum therefore works
 * perfectly against a freshly created schema — which is what every test uses — and then fails
 * on the real database with "violates check constraint". The failure appears only in
 * production, only for the new value, and looks nothing like its cause.</p>
 *
 * <p>That happened here the first time a new lead source was added: contact-form enquiries were
 * silently refused while every test passed. Dropping the constraints removes a whole class of
 * that failure. Nothing is lost by it — the values are enums in Java, so an invalid one cannot
 * reach the database in the first place, and the constraint was only ever a second copy of a
 * rule the application already enforces.</p>
 *
 * <p>Runs on every boot and is a no-op once the constraints are gone, so a schema Hibernate
 * recreates later is cleaned up on the next start rather than waiting to break.</p>
 */
@Slf4j
@Configuration
public class EnumConstraintRunner {

    /** Table and column for every enum-backed field this application owns. */
    private static final Map<String, List<String>> ENUM_COLUMNS = Map.of(
            "leads", List.of("stage", "grade", "source", "background", "lost_reason"),
            "lead_activities", List.of("type", "outcome", "direction", "stage_to"),
            "ladder_steps", List.of("grade"),
            "assets", List.of("type")
    );

    @Bean
    @Order(1)
    public ApplicationRunner dropEnumCheckConstraints(JdbcTemplate jdbc) {
        return args -> {
            int dropped = 0;
            for (Map.Entry<String, List<String>> table : ENUM_COLUMNS.entrySet()) {
                for (String column : table.getValue()) {
                    // Postgres names a generated column check {table}_{column}_check.
                    String constraint = table.getKey() + "_" + column + "_check";
                    try {
                        jdbc.execute("ALTER TABLE " + table.getKey()
                                + " DROP CONSTRAINT IF EXISTS " + constraint);
                        dropped++;
                    } catch (RuntimeException e) {
                        // A table that does not exist yet, or a database that does not support
                        // the syntax, is not worth failing a boot over.
                        log.debug("Could not drop {}: {}", constraint, e.getMessage());
                    }
                }
            }
            log.info("Checked {} enum column constraint(s). Enum values are validated in the "
                    + "application, so adding one no longer requires a schema migration.", dropped);
        };
    }
}

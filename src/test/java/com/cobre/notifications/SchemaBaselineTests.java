package com.cobre.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-02 database baseline: proves Flyway applies the three migrations and that R2DBC can
 * read and write the resulting schema against a real PostgreSQL (Testcontainers).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaBaselineTests {

    @Autowired
    DatabaseClient db;

    /** Flyway ran and R2DBC connects: all three owned tables are present. */
    @Test
    void schemaLoads() {
        StepVerifier.create(
                        db.sql("""
                                SELECT table_name FROM information_schema.tables
                                WHERE table_schema = 'public'
                                  AND table_name IN ('subscriptions', 'notifications', 'delivery_attempts')
                                """)
                                .map(row -> row.get("table_name", String.class))
                                .all()
                                .collectList())
                .assertNext(tables -> assertThat(tables)
                        .containsExactlyInAnyOrder("subscriptions", "notifications", "delivery_attempts"))
                .verifyComplete();
    }

    /** R2DBC round-trips the notifications schema; column defaults are applied on insert. */
    @Test
    void notificationRoundTrips() {
        StepVerifier.create(
                        db.sql("""
                                INSERT INTO notifications (id, client_id, event_type, content)
                                VALUES ('EVT-TEST-1', 'CLIENT-TEST', 'credit_deposit', 'Direct deposit received')
                                """)
                                .fetch().rowsUpdated()
                                .then(db.sql("""
                                        SELECT delivery_status, attempts, created_at
                                        FROM notifications WHERE id = 'EVT-TEST-1'
                                        """).fetch().first()))
                .assertNext(row -> {
                    assertThat(row.get("delivery_status")).isEqualTo("PENDING");
                    assertThat(((Number) row.get("attempts")).intValue()).isZero();
                    assertThat(row.get("created_at")).isNotNull();
                })
                .verifyComplete();
    }

    /** The VARCHAR + CHECK design rejects any delivery_status outside the allowed set. */
    @Test
    void deliveryStatusCheckConstraintRejectsUnknownValue() {
        StepVerifier.create(
                        db.sql("""
                                INSERT INTO notifications (id, client_id, event_type, content, delivery_status)
                                VALUES ('EVT-TEST-2', 'CLIENT-TEST', 'credit_deposit', 'bad', 'BOGUS')
                                """).fetch().rowsUpdated())
                .expectError()
                .verify();
    }
}

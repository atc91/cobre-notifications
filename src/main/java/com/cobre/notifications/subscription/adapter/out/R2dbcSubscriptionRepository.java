package com.cobre.notifications.subscription.adapter.out;

import com.cobre.notifications.delivery.domain.model.SubscriptionLookup;
import com.cobre.notifications.delivery.domain.port.out.SubscriptionPort;
import com.cobre.notifications.subscription.domain.model.SubscriptionRegistration;
import com.cobre.notifications.subscription.domain.port.out.SubscriptionStorePort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * R2DBC adapter over the {@code subscriptions} table (owned by this context). It plays two roles:
 *
 * <ul>
 *   <li>implements the subscription context's {@link SubscriptionStorePort} — the seed write path;</li>
 *   <li>implements the {@code delivery} context's {@link SubscriptionPort} — the read path the ingest
 *       gate resolves against.</li>
 * </ul>
 *
 * <p>This is the deliberate cross-context seam: {@code delivery} reaches subscription data only through
 * {@link SubscriptionPort} (returning the delivery-owned {@link SubscriptionLookup}), never by touching
 * this table directly. Built on {@link DatabaseClient} with parameterized SQL only (OWASP A03).
 */
@Repository
public class R2dbcSubscriptionRepository implements SubscriptionPort, SubscriptionStorePort {

    // The gate query: active, client-scoped, exact-or-wildcard match, with the exact event_type
    // preferred over '*' (true sorts before false under DESC). LIMIT 1 returns the single best row.
    private static final String RESOLVE = """
            SELECT target_url, secret
            FROM subscriptions
            WHERE client_id = :clientId
              AND active = true
              AND event_type IN (:eventType, '*')
            ORDER BY (event_type = :eventType) DESC
            LIMIT 1
            """;

    private static final String INSERT = """
            INSERT INTO subscriptions (client_id, event_type, target_url, secret, active)
            VALUES (:clientId, :eventType, :targetUrl, :secret, :active)
            ON CONFLICT (client_id, event_type) DO NOTHING
            """;

    private final DatabaseClient db;

    public R2dbcSubscriptionRepository(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<SubscriptionLookup> resolve(String clientId, String eventType) {
        return db.sql(RESOLVE)
                .bind("clientId", clientId)
                .bind("eventType", eventType)
                .map(row -> new SubscriptionLookup(
                        row.get("target_url", String.class),
                        row.get("secret", String.class)))
                .one();
    }

    @Override
    public Mono<Void> save(SubscriptionRegistration s) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(INSERT)
                .bind("clientId", s.clientId())
                .bind("eventType", s.eventType())
                .bind("targetUrl", s.targetUrl())
                .bind("active", s.active());
        spec = s.secret() == null
                ? spec.bindNull("secret", String.class)
                : spec.bind("secret", s.secret());
        // ON CONFLICT DO NOTHING: a duplicate (client_id, event_type) leaves the existing row untouched.
        return spec.fetch().rowsUpdated().then();
    }
}

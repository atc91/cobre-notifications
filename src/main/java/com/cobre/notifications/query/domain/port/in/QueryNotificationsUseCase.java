package com.cobre.notifications.query.domain.port.in;

import com.cobre.notifications.delivery.domain.model.NotificationFilter;
import com.cobre.notifications.delivery.domain.model.NotificationPage;
import com.cobre.notifications.query.domain.model.NotificationWithAttempts;
import reactor.core.publisher.Mono;

/**
 * Inbound port of the {@code query} context: the self-service read surface over a client's
 * notifications (P-09/P-10). Driven by {@code NotificationEventController}. Both operations are
 * client-scoped — the {@code clientId} is threaded straight through to the delivery read port.
 */
public interface QueryNotificationsUseCase {

    /** One page of the client's notifications matching {@code filter}, newest first, with a total count. */
    Mono<NotificationPage> list(String clientId, NotificationFilter filter, int page, int size);

    /**
     * The notification {@code id} with its delivery-attempt history, if owned by {@code clientId}.
     * Signals {@code NotFoundException} when absent or not owned (no existence leak).
     */
    Mono<NotificationWithAttempts> get(String clientId, String id);
}

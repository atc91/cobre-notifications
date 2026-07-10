package com.cobre.notifications.delivery.domain.port.out;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.Notification;
import com.cobre.notifications.delivery.domain.model.NotificationFilter;
import com.cobre.notifications.delivery.domain.model.NotificationPage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Delivery-owned outbound port giving the {@code query} context read access to the {@code Notification}
 * aggregate <em>without</em> a cross-context SQL join (P-09/P-10). Every method is client-scoped: the
 * caller-supplied {@code clientId} is always applied so a client can never observe another's data
 * (OWASP A01). Implemented by the delivery R2DBC adapter.
 */
public interface NotificationReadPort {

    /**
     * One page of the client's notifications matching {@code filter}, newest first, plus the total
     * matching count. {@code page} is 0-based; {@code size} is the page length.
     */
    Mono<NotificationPage> findPage(String clientId, NotificationFilter filter, int page, int size);

    /** The notification with {@code id} <em>if owned by</em> {@code clientId}; empty otherwise. */
    Mono<Notification> findByIdForClient(String id, String clientId);

    /** The append-only delivery-attempt history for a notification, ordered by {@code attemptNo}. */
    Flux<DeliveryAttempt> findAttempts(String notificationId);
}

package com.cobre.notifications.query.application;

import com.cobre.notifications.common.error.NotFoundException;
import com.cobre.notifications.delivery.domain.model.NotificationFilter;
import com.cobre.notifications.delivery.domain.model.NotificationPage;
import com.cobre.notifications.delivery.domain.port.out.NotificationReadPort;
import com.cobre.notifications.query.domain.model.NotificationWithAttempts;
import com.cobre.notifications.query.domain.port.in.QueryNotificationsUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Use-case implementation for the self-service read surface (P-09/P-10). Owns no data: it reaches the
 * {@code delivery} aggregate solely through {@link NotificationReadPort}, always passing the caller's
 * {@code clientId} so scoping (OWASP A01) is enforced in this layer, not the controller. A missing or
 * not-owned lookup is turned into a {@link NotFoundException} so 404 — not 403 — is returned.
 */
@Service
public class QueryNotificationsService implements QueryNotificationsUseCase {

    private final NotificationReadPort readPort;

    public QueryNotificationsService(NotificationReadPort readPort) {
        this.readPort = readPort;
    }

    @Override
    public Mono<NotificationPage> list(String clientId, NotificationFilter filter, int page, int size) {
        return readPort.findPage(clientId, filter, page, size);
    }

    @Override
    public Mono<NotificationWithAttempts> get(String clientId, String id) {
        return readPort.findByIdForClient(id, clientId)
                .switchIfEmpty(Mono.error(() -> new NotFoundException("notification not found: " + id)))
                .flatMap(notification -> readPort.findAttempts(notification.id())
                        .collectList()
                        .map(attempts -> new NotificationWithAttempts(notification, attempts)));
    }
}

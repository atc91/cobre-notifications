package com.cobre.notifications.query.application;

import com.cobre.notifications.delivery.domain.port.out.NotificationReplayPort;
import com.cobre.notifications.query.domain.port.in.ReplayNotificationUseCase;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Use-case implementation for replay (P-11). A thin client-scoped orchestration: the ownership +
 * {@code FAILED} guard and the atomic {@code FAILED → PENDING} transition live in the delivery replay
 * port (which owns the aggregate and its transaction); this service only threads the caller's
 * {@code clientId} through. Once reset to {@code PENDING} due now, the row is picked up by the
 * existing delivery scheduler (P-07/P-08) — no separate re-delivery path.
 */
@Service
public class ReplayNotificationService implements ReplayNotificationUseCase {

    private final NotificationReplayPort replayPort;

    public ReplayNotificationService(NotificationReplayPort replayPort) {
        this.replayPort = replayPort;
    }

    @Override
    public Mono<Void> replay(String clientId, String id) {
        return replayPort.replay(id, clientId);
    }
}

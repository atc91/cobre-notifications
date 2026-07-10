package com.cobre.notifications.delivery.adapter.in;

import com.cobre.notifications.delivery.domain.port.in.DeliverNotificationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Driving adapter that polls for due notifications and drives {@link DeliverNotificationUseCase}
 * (P-07). Runs on a fixed delay ({@code notifications.delivery.poll-interval-ms}); because the claim
 * uses {@code FOR UPDATE SKIP LOCKED}, an overlapping run simply skips locked rows, so there is no
 * double-delivery even if a poll starts before the previous batch finishes.
 *
 * <p>Gated by {@code notifications.delivery.scheduler-enabled} (default {@code true}); the test profile
 * disables it so {@code @SpringBootTest} contexts drive {@code deliverDue()} deterministically instead.
 */
@Component
@ConditionalOnProperty(name = "notifications.delivery.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DueDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DueDeliveryScheduler.class);

    private final DeliverNotificationUseCase deliver;

    public DueDeliveryScheduler(DeliverNotificationUseCase deliver) {
        this.deliver = deliver;
    }

    @Scheduled(fixedDelayString = "${notifications.delivery.poll-interval-ms:5000}")
    public void pollAndDeliver() {
        deliver.deliverDue().subscribe(
                ignored -> {},
                err -> log.error("delivery poll failed: {}", err.toString()));
    }
}

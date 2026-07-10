package com.cobre.notifications.query.domain.model;

import com.cobre.notifications.delivery.domain.model.DeliveryAttempt;
import com.cobre.notifications.delivery.domain.model.Notification;

import java.util.List;

/**
 * The detail view returned by {@code GET /notification_events/{id}} (P-10): the delivery-owned
 * {@link Notification} aggregate together with its append-only {@link DeliveryAttempt} history. The
 * {@code query} context composes this from the delivery read port; it never joins the tables itself.
 */
public record NotificationWithAttempts(Notification notification, List<DeliveryAttempt> attempts) {
}

package com.cobre.notifications.delivery.domain.model;

/**
 * Thrown when a {@link Notification} is asked to make a status transition its current
 * {@link DeliveryStatus} does not allow. A pure-domain signal; the {@code common} web layer maps
 * it to an HTTP response (e.g. 409) in a later phase.
 */
public class IllegalStatusTransitionException extends RuntimeException {

    public IllegalStatusTransitionException(DeliveryStatus from, DeliveryStatus to) {
        super("Illegal delivery status transition: " + from + " -> " + to);
    }
}

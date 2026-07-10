/**
 * {@code delivery} bounded context — delivers platform events to client webhooks.
 *
 * <p>Data-owning context for the {@code Notification} aggregate ({@code notifications},
 * {@code delivery_attempts}). Hosts ingest, the delivery use case, the due-work scheduler,
 * and the retry engine. Organized as a hexagon: {@code domain} / {@code application} /
 * {@code adapter}, with dependencies pointing inward.
 */
package com.cobre.notifications.delivery;

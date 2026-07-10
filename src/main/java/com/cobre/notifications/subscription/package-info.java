/**
 * {@code subscription} bounded context — answers "is client C subscribed to event E, and to
 * which URL?".
 *
 * <p>Data-owning context for {@code subscriptions}. Gates ingestion so no client ever
 * receives another client's events. Organized as a hexagon: {@code domain} /
 * {@code application} / {@code adapter}, with dependencies pointing inward.
 */
package com.cobre.notifications.subscription;

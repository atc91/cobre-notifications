/**
 * {@code query} bounded context — the self-service read + command surface over the
 * {@code delivery} aggregate (list, fetch, replay).
 *
 * <p>Owns no data of its own; it reaches {@code delivery} only through that context's ports,
 * never via a direct SQL join. Organized as a hexagon: {@code domain} / {@code application} /
 * {@code adapter}, with dependencies pointing inward.
 */
package com.cobre.notifications.query;

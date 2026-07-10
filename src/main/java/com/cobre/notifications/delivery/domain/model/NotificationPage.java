package com.cobre.notifications.delivery.domain.model;

import java.util.List;

/**
 * One page of a client-scoped notification listing plus the total number of matching rows, so the API
 * can report {@code total_elements} without the caller paging to the end.
 *
 * @param content       the notifications on this page, newest first
 * @param totalElements total count of the client's notifications matching the filter (across all pages)
 */
public record NotificationPage(List<Notification> content, long totalElements) {
}

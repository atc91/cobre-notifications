package com.cobre.notifications.common.error;

/**
 * The JSON body returned for a mapped error response: a short machine-readable {@code error} slug and
 * a human-readable {@code message}.
 */
public record ApiError(String error, String message) {
}

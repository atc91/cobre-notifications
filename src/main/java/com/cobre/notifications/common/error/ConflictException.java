package com.cobre.notifications.common.error;

/**
 * Thrown when a request cannot be applied in the resource's current state — e.g. replaying a
 * notification that is not in the {@code FAILED} dead-letter state. Mapped to HTTP 409 by
 * {@link GlobalExceptionHandler}.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

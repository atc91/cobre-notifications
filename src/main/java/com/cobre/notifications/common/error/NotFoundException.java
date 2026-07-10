package com.cobre.notifications.common.error;

/**
 * Thrown when a requested resource does not exist <em>or</em> is not owned by the caller. Mapped to
 * HTTP 404 by {@link GlobalExceptionHandler}. Deliberately conflates "absent" and "not owned" so the
 * API never leaks the existence of another client's notification (OWASP A01).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}

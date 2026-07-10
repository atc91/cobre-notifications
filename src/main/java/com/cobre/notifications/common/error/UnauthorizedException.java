package com.cobre.notifications.common.error;

/**
 * Thrown when the caller cannot be identified — for now, a missing or blank {@code X-Client-Id}
 * header (the pre-auth stand-in that P-12's OAuth2 / API-key layer will replace). Mapped to HTTP 401
 * by {@link GlobalExceptionHandler}.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}

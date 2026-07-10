package com.cobre.notifications.common.error;

/**
 * Thrown when request input is malformed — an unparseable date, an unknown {@code delivery_status},
 * or an out-of-range page/size. Mapped to HTTP 400 by {@link GlobalExceptionHandler}.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}

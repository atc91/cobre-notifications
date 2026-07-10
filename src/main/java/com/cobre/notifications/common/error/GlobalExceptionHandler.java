package com.cobre.notifications.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

/**
 * The single source of exception→HTTP mapping for the whole service (per the architecture). Services
 * and adapters throw; controllers own {@code ResponseEntity} for the happy path while this advice
 * translates the domain-signalling exceptions into status codes and a consistent {@link ApiError}
 * body. {@link ServerWebInputException} (Spring's own signal for a mistyped/missing query param) is
 * folded into the 400 mapping so malformed input responds uniformly.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("not_found", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError("conflict", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError("unauthorized", e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(new ApiError("bad_request", e.getMessage()));
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiError> malformedInput(ServerWebInputException e) {
        return ResponseEntity.badRequest().body(new ApiError("bad_request", "malformed request parameter"));
    }
}

package com.cobre.notifications.query.domain.model;

/**
 * A validated pagination request for the list endpoint (P-09). Pure value object: it normalizes and
 * bounds the raw {@code page}/{@code size} inputs so no invalid paging reaches the store. {@code size}
 * is capped at a caller-supplied maximum to stop a client requesting an unbounded page.
 *
 * @param page 0-based page index
 * @param size page length (already capped at the configured maximum)
 */
public record PageRequest(int page, int size) {

    /**
     * Build a request, rejecting a negative page or a non-positive size with
     * {@link IllegalArgumentException} and clamping {@code size} down to {@code maxSize}.
     */
    public static PageRequest of(int page, int size, int maxSize) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        return new PageRequest(page, Math.min(size, maxSize));
    }

    /** The row offset this page starts at ({@code page * size}). */
    public long offset() {
        return (long) page * size;
    }
}

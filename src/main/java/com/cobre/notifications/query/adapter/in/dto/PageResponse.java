package com.cobre.notifications.query.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Pagination envelope for the list endpoint (P-09): the page {@code content} plus the echoed
 * {@code page}/{@code size} and the {@code total_elements} across all pages.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements) {
}

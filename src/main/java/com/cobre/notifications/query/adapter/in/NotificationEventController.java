package com.cobre.notifications.query.adapter.in;

import com.cobre.notifications.common.error.BadRequestException;
import com.cobre.notifications.common.error.UnauthorizedException;
import com.cobre.notifications.delivery.domain.model.DeliveryStatus;
import com.cobre.notifications.delivery.domain.model.NotificationFilter;
import com.cobre.notifications.query.adapter.in.dto.NotificationDetail;
import com.cobre.notifications.query.adapter.in.dto.NotificationSummary;
import com.cobre.notifications.query.adapter.in.dto.PageResponse;
import com.cobre.notifications.query.domain.model.PageRequest;
import com.cobre.notifications.query.domain.port.in.QueryNotificationsUseCase;
import com.cobre.notifications.query.domain.port.in.ReplayNotificationUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Driving adapter for the three mandated self-service endpoints (P-09/P-10/P-11). Reactive throughout
 * ({@code Mono<ResponseEntity<…>>}); business rules and client scoping live in the use cases, while
 * this adapter only parses input and shapes the HTTP response. Domain-signalling exceptions
 * ({@code NotFound}/{@code Conflict}/{@code BadRequest}/{@code Unauthorized}) are translated centrally
 * by {@code GlobalExceptionHandler}.
 *
 * <p>The caller is identified by the required {@code X-Client-Id} header — a documented pre-auth
 * stand-in that P-12's OAuth2 / API-key layer will replace. A missing header is a 401.
 */
@RestController
@RequestMapping("/notification_events")
public class NotificationEventController {

    private final QueryNotificationsUseCase queryUseCase;
    private final ReplayNotificationUseCase replayUseCase;
    private final QueryProperties properties;

    public NotificationEventController(QueryNotificationsUseCase queryUseCase,
                                       ReplayNotificationUseCase replayUseCase,
                                       QueryProperties properties) {
        this.queryUseCase = queryUseCase;
        this.replayUseCase = replayUseCase;
        this.properties = properties;
    }

    @GetMapping
    public Mono<ResponseEntity<PageResponse<NotificationSummary>>> list(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @RequestParam(name = "created_from", required = false) String createdFrom,
            @RequestParam(name = "created_to", required = false) String createdTo,
            @RequestParam(name = "delivery_status", required = false) String deliveryStatus,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size) {

        String clientId = requireClient(clientIdHeader);
        NotificationFilter filter = buildFilter(createdFrom, createdTo, deliveryStatus);
        PageRequest pageRequest = buildPageRequest(page, size);

        return queryUseCase.list(clientId, filter, pageRequest.page(), pageRequest.size())
                .map(result -> {
                    var content = result.content().stream().map(NotificationSummary::from).toList();
                    return ResponseEntity.ok(new PageResponse<>(
                            content, pageRequest.page(), pageRequest.size(), result.totalElements()));
                });
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<NotificationDetail>> get(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @PathVariable String id) {

        String clientId = requireClient(clientIdHeader);
        return queryUseCase.get(clientId, id)
                .map(view -> ResponseEntity.ok(NotificationDetail.from(view)));
    }

    @PostMapping("/{id}/replay")
    public Mono<ResponseEntity<Void>> replay(
            @RequestHeader(value = "X-Client-Id", required = false) String clientIdHeader,
            @PathVariable String id) {

        String clientId = requireClient(clientIdHeader);
        return replayUseCase.replay(clientId, id)
                .then(Mono.fromSupplier(() -> ResponseEntity.accepted().build()));
    }

    private static String requireClient(String clientIdHeader) {
        if (clientIdHeader == null || clientIdHeader.isBlank()) {
            throw new UnauthorizedException("missing X-Client-Id header");
        }
        return clientIdHeader;
    }

    private static NotificationFilter buildFilter(String createdFrom, String createdTo, String deliveryStatus) {
        Instant from = parseInstant("created_from", createdFrom);
        Instant to = parseInstant("created_to", createdTo);
        DeliveryStatus status = parseStatus(deliveryStatus);
        try {
            return new NotificationFilter(from, to, status);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private PageRequest buildPageRequest(Integer page, Integer size) {
        int pageValue = page != null ? page : 0;
        int sizeValue = size != null ? size : properties.getDefaultPageSize();
        try {
            return PageRequest.of(pageValue, sizeValue, properties.getMaxPageSize());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private static Instant parseInstant(String param, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(param + " must be an ISO-8601 instant (e.g. 2026-07-10T12:00:00Z)");
        }
    }

    private static DeliveryStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "delivery_status must be one of PENDING, DELIVERING, RETRYING, DELIVERED, FAILED");
        }
    }
}

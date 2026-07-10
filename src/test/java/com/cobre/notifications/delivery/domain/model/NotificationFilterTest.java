package com.cobre.notifications.delivery.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit test of the list filter's construction rules. No Spring. */
class NotificationFilterTest {

    private static final Instant T0 = Instant.parse("2026-07-10T12:00:00Z");

    @Test
    void rejectsCreatedFromAfterCreatedTo() {
        assertThatThrownBy(() -> new NotificationFilter(T0, T0.minusSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsCreatedFromEqualToCreatedTo() {
        assertThatCode(() -> new NotificationFilter(T0, T0, DeliveryStatus.FAILED))
                .doesNotThrowAnyException();
    }

    @Test
    void unfilteredHasNoConstraints() {
        NotificationFilter filter = NotificationFilter.unfiltered();
        assertThat(filter.createdFrom()).isNull();
        assertThat(filter.createdTo()).isNull();
        assertThat(filter.status()).isNull();
    }
}

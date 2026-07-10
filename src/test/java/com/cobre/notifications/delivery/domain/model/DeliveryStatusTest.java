package com.cobre.notifications.delivery.domain.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.cobre.notifications.delivery.domain.model.DeliveryStatus.DELIVERED;
import static com.cobre.notifications.delivery.domain.model.DeliveryStatus.DELIVERING;
import static com.cobre.notifications.delivery.domain.model.DeliveryStatus.FAILED;
import static com.cobre.notifications.delivery.domain.model.DeliveryStatus.PENDING;
import static com.cobre.notifications.delivery.domain.model.DeliveryStatus.RETRYING;
import static org.assertj.core.api.Assertions.assertThat;

/** Pure-domain unit test of the status predicates and the transition matrix. No Spring. */
class DeliveryStatusTest {

    @Test
    void terminalStatesAreDeliveredAndFailed() {
        assertThat(DELIVERED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(DELIVERING.isTerminal()).isFalse();
        assertThat(RETRYING.isTerminal()).isFalse();
    }

    @Test
    void onlyFailedIsReplayable() {
        assertThat(FAILED.isReplayable()).isTrue();
        for (DeliveryStatus s : EnumSet.complementOf(EnumSet.of(FAILED))) {
            assertThat(s.isReplayable()).as("%s replayable", s).isFalse();
        }
    }

    @Test
    void legalTransitionsAreAllowed() {
        Map<DeliveryStatus, Set<DeliveryStatus>> legal = Map.of(
                PENDING, Set.of(DELIVERING),
                RETRYING, Set.of(DELIVERING),
                DELIVERING, Set.of(DELIVERED, RETRYING, FAILED),
                FAILED, Set.of(PENDING),
                DELIVERED, Set.of());

        legal.forEach((from, targets) ->
                targets.forEach(to ->
                        assertThat(from.canTransitionTo(to))
                                .as("%s -> %s should be legal", from, to)
                                .isTrue()));
    }

    @Test
    void illegalTransitionsAreRejected() {
        Map<DeliveryStatus, Set<DeliveryStatus>> legal = Map.of(
                PENDING, Set.of(DELIVERING),
                RETRYING, Set.of(DELIVERING),
                DELIVERING, Set.of(DELIVERED, RETRYING, FAILED),
                FAILED, Set.of(PENDING),
                DELIVERED, Set.of());

        for (DeliveryStatus from : DeliveryStatus.values()) {
            for (DeliveryStatus to : DeliveryStatus.values()) {
                boolean expected = legal.get(from).contains(to);
                assertThat(from.canTransitionTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }
}

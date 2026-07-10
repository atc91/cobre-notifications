package com.cobre.notifications.query.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit test of the pagination value object's bounds. No Spring. */
class PageRequestTest {

    @Test
    void keepsPageAndSizeWhenWithinBounds() {
        PageRequest pr = PageRequest.of(2, 20, 100);
        assertThat(pr.page()).isEqualTo(2);
        assertThat(pr.size()).isEqualTo(20);
        assertThat(pr.offset()).isEqualTo(40L);
    }

    @Test
    void capsSizeAtMax() {
        assertThat(PageRequest.of(0, 500, 100).size()).isEqualTo(100);
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> PageRequest.of(-1, 20, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThatThrownBy(() -> PageRequest.of(0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

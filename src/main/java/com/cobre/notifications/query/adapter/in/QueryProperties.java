package com.cobre.notifications.query.adapter.in;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pagination bounds for the list endpoint, bound from {@code notifications.query.*}. Lives beside the
 * controller because it configures the HTTP adapter's defaults, not domain behaviour.
 */
@ConfigurationProperties("notifications.query")
public class QueryProperties {

    /** Page length used when the client omits {@code size}. */
    private int defaultPageSize = 20;

    /** Hard cap on {@code size}, so a client cannot request an unbounded page. */
    private int maxPageSize = 100;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}

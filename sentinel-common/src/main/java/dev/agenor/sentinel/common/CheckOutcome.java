package dev.agenor.sentinel.common;

import java.time.Instant;

/**
 * What a probe found when it checked a URL, as published on the {@code outcomes} topic.
 *
 * <p>{@code httpStatus} is 0 when no HTTP response was received; {@code error} then says why.
 * A check is a success when a response arrived with a status below 500.
 */
public record CheckOutcome(
        String requestId,
        long targetId,
        String url,
        String probeId,
        int httpStatus,
        long latencyMillis,
        String error,
        Instant checkedAt) {

    public static final String TOPIC = "outcomes";

    public boolean success() {
        return error == null && httpStatus > 0 && httpStatus < 500;
    }
}

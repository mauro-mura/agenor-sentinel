package dev.agenor.sentinel.reconcile;

import java.time.Instant;

/**
 * One row of the coordinator's ledger, with what the rest of PostgreSQL says about it.
 *
 * @param ageSeconds      how long ago the request was written, by the database's clock
 * @param probeNodeStatus the {@code status} stored in the directory rows of the probe's node, joined
 *                        with {@code +} when they differ, or {@code null} when the node has no row
 *                        left. It is what the row says, not whether the node is alive: a node killed
 *                        without goodbye keeps {@code RUNNING} until something removes its row.
 * @param probeNodeLastSeen the latest {@code last_seen} of those rows, as stored (a timestamp with no
 *                        time zone), or {@code null}
 * @param outcomeApplied  whether the alerter recorded the outcome in {@code outcome_seen}
 */
record LedgerEntry(
        String requestId,
        Instant createdAt,
        double ageSeconds,
        long targetId,
        String probeId,
        String probeNodeId,
        String probeNodeStatus,
        String probeNodeLastSeen,
        Instant repliedAt,
        String replyPerformative,
        Instant errorAt,
        String errorClass,
        String errorMessage,
        boolean outcomeApplied) {

    boolean answered() {
        return repliedAt != null;
    }
}

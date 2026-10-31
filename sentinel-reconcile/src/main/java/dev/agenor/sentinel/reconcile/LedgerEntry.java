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
 * @param probeLastHeartbeat the same {@code last_seen} as an instant ({@code last_seen} is written in
 *                        UTC), or {@code null}
 * @param senderId        the agent id of the coordinator that sent the request, the same on every
 *                        start; {@code null} for rows written before the ledger recorded it
 * @param senderNodeId    the node the sender's mail was routed to when it sent the request, or
 *                        {@code null}
 * @param senderCurrentNodeId the node of the sender's directory row now, or {@code null} when it has
 *                        no row: a restarted coordinator overwrites the row of the one before
 * @param senderNodeLastSent the latest request in the ledger sent from {@code senderNodeId}
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
        Instant probeLastHeartbeat,
        String senderId,
        String senderNodeId,
        String senderCurrentNodeId,
        Instant senderNodeLastSent,
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

package dev.agenor.sentinel.reconcile;

import dev.agenor.sentinel.reconcile.RedisSnapshot.Kind;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Sighting;
import dev.agenor.sentinel.reconcile.RedisSnapshot.StreamInfo;

import java.time.Instant;
import java.util.List;

/**
 * Assigns a ledger request its final state (CRITERION §4), and says where the request physically
 * is in Redis (Q2's second measure).
 *
 * <p>The states are tried in this order, and the first that applies wins:
 * <ol>
 *   <li>{@link State#ANSWERED}: the coordinator recorded a reply.</li>
 *   <li>{@link State#DEAD_LETTERED}: the REQUEST is in a {@code :dlq} stream.</li>
 *   <li>{@link State#TIMED_OUT}: the coordinator recorded an error. CRITERION §4 calls this state
 *       "timed out, with an error the application saw"; the error class is reported with it, since
 *       not every error is a timeout.</li>
 *   <li>{@link State#IN_FLIGHT}: none of the above, and the request is younger than the grace
 *       period, so it may still be answered or fail.</li>
 *   <li>{@link State#LOST}: none of the above.</li>
 * </ol>
 * Dead-lettered comes before timed out because a dead-lettered request is also timed out by the
 * coordinator: in the opposite order the dead-lettered state could never be seen.
 *
 * <p>An unanswered request also gets a {@link Cause}: which end of it stopped while it was open. The
 * cause never changes the state. A request whose coordinator died stays {@link State#LOST}, as
 * CRITERION §4 defines it; the cause only keeps it apart from the requests a dead probe lost (Q2).
 */
final class Classifier {

    /** The coordinator's request timeout ({@code CoordinatorAgent.REQUEST_TIMEOUT}). */
    static final long REQUEST_TIMEOUT_SECONDS = 15;

    enum State { ANSWERED, DEAD_LETTERED, TIMED_OUT, IN_FLIGHT, LOST }

    /**
     * Which end of an unanswered request stopped while it was open: before the request timeout ran
     * out. The two ends are told apart in different ways, because their identities differ.
     * <ul>
     *   <li>A probe has a new agent id on every start, and a probe killed without goodbye leaves its
     *       row behind. It stopped in time when its last heartbeat ({@code last_seen}) is older than
     *       the request plus the timeout: alive past that moment, it would have heartbeated after
     *       it. A probe with no row left is not counted, since nothing says when it left: an orderly
     *       stop removes the row, and the stop may have come long after the request.</li>
     *   <li>The coordinator's agent id is the same on every start, and the new one overwrites the
     *       row of the one before, so its heartbeat says nothing about a past life. Its node does:
     *       the sender stopped when its directory row now names another node, or none, and it
     *       stopped in time when that node's last request in the ledger came less than the timeout
     *       after this one. The coordinator sends every round while targets are due, so its last
     *       request falls within a round of its death.</li>
     * </ul>
     */
    enum Cause {
        /** The probe stopped; the coordinator did not. */
        PROBE_STOPPED,
        /**
         * The coordinator that sent the request stopped. Its future and its timeout died with it
         * ({@code dialog-protocol}), so nothing records an error, and a reply goes to the stream of
         * a node that will never read it again.
         */
        SENDER_STOPPED,
        /** Both stopped. */
        BOTH_STOPPED,
        /** Neither stopped while the request was open. */
        NONE,
        /** An answered request, or a ledger row with no sender node recorded. */
        NOT_APPLICABLE
    }

    /** Where the REQUEST is in Redis. The first four come from {@link RedisSnapshot.Delivery}. */
    enum Location {
        PENDING, UNREAD, DELIVERED, NO_GROUP,
        DEAD_LETTER_QUEUE,
        /**
         * Not in the probe's stream, which no longer reaches back to the request's time: trimmed, or
         * deleted and created again.
         */
        TRIMMED,
        /** The probe's stream does not exist. */
        STREAM_GONE,
        /** The probe's stream exists and covers the request's time, but the REQUEST is not in it. */
        NOT_FOUND
    }

    /** What became of the outcome of an INFORM reply: O1 in FINDINGS.md. */
    enum Outcome {
        /** The alerter recorded it. */
        APPLIED,
        /** On the topic, never recorded by the alerter. */
        PUBLISHED_NOT_APPLIED,
        /** In the topic's dead-letter stream, never recorded. */
        DEAD_LETTERED,
        /** Not on the topic, which no longer reaches back to the reply's time (see {@link Location#TRIMMED}). */
        TRIMMED,
        /** Not on the topic, which covers the reply's time. */
        NOT_PUBLISHED,
        /** Not an answered INFORM: there is no outcome to look for. */
        NOT_APPLICABLE
    }

    /**
     * @param reply the probe's reply as found in Redis, or {@code null}; for a request whose sender
     *              stopped, its stream is the dead sender's
     */
    record Verdict(State state, Location location, Sighting request, Outcome outcome, Cause cause, Sighting reply) {
    }

    private Classifier() {
    }

    static Verdict classify(LedgerEntry entry, List<Sighting> sightings, StreamInfo probeStream,
                            StreamInfo topicStream, double graceSeconds) {
        // A dead-lettered copy of the REQUEST says more than the one left in the source stream.
        Sighting request = sightings.stream()
                .filter(s -> s.kind() == Kind.REQUEST)
                .reduce(null, (found, s) -> found == null || (s.deadLettered() && !found.deadLettered()) ? s : found);
        Location location = location(entry, request, probeStream);

        State state;
        if (entry.answered()) {
            state = State.ANSWERED;
        } else if (location == Location.DEAD_LETTER_QUEUE) {
            state = State.DEAD_LETTERED;
        } else if (entry.errorAt() != null) {
            state = State.TIMED_OUT;
        } else if (entry.ageSeconds() < graceSeconds) {
            state = State.IN_FLIGHT;
        } else {
            state = State.LOST;
        }
        Sighting reply = sightings.stream().filter(s -> s.kind() == Kind.REPLY).findFirst().orElse(null);
        return new Verdict(state, location, request, outcome(entry, sightings, topicStream),
                state == State.ANSWERED ? Cause.NOT_APPLICABLE : cause(entry), reply);
    }

    private static Cause cause(LedgerEntry entry) {
        if (entry.senderNodeId() == null) {
            return Cause.NOT_APPLICABLE;
        }
        Instant timedOutAt = entry.createdAt().plusSeconds(REQUEST_TIMEOUT_SECONDS);
        boolean probe = entry.probeLastHeartbeat() != null && entry.probeLastHeartbeat().isBefore(timedOutAt);
        boolean sender = !entry.senderNodeId().equals(entry.senderCurrentNodeId())
                && entry.senderNodeLastSent() != null && entry.senderNodeLastSent().isBefore(timedOutAt);
        if (probe && sender) {
            return Cause.BOTH_STOPPED;
        }
        return sender ? Cause.SENDER_STOPPED : probe ? Cause.PROBE_STOPPED : Cause.NONE;
    }

    private static Location location(LedgerEntry entry, Sighting request, StreamInfo probeStream) {
        if (request != null) {
            return request.deadLettered() ? Location.DEAD_LETTER_QUEUE : Location.valueOf(request.delivery().name());
        }
        if (probeStream == null) {
            return Location.STREAM_GONE;
        }
        return startsAfter(probeStream, entry.createdAt().toEpochMilli()) ? Location.TRIMMED : Location.NOT_FOUND;
    }

    private static Outcome outcome(LedgerEntry entry, List<Sighting> sightings, StreamInfo topicStream) {
        if (!entry.answered() || !"INFORM".equals(entry.replyPerformative())) {
            return Outcome.NOT_APPLICABLE;
        }
        if (entry.outcomeApplied()) {
            return Outcome.APPLIED;
        }
        List<Sighting> outcomes = sightings.stream().filter(s -> s.kind() == Kind.OUTCOME).toList();
        if (outcomes.stream().anyMatch(Sighting::deadLettered)) {
            return Outcome.DEAD_LETTERED;
        }
        if (!outcomes.isEmpty()) {
            return Outcome.PUBLISHED_NOT_APPLIED;
        }
        return topicStream != null && startsAfter(topicStream, entry.repliedAt().toEpochMilli())
                ? Outcome.TRIMMED : Outcome.NOT_PUBLISHED;
    }

    /** True when the stream's oldest entry is younger than the given time: older entries were trimmed. */
    private static boolean startsAfter(StreamInfo stream, long epochMillis) {
        return stream.firstEntryId() != null && RedisSnapshot.millisOf(stream.firstEntryId()) > epochMillis;
    }
}

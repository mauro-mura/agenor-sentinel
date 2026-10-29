package dev.agenor.sentinel.reconcile;

import dev.agenor.sentinel.reconcile.RedisSnapshot.Kind;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Sighting;
import dev.agenor.sentinel.reconcile.RedisSnapshot.StreamInfo;

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
 */
final class Classifier {

    enum State { ANSWERED, DEAD_LETTERED, TIMED_OUT, IN_FLIGHT, LOST }

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

    record Verdict(State state, Location location, Sighting request, Outcome outcome) {
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
        return new Verdict(state, location, request, outcome(entry, sightings, topicStream));
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

package dev.agenor.sentinel.reconcile;

import dev.agenor.sentinel.reconcile.Classifier.Cause;
import dev.agenor.sentinel.reconcile.Classifier.Location;
import dev.agenor.sentinel.reconcile.Classifier.Outcome;
import dev.agenor.sentinel.reconcile.Classifier.State;
import dev.agenor.sentinel.reconcile.Classifier.Verdict;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Delivery;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Kind;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Sighting;
import dev.agenor.sentinel.reconcile.RedisSnapshot.StreamInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClassifierTest {

    static final Instant SENT = Instant.parse("2026-10-29T21:27:58.505Z");
    static final String NODE = "837b34eb-f44b-45fa-aea9-5d12c84dff9d";
    static final String NODE_STREAM = "sentinel:node:" + NODE;
    static final String SENDER = "c0a1b2c3-0000-4000-8000-000000000001";
    static final String SENDER_NODE = "5e4d3c2b-0000-4000-8000-000000000002";
    static final String SENDER_STREAM = "sentinel:node:" + SENDER_NODE;
    static final double GRACE = 30;

    /** A probe stream whose first entry is older than the request: nothing was trimmed. */
    static final StreamInfo PROBE_STREAM = new StreamInfo(NODE_STREAM, 10, 1,
            (SENT.toEpochMilli() - 60_000) + "-0");
    static final StreamInfo TOPIC = new StreamInfo("sentinel:topic:outcomes", 10, 1,
            (SENT.toEpochMilli() - 60_000) + "-0");

    @Test
    void aRecordedReplyIsAnsweredWhateverElseIsFound() {
        var entry = entry(60).replied("INFORM").errored().build();

        Verdict v = Classifier.classify(entry, List.of(request(Delivery.NO_GROUP, true)), PROBE_STREAM, TOPIC, GRACE);

        assertThat(v.state()).isEqualTo(State.ANSWERED);
    }

    @Test
    void aDeadLetteredRequestIsDeadLetteredAlthoughTheCoordinatorAlsoSawItTimeOut() {
        var entry = entry(60).errored().build();
        var sightings = List.of(request(Delivery.DELIVERED, false), request(Delivery.NO_GROUP, true));

        Verdict v = Classifier.classify(entry, sightings, PROBE_STREAM, TOPIC, GRACE);

        assertThat(v.state()).isEqualTo(State.DEAD_LETTERED);
        assertThat(v.location()).isEqualTo(Location.DEAD_LETTER_QUEUE);
        assertThat(v.request().deadLettered()).isTrue();
    }

    @Test
    void anErrorTheCoordinatorSawIsTimedOutAndTheRequestIsWhereRedisHasIt() {
        var entry = entry(60).errored().build();

        Verdict v = Classifier.classify(entry, List.of(request(Delivery.PENDING, false)), PROBE_STREAM, TOPIC, GRACE);

        assertThat(v.state()).isEqualTo(State.TIMED_OUT);
        assertThat(v.location()).isEqualTo(Location.PENDING);
    }

    @Test
    void noReplyAndNoErrorIsInFlightWithinTheGracePeriodAndLostAfterIt() {
        var sightings = List.of(request(Delivery.UNREAD, false));

        assertThat(Classifier.classify(entry(10).build(), sightings, PROBE_STREAM, TOPIC, GRACE).state())
                .isEqualTo(State.IN_FLIGHT);
        assertThat(Classifier.classify(entry(31).build(), sightings, PROBE_STREAM, TOPIC, GRACE))
                .satisfies(v -> {
                    assertThat(v.state()).isEqualTo(State.LOST);
                    assertThat(v.location()).isEqualTo(Location.UNREAD);
                });
    }

    @Test
    void aRequestNotFoundIsLocatedByWhatIsLeftOfTheProbeStream() {
        var entry = entry(60).build();
        var trimmed = new StreamInfo(NODE_STREAM, 10, 1, (SENT.toEpochMilli() + 1) + "-0");

        assertThat(Classifier.classify(entry, List.of(), null, TOPIC, GRACE).location())
                .isEqualTo(Location.STREAM_GONE);
        assertThat(Classifier.classify(entry, List.of(), trimmed, TOPIC, GRACE).location())
                .isEqualTo(Location.TRIMMED);
        assertThat(Classifier.classify(entry, List.of(), PROBE_STREAM, TOPIC, GRACE).location())
                .isEqualTo(Location.NOT_FOUND);
    }

    @Test
    void anAppliedOutcomeIsApplied() {
        var entry = entry(60).replied("INFORM").applied().build();

        assertThat(Classifier.classify(entry, List.of(), PROBE_STREAM, TOPIC, GRACE).outcome())
                .isEqualTo(Outcome.APPLIED);
    }

    @Test
    void anOutcomeOnTheTopicThatTheAlerterNeverRecordedIsPublishedNotApplied() {
        var entry = entry(60).replied("INFORM").build();

        assertThat(Classifier.classify(entry, List.of(outcome(false)), PROBE_STREAM, TOPIC, GRACE).outcome())
                .isEqualTo(Outcome.PUBLISHED_NOT_APPLIED);
        assertThat(Classifier.classify(entry, List.of(outcome(false), outcome(true)), PROBE_STREAM, TOPIC, GRACE)
                .outcome()).isEqualTo(Outcome.DEAD_LETTERED);
    }

    @Test
    void anOutcomeMissingFromTheTopicIsNotPublishedUnlessTheTopicWasTrimmed() {
        var entry = entry(60).replied("INFORM").build();
        var trimmed = new StreamInfo(TOPIC.key(), 10, 1, (SENT.toEpochMilli() + 5_000) + "-0");

        assertThat(Classifier.classify(entry, List.of(), PROBE_STREAM, TOPIC, GRACE).outcome())
                .isEqualTo(Outcome.NOT_PUBLISHED);
        assertThat(Classifier.classify(entry, List.of(), PROBE_STREAM, trimmed, GRACE).outcome())
                .isEqualTo(Outcome.TRIMMED);
    }

    @Test
    void onlyAnAnsweredInformHasAnOutcome() {
        assertThat(Classifier.classify(entry(60).replied("FAILURE").build(), List.of(), PROBE_STREAM, TOPIC, GRACE)
                .outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(Classifier.classify(entry(60).errored().build(), List.of(), PROBE_STREAM, TOPIC, GRACE)
                .outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void anEndStoppedWhileTheRequestWasOpenWhenItsLastHeartbeatCameBeforeTheTimeout() {
        Instant before = SENT.plusSeconds(Classifier.REQUEST_TIMEOUT_SECONDS - 1);
        Instant after = SENT.plusSeconds(Classifier.REQUEST_TIMEOUT_SECONDS + 1);

        assertThat(cause(entry(60).probeHeartbeat(before).senderHeartbeat(after))).isEqualTo(Cause.PROBE_STOPPED);
        assertThat(cause(entry(60).probeHeartbeat(after).senderHeartbeat(before))).isEqualTo(Cause.SENDER_STOPPED);
        assertThat(cause(entry(60).probeHeartbeat(before).senderHeartbeat(before))).isEqualTo(Cause.BOTH_STOPPED);
        assertThat(cause(entry(60).probeHeartbeat(after).senderHeartbeat(after))).isEqualTo(Cause.NONE);
    }

    @Test
    void anEndWithNoDirectoryRowIsNotCountedAsStopped() {
        Instant after = SENT.plusSeconds(Classifier.REQUEST_TIMEOUT_SECONDS + 1);

        assertThat(cause(entry(60).probeHeartbeat(null).senderHeartbeat(after))).isEqualTo(Cause.NONE);
        assertThat(cause(entry(60).probeHeartbeat(after).senderHeartbeat(null))).isEqualTo(Cause.NONE);
    }

    @Test
    void theCauseNeverChangesTheState() {
        var lost = entry(60).senderHeartbeat(SENT);

        Verdict v = Classifier.classify(lost.build(), List.of(request(Delivery.DELIVERED, false), reply()),
                PROBE_STREAM, TOPIC, GRACE);

        assertThat(v.state()).isEqualTo(State.LOST);
        assertThat(v.cause()).isEqualTo(Cause.SENDER_STOPPED);
        assertThat(v.reply().key()).isEqualTo(SENDER_STREAM);
        assertThat(Classifier.classify(lost.replied("INFORM").build(), List.of(), PROBE_STREAM, TOPIC, GRACE).cause())
                .isEqualTo(Cause.NOT_APPLICABLE);
    }

    @Test
    void aRowWithNoSenderRecordedHasNoCause() {
        assertThat(cause(entry(60).probeHeartbeat(SENT).noSender())).isEqualTo(Cause.NOT_APPLICABLE);
    }

    static Cause cause(EntryBuilder entry) {
        return Classifier.classify(entry.build(), List.of(), PROBE_STREAM, TOPIC, GRACE).cause();
    }

    static Sighting reply() {
        return new Sighting(SENDER_STREAM, (SENT.toEpochMilli() + 380) + "-0", Kind.REPLY, false,
                Delivery.UNREAD, 0, null);
    }

    static Sighting request(Delivery delivery, boolean deadLettered) {
        return new Sighting(deadLettered ? NODE_STREAM + ":dlq" : NODE_STREAM, SENT.toEpochMilli() + "-0",
                Kind.REQUEST, deadLettered, delivery, 0, null);
    }

    static Sighting outcome(boolean deadLettered) {
        return new Sighting(deadLettered ? "sentinel:topic:outcomes:dlq" : "sentinel:topic:outcomes",
                (SENT.toEpochMilli() + 400) + "-0", Kind.OUTCOME, deadLettered, Delivery.DELIVERED, 0, null);
    }

    static EntryBuilder entry(double ageSeconds) {
        return new EntryBuilder(ageSeconds);
    }

    static final class EntryBuilder {
        private final double age;
        private Instant repliedAt;
        private String performative;
        private Instant errorAt;
        private boolean applied;
        private String senderId = SENDER;
        private Instant probeHeartbeat = SENT.plusSeconds(60);
        private Instant senderHeartbeat = SENT.plusSeconds(60);

        EntryBuilder(double age) {
            this.age = age;
        }

        EntryBuilder probeHeartbeat(Instant at) {
            this.probeHeartbeat = at;
            return this;
        }

        EntryBuilder senderHeartbeat(Instant at) {
            this.senderHeartbeat = at;
            return this;
        }

        EntryBuilder noSender() {
            this.senderId = null;
            return this;
        }

        EntryBuilder replied(String performative) {
            this.repliedAt = SENT.plusMillis(380);
            this.performative = performative;
            return this;
        }

        EntryBuilder errored() {
            this.errorAt = SENT.plusSeconds(15);
            return this;
        }

        EntryBuilder applied() {
            this.applied = true;
            return this;
        }

        LedgerEntry build() {
            return new LedgerEntry("34756dd8-19a8-4223-8767-43da5fc41926", SENT, age, 1,
                    "e100ca15-5d24-4cfa-8a20-a60d0b46d783", NODE, "RUNNING", "2026-10-29 21:27:51.39475",
                    probeHeartbeat, senderId, senderId == null ? null : SENDER_NODE, senderHeartbeat,
                    repliedAt, performative,
                    errorAt, errorAt == null ? null : "java.util.concurrent.TimeoutException", null, applied);
        }
    }
}

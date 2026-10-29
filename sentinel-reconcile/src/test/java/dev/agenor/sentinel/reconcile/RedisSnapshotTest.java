package dev.agenor.sentinel.reconcile;

import dev.agenor.sentinel.reconcile.RedisSnapshot.Delivery;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Group;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Kind;
import dev.agenor.sentinel.reconcile.RedisSnapshot.Sighting;
import io.lettuce.core.models.stream.PendingMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RedisSnapshotTest {

    static final String REQUEST_ID = "34756dd8-19a8-4223-8767-43da5fc41926";
    static final String PROBE_STREAM = "sentinel:node:837b34eb-f44b-45fa-aea9-5d12c84dff9d";

    /** The {@code payload} field of a REQUEST entry, as Agenor 0.39.0 wrote it to a probe's stream. */
    static final String REQUEST_PAYLOAD = """
            {"id":"4b06b7df-72c6-4dac-b981-a14713920c38","topic":null,"senderId":"coordinator",\
            "receiverId":"e100ca15-5d24-4cfa-8a20-a60d0b46d783","correlationId":null,\
            "content":{"requestId":"34756dd8-19a8-4223-8767-43da5fc41926","targetId":1,\
            "url":"http://fixtures:8080/t/alpha","timeoutMillis":5000},\
            "headers":{"content-class":"dev.agenor.sentinel.common.CheckRequest","performative":"REQUEST",\
            "protocol":"request","conversationId":"e024e188-30f4-46e7-87ba-c97d025a5ee0"},\
            "timestamp":"2026-10-29T21:27:58.510643864Z"}""";

    /** The {@code payload} field of the INFORM reply, from the coordinator's stream. */
    static final String REPLY_PAYLOAD = """
            {"id":"e02ae1fd-67b7-42dd-b1fa-8984abe0cc08","topic":null,\
            "senderId":"e100ca15-5d24-4cfa-8a20-a60d0b46d783","receiverId":"coordinator",\
            "correlationId":"4b06b7df-72c6-4dac-b981-a14713920c38",\
            "content":{"requestId":"34756dd8-19a8-4223-8767-43da5fc41926","targetId":1,\
            "url":"http://fixtures:8080/t/alpha","probeId":"e100ca15-5d24-4cfa-8a20-a60d0b46d783",\
            "httpStatus":200,"latencyMillis":259,"error":null,"checkedAt":"2026-10-29T21:27:58.627539686Z"},\
            "headers":{"performative":"INFORM","protocol":"request",\
            "conversationId":"e024e188-30f4-46e7-87ba-c97d025a5ee0",\
            "content-class":"dev.agenor.sentinel.common.CheckOutcome"},\
            "timestamp":"2026-10-29T21:27:58.887893168Z"}""";

    @Test
    void findsTheRequestIdInAPayload() {
        assertThat(RedisSnapshot.requestIds(REQUEST_PAYLOAD)).containsExactly(REQUEST_ID);
        assertThat(RedisSnapshot.performative(REQUEST_PAYLOAD)).isEqualTo("REQUEST");
        assertThat(RedisSnapshot.performative(REPLY_PAYLOAD)).isEqualTo("INFORM");
    }

    @Test
    void findsTheRequestIdInEscapedJson() {
        String escaped = REQUEST_PAYLOAD.replace("\"", "\\\"");

        assertThat(RedisSnapshot.requestIds(escaped)).containsExactly(REQUEST_ID);
        assertThat(RedisSnapshot.performative(escaped)).isEqualTo("REQUEST");
    }

    @Test
    void anEntryWithoutARequestIdMentionsNone() {
        assertThat(RedisSnapshot.requestIds("{\"id\":\"x\",\"content\":{}}")).isEmpty();
    }

    @Test
    void aRequestStillInAPendingListIsPending() {
        var group = new Group("sentinel:cg:node", "1793309278551-0",
                Map.of("1793309278551-0", new PendingMessage("1793309278551-0", "probe-consumer", 31_000, 2)));

        Sighting s = RedisSnapshot.sighting("sentinel", PROBE_STREAM, "1793309278551-0", REQUEST_PAYLOAD,
                List.of(group));

        assertThat(s.kind()).isEqualTo(Kind.REQUEST);
        assertThat(s.deadLettered()).isFalse();
        assertThat(s.delivery()).isEqualTo(Delivery.PENDING);
        assertThat(s.deliveries()).isEqualTo(2);
        assertThat(s.consumer()).isEqualTo("sentinel:cg:node/probe-consumer");
    }

    @Test
    void anEntryPastTheLastDeliveredIdIsUnread() {
        var group = new Group("sentinel:cg:node", "1793309278550-9", Map.of());

        Sighting s = RedisSnapshot.sighting("sentinel", PROBE_STREAM, "1793309278551-0", REQUEST_PAYLOAD,
                List.of(group));

        assertThat(s.delivery()).isEqualTo(Delivery.UNREAD);
    }

    @Test
    void anAcknowledgedEntryIsDelivered() {
        var group = new Group("sentinel:cg:node", "1793309278551-0", Map.of());

        Sighting s = RedisSnapshot.sighting("sentinel", PROBE_STREAM, "1793309278551-0", REQUEST_PAYLOAD,
                List.of(group));

        assertThat(s.delivery()).isEqualTo(Delivery.DELIVERED);
    }

    @Test
    void anEntryInAStreamWithNoGroupHasNoGroup() {
        Sighting s = RedisSnapshot.sighting("sentinel", PROBE_STREAM, "1-0", REQUEST_PAYLOAD, List.of());

        assertThat(s.delivery()).isEqualTo(Delivery.NO_GROUP);
    }

    @Test
    void aRequestInTheDeadLetterStreamOfANodeIsADeadLetteredRequest() {
        Sighting s = RedisSnapshot.sighting("sentinel", PROBE_STREAM + ":dlq", "1-0", REQUEST_PAYLOAD, List.of());

        assertThat(s.kind()).isEqualTo(Kind.REQUEST);
        assertThat(s.deadLettered()).isTrue();
    }

    @Test
    void theReplyInTheCoordinatorStreamIsAReply() {
        Sighting s = RedisSnapshot.sighting("sentinel", "sentinel:node:19dd02b0-e97a-453d-9634-c825fca9609b",
                "1-0", REPLY_PAYLOAD, List.of());

        assertThat(s.kind()).isEqualTo(Kind.REPLY);
    }

    @Test
    void anEntryOnATopicIsAnOutcome() {
        assertThat(RedisSnapshot.sighting("sentinel", "sentinel:topic:outcomes", "1-0", REPLY_PAYLOAD, List.of())
                .kind()).isEqualTo(Kind.OUTCOME);
        assertThat(RedisSnapshot.sighting("sentinel", "sentinel:topic:outcomes:dlq", "1-0", REPLY_PAYLOAD, List.of()))
                .satisfies(s -> {
                    assertThat(s.kind()).isEqualTo(Kind.OUTCOME);
                    assertThat(s.deadLettered()).isTrue();
                });
    }

    @Test
    void streamIdsCompareByMillisThenSequence() {
        assertThat(RedisSnapshot.compareIds("1793309278551-0", "1793309278550-9")).isPositive();
        assertThat(RedisSnapshot.compareIds("1793309278551-1", "1793309278551-2")).isNegative();
        assertThat(RedisSnapshot.compareIds("0-0", "0-0")).isZero();
        assertThat(RedisSnapshot.millisOf("1793309278551-3")).isEqualTo(1793309278551L);
    }
}

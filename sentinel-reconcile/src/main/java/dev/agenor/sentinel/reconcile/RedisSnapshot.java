package dev.agenor.sentinel.reconcile;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.PendingMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every stream under the key prefix, read once, and indexed by the application's request id.
 *
 * <p>The key layout is the one {@code adapters/redis} documents for Agenor 0.39.0:
 * {@code <prefix>:node:<nodeId>} for each node's mailbox, {@code <prefix>:topic:<name>} for each
 * topic, and {@code <sourceStreamKey>:dlq} for what was dead-lettered from a stream. Each entry
 * carries the message as JSON in its {@code payload} field. The request id is found inside the
 * payload, not by Agenor's message id: the coordinator never learns that id for a request that
 * gets no reply (FINDINGS.md, C1).
 *
 * <p>Read while the system runs, the snapshot is not atomic. Requests younger than the grace period
 * are reported as in flight for that reason as well.
 */
final class RedisSnapshot {

    /** What a stream entry that mentions a request id is. */
    enum Kind {
        /** The coordinator's REQUEST, in a node stream. */
        REQUEST,
        /** Any other message in a node stream: the probe's reply. */
        REPLY,
        /** The outcome the coordinator published on a topic. */
        OUTCOME,
        OTHER
    }

    /** Where an entry stands with the consumer groups of its stream. */
    enum Delivery {
        /** Delivered to a consumer and never acknowledged. */
        PENDING,
        /** Past the last id delivered to every group: nobody has read it yet. */
        UNREAD,
        /** Delivered and acknowledged. */
        DELIVERED,
        /** The stream has no consumer group at all. */
        NO_GROUP
    }

    record Sighting(String key, String entryId, Kind kind, boolean deadLettered, Delivery delivery,
                    long deliveries, String consumer) {
    }

    /** A stream as it was when read; {@code firstEntryId} is null when it is empty. */
    record StreamInfo(String key, long length, int groups, String firstEntryId) {
    }

    record Group(String name, String lastDeliveredId, Map<String, PendingMessage> pending) {
    }

    private static final int PAGE = 1_000;
    private static final Pattern REQUEST_ID =
            Pattern.compile("requestId\\\\*\"\\s*:\\s*\\\\*\"([0-9a-fA-F-]{36})");
    private static final Pattern PERFORMATIVE =
            Pattern.compile("performative\\\\*\"\\s*:\\s*\\\\*\"([A-Z_]+)");

    private final String prefix;
    private final Map<String, StreamInfo> streams;
    private final Map<String, List<Sighting>> byRequest;

    private RedisSnapshot(String prefix, Map<String, StreamInfo> streams, Map<String, List<Sighting>> byRequest) {
        this.prefix = prefix;
        this.streams = streams;
        this.byRequest = byRequest;
    }

    static RedisSnapshot read(RedisCommands<String, String> redis, String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        ScanIterator.scan(redis, ScanArgs.Builder.matches(prefix + ":*").limit(PAGE)).forEachRemaining(keys::add);

        Map<String, StreamInfo> streams = new HashMap<>();
        Map<String, List<Sighting>> byRequest = new HashMap<>();
        for (String key : keys.stream().sorted().toList()) {
            if (!"stream".equals(redis.type(key))) {
                continue;
            }
            List<Group> groups = groups(redis, key);
            String first = null;
            long length = 0;
            String last = null;
            while (true) {
                Range<String> range = last == null
                        ? Range.create("-", "+")
                        : Range.from(Range.Boundary.excluding(last), Range.Boundary.unbounded());
                List<StreamMessage<String, String>> page = redis.xrange(key, range, Limit.from(PAGE));
                for (StreamMessage<String, String> entry : page) {
                    if (first == null) {
                        first = entry.getId();
                    }
                    length++;
                    String text = String.join("\n", entry.getBody().values());
                    for (String requestId : requestIds(text)) {
                        byRequest.computeIfAbsent(requestId, id -> new ArrayList<>())
                                .add(sighting(prefix, key, entry.getId(), text, groups));
                    }
                }
                if (page.size() < PAGE) {
                    break;
                }
                last = page.getLast().getId();
            }
            streams.put(key, new StreamInfo(key, length, groups.size(), first));
        }
        return new RedisSnapshot(prefix, streams, byRequest);
    }

    List<Sighting> sightings(String requestId) {
        return byRequest.getOrDefault(requestId, List.of());
    }

    /** The mailbox of a node, or null if no such stream exists any more. */
    StreamInfo nodeStream(String nodeId) {
        return nodeId == null ? null : streams.get(nodeStreamKey(nodeId));
    }

    /** The key of a node's mailbox, whether it exists or not; null for a null node id. */
    String nodeStreamKey(String nodeId) {
        return nodeId == null ? null : prefix + ":node:" + nodeId;
    }

    StreamInfo topicStream(String topic) {
        return streams.get(prefix + ":topic:" + topic);
    }

    Map<String, StreamInfo> streams() {
        return streams;
    }

    private static List<Group> groups(RedisCommands<String, String> redis, String key) {
        List<Group> groups = new ArrayList<>();
        for (Object raw : redis.xinfoGroups(key)) {
            Map<String, Object> info = pairs((List<?>) raw);
            String name = String.valueOf(info.get("name"));
            long pendingCount = ((Number) info.get("pending")).longValue();
            Map<String, PendingMessage> pending = new HashMap<>();
            String after = null;
            while (pending.size() < pendingCount) {
                Range<String> range = after == null
                        ? Range.create("-", "+")
                        : Range.from(Range.Boundary.excluding(after), Range.Boundary.unbounded());
                List<PendingMessage> page = redis.xpending(key, name, range, Limit.from(PAGE));
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(p -> pending.put(p.getId(), p));
                after = page.getLast().getId();
            }
            groups.add(new Group(name, String.valueOf(info.get("last-delivered-id")), pending));
        }
        return groups;
    }

    private static Map<String, Object> pairs(List<?> flat) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            map.put(String.valueOf(flat.get(i)), flat.get(i + 1));
        }
        return map;
    }

    static Sighting sighting(String prefix, String key, String entryId, String text, List<Group> groups) {
        boolean deadLettered = key.endsWith(":dlq");
        String source = deadLettered ? key.substring(0, key.length() - ":dlq".length()) : key;
        Kind kind;
        if (source.startsWith(prefix + ":topic:")) {
            kind = Kind.OUTCOME;
        } else if (source.startsWith(prefix + ":node:")) {
            kind = "REQUEST".equals(performative(text)) ? Kind.REQUEST : Kind.REPLY;
        } else {
            kind = Kind.OTHER;
        }
        if (groups.isEmpty()) {
            return new Sighting(key, entryId, kind, deadLettered, Delivery.NO_GROUP, 0, null);
        }
        for (Group group : groups) {
            PendingMessage pending = group.pending().get(entryId);
            if (pending != null) {
                return new Sighting(key, entryId, kind, deadLettered, Delivery.PENDING,
                        pending.getRedeliveryCount(), group.name() + "/" + pending.getConsumer());
            }
        }
        boolean unread = groups.stream().allMatch(g -> compareIds(entryId, g.lastDeliveredId()) > 0);
        return new Sighting(key, entryId, kind, deadLettered, unread ? Delivery.UNREAD : Delivery.DELIVERED, 0, null);
    }

    /** Every request id an entry mentions, in the order found, whether the JSON is escaped or not. */
    static Set<String> requestIds(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = REQUEST_ID.matcher(text);
        while (m.find()) {
            ids.add(m.group(1).toLowerCase());
        }
        return ids;
    }

    static String performative(String text) {
        Matcher m = PERFORMATIVE.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Orders two stream ids ({@code <millis>-<sequence>}). */
    static int compareIds(String a, String b) {
        long[] x = parseId(a);
        long[] y = parseId(b);
        int byMillis = Long.compare(x[0], y[0]);
        return byMillis != 0 ? byMillis : Long.compare(x[1], y[1]);
    }

    static long millisOf(String id) {
        return parseId(id)[0];
    }

    private static long[] parseId(String id) {
        int dash = id.indexOf('-');
        return dash < 0
                ? new long[]{Long.parseLong(id), 0}
                : new long[]{Long.parseLong(id.substring(0, dash)), Long.parseLong(id.substring(dash + 1))};
    }
}

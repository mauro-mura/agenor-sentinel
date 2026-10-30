package dev.agenor.sentinel.reconcile;

import dev.agenor.sentinel.reconcile.Classifier.Location;
import dev.agenor.sentinel.reconcile.Classifier.Outcome;
import dev.agenor.sentinel.reconcile.Classifier.State;
import dev.agenor.sentinel.reconcile.Classifier.Verdict;
import io.lettuce.core.RedisClient;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The reconciliation script of CRITERION §4. Reads the ledger from PostgreSQL first, then every
 * stream from Redis, and assigns each request a state with {@link Classifier}. Prints a summary and,
 * with {@code --csv}, writes one row per request.
 *
 * <p>Configured by environment, with defaults for the compose stack seen from the host:
 * {@code JDBC_URL}, {@code JDBC_USERNAME}, {@code JDBC_PASSWORD}, {@code REDIS_URI} and
 * {@code KEY_PREFIX} (the {@code consumer-group-prefix} of the three roles).
 *
 * <p>Usage: {@code scripts/reconcile [--csv FILE] [--grace SECONDS]}. The grace period, 30 s by
 * default, is twice the coordinator's request timeout: a request younger than that without an
 * answer or an error is reported as in flight rather than lost.
 *
 * <p>A ledger with any request in flight is not a final result (FINDINGS.md, C2): the script says
 * so and exits with status 3, so that the fifth state never takes the place of a lost one. Stop the
 * dispatching with {@code scripts/quiesce}, wait out the grace period, and run it again.
 */
public final class Reconcile {

    static final String TOPIC = "outcomes";

    private static final List<String> COLUMNS = List.of(
            "request_id", "created_at", "target_id", "probe_id", "probe_node_id", "probe_node_status",
            "probe_node_last_seen",
            "state", "request_location", "request_key", "request_entry_id", "deliveries", "consumer",
            "reply_performative", "error_class", "error_message", "outcome");

    private Reconcile() {
    }

    public static void main(String[] args) throws Exception {
        Path csv = null;
        double grace = 30;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--csv" -> csv = Path.of(args[++i]);
                case "--grace" -> grace = Double.parseDouble(args[++i]);
                default -> {
                    System.err.println("usage: reconcile [--csv FILE] [--grace SECONDS]");
                    System.exit(2);
                }
            }
        }
        String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://127.0.0.1:55432/sentinel");
        String redisUri = env("REDIS_URI", "redis://127.0.0.1:56379");
        String prefix = env("KEY_PREFIX", "sentinel");

        Instant at = Instant.now();
        List<LedgerEntry> ledger;
        try (Connection connection = DriverManager.getConnection(jdbcUrl,
                env("JDBC_USERNAME", "sentinel"), env("JDBC_PASSWORD", "sentinel"))) {
            ledger = LedgerSource.read(connection);
        }
        RedisSnapshot redis;
        RedisClient client = RedisClient.create(redisUri);
        try (var connection = client.connect()) {
            redis = RedisSnapshot.read(connection.sync(), prefix);
        } finally {
            client.shutdown();
        }

        List<Verdict> verdicts = new ArrayList<>(ledger.size());
        for (LedgerEntry entry : ledger) {
            verdicts.add(Classifier.classify(entry, redis.sightings(entry.requestId()),
                    redis.nodeStream(entry.probeNodeId()), redis.topicStream(TOPIC), grace));
        }

        summary(System.out, at, prefix, redis, ledger, verdicts);
        if (csv != null) {
            try (Writer out = Files.newBufferedWriter(csv)) {
                writeCsv(out, ledger, verdicts);
            }
            System.out.println();
            System.out.println("One row per request: " + csv);
        }
        long inFlight = verdicts.stream().filter(v -> v.state() == State.IN_FLIGHT).count();
        if (inFlight > 0) {
            System.out.println();
            System.out.printf("NOT A FINAL RESULT: %d request(s) still in flight. Stop the dispatching "
                    + "(scripts/quiesce), wait %.0f s, and run again.%n", inFlight, grace);
            System.exit(3);
        }
    }

    static void summary(PrintStream out, Instant at, String prefix, RedisSnapshot redis,
                        List<LedgerEntry> ledger, List<Verdict> verdicts) {
        out.printf("Reconciliation at %s: %d request(s) in the ledger, %d stream(s) under '%s:'%n",
                at, ledger.size(), redis.streams().size(), prefix);

        out.println();
        out.println("State of each request (CRITERION §4)");
        Map<State, Long> byState = new EnumMap<>(State.class);
        for (State s : State.values()) {
            byState.put(s, 0L);
        }
        verdicts.forEach(v -> byState.merge(v.state(), 1L, Long::sum));
        byState.forEach((state, n) -> out.printf("  %-16s %8d%n", state, n));

        out.println();
        out.println("Where the unanswered requests are in Redis (Q2)");
        Map<String, Long> where = new TreeMap<>();
        for (int i = 0; i < verdicts.size(); i++) {
            Verdict v = verdicts.get(i);
            if (v.state() != State.ANSWERED) {
                String node = Objects.requireNonNullElse(ledger.get(i).probeNodeStatus(), "no directory row");
                where.merge(String.format("%-14s %-18s probe's directory row: %s", v.state(), v.location(), node),
                        1L, Long::sum);
            }
        }
        if (where.isEmpty()) {
            out.println("  none");
        }
        where.forEach((line, n) -> out.printf("  %s %8d%n", line, n));

        out.println();
        out.println("Outcomes of INFORM replies (O1)");
        Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        verdicts.stream().map(Verdict::outcome).filter(o -> o != Outcome.NOT_APPLICABLE)
                .forEach(o -> outcomes.merge(o, 1L, Long::sum));
        if (outcomes.isEmpty()) {
            out.println("  none");
        }
        outcomes.forEach((outcome, n) -> out.printf("  %-22s %8d%n", outcome, n));
    }

    static void writeCsv(Writer out, List<LedgerEntry> ledger, List<Verdict> verdicts) throws IOException {
        out.write(String.join(",", COLUMNS));
        out.write('\n');
        for (int i = 0; i < ledger.size(); i++) {
            LedgerEntry e = ledger.get(i);
            Verdict v = verdicts.get(i);
            var request = v.request();
            List<Object> row = List.of(
                    e.requestId(), e.createdAt(), e.targetId(), e.probeId(), nz(e.probeNodeId()),
                    nz(e.probeNodeStatus()), nz(e.probeNodeLastSeen()), v.state(), v.location(),
                    request == null ? "" : request.key(), request == null ? "" : request.entryId(),
                    request == null ? "" : request.deliveries(),
                    request == null ? "" : nz(request.consumer()),
                    nz(e.replyPerformative()), nz(e.errorClass()), nz(e.errorMessage()), v.outcome());
            out.write(String.join(",", row.stream().map(String::valueOf).map(Reconcile::csvField).toList()));
            out.write('\n');
        }
    }

    static String csvField(String value) {
        if (Stream.of(",", "\"", "\n", "\r").noneMatch(value::contains)) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static Object nz(Object value) {
        return value == null ? "" : value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

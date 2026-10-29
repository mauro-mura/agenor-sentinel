package dev.agenor.sentinel.alerter;

import dev.agenor.sentinel.common.CheckOutcome;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Target state and alerts, decided under a row lock on the target so outcomes cannot interleave. */
class AlertStore {

    record Alert(long id, long targetId, String targetName, String kind, String requestId, Instant raisedAt) {
    }

    private record State(String name, int threshold, int failures, boolean down, Timestamp lastCheckedAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    AlertStore(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Applies one outcome; returns the alert it raised, if any. */
    Optional<Alert> apply(CheckOutcome outcome) {
        return tx.execute(status -> {
            UUID requestId = UUID.fromString(outcome.requestId());
            int inserted = jdbc.sql("""
                            INSERT INTO outcome_seen (request_id, target_id, success)
                            VALUES (:id, :target, :success)
                            ON CONFLICT (request_id) DO NOTHING
                            """)
                    .param("id", requestId)
                    .param("target", outcome.targetId())
                    .param("success", outcome.success())
                    .update();
            if (inserted == 0) {
                return Optional.empty();
            }

            jdbc.sql("INSERT INTO target_state (target_id) VALUES (:target) ON CONFLICT DO NOTHING")
                    .param("target", outcome.targetId())
                    .update();
            State state = jdbc.sql("""
                            SELECT t.name, t.threshold, s.consecutive_failures, s.down, s.last_checked_at
                              FROM target_state s JOIN target t ON t.id = s.target_id
                             WHERE s.target_id = :target
                               FOR UPDATE OF s
                            """)
                    .param("target", outcome.targetId())
                    .query((rs, n) -> new State(rs.getString(1), rs.getInt(2), rs.getInt(3),
                            rs.getBoolean(4), rs.getTimestamp(5)))
                    .single();

            // Outcomes of one target can arrive out of order (two probes, overlapping handlers).
            // An outcome older than the last one applied is recorded as seen and otherwise ignored.
            Timestamp checkedAt = Timestamp.from(outcome.checkedAt());
            if (state.lastCheckedAt() != null && checkedAt.before(state.lastCheckedAt())) {
                return Optional.empty();
            }

            int failures = outcome.success() ? 0 : state.failures() + 1;
            String kind = null;
            boolean down = state.down();
            if (!outcome.success() && !down && failures >= state.threshold()) {
                kind = "DOWN";
                down = true;
            } else if (outcome.success() && down) {
                kind = "UP";
                down = false;
            }
            jdbc.sql("""
                            UPDATE target_state
                               SET consecutive_failures = :failures, down = :down, last_checked_at = :at
                             WHERE target_id = :target
                            """)
                    .param("failures", failures)
                    .param("down", down)
                    .param("at", checkedAt)
                    .param("target", outcome.targetId())
                    .update();
            if (kind == null) {
                return Optional.empty();
            }
            Instant raisedAt = Instant.now();
            long alertId = jdbc.sql("""
                            INSERT INTO alert (target_id, kind, request_id, raised_at)
                            VALUES (:target, :kind, :id, :at)
                            RETURNING id
                            """)
                    .param("target", outcome.targetId())
                    .param("kind", kind)
                    .param("id", requestId)
                    .param("at", Timestamp.from(raisedAt))
                    .query(Long.class)
                    .single();
            return Optional.of(new Alert(alertId, outcome.targetId(), state.name(), kind,
                    outcome.requestId(), raisedAt));
        });
    }

    void recordWebhookStatus(long alertId, int httpStatus) {
        jdbc.sql("UPDATE alert SET webhook_status = :status WHERE id = :id")
                .param("status", httpStatus)
                .param("id", alertId)
                .update();
    }
}

package dev.agenor.sentinel.reconcile;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Reads the ledger, joined with the alerter's {@code outcome_seen} and Agenor's directory rows. */
final class LedgerSource {

    private static final String QUERY = """
            SELECT l.request_id, l.created_at,
                   EXTRACT(EPOCH FROM now() - l.created_at) AS age_seconds,
                   l.target_id, l.probe_id, l.probe_node_id,
                   (SELECT string_agg(DISTINCT a.status, '+' ORDER BY a.status)
                      FROM agenor_agents a
                     WHERE a.node_id = l.probe_node_id) AS probe_node_status,
                   (SELECT max(a.last_seen)::text
                      FROM agenor_agents a
                     WHERE a.node_id = l.probe_node_id) AS probe_node_last_seen,
                   l.replied_at, l.reply_performative,
                   l.error_at, l.error_class, l.error_message,
                   o.request_id IS NOT NULL AS outcome_applied
              FROM ledger l
              LEFT JOIN outcome_seen o ON o.request_id = l.request_id
             ORDER BY l.created_at, l.request_id
            """;

    private LedgerSource() {
    }

    static List<LedgerEntry> read(Connection connection) throws SQLException {
        List<LedgerEntry> entries = new ArrayList<>();
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(QUERY)) {
            while (rs.next()) {
                entries.add(new LedgerEntry(
                        rs.getString("request_id"),
                        instant(rs, "created_at"),
                        rs.getDouble("age_seconds"),
                        rs.getLong("target_id"),
                        rs.getString("probe_id"),
                        rs.getString("probe_node_id"),
                        rs.getString("probe_node_status"),
                        rs.getString("probe_node_last_seen"),
                        instant(rs, "replied_at"),
                        rs.getString("reply_performative"),
                        instant(rs, "error_at"),
                        rs.getString("error_class"),
                        rs.getString("error_message"),
                        rs.getBoolean("outcome_applied")));
            }
        }
        return entries;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

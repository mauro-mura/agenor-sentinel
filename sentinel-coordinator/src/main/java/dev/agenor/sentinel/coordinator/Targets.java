package dev.agenor.sentinel.coordinator;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

/** The {@code target} table: which targets are due for a check. */
class Targets {

    record Target(long id, String name, String url, int intervalSeconds) {
    }

    private final JdbcClient jdbc;

    Targets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the targets whose check is due and moves their next check one interval ahead. A target
     * is claimed once per interval whatever happens to the check, so a lost request shows up as a
     * missing outcome rather than as a retry storm. One statement, so two claims never overlap.
     */
    List<Target> claimDue() {
        return jdbc.sql("""
                        UPDATE target
                           SET next_due_at = now() + make_interval(secs => interval_seconds)
                         WHERE next_due_at <= now()
                     RETURNING id, name, url, interval_seconds
                        """)
                .query((rs, n) -> new Target(rs.getLong("id"), rs.getString("name"),
                        rs.getString("url"), rs.getInt("interval_seconds")))
                .list();
    }
}

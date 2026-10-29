-- The application's own tables. Agenor's directory tables (agenor_agents, ...) are created by
-- Agenor's own migrations, which keep a separate history table.

-- What is monitored. Targets are fake services from sentinel-fixtures, never third-party ones.
CREATE TABLE target (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT        NOT NULL UNIQUE,
    url              TEXT        NOT NULL,
    interval_seconds INTEGER     NOT NULL CHECK (interval_seconds > 0),
    threshold        INTEGER     NOT NULL CHECK (threshold > 0),
    next_due_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The reconciliation ledger (CRITERION §4). One row per coordinator request, written before the
-- request is sent. The application records only what it saw; the final state (answered, timed
-- out, dead-lettered, lost) is assigned by an external script.
CREATE TABLE ledger (
    request_id      UUID        PRIMARY KEY,
    target_id       BIGINT      NOT NULL REFERENCES target (id),
    probe_id        TEXT        NOT NULL,
    probe_node_id   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    replied_at      TIMESTAMPTZ,
    reply_performative TEXT,
    reply_message_id TEXT,
    in_reply_to     TEXT,
    error_at        TIMESTAMPTZ,
    error_class     TEXT,
    error_message   TEXT
);
CREATE INDEX ledger_probe_idx ON ledger (probe_id);

-- The alerter's view of each target. Kept in the database so that a restarted alerter carries on
-- where the previous one stopped.
CREATE TABLE target_state (
    target_id            BIGINT      PRIMARY KEY REFERENCES target (id),
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    down                 BOOLEAN     NOT NULL DEFAULT FALSE,
    last_checked_at      TIMESTAMPTZ
);

-- Outcomes the alerter has already applied: delivery is at-least-once, so a redelivered outcome
-- must not count twice.
CREATE TABLE outcome_seen (
    request_id  UUID        PRIMARY KEY,
    target_id   BIGINT      NOT NULL,
    success     BOOLEAN     NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Alerts as a user sees them. A duplicate or a missing row here is the error that matters.
CREATE TABLE alert (
    id             BIGSERIAL   PRIMARY KEY,
    target_id      BIGINT      NOT NULL REFERENCES target (id),
    kind           TEXT        NOT NULL CHECK (kind IN ('DOWN', 'UP')),
    request_id     UUID        NOT NULL,
    raised_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    webhook_status INTEGER
);

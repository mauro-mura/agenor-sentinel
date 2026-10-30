package dev.agenor.sentinel.coordinator;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/**
 * The application's half of the reconciliation ledger (CRITERION §4): every request is written
 * before it is sent, and what the coordinator later saw (a reply, or an error) is written next to
 * it. The final state is assigned by {@code scripts/reconcile}, not here.
 */
class Ledger {

    private final JdbcClient jdbc;

    Ledger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void recordSent(UUID requestId, String senderId, long targetId, String probeId, String probeNodeId) {
        jdbc.sql("""
                        INSERT INTO ledger (request_id, sender_id, target_id, probe_id, probe_node_id)
                        VALUES (:id, :sender, :target, :probe, :node)
                        """)
                .param("id", requestId)
                .param("sender", senderId)
                .param("target", targetId)
                .param("probe", probeId)
                .param("node", probeNodeId)
                .update();
    }

    void recordReply(UUID requestId, String performative, String replyMessageId, String inReplyTo) {
        jdbc.sql("""
                        UPDATE ledger
                           SET replied_at = now(), reply_performative = :performative,
                               reply_message_id = :reply, in_reply_to = :inReplyTo
                         WHERE request_id = :id
                        """)
                .param("id", requestId)
                .param("performative", performative)
                .param("reply", replyMessageId)
                .param("inReplyTo", inReplyTo)
                .update();
    }

    void recordError(UUID requestId, Throwable error) {
        jdbc.sql("""
                        UPDATE ledger
                           SET error_at = now(), error_class = :cls, error_message = :msg
                         WHERE request_id = :id
                        """)
                .param("id", requestId)
                .param("cls", error.getClass().getName())
                .param("msg", String.valueOf(error.getMessage()))
                .update();
    }
}

package dev.agenor.sentinel.alerter;

import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.AgenorMessageHandler;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.sentinel.common.CheckOutcome;

import java.util.Optional;

/**
 * Turns outcomes into alerts: DOWN after a target's threshold of failed outcomes in a row, UP on
 * the first success after that.
 *
 * <p>Delivery is at-least-once, so the decision is made in {@link AlertStore}, in one transaction
 * that also records the outcome as applied. A redelivered outcome changes nothing.
 */
@Agent("alerter")
public class AlerterAgent extends BaseAgent {

    private final AlertStore store;
    private final Webhook webhook;

    AlerterAgent(AlertStore store, Webhook webhook) {
        this.store = store;
        this.webhook = webhook;
    }

    @AgenorMessageHandler(CheckOutcome.TOPIC)
    public void onOutcome(Message message) {
        CheckOutcome outcome = message.getContent(CheckOutcome.class);
        Optional<AlertStore.Alert> alert = store.apply(outcome);
        alert.ifPresent(a -> {
            log.info("{} {} (request {})", a.kind(), a.targetName(), a.requestId());
            store.recordWebhookStatus(a.id(), webhook.post(a));
        });
    }
}

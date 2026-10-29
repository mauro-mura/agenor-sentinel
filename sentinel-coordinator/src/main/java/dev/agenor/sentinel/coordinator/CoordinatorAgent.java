package dev.agenor.sentinel.coordinator;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.Message;
import dev.agenor.core.annotations.Agent;
import dev.agenor.core.annotations.Behavior;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.sentinel.common.CheckOutcome;
import dev.agenor.sentinel.common.CheckRequest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Each round, claims the targets that are due, hands each one to a probe found by capability, and
 * publishes every outcome on the {@code outcomes} topic.
 *
 * <p>Probes are chosen round-robin among whatever {@code findByCapability("probe")} returns at that
 * moment, with no filter of its own: what that costs when a probe dies is Q3's measure.
 */
@Agent("coordinator")
public class CoordinatorAgent extends BaseAgent {

    static final String PROBE_CAPABILITY = "probe";
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final int CHECK_TIMEOUT_MILLIS = 5_000;

    private final DialogueCapability dialogue = new DialogueCapability(this);
    private final AtomicLong next = new AtomicLong();
    private final Ledger ledger;
    private final Targets targets;

    CoordinatorAgent(Ledger ledger, Targets targets) {
        this.ledger = ledger;
        this.targets = targets;
    }

    @Behavior(type = CYCLIC, interval = "2s")
    public void round() {
        List<Targets.Target> due = targets.claimDue();
        if (due.isEmpty()) {
            return;
        }
        List<AgentDescriptor> probes = agentDirectory.findByCapability(PROBE_CAPABILITY).join();
        if (probes.isEmpty()) {
            log.warn("{} target(s) due and no probe in the directory", due.size());
            return;
        }
        for (Targets.Target target : due) {
            AgentDescriptor probe = probes.get((int) Math.floorMod(next.getAndIncrement(), (long) probes.size()));
            dispatch(target, probe);
        }
    }

    private void dispatch(Targets.Target target, AgentDescriptor probe) {
        UUID requestId = UUID.randomUUID();
        String nodeId = probe.endpoint() == null ? null : probe.endpoint().nodeId();
        ledger.recordSent(requestId, target.id(), probe.agentId(), nodeId);

        var check = new CheckRequest(requestId.toString(), target.id(), target.url(), CHECK_TIMEOUT_MILLIS);
        dialogue.request(probe.agentId(), check, REQUEST_TIMEOUT)
                .thenAccept(reply -> onReply(requestId, reply))
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    log.warn("request {} to {} for {} failed: {}", requestId, probe.agentId(),
                            target.name(), cause.toString());
                    ledger.recordError(requestId, cause);
                    return null;
                });
    }

    private void onReply(UUID requestId, DialogueMessage reply) {
        ledger.recordReply(requestId, reply.performative().name(), reply.id(), reply.inReplyTo());
        if (reply.performative() != Performative.INFORM) {
            log.warn("request {} answered {}: {}", requestId, reply.performative(), reply.content());
            return;
        }
        CheckOutcome outcome = reply.contentAs(CheckOutcome.class);
        getMessageDispatcher().publish(Message.builder()
                .topic(CheckOutcome.TOPIC)
                .senderId(getAgentId())
                .content(outcome)
                .build());
    }
}

package dev.agenor.sentinel.coordinator;

import dev.agenor.core.AgentDescriptor;
import dev.agenor.core.AgentEndpoint;
import dev.agenor.core.AgentStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static dev.agenor.core.BehaviorType.CYCLIC;

/**
 * Each round, claims the targets that are due, hands each one to a probe found by capability, and
 * publishes every outcome on the {@code outcomes} topic.
 *
 * <p>Probes are chosen round-robin among those {@code findByCapability("probe")} returns at that
 * moment whose presence reads {@code RUNNING} (see {@link #live}). Without that filter a probe
 * killed without goodbye stays a candidate for as long as the run lasts (FINDINGS.md, Q2).
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
        List<AgentDescriptor> probes = live(agentDirectory.findByCapability(PROBE_CAPABILITY).join());
        if (probes.isEmpty()) {
            log.warn("{} target(s) due and no live probe in the directory", due.size());
            return;
        }
        String ownNode = ownNode();
        for (Targets.Target target : due) {
            AgentDescriptor probe = probes.get((int) Math.floorMod(next.getAndIncrement(), (long) probes.size()));
            dispatch(target, probe, ownNode);
        }
    }

    /**
     * The node other nodes route this agent's mail to, so where the probes' replies go: what
     * {@code resolveEndpoint} answers for this agent's own id, the lookup every {@code sendTo} makes.
     * The agent id stays {@code coordinator} across restarts and the node id does not, so only the
     * node tells one life of the coordinator from the next in the ledger. Null if unresolved.
     */
    private String ownNode() {
        return agentDirectory.resolveEndpoint(getAgentId()).join().map(AgentEndpoint::nodeId).orElse(null);
    }

    /**
     * The candidates whose presence reads {@code RUNNING}.
     *
     * @friction Q3: {@code findByCapability} does not filter by liveness ({@code directory.md}), and
     *     a probe killed without goodbye keeps its directory row with status {@code RUNNING}, so a
     *     filter on {@code AgentDescriptor.status()}, or {@code AgentQuery.status(RUNNING)}, keeps it
     *     as well. Only {@code getStatus} weighs {@code last_seen} against the staleness window
     *     ({@code adapters/jdbc-directory.md}), and it takes one call per candidate.
     */
    private List<AgentDescriptor> live(List<AgentDescriptor> candidates) {
        List<CompletableFuture<AgentStatus>> statuses = candidates.stream()
                .map(probe -> agentDirectory.getStatus(probe.agentId()))
                .toList();
        List<AgentDescriptor> live = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (statuses.get(i).join() == AgentStatus.RUNNING) {
                live.add(candidates.get(i));
            }
        }
        return live;
    }

    private void dispatch(Targets.Target target, AgentDescriptor probe, String ownNode) {
        UUID requestId = UUID.randomUUID();
        String nodeId = probe.endpoint() == null ? null : probe.endpoint().nodeId();
        ledger.recordSent(requestId, getAgentId(), ownNode, target.id(), probe.agentId(), nodeId);

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

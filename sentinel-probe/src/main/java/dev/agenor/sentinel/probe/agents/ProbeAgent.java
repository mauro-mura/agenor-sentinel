package dev.agenor.sentinel.probe.agents;

import dev.agenor.core.annotations.Agent;
import dev.agenor.core.dialogue.DialogueHandler;
import dev.agenor.core.dialogue.DialogueMessage;
import dev.agenor.core.dialogue.Performative;
import dev.agenor.runtime.agent.BaseAgent;
import dev.agenor.runtime.dialogue.DialogueCapability;
import dev.agenor.sentinel.common.CheckOutcome;
import dev.agenor.sentinel.common.CheckRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Checks one URL per REQUEST and answers with a {@link CheckOutcome}.
 *
 * <p>The agent has no fixed id. Every probe process runs this same class, so a fixed
 * {@code @Agent("probe")} would give all of them one address (Javadoc of {@code @Agent}). Left
 * empty, the id is a random UUID, new on every start, which is what the coordinator does not need
 * to know: it finds probes by capability.
 *
 * <p>A target that does not answer is not a failure of the request: the probe answers INFORM with
 * the error in the outcome. FAILURE is reserved for a request the probe could not read.
 */
@Agent(type = "probe", capabilities = {"probe"})
public class ProbeAgent extends BaseAgent {

    private final DialogueCapability dialogue = new DialogueCapability(this);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @DialogueHandler(performatives = Performative.REQUEST)
    public void onCheck(DialogueMessage request) {
        CheckRequest check;
        try {
            check = request.contentAs(CheckRequest.class);
        } catch (RuntimeException e) {
            dialogue.failure(request, "unreadable check request: " + e.getMessage());
            return;
        }
        poison(check);
        dialogue.inform(request, check(check));
    }

    /**
     * The poison targets of Q6 (CRITERION §4). A check of {@code /t/poison-exit} terminates this
     * process with {@code System.exit}, a fault that is not an exception; a check of
     * {@code /t/poison-throw} fails the handler with an exception, the case {@code adapters/redis}
     * documents. The poison is in the request, not in the target's answer, so every delivery of the
     * same request does the same thing again.
     */
    private static void poison(CheckRequest check) {
        if (check.url().endsWith("/t/poison-exit")) {
            System.exit(1);
        }
        if (check.url().endsWith("/t/poison-throw")) {
            throw new IllegalStateException("poison target " + check.url());
        }
    }

    CheckOutcome check(CheckRequest check) {
        Instant started = Instant.now();
        int status = 0;
        String error = null;
        try {
            HttpRequest get = HttpRequest.newBuilder(URI.create(check.url()))
                    .timeout(Duration.ofMillis(check.timeoutMillis()))
                    .GET()
                    .build();
            status = http.send(get, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long latency = Duration.between(started, Instant.now()).toMillis();
        return new CheckOutcome(check.requestId(), check.targetId(), check.url(), getAgentId(),
                status, latency, error, started);
    }
}

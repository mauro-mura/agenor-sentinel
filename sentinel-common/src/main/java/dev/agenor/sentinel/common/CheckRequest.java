package dev.agenor.sentinel.common;

/**
 * What the coordinator asks a probe: check this URL once.
 *
 * <p>{@code requestId} is the application's own id for the request, written to the ledger before
 * the request is sent. It travels in the payload because the request protocol does not expose
 * Agenor's message id before sending.
 *
 * @friction Q2: {@code DialogueCapability.request(...)} builds and sends the REQUEST itself and
 *     returns only the future of the reply, so the ledger cannot record the message id before the
 *     send, as CRITERION §4 asks. The id is carried here instead, and the reply's
 *     {@code inReplyTo()} is recorded when a reply arrives.
 */
public record CheckRequest(String requestId, long targetId, String url, int timeoutMillis) {
}

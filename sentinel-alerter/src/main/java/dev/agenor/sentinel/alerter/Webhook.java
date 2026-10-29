package dev.agenor.sentinel.alerter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Posts each alert to a local webhook, served by sentinel-fixtures. Never to a third party. */
class Webhook {

    private final URI url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    Webhook(URI url) {
        this.url = url;
    }

    /** Returns the HTTP status, or 0 when the webhook could not be reached. */
    int post(AlertStore.Alert alert) {
        String body = """
                {"alertId":%d,"targetId":%d,"target":"%s","kind":"%s","requestId":"%s","raisedAt":"%s"}"""
                .formatted(alert.id(), alert.targetId(), alert.targetName(), alert.kind(),
                        alert.requestId(), alert.raisedAt());
        try {
            HttpRequest post = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return http.send(post, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}

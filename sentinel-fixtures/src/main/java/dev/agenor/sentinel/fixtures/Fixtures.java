package dev.agenor.sentinel.fixtures;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * The fake services the monitor checks, and the webhook it alerts.
 *
 * <ul>
 *   <li>{@code GET /t/{name}}: 200 when the target is up (the default), 503 when it is down.</li>
 *   <li>{@code POST /control/{name}/up} and {@code /down}: switch a target.</li>
 *   <li>{@code GET /control}: every target that has been switched, with its state.</li>
 *   <li>{@code POST /webhook}: records an alert body; {@code GET /webhook} returns them all, one
 *       JSON object per line, each with the time it was received.</li>
 * </ul>
 *
 * <p>State lives in memory: restarting this container resets every target to up and forgets the
 * webhook log. The alert table in PostgreSQL is the durable record.
 */
public final class Fixtures {

    private static final Map<String, Boolean> DOWN = new ConcurrentHashMap<>();
    private static final List<String> WEBHOOK = new CopyOnWriteArrayList<>();

    private Fixtures() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/t/", Fixtures::target);
        server.createContext("/control", Fixtures::control);
        server.createContext("/webhook", Fixtures::webhook);
        server.start();
        System.out.println("fixtures listening on " + port);
    }

    private static void target(HttpExchange ex) throws IOException {
        String name = ex.getRequestURI().getPath().substring("/t/".length());
        boolean down = DOWN.getOrDefault(name, false);
        respond(ex, down ? 503 : 200, down ? "down\n" : "up\n");
    }

    private static void control(HttpExchange ex) throws IOException {
        String[] parts = ex.getRequestURI().getPath().split("/");
        if (parts.length == 2 && "GET".equals(ex.getRequestMethod())) {
            StringBuilder body = new StringBuilder();
            DOWN.forEach((name, down) -> body.append(name).append(' ').append(down ? "down" : "up").append('\n'));
            respond(ex, 200, body.toString());
            return;
        }
        if (parts.length == 4 && "POST".equals(ex.getRequestMethod())
                && ("up".equals(parts[3]) || "down".equals(parts[3]))) {
            DOWN.put(parts[2], "down".equals(parts[3]));
            System.out.println(Instant.now() + " target " + parts[2] + " " + parts[3]);
            respond(ex, 200, parts[2] + " " + parts[3] + "\n");
            return;
        }
        respond(ex, 404, "usage: POST /control/{name}/up|down, GET /control\n");
    }

    private static void webhook(HttpExchange ex) throws IOException {
        if ("POST".equals(ex.getRequestMethod())) {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip();
            String line = "{\"receivedAt\":\"" + Instant.now() + "\",\"alert\":" + body + "}";
            WEBHOOK.add(line);
            System.out.println("webhook " + line);
            respond(ex, 204, null);
            return;
        }
        respond(ex, 200, String.join("\n", WEBHOOK) + (WEBHOOK.isEmpty() ? "" : "\n"));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        try (ex) {
            if (body == null) {
                ex.sendResponseHeaders(status, -1);
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}

package com.thediscordlist.jda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The one HTTP client every publisher shares.
 *
 * <p>There is a single client-side suspension rather than one per endpoint, because the server counts stats
 * and commands against one per-bot budget. Heartbeats bypass it: a dropped stats post is a
 * stale number, a dropped heartbeat is a false "down" on the public status page.
 */
final class ApiClient {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final long DEFAULT_RETRY_AFTER_MS = 60_000;
    static final long MAX_RETRY_AFTER_MS = 3_600_000;

    enum Kind { OK, PAYLOAD, AUTH, PLAN, CONFIG, THROTTLED, TRANSIENT, DROPPED }

    record Outcome(Kind kind, Integer status, JsonNode body, String error) {
        static Outcome of(Kind kind, Integer status) {
            return new Outcome(kind, status, null, null);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final String baseUrl;
    private final String token;
    private final String userAgent;
    volatile long suspendedUntil;

    ApiClient(String baseUrl, String token, String userAgent) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.userAgent = userAgent;
    }

    Outcome post(String path, Object body, boolean heartbeat) {
        if (!heartbeat && Publisher.now() < suspendedUntil) {
            return Outcome.of(Kind.DROPPED, null);
        }

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Kind.TRANSIENT, null, null, "Interrupted.");
        } catch (Exception e) {
            return new Outcome(Kind.TRANSIENT, null, null, describe(e));
        }

        // Server precedence is 404 -> 429 -> 401 -> 422 -> 403/409 -> 200. Gates fire after validation, so
        // plan state is only inferable from a clean payload.
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new Outcome(Kind.OK, status, parse(response.body()), null);
        }
        if (status == 401) {
            return Outcome.of(Kind.AUTH, status);
        }
        if (status == 403) {
            return Outcome.of(Kind.PLAN, status);
        }
        // 404 is "unknown id, or the id belongs to a server listing". It never means "bad token", so
        // probing cannot enumerate listings.
        if (status == 404 || status == 409) {
            return Outcome.of(Kind.CONFIG, status);
        }
        if (status == 429) {
            suspend(response.headers());
            return Outcome.of(Kind.THROTTLED, status);
        }
        if (status == 408 || status == 425 || status >= 500) {
            return new Outcome(Kind.TRANSIENT, status, null, "TheDiscordList responded " + status + ".");
        }
        return Outcome.of(Kind.PAYLOAD, status);
    }

    /** {@code HttpClient} is closeable from Java 21. Before that its threads end once it is unreachable. */
    void close() {
        if (http instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Best effort: the publishers are already stopped.
            }
        }
    }

    private void suspend(HttpHeaders headers) {
        double retryAfter = number(headers.firstValue("Retry-After").orElse(null));
        double reset = number(headers.firstValue("X-RateLimit-Reset").orElse(null));
        long waitMs = retryAfter > 0 ? Math.round(retryAfter * 1000) : DEFAULT_RETRY_AFTER_MS;
        // The reset is a wall-clock epoch, so only the time left until it carries over to the monotonic clock.
        if (reset > 0) {
            waitMs = Math.max(waitMs, Math.round(reset * 1000) - System.currentTimeMillis());
        }
        suspendedUntil = Publisher.now() + Math.min(waitMs, MAX_RETRY_AFTER_MS)
                + ThreadLocalRandom.current().nextLong(2_001);
    }

    private static double number(String value) {
        if (value == null) {
            return Double.NaN;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return Double.isFinite(parsed) ? parsed : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isEmpty() ? null : JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }
}

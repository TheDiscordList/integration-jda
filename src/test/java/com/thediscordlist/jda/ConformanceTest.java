package com.thediscordlist.jda;

import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Holds this SDK to the fixture every TheDiscordList SDK shares. The fixture is copied verbatim; never edit it here. */
class ConformanceTest {
    private static final JsonNode FIXTURE = load();

    private static JsonNode load() {
        try (InputStream in = ConformanceTest.class.getResourceAsStream("/sdk-conformance.json")) {
            return ApiClient.JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyWebhookVectorVerifiesAsTheFixtureSays() throws Exception {
        JsonNode webhooks = FIXTURE.path("webhook_signature");
        String secret = webhooks.path("secret").asText();
        assertEquals(Duration.ofSeconds(webhooks.path("tolerance_seconds").asLong()), Webhooks.DEFAULT_TOLERANCE);

        for (JsonNode vector : webhooks.path("vectors")) {
            byte[] body = vector.path("body").asText().getBytes(StandardCharsets.UTF_8);
            String header = vector.path("header").asText();
            Clock now = Clock.fixed(Instant.ofEpochSecond(vector.path("now").asLong()), ZoneOffset.UTC);
            if (vector.path("valid").asBoolean()) {
                Webhooks.verify(body, header, secret, Webhooks.DEFAULT_TOLERANCE, now);
            } else {
                assertThrows(Webhooks.VerificationException.class,
                        () -> Webhooks.verify(body, header, secret, Webhooks.DEFAULT_TOLERANCE, now),
                        vector.path("name").asText());
            }
        }
    }

    @Test
    void everyDefaultMatchesTheFixture() {
        JsonNode defaults = FIXTURE.path("defaults");
        AutopostOptions options = new AutopostOptions();
        assertEquals(defaults.path("base_url").asText(), AutopostOptions.DEFAULT_BASE_URL);
        assertEquals(defaults.at("/retry/attempts").asInt(), Publisher.MAX_ATTEMPTS);

        Map<String, Long> millis = new LinkedHashMap<>();
        millis.put("/timeout_seconds", ApiClient.TIMEOUT.toMillis());
        millis.put("/retry/backoff_base_seconds", options.backoffBaseMillis);
        millis.put("/retry/backoff_max_seconds", Publisher.BACKOFF_CAP_MS);
        millis.put("/rate_limit/default_seconds", ApiClient.DEFAULT_RETRY_AFTER_MS);
        millis.put("/rate_limit/max_seconds", ApiClient.MAX_RETRY_AFTER_MS);
        millis.put("/rate_limit/jitter_max_seconds", ApiClient.RATE_LIMIT_JITTER_MS);
        millis.put("/forbidden_reprobe_seconds", Publisher.PLAN_REPROBE_MS);
        millis.put("/stats/first_delay_seconds", Publisher.Stats.FIRST_POST_DELAY_MS);
        millis.put("/stats/interval_seconds", options.statsInterval.toMillis());
        millis.put("/stats/debounce_seconds", options.statsDebounce.toMillis());
        millis.put("/stats/min_gap_seconds", options.statsMinGap.toMillis());
        millis.put("/stats/force_after_seconds", Publisher.Stats.FORCE_AFTER_MS);
        millis.put("/heartbeat/interval_seconds", options.heartbeatInterval.toMillis());
        millis.put("/heartbeat/jitter_max_seconds", Publisher.Heartbeat.JITTER_MS);
        millis.put("/commands/first_delay_seconds", Publisher.Commands.DELAY_MS);
        millis.put("/commands/retry_seconds", options.commandsRetryMillis);
        millis.forEach((pointer, actual) -> assertEquals(defaults.at(pointer).asLong() * 1000, actual, pointer));
    }

    @Test
    void shardStatesMatchTheFixture() {
        Set<String> expected = new HashSet<>();
        FIXTURE.path("shard_states").forEach(state -> expected.add(state.asText()));

        assertEquals(expected, Arrays.stream(JDA.Status.values()).map(TheDiscordList::status).collect(Collectors.toSet()));
    }
}

package com.thediscordlist.jda;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Verifies TheDiscordList's inbound webhooks.
 *
 * <p>This class needs only the JDK and Jackson, which every supported Discord library already brings, so a
 * webhook-only service can use it without logging a bot in.
 *
 * <pre>{@code
 * try {
 *     Webhooks.Event event = Webhooks.verify(rawBody, request.getHeader(Webhooks.SIGNATURE_HEADER), secret);
 * } catch (Webhooks.VerificationException e) {
 *     return 400; // e.code() says why
 * }
 * }</pre>
 */
public final class Webhooks {
    public static final String SIGNATURE_HEADER = "X-TDL-Signature";
    public static final String EVENT_HEADER = "X-TDL-Event";
    public static final String DELIVERY_HEADER = "X-TDL-Delivery";
    public static final String ATTEMPT_HEADER = "X-TDL-Attempt";
    public static final Duration DEFAULT_TOLERANCE = Duration.ofSeconds(300);

    private Webhooks() {}

    /** Why a delivery failed verification. */
    public enum Code { MISSING_SIGNATURE, MALFORMED_SIGNATURE, STALE_TIMESTAMP, INVALID_SIGNATURE, MALFORMED_BODY }

    /** A delivery that must be answered with a 400. */
    public static final class VerificationException extends Exception {
        private static final long serialVersionUID = 1L;

        private final Code code;

        VerificationException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    /**
     * The listing an event is about.
     *
     * @param id   the guild id for a server, the application id for a bot
     * @param type {@code "server"} or {@code "bot"}
     */
    public record Listing(String id, String type, String name, String url) {}

    /**
     * A verified delivery.
     *
     * @param id        equals {@code X-TDL-Delivery} and is stable across retries; deduplicate on it
     * @param event     {@code listing.bumped}, {@code listing.upvoted}, {@code server.joined}, {@code bot.added},
     *                  {@code bot.stats_reported}, {@code webhook.ping}, or a newer name this version does not
     *                  know yet
     * @param createdAt ISO 8601 enqueue time, not send time
     * @param data      the event-specific fields
     * @param raw       the whole parsed body
     */
    public record Event(String id, String event, String createdAt, Listing listing, JsonNode data, JsonNode raw) {}

    /** Verifies with the default five minute tolerance. */
    public static Event verify(byte[] rawBody, String signatureHeader, String secret) throws VerificationException {
        return verify(rawBody, signatureHeader, secret, DEFAULT_TOLERANCE, Clock.systemUTC());
    }

    /** Verifies a body already read as UTF-8 text. Prefer the {@code byte[]} overload. */
    public static Event verify(String rawBody, String signatureHeader, String secret) throws VerificationException {
        return verify(rawBody.getBytes(StandardCharsets.UTF_8), signatureHeader, secret);
    }

    /**
     * Verifies {@code X-TDL-Signature} and returns the parsed envelope.
     *
     * <p>{@code rawBody} must be the exact bytes that arrived. Re-serialising a parsed body changes escaping and
     * key order, so it can never verify: the signature covers bytes, not meaning.
     *
     * @param tolerance two-sided freshness window around the signed timestamp
     * @param clock     the current time, injectable for tests
     */
    public static Event verify(byte[] rawBody, String signatureHeader, String secret, Duration tolerance, Clock clock)
            throws VerificationException {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("A webhook secret is required.");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new VerificationException(Code.MISSING_SIGNATURE, "Missing " + SIGNATURE_HEADER + " header.");
        }

        // Split on the first '=' only: a value may legitimately contain '='.
        String timestamp = null;
        String signature = null;
        for (String part : signatureHeader.split(",")) {
            int eq = part.indexOf('=');
            if (eq == -1) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("t") && timestamp == null) {
                timestamp = value;
            } else if (key.equals("v1") && signature == null && !value.isEmpty()) {
                signature = value;
            }
        }

        long seconds;
        try {
            seconds = Long.parseLong(timestamp == null ? "" : timestamp);
        } catch (NumberFormatException e) {
            seconds = Long.MIN_VALUE;
        }
        if (seconds == Long.MIN_VALUE || signature == null) {
            throw new VerificationException(Code.MALFORMED_SIGNATURE, "Malformed " + SIGNATURE_HEADER + " header.");
        }

        // Two-sided, so a sender skewed into the future is caught too. Checked against t, never created_at,
        // which is stamped at enqueue time and can be over an hour older on a retry.
        if (Math.abs(clock.millis() - seconds * 1000.0) > tolerance.toMillis()) {
            throw new VerificationException(Code.STALE_TIMESTAMP, "Webhook timestamp outside the tolerance window.");
        }

        String expected = sign(secret, timestamp, rawBody);
        // Compare hex strings in constant time; MessageDigest.isEqual does not short-circuit.
        if (!MessageDigest.isEqual(
                signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII))) {
            throw new VerificationException(Code.INVALID_SIGNATURE, "Webhook signature did not match.");
        }

        JsonNode root;
        try {
            root = ApiClient.JSON.readTree(rawBody);
        } catch (Exception e) {
            root = null;
        }
        if (root == null || !root.isObject()) {
            throw new VerificationException(Code.MALFORMED_BODY, "Webhook body was not a JSON object.");
        }

        JsonNode listing = root.path("listing");
        return new Event(
                text(root, "id"),
                text(root, "event"),
                text(root, "created_at"),
                listing.isObject()
                        ? new Listing(text(listing, "id"), text(listing, "type"), text(listing, "name"), text(listing, "url"))
                        : null,
                root.path("data"),
                root);
    }

    /** {@code hex(HMAC_SHA256(secret, "<t>.<raw body>"))}. */
    static String sign(String secret, String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

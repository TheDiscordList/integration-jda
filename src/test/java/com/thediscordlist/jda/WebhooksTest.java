package com.thediscordlist.jda;

import com.thediscordlist.jda.Webhooks.Code;
import com.thediscordlist.jda.Webhooks.VerificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhooksTest {
    /** The shared vector, computed with the server's own PHP formula. */
    private static final String SECRET = "whsec_test";
    private static final long T = 1_700_000_000L;
    private static final String BODY = "{\"id\":\"d1\",\"event\":\"webhook.ping\",\"created_at\":\"2023-11-14T22:13:20+00:00\","
            + "\"listing\":{\"id\":\"1\",\"type\":\"bot\",\"name\":\"Bot\",\"url\":\"https://thediscordlist.com/bots/1\"},"
            + "\"data\":{\"message\":\"hi\"}}";
    private static final String VECTOR_V1 = "b6eeea31d04c3f86990dcbb706dfaf988c9ff2fc234316ae77e168831127dc59";

    private static Clock at(long seconds) {
        return Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
    }

    private static Webhooks.Event verify(String body, String header, String secret, long nowSeconds)
            throws VerificationException {
        return Webhooks.verify(body.getBytes(StandardCharsets.UTF_8), header, secret, Webhooks.DEFAULT_TOLERANCE, at(nowSeconds));
    }

    private static Code failure(String body, String header, String secret, long nowSeconds) {
        return assertThrows(VerificationException.class, () -> verify(body, header, secret, nowSeconds)).code();
    }

    private static String hmac(String secret, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sharedVectorMatches() throws Exception {
        String computed = hmac(SECRET, T + "." + BODY);

        assertEquals(VECTOR_V1, computed);
        assertEquals(VECTOR_V1, Webhooks.sign(SECRET, Long.toString(T), BODY.getBytes(StandardCharsets.UTF_8)));

        Webhooks.Event event = verify(BODY, "t=1700000000,v1=" + VECTOR_V1, SECRET, T);
        assertEquals("d1", event.id());
        assertEquals("webhook.ping", event.event());
        assertEquals("2023-11-14T22:13:20+00:00", event.createdAt());
        assertEquals(new Webhooks.Listing("1", "bot", "Bot", "https://thediscordlist.com/bots/1"), event.listing());
        assertEquals("hi", event.data().path("message").asText());
    }

    @Test
    void stringBodiesAndTheDefaultClockWork() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String header = "t=" + now + ",v1=" + hmac(SECRET, now + "." + BODY);

        assertEquals("d1", Webhooks.verify(BODY, header, SECRET).id());
    }

    @Test
    void tamperedBodyFails() {
        String tampered = BODY.replace("\"hi\"", "\"ho\"");
        assertEquals(Code.INVALID_SIGNATURE, failure(tampered, "t=1700000000,v1=" + VECTOR_V1, SECRET, T));
    }

    @Test
    void wrongSecretFails() {
        assertEquals(Code.INVALID_SIGNATURE, failure(BODY, "t=1700000000,v1=" + VECTOR_V1, "whsec_other", T));
    }

    @Test
    void staleTimestampsFailInBothDirections() {
        String header = "t=1700000000,v1=" + VECTOR_V1;
        assertEquals(Code.STALE_TIMESTAMP, failure(BODY, header, SECRET, T + 301));
        assertEquals(Code.STALE_TIMESTAMP, failure(BODY, header, SECRET, T - 301));
    }

    @Test
    void theToleranceEdgeStillVerifies() throws Exception {
        String header = "t=1700000000,v1=" + VECTOR_V1;
        verify(BODY, header, SECRET, T + 300);
        verify(BODY, header, SECRET, T - 300);
    }

    @Test
    void aCustomToleranceIsHonoured() {
        assertEquals(Code.STALE_TIMESTAMP, assertThrows(VerificationException.class, () -> Webhooks.verify(
                BODY.getBytes(StandardCharsets.UTF_8), "t=1700000000,v1=" + VECTOR_V1, SECRET,
                Duration.ofSeconds(10), at(T + 11))).code());
    }

    @Test
    void missingHeaderFails() {
        assertEquals(Code.MISSING_SIGNATURE, failure(BODY, null, SECRET, T));
        assertEquals(Code.MISSING_SIGNATURE, failure(BODY, "", SECRET, T));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "garbage",
        "t=1700000000",
        "v1=" + VECTOR_V1,
        "t=abc,v1=" + VECTOR_V1,
        "t=,v1=" + VECTOR_V1,
        "t=1700000000,v1=",
        "t=1.7e9,v1=" + VECTOR_V1,
    })
    void malformedHeadersFail(String header) {
        assertEquals(Code.MALFORMED_SIGNATURE, failure(BODY, header, SECRET, T));
    }

    @Test
    void headerValuesMayContainExtraEquals() throws Exception {
        verify(BODY, "t=1700000000,v0=abc==,v1=" + VECTOR_V1 + ",x=a=b=c", SECRET, T);
        verify(BODY, " t = 1700000000 , v1 = " + VECTOR_V1, SECRET, T);
        assertEquals(Code.INVALID_SIGNATURE, failure(BODY, "t=1700000000,v1=" + VECTOR_V1 + "=", SECRET, T));
    }

    @Test
    void theFirstTimestampAndFirstNonEmptySignatureWin() throws Exception {
        verify(BODY, "t=1700000000,t=1,v1=,v1=" + VECTOR_V1 + ",v1=deadbeef", SECRET, T);
        assertEquals(Code.INVALID_SIGNATURE, failure(BODY, "t=1700000000,v1=deadbeef,v1=" + VECTOR_V1, SECRET, T));
    }

    @Test
    void uppercaseHexVerifies() throws Exception {
        verify(BODY, "t=1700000000,v1=" + VECTOR_V1.toUpperCase(Locale.ROOT), SECRET, T);
    }

    @Test
    void aSignedNonJsonBodyIsMalformed() throws Exception {
        String body = "not json";
        String header = "t=1700000000,v1=" + hmac(SECRET, T + "." + body);
        assertEquals(Code.MALFORMED_BODY, failure(body, header, SECRET, T));

        String array = "[1,2]";
        assertEquals(Code.MALFORMED_BODY, failure(array, "t=1700000000,v1=" + hmac(SECRET, T + "." + array), SECRET, T));
    }

    @Test
    void unknownEventsParse() throws Exception {
        String body = "{\"id\":\"d2\",\"event\":\"listing.renamed\",\"created_at\":\"2023-11-14T22:13:20+00:00\","
                + "\"listing\":{\"id\":\"9\",\"type\":\"server\",\"name\":\"S\",\"url\":\"u\"},\"data\":{\"new\":true}}";
        Webhooks.Event event = verify(body, "t=1700000000,v1=" + hmac(SECRET, T + "." + body), SECRET, T);

        assertEquals("listing.renamed", event.event());
        assertEquals("server", event.listing().type());
        assertEquals(true, event.data().path("new").asBoolean());
    }

    @Test
    void reserialisedBodiesNeverVerify() throws Exception {
        String reserialised = ApiClient.JSON.writeValueAsString(ApiClient.JSON.readTree(BODY)).replace("/", "\\/");
        assertEquals(Code.INVALID_SIGNATURE, failure(reserialised, "t=1700000000,v1=" + VECTOR_V1, SECRET, T));
    }
}

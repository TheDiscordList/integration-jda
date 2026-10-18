package com.thediscordlist.jda;

import com.thediscordlist.jda.Autoposter.Failure;
import com.thediscordlist.jda.Autoposter.Kind;
import com.thediscordlist.jda.Autoposter.PostResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoposterTest {
    private static final Topology SINGLE = Topology.allLocal(2);

    private StubServer server;
    private FakeBot bot;
    private Recorder events;
    private Autoposter poster;

    @BeforeEach
    void setUp() throws Exception {
        server = new StubServer();
        bot = new FakeBot();
        events = new Recorder();
    }

    @AfterEach
    void tearDown() {
        if (poster != null) {
            poster.close();
        }
        server.close();
    }

    private Autoposter build(Topology topology, AutopostOptions options) {
        options.baseUrl(server.baseUrl()).listener(events).statsMinGap(Duration.ZERO);
        options.backoffBaseMillis = 1;
        poster = Autoposter.create("tdl_token", options, "TheDiscordList-test/1.0", bot);
        poster.build("161660517914509312", topology);
        return poster;
    }

    private Autoposter build() {
        return build(SINGLE, new AutopostOptions());
    }

    private static PostResult await(java.util.concurrent.CompletableFuture<PostResult> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }

    @Test
    void statsRequestCarriesHeadersAndBody() throws Exception {
        PostResult result = await(build().postStats());

        assertTrue(result.ok());
        assertEquals(200, result.status());
        StubServer.Request request = server.take();
        assertEquals("POST", request.method());
        assertEquals("/api/bots/161660517914509312/stats", request.path());
        assertEquals("Bearer tdl_token", request.headers().getFirst("Authorization"));
        assertEquals("application/json", request.headers().getFirst("Content-Type"));
        assertEquals("TheDiscordList-test/1.0", request.headers().getFirst("User-Agent"));
        assertEquals(ApiClient.JSON.readTree("{\"server_count\":42,\"shard_count\":2}"), request.json());
        assertEquals(List.of("posted:STATS:200"), events.log);
    }

    @Test
    void heartbeatPayloadOmitsAbsentFields() throws Exception {
        bot.shards = List.of(Bot.shard(0, "ready", 41L, 10), Bot.shard(1, "connecting", null, null),
                Bot.shard(2, "ready", 9_999_999L, 3), Bot.shard(3, "connecting", -1L, 0));

        assertTrue(await(build(new Topology(2, 3, false, 4, false), new AutopostOptions()).postHeartbeat()).ok());

        StubServer.Request request = server.take();
        assertEquals("/api/bots/161660517914509312/heartbeat", request.path());
        assertEquals(ApiClient.JSON.readTree("""
                {"cluster":2,"cluster_count":3,"shards":[
                  {"id":0,"status":"ready","latency_ms":41,"guilds":10},
                  {"id":1,"status":"connecting"},
                  {"id":2,"status":"ready","latency_ms":600000,"guilds":3},
                  {"id":3,"status":"connecting","guilds":0}]}"""), request.json());
        assertFalse(request.body().contains("null"));
    }

    @Test
    void heartbeatRetunesFromTheResponse() throws Exception {
        server.respond(200, "{\"cluster\":0,\"status\":\"up\",\"shard_count\":1,\"next_beat_within_seconds\":120}");
        build();

        await(poster.postHeartbeat());

        assertEquals(40_000, poster.heartbeat().interval());
    }

    @Test
    void commandsArePostedAndEmptyListsSkipped() throws Exception {
        bot.commands = List.of(Map.of("name", "ping", "description", "Pong"));
        assertTrue(await(build().postCommands()).ok());
        assertEquals(ApiClient.JSON.readTree("{\"commands\":[{\"name\":\"ping\",\"description\":\"Pong\"}]}"),
                server.take().json());

        bot.commands = List.of();
        assertEquals("empty", await(poster.postCommands()).skipped());
        server.assertQuiet(200);
    }

    @Test
    void emptyCommandsArePostedWhenOptedIn() throws Exception {
        bot.commands = List.of();
        assertTrue(await(build(SINGLE, new AutopostOptions().postEmptyCommands(true)).postCommands()).ok());
        assertEquals("{\"commands\":[]}", server.take().body());
    }

    @Test
    void unauthorizedDisablesEveryPublisherAndReportsOnce() throws Exception {
        server.respond(401, "{\"message\":\"Unauthenticated.\"}");
        PostResult result = await(build().postStats());

        assertEquals(401, result.status());
        assertEquals(List.of("disabled:STATS:401:unauthorized"), events.log);
        assertEquals("unauthorized", poster.stats().disabledReason);
        assertEquals("unauthorized", poster.heartbeat().disabledReason);
        assertEquals("unauthorized", poster.commands().disabledReason);

        // A manual post re-arms the publisher it targets.
        assertTrue(await(poster.postHeartbeat()).ok());
        assertNull(poster.heartbeat().disabledReason);
    }

    @Test
    void forbiddenHeartbeatSuspendsInsteadOfDisabling() throws Exception {
        server.respond(403, "{}").respond(403, "{}");
        build();

        PostResult first = await(poster.postHeartbeat());
        await(poster.postHeartbeat());

        assertEquals(403, first.status());
        assertEquals("plan", first.skipped());
        assertNull(poster.heartbeat().disabledReason);
        assertTrue(poster.heartbeat().suspendedUntil >= Publisher.now() + Duration.ofMinutes(29).toMillis());
        assertEquals(List.of("error:HEARTBEAT:403:false"), events.log);
    }

    @Test
    void shardTransitionsBeatNoSoonerThanTenSecondsAfterTheLastBeat() throws Exception {
        build();

        poster.startHeartbeat();
        server.take();
        poster.shardsChanged();

        server.assertQuiet(2_000);
    }

    @Test
    void shardTransitionsWaitOutAPlanSuspension() throws Exception {
        server.respond(403, "{}");
        build();

        poster.startHeartbeat();
        server.take();
        poster.shardsChanged();

        server.assertQuiet(1_500);
    }

    @Test
    void notFoundDisablesThatPublisherOnly() throws Exception {
        server.respond(404, "{\"message\":\"Not found.\"}");
        PostResult result = await(build().postStats());

        assertEquals(404, result.status());
        assertEquals(List.of("disabled:STATS:404:not-found"), events.log);
        assertNull(poster.heartbeat().disabledReason);
        assertTrue(await(poster.postHeartbeat()).ok());
    }

    @Test
    void conflictDisablesCommandsAndAManualPostRearms() throws Exception {
        bot.commands = List.of(Map.of("name", "ping"));
        server.respond(409, "{}");
        build();

        assertEquals(409, await(poster.postCommands()).status());
        assertEquals(List.of("disabled:COMMANDS:409:conflict"), events.log);
        assertEquals("conflict", poster.commands().disabledReason);

        server.take();
        assertTrue(await(poster.postCommands()).ok());
        server.take();
    }

    @Test
    void rateLimitSuspendsStatsAndCommandsButNotHeartbeats() throws Exception {
        bot.commands = List.of(Map.of("name", "ping"));
        server.respond(429, "{}", "Retry-After", "120");
        build();

        assertEquals("throttled", await(poster.postStats()).skipped());
        server.take();
        long now = Publisher.now();
        assertTrue(poster.api().suspendedUntil >= now + 115_000);
        assertTrue(poster.api().suspendedUntil <= now + 123_000);

        assertEquals("suspended", await(poster.postCommands()).skipped());
        server.assertQuiet(200);

        assertTrue(await(poster.postHeartbeat()).ok());
        assertEquals("/api/bots/161660517914509312/heartbeat", server.take().path());
    }

    @Test
    void rateLimitHonoursTheResetHeader() throws Exception {
        long reset = System.currentTimeMillis() / 1000 + 300;
        server.respond(429, "{}", "Retry-After", "1", "X-RateLimit-Reset", Long.toString(reset));

        long now = Publisher.now();
        await(build().postStats());

        assertTrue(poster.api().suspendedUntil >= now + 295_000);
        assertTrue(poster.api().suspendedUntil <= Publisher.now() + 303_000);
    }

    @Test
    void rateLimitWithoutRetryAfterWaitsAMinute() throws Exception {
        server.respond(429, "{}");
        await(build().postStats());

        assertTrue(poster.api().suspendedUntil >= Publisher.now() + 55_000);
    }

    @Test
    void rateLimitSuspensionsAreCappedAtAnHour() throws Exception {
        long reset = System.currentTimeMillis() / 1000 + 86_400;
        server.respond(429, "{}", "Retry-After", "86400", "X-RateLimit-Reset", Long.toString(reset));

        await(build().postStats());

        assertTrue(poster.api().suspendedUntil <= Publisher.now() + ApiClient.MAX_RETRY_AFTER_MS + 2_001);
    }

    @Test
    void serverErrorsAreRetried() throws Exception {
        server.respond(503, "").respond(502, "");

        PostResult result = await(build().postStats());

        assertTrue(result.ok());
        server.take();
        server.take();
        server.take();
        assertEquals(List.of("posted:STATS:200"), events.log);
    }

    @Test
    void serverErrorsGiveUpAfterThreeAttempts() throws Exception {
        server.respond(500, "").respond(500, "").respond(500, "");

        PostResult result = await(build().postStats());

        assertEquals(500, result.status());
        assertEquals(3, server.pending());
        assertEquals(List.of("error:STATS:500:true"), events.log);
        assertNull(poster.stats().disabledReason);
    }

    @Test
    void requestTimeoutsAndTooEarlyAreRetried() throws Exception {
        server.respond(408, "").respond(425, "");

        assertTrue(await(build().postStats()).ok());
        assertEquals(3, server.pending());
    }

    @Test
    void otherClientErrorsAreNotRetried() throws Exception {
        server.respond(405, "{}");

        PostResult result = await(build().postStats());

        assertEquals(405, result.status());
        assertEquals(1, server.pending());
        assertEquals(List.of("error:STATS:405:false"), events.log);
        assertNull(poster.stats().disabledReason);
    }

    @Test
    void networkFailuresAreTransient() throws Exception {
        build();
        server.close();

        PostResult result = await(poster.postStats());

        assertFalse(result.ok());
        assertNull(result.status());
        assertEquals(List.of("error:STATS:null:true"), events.log);
    }

    @Test
    void payloadErrorsAreReportedOnceAndStayEnabled() throws Exception {
        server.respond(422, "{}").respond(422, "{}");
        build();

        await(poster.postStats());
        await(poster.postStats());

        assertEquals(List.of("error:STATS:422:false"), events.log);
        assertNull(poster.stats().disabledReason);
    }

    @Test
    void minGapHoldsManualStatsPosts() throws Exception {
        AutopostOptions options = new AutopostOptions().baseUrl(server.baseUrl()).listener(events)
                .statsMinGap(Duration.ofMinutes(1));
        poster = Autoposter.create("tdl_token", options, "ua", bot);
        poster.build("1", SINGLE);

        assertTrue(await(poster.postStats()).ok());
        bot.serverCount = 43;
        assertEquals("min-gap", await(poster.postStats()).skipped());
        server.take();
        server.assertQuiet(200);
    }

    @Test
    void unchangedCountsAreNotReposted() throws Exception {
        build();
        assertTrue(await(poster.postStats()).ok());
        assertEquals("unchanged", await(poster.postStats()).skipped());
    }

    @Test
    void missingTokenIsInert() throws Exception {
        for (String token : new String[] {null, "", "   "}) {
            Autoposter inert = Autoposter.create(token, new AutopostOptions().baseUrl(server.baseUrl()), "ua", bot);
            inert.build("1", SINGLE);
            inert.startAll();

            assertTrue(inert.isInert());
            assertEquals("disabled", await(inert.postStats()).skipped());
            assertEquals("disabled", await(inert.postHeartbeat()).skipped());
            assertEquals("disabled", await(inert.postCommands()).skipped());
            inert.close();
        }
        server.assertQuiet(200);
    }

    @Test
    void pastedBearerPrefixesAndWhitespaceAreStripped() throws Exception {
        AutopostOptions options = new AutopostOptions().baseUrl(server.baseUrl()).listener(events);
        poster = Autoposter.create("  Bearer tdl_token\n", options, "ua", bot);
        poster.build("1", SINGLE);

        assertTrue(await(poster.postHeartbeat()).ok());
        assertEquals("Bearer tdl_token", server.take().headers().getFirst("Authorization"));
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void closeShutsTheHttpClientDown() throws Exception {
        build().close();

        assertEquals(ApiClient.Kind.TRANSIENT, poster.api().post("/bots/1/heartbeat", Map.of(), true).kind());
        server.assertQuiet(200);
    }

    @Test
    void unknownClusterCountNeverSendsAHeartbeat() throws Exception {
        build(new Topology(0, null, true, 4, false), new AutopostOptions());
        poster.startAll();

        server.assertQuiet(500);
        assertEquals(List.of("disabled:HEARTBEAT:null:cluster-count-unknown"), events.log);
        assertEquals("cluster-count-unknown", await(poster.postHeartbeat()).skipped());
        assertEquals("unmeasurable", await(poster.postStats()).skipped());
        server.assertQuiet(200);
    }

    @Test
    void knownClusterCountHeartbeatsImmediately() throws Exception {
        build(SINGLE, new AutopostOptions());
        poster.startAll();

        assertEquals("/api/bots/161660517914509312/heartbeat", server.take().path());
    }

    @Test
    void serverCountSupplierFeedsStatsWhenTheCacheIsPartial() throws Exception {
        build(new Topology(0, 4, true, 8, false), new AutopostOptions().serverCount(() -> 1_000));

        assertTrue(await(poster.postStats()).ok());
        assertEquals(ApiClient.JSON.readTree("{\"server_count\":1000,\"shard_count\":8}"), server.take().json());
    }

    @Test
    void onlyTheStatsPublisherPostsStats() throws Exception {
        build(new Topology(1, 4, false, 8, false), new AutopostOptions().serverCount(() -> 1_000));

        assertEquals("not-elected", await(poster.postStats()).skipped());
        server.assertQuiet(200);
    }

    @Test
    void throwingListenersAndSuppliersNeverReachTheCaller() throws Exception {
        AutopostOptions options = new AutopostOptions().baseUrl(server.baseUrl())
                .serverCount(() -> {
                    throw new IllegalStateException("supplier boom");
                })
                .listener(new Autoposter.Listener() {
                    @Override
                    public void onError(Failure failure) {
                        throw new IllegalStateException("listener boom");
                    }
                });
        poster = Autoposter.create("tdl_token", options, "ua", bot);
        poster.build("1", SINGLE);

        assertEquals("unmeasurable", await(poster.postStats()).skipped());
    }

    @Test
    void closedPostersAnswerWithoutSending() throws Exception {
        build().close();

        assertEquals("closed", await(poster.postStats()).skipped());
        server.assertQuiet(200);
    }

    @Test
    void versionMatchesThePom() {
        assertEquals(System.getProperty("project.version"), Autoposter.VERSION);
    }

    static final class FakeBot implements Bot {
        volatile List<Map<String, Object>> shards = List.of(Bot.shard(0, "ready", 40L, 21), Bot.shard(1, "ready", 45L, 21));
        volatile Integer serverCount = 42;
        volatile List<Map<String, Object>> commands = List.of();

        @Override
        public List<Map<String, Object>> shards() {
            return shards;
        }

        @Override
        public Integer serverCount() {
            return serverCount;
        }

        @Override
        public List<Map<String, Object>> commands(String botId) {
            return new ArrayList<>(commands);
        }
    }

    static final class Recorder implements Autoposter.Listener {
        final List<String> log = new CopyOnWriteArrayList<>();

        @Override
        public void onPosted(Kind type, int status) {
            log.add("posted:" + type + ":" + status);
        }

        @Override
        public void onError(Failure failure) {
            log.add("error:" + failure.type() + ":" + failure.status() + ":" + failure.retryable());
        }

        @Override
        public void onDisabled(Kind type, Integer status, String reason) {
            log.add("disabled:" + type + ":" + status + ":" + reason);
        }
    }
}

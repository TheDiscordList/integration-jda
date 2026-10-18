package com.thediscordlist.jda;

import com.fasterxml.jackson.databind.JsonNode;
import com.thediscordlist.jda.Autoposter.Failure;
import com.thediscordlist.jda.Autoposter.Kind;
import com.thediscordlist.jda.Autoposter.PostResult;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * One publisher state machine.
 *
 * <p>Each publisher owns a single daemon thread, and every piece of its state is touched only from that
 * thread: timers, manual posts and library events are all funnelled onto it. Nothing thrown in here reaches
 * the host bot; failures become {@link Failure} events.
 */
abstract class Publisher {
    static final int MAX_ATTEMPTS = 3;
    static final long PLAN_REPROBE_MS = 1_800_000;
    private static final long BACKOFF_CAP_MS = 30_000;
    private static final long ORIGIN = System.nanoTime();

    record Context(ApiClient api, Bot bot, Topology topology, String botId, AutopostOptions options) {}

    final Kind kind;
    final Autoposter owner;
    final Context ctx;
    volatile String disabledReason;
    volatile long suspendedUntil;

    private final ScheduledThreadPoolExecutor executor;
    private final Set<Integer> reported = new HashSet<>();
    private ScheduledFuture<?> timer;

    Publisher(Kind kind, Autoposter owner, Context ctx) {
        this.kind = kind;
        this.owner = owner;
        this.ctx = ctx;
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "thediscordlist-" + kind.name().toLowerCase(Locale.ROOT));
            thread.setDaemon(true);
            return thread;
        });
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /** Monotonic milliseconds, immune to wall-clock jumps. */
    static long now() {
        return (System.nanoTime() - ORIGIN) / 1_000_000;
    }

    abstract void start();

    /** A manual post. It re-arms a publisher that disabled itself. */
    abstract PostResult post();

    final void execute(Runnable task) {
        try {
            executor.execute(() -> guarded(task));
        } catch (RejectedExecutionException closed) {
            // Closed: nothing left to do.
        }
    }

    final CompletableFuture<PostResult> submit() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return post();
                } catch (Throwable error) {
                    unexpected(error);
                    return PostResult.skipped("error");
                }
            }, executor);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.completedFuture(PostResult.skipped("closed"));
        }
    }

    final void schedule(long delayMs, Runnable run) {
        clear();
        try {
            timer = executor.schedule(() -> guarded(run), Math.max(delayMs, 0), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException closed) {
            timer = null;
        }
    }

    final void clear() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    final boolean scheduled() {
        return timer != null && !timer.isDone();
    }

    final void shutdown() {
        executor.shutdownNow();
    }

    private void guarded(Runnable run) {
        try {
            run.run();
        } catch (Throwable error) {
            unexpected(error);
        }
    }

    final void unexpected(Throwable error) {
        owner.error(new Failure(kind, null, false, ApiClient.describe(error)));
    }

    /** Emits {@code disabled} exactly once and stops the loop. */
    final void disable(Integer status, String reason) {
        clear();
        if (disabledReason != null) {
            return;
        }
        disabledReason = reason;
        owner.disabled(kind, status, reason);
    }

    /** Stops without an event: a sibling publisher already reported why. */
    final void silence(String reason) {
        if (disabledReason == null) {
            disabledReason = reason;
        }
        execute(this::clear);
    }

    final void rearm() {
        disabledReason = null;
    }

    private void reportOnce(int status, String message) {
        if (reported.add(status)) {
            owner.error(new Failure(kind, status, false, message));
        }
    }

    /**
     * Sends one request, retrying only transient failures. A retry that would outlast the next scheduled run
     * is dropped: that run carries fresher data, and a failed request still burns rate-limit budget.
     */
    final PostResult send(String path, Object body, boolean heartbeat, long nextTickMs) {
        ApiClient.Outcome outcome = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            outcome = ctx.api().post(path, body, heartbeat);
            if (outcome.kind() != ApiClient.Kind.TRANSIENT || attempt == MAX_ATTEMPTS - 1) {
                break;
            }
            long delay = backoff(attempt);
            if (nextTickMs > 0 && delay >= nextTickMs) {
                break;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return react(outcome);
    }

    /** {@code min(base * 2^attempt, 30s)}, scaled into its upper half. */
    final long backoff(int attempt) {
        long ceiling = Math.min(ctx.options().backoffBaseMillis << attempt, BACKOFF_CAP_MS);
        return Math.round(ceiling * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
    }

    PostResult react(ApiClient.Outcome outcome) {
        Integer status = outcome.status();
        switch (outcome.kind()) {
            case OK:
                owner.posted(kind, status);
                return new PostResult(true, status, null);
            case DROPPED:
                return PostResult.skipped("suspended");
            case PAYLOAD:
                // Stays enabled: the next run may carry valid data.
                reportOnce(status, "TheDiscordList rejected the " + name() + " payload (" + status + ").");
                return new PostResult(false, status, null);
            case AUTH:
                disable(status, "unauthorized");
                owner.authFailed();
                return new PostResult(false, status, null);
            case PLAN:
                // Not a disable: the plan is derived per request, so a bot can gain it mid-run.
                suspendedUntil = now() + PLAN_REPROBE_MS;
                reportOnce(status, "The " + name() + " endpoint needs a higher plan. Re-probing in 30 minutes.");
                return new PostResult(false, status, "plan");
            case CONFIG:
                disable(status, status == 409 ? "conflict" : "not-found");
                return new PostResult(false, status, null);
            case THROTTLED:
                return new PostResult(false, status, "throttled");
            default:
                owner.error(new Failure(kind, status, true, outcome.error()));
                return new PostResult(false, status, null);
        }
    }

    final String name() {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    static final class Stats extends Publisher {
        static final long FIRST_POST_DELAY_MS = 5_000;
        static final long REMEASURE_MS = 60_000;
        /** Republish an unchanged count this often, so a listing never looks abandoned. */
        static final long FORCE_AFTER_MS = 1_800_000;

        private boolean started;
        private boolean posted;
        private long lastPostAt;
        private Integer lastCount;
        private int lastShardCount;
        private long debounceCap;

        Stats(Autoposter owner, Context ctx) {
            super(Kind.STATS, owner, ctx);
        }

        @Override
        void start() {
            if (!ctx.topology().statsPublisher()) {
                return;
            }
            started = true;
            schedule(FIRST_POST_DELAY_MS, this::tick);
        }

        /** Trailing debounce, hard-capped so a join storm cannot starve the post. */
        void guildsChanged() {
            if (!started || disabledReason != null) {
                return;
            }
            long now = now();
            if (debounceCap == 0) {
                debounceCap = now + minGap();
            }
            schedule(Math.min(now + ctx.options().statsDebounce.toMillis(), debounceCap) - now, this::tick);
        }

        @Override
        PostResult post() {
            rearm();
            return publish(true);
        }

        private void tick() {
            publish(false);
        }

        private PostResult publish(boolean manual) {
            debounceCap = 0;
            if (!manual && disabledReason != null) {
                return PostResult.skipped("disabled");
            }
            if (!ctx.topology().statsPublisher()) {
                return PostResult.skipped("not-elected");
            }

            long now = now();
            if (!manual && now < suspendedUntil) {
                schedule(suspendedUntil - now, this::tick);
                return PostResult.skipped("plan");
            }
            // A hard floor. Join storms, mass reconnects and manual posts all route through it, so the
            // ceiling is structurally one request per gap.
            if (posted && now < lastPostAt + minGap()) {
                schedule(lastPostAt + minGap() - now, this::tick);
                return PostResult.skipped("min-gap");
            }

            Integer count = measure();
            if (count == null) {
                schedule(REMEASURE_MS, this::tick);
                return PostResult.skipped("unmeasurable");
            }

            int shardCount = ctx.topology().shardCount();
            // Every stats post re-indexes the listing and fires a webhook even when nothing moved, and the
            // server does not dedupe, so the skip has to happen here.
            if (count.equals(lastCount) && shardCount == lastShardCount && now() < lastPostAt + FORCE_AFTER_MS) {
                schedule(interval(), this::tick);
                return PostResult.skipped("unchanged");
            }

            posted = true;
            lastPostAt = now();
            // shard_count is always sent: the server stores `shard_count ?? null`, so omitting it erases it.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("server_count", count);
            body.put("shard_count", shardCount);
            PostResult result = send("/bots/" + ctx.botId() + "/stats", body, false, interval());
            if (result.ok()) {
                lastCount = count;
                lastShardCount = shardCount;
            }
            if (disabledReason == null) {
                schedule(interval(), this::tick);
            }
            return result;
        }

        private Integer measure() {
            IntSupplier supplier = ctx.options().serverCount;
            try {
                if (supplier != null) {
                    int value = supplier.getAsInt();
                    return value >= 0 ? value : null;
                }
                return ctx.topology().localIsTotal() ? ctx.bot().serverCount() : null;
            } catch (Throwable error) {
                unexpected(error);
                return null;
            }
        }

        private long interval() {
            return ctx.options().statsInterval.toMillis();
        }

        private long minGap() {
            return ctx.options().statsMinGap.toMillis();
        }
    }

    static final class Heartbeat extends Publisher {
        static final long MIN_INTERVAL_MS = 15_000;
        static final long MAX_INTERVAL_MS = 60_000;
        static final long JITTER_MS = 5_000;
        static final long BEAT_NOW_DELAY_MS = 1_000;
        static final long OUT_OF_BAND_GAP_MS = 10_000;

        private long interval;
        private boolean started;
        private boolean beatNowPending;
        private long nextTarget;
        private Long lastBeatAt;
        private Double nextBeatWithinSeconds;

        Heartbeat(Autoposter owner, Context ctx) {
            super(Kind.HEARTBEAT, owner, ctx);
            this.interval = Math.max(ctx.options().heartbeatInterval.toMillis(), 1);
        }

        long interval() {
            return interval;
        }

        @Override
        void start() {
            // Every beat deletes cluster rows with an id >= its cluster_count. A process that guessed 1 would
            // erase all of its siblings and their monitoring history, so an unknown count means silence.
            if (ctx.topology().clusterCount() == null) {
                disable(null, "cluster-count-unknown");
                return;
            }
            started = true;
            nextTarget = now();
            schedule(0, this::tick);
        }

        /**
         * An out-of-band beat after a shard transition, no sooner than ten seconds after the previous beat. Calls
         * inside the delay coalesce into one.
         */
        void beatNow() {
            if (!started || disabledReason != null || beatNowPending || now() < suspendedUntil) {
                return;
            }
            beatNowPending = true;
            long earliest = lastBeatAt == null ? 0 : lastBeatAt + OUT_OF_BAND_GAP_MS;
            schedule(Math.max(earliest - now(), BEAT_NOW_DELAY_MS), this::tick);
        }

        @Override
        PostResult post() {
            rearm();
            if (ctx.topology().clusterCount() == null) {
                return PostResult.skipped("cluster-count-unknown");
            }
            PostResult result = beat(true);
            if (started && disabledReason == null && !scheduled()) {
                scheduleNext(0);
            }
            return result;
        }

        private void tick() {
            beatNowPending = false;
            beat(false);
        }

        private PostResult beat(boolean manual) {
            if (!manual && disabledReason != null) {
                return PostResult.skipped("disabled");
            }

            List<Map<String, Object>> shards;
            try {
                shards = ctx.bot().shards();
            } catch (Throwable error) {
                unexpected(error);
                shards = List.of();
            }
            // Before login. The API requires at least one shard.
            if (shards.isEmpty()) {
                if (!manual) {
                    scheduleNext(0);
                }
                return PostResult.skipped("no-shards");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cluster", ctx.topology().clusterId());
            body.put("cluster_count", ctx.topology().clusterCount());
            body.put("shards", shards);
            lastBeatAt = now();
            PostResult result = send("/bots/" + ctx.botId() + "/heartbeat", body, true, interval);
            if (result.ok()) {
                retune();
            }
            if (!manual && disabledReason == null) {
                scheduleNext("throttled".equals(result.skipped()) ? interval : 0);
            }
            return result;
        }

        @Override
        PostResult react(ApiClient.Outcome outcome) {
            if (outcome.kind() == ApiClient.Kind.OK) {
                JsonNode seconds = outcome.body() == null ? null : outcome.body().get("next_beat_within_seconds");
                nextBeatWithinSeconds = seconds != null && seconds.isNumber() && seconds.asDouble() > 0
                        ? seconds.asDouble()
                        : null;
            }
            return super.react(outcome);
        }

        /**
         * The cadence follows {@code next_beat_within_seconds} rather than a constant, so a change to the
         * server's grace window retunes existing installs without a release.
         */
        private void retune() {
            if (nextBeatWithinSeconds != null) {
                long proposed = Math.round(nextBeatWithinSeconds / 3 * 1000);
                interval = Math.min(Math.max(proposed, MIN_INTERVAL_MS), MAX_INTERVAL_MS);
            }
        }

        /**
         * Absolute targets, so a slow request cannot drift the cadence and an out-of-band beat does not push
         * the next regular one back. After a long suspension the target catches up instead of bursting.
         */
        private void scheduleNext(long minDelayMs) {
            long now = now();
            if (nextTarget <= now) {
                nextTarget += ((now - nextTarget) / interval + 1) * interval;
            }
            long jitter = ThreadLocalRandom.current().nextLong(JITTER_MS + 1);
            long delay = Math.max(nextTarget + jitter - now, minDelayMs);
            schedule(Math.max(delay, Math.max(BEAT_NOW_DELAY_MS, suspendedUntil - now)), this::tick);
        }
    }

    static final class Commands extends Publisher {
        /** Offset from the first stats post: the two share one server-side rate budget. */
        static final long DELAY_MS = 10_000;

        Commands(Autoposter owner, Context ctx) {
            super(Kind.COMMANDS, owner, ctx);
        }

        /** Once. Commands change on deploy, and a deploy is a restart. */
        @Override
        void start() {
            if (ctx.topology().statsPublisher()) {
                schedule(DELAY_MS, this::publish);
            }
        }

        @Override
        PostResult post() {
            rearm();
            return publish();
        }

        private PostResult publish() {
            if (disabledReason != null) {
                return PostResult.skipped("disabled");
            }
            if (now() < suspendedUntil) {
                return PostResult.skipped("plan");
            }

            List<Map<String, Object>> commands;
            try {
                commands = ctx.bot().commands(ctx.botId());
            } catch (Throwable error) {
                unexpected(error);
                return PostResult.skipped("fetch-failed");
            }
            // An empty array CLEARS the listing's commands, so it is never sent by accident.
            if (commands.isEmpty() && !ctx.options().postEmptyCommands) {
                return PostResult.skipped("empty");
            }
            return send("/bots/" + ctx.botId() + "/commands", Map.of("commands", commands), false, 0);
        }
    }
}

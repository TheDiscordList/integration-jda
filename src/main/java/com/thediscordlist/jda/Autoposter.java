package com.thediscordlist.jda;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A running TheDiscordList integration, returned by {@link TheDiscordList#autopost}.
 *
 * <p>Nothing here throws at the host bot. The {@code postX()} methods never complete exceptionally, background
 * failures become {@link Listener} events, and {@link #close()} stops every timer and detaches every listener.
 * Each publisher runs on its own daemon thread, so listener callbacks arrive on those threads.
 */
public final class Autoposter implements AutoCloseable {
    /** Kept in step with the POM by a test. */
    public static final String VERSION = "0.2.0";

    private static final System.Logger LOG = System.getLogger(Autoposter.class.getPackageName());

    /** The three publishers. */
    public enum Kind { STATS, HEARTBEAT, COMMANDS }

    /**
     * The outcome of one post.
     *
     * @param ok      whether TheDiscordList accepted it
     * @param status  the HTTP status, or {@code null} when no response arrived
     * @param skipped why nothing was sent ({@code "unchanged"}, {@code "min-gap"}, {@code "suspended"},
     *                {@code "disabled"}, ...), or {@code null}
     */
    public record PostResult(boolean ok, Integer status, String skipped) {
        static PostResult skipped(String reason) {
            return new PostResult(false, null, reason);
        }
    }

    /**
     * A failed post or an unexpected error.
     *
     * @param type      the publisher it came from
     * @param status    the HTTP status, or {@code null}
     * @param retryable whether it was a transient failure that the next run retries
     * @param message   what went wrong
     */
    public record Failure(Kind type, Integer status, boolean retryable, String message) {}

    /** Observes the publishers. Every method has a no-op default. */
    public interface Listener {
        default void onPosted(Kind type, int status) {}

        default void onError(Failure failure) {}

        /**
         * A publisher switched itself off. Reasons: {@code unauthorized} (401, every publisher stops),
         * {@code not-found} (404), {@code conflict} (409) and {@code cluster-count-unknown}. A manual post
         * re-arms it.
         */
        default void onDisabled(Kind type, Integer status, String reason) {}
    }

    private final AutopostOptions options;
    private final ApiClient api;
    private final Bot bot;
    private final Listener listener;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final List<Runnable> closeHooks = new CopyOnWriteArrayList<>();

    private volatile Publisher.Stats stats;
    private volatile Publisher.Heartbeat heartbeat;
    private volatile Publisher.Commands commands;
    private boolean built;
    private boolean heartbeatStarted;
    private boolean allStarted;
    private boolean closed;

    private Autoposter(AutopostOptions options, ApiClient api, Bot bot) {
        this.options = options;
        this.api = api;
        this.bot = bot;
        this.listener = options.listener == null ? new LoggingListener() : options.listener;
    }

    /** A missing token returns an inert instance: a forgotten environment variable must not take down a deploy. */
    static Autoposter create(String token, AutopostOptions options, String userAgent, Bot bot) {
        AutopostOptions resolved = options == null ? new AutopostOptions() : options;
        String key = token == null ? "" : token.strip();
        if (key.startsWith("Bearer ")) {
            key = key.substring("Bearer ".length()).strip();
        }
        if (key.isEmpty()) {
            LOG.log(Level.WARNING, "No TheDiscordList stats token supplied; auto-posting is disabled.");
            return new Autoposter(resolved, null, bot);
        }
        return new Autoposter(resolved, new ApiClient(resolved.baseUrl, key, userAgent), bot);
    }

    boolean isInert() {
        return api == null;
    }

    ApiClient api() {
        return api;
    }

    Publisher.Stats stats() {
        return stats;
    }

    Publisher.Heartbeat heartbeat() {
        return heartbeat;
    }

    Publisher.Commands commands() {
        return commands;
    }

    synchronized void build(String botId, Topology topology) {
        if (api == null || closed || built) {
            return;
        }
        built = true;

        Publisher.Context ctx = new Publisher.Context(api, bot, topology, botId, options);
        if (options.statsEnabled) {
            stats = new Publisher.Stats(this, ctx);
            if (topology.statsPublisher() && !topology.localIsTotal() && options.serverCount == null) {
                warnOnce("server-count", "This process does not run every shard, and the Discord library has no "
                        + "way to sum guild counts across processes, so stats are not posted. Pass "
                        + "AutopostOptions.serverCount(...) with the bot's total.");
            }
        }
        if (options.heartbeatEnabled) {
            heartbeat = new Publisher.Heartbeat(this, ctx);
        }
        if (options.commandsEnabled) {
            commands = new Publisher.Commands(this, ctx);
        }
    }

    /** Heartbeat first: a booting bot must read "connecting" on its status page, not dead. */
    synchronized void startHeartbeat() {
        Publisher.Heartbeat publisher = heartbeat;
        if (closed || heartbeatStarted || publisher == null) {
            return;
        }
        heartbeatStarted = true;
        publisher.execute(publisher::start);
    }

    synchronized void startAll() {
        if (closed || allStarted || !built) {
            return;
        }
        allStarted = true;
        startHeartbeat();
        Publisher.Stats statsPublisher = stats;
        if (statsPublisher != null) {
            statsPublisher.execute(statsPublisher::start);
        }
        Publisher.Commands commandsPublisher = commands;
        if (commandsPublisher != null) {
            commandsPublisher.execute(commandsPublisher::start);
        }
    }

    void guildsChanged() {
        Publisher.Stats publisher = stats;
        if (publisher != null) {
            publisher.execute(publisher::guildsChanged);
        }
    }

    void shardsChanged() {
        Publisher.Heartbeat publisher = heartbeat;
        if (publisher != null) {
            publisher.execute(publisher::beatNow);
        }
    }

    /** Posts the server count now. Subject to the 60 second floor between stats posts. */
    public CompletableFuture<PostResult> postStats() {
        return submit(stats);
    }

    /** Sends a heartbeat now. */
    public CompletableFuture<PostResult> postHeartbeat() {
        return submit(heartbeat);
    }

    /** Posts the registered commands now. */
    public CompletableFuture<PostResult> postCommands() {
        return submit(commands);
    }

    private static CompletableFuture<PostResult> submit(Publisher publisher) {
        return publisher == null
                ? CompletableFuture.completedFuture(PostResult.skipped("disabled"))
                : publisher.submit();
    }

    /** Stops every timer and detaches every client listener. Not restartable. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        for (Publisher publisher : publishers()) {
            publisher.shutdown();
        }
        if (api != null) {
            api.close();
        }
        for (Runnable hook : closeHooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.log(Level.DEBUG, "Detaching TheDiscordList listener failed", e);
            }
        }
    }

    void onClose(Runnable hook) {
        closeHooks.add(hook);
    }

    void warnOnce(String key, String message) {
        if (warned.add(key)) {
            LOG.log(Level.WARNING, message);
        }
    }

    void posted(Kind type, int status) {
        notify(l -> l.onPosted(type, status));
    }

    void error(Failure failure) {
        notify(l -> l.onError(failure));
    }

    void disabled(Kind type, Integer status, String reason) {
        notify(l -> l.onDisabled(type, status, reason));
    }

    /** A 401 means the token is wrong for every endpoint. Reported once, by the publisher that saw it. */
    void authFailed() {
        for (Publisher publisher : publishers()) {
            publisher.silence("unauthorized");
        }
    }

    private List<Publisher> publishers() {
        List<Publisher> all = new ArrayList<>(3);
        for (Publisher publisher : new Publisher[] {stats, heartbeat, commands}) {
            if (publisher != null) {
                all.add(publisher);
            }
        }
        return all;
    }

    private void notify(Consumer<Listener> event) {
        try {
            event.accept(listener);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "A TheDiscordList listener threw", e);
        }
    }

    private static final class LoggingListener implements Listener {
        @Override
        public void onError(Failure failure) {
            LOG.log(Level.WARNING, "[thediscordlist] {0}: {1}", name(failure.type()), failure.message());
        }

        @Override
        public void onDisabled(Kind type, Integer status, String reason) {
            LOG.log(Level.WARNING, "[thediscordlist] {0} publisher disabled: {1}", name(type), explain(reason));
        }

        private static String name(Kind type) {
            return type.name().toLowerCase(Locale.ROOT);
        }

        private static String explain(String reason) {
            return switch (reason) {
                case "unauthorized" -> "TheDiscordList rejected the stats token (401). Every publisher is off "
                        + "until a manual post re-arms it.";
                case "not-found" -> "TheDiscordList does not know this bot id, or it belongs to a server listing "
                        + "(404). Check AutopostOptions.botId.";
                case "conflict" -> "This listing is not set up for slash commands (409).";
                case "cluster-count-unknown" -> "Could not determine how many clusters this bot runs. Heartbeats "
                        + "stay off, because a wrong cluster_count deletes sibling clusters server-side. Pass "
                        + "AutopostOptions.cluster(id, count) to enable them.";
                default -> reason;
            };
        }
    }
}

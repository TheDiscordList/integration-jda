package com.thediscordlist.jda;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Options for {@link TheDiscordList#autopost}. Every setter returns {@code this}.
 *
 * <pre>{@code
 * new AutopostOptions()
 *     .statsInterval(Duration.ofMinutes(10))
 *     .postEmptyCommands(false)
 *     .listener(myListener);
 * }</pre>
 */
public final class AutopostOptions {
    /** The production API. */
    public static final String DEFAULT_BASE_URL = "https://thediscordlist.com/api";

    /**
     * Names this process's place in a multi-process bot.
     *
     * @param id    this process's cluster index, from 0
     * @param count how many clusters the bot runs, 1 to 1024
     */
    public record Cluster(int id, int count) {
        public Cluster {
            if (count < 1 || count > 1024) {
                throw new IllegalArgumentException("cluster count must be between 1 and 1024, got " + count);
            }
            if (id < 0 || id >= count) {
                throw new IllegalArgumentException("cluster id must be between 0 and count - 1, got " + id);
            }
        }
    }

    String baseUrl = DEFAULT_BASE_URL;
    String botId;
    boolean statsEnabled = true;
    Duration statsInterval = Duration.ofMinutes(5);
    Duration statsDebounce = Duration.ofSeconds(20);
    Duration statsMinGap = Duration.ofSeconds(60);
    boolean heartbeatEnabled = true;
    Duration heartbeatInterval = Duration.ofSeconds(30);
    boolean commandsEnabled = true;
    boolean postEmptyCommands;
    Cluster cluster;
    IntSupplier serverCount;
    Autoposter.Listener listener;
    long backoffBaseMillis = 1_000;

    /** Default: {@value #DEFAULT_BASE_URL}. */
    public AutopostOptions baseUrl(String baseUrl) {
        String trimmed = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI.create(trimmed);
        this.baseUrl = trimmed;
        return this;
    }

    /** The Discord application id. Default: read from the client once it is ready. */
    public AutopostOptions botId(String botId) {
        this.botId = botId;
        return this;
    }

    /** Default: {@code true}. */
    public AutopostOptions stats(boolean enabled) {
        this.statsEnabled = enabled;
        return this;
    }

    /** Default: 5 minutes. */
    public AutopostOptions statsInterval(Duration interval) {
        this.statsInterval = nonNegative(interval, "statsInterval");
        return this;
    }

    /** Trailing debounce after a guild join or leave. Default: 20 seconds. */
    public AutopostOptions statsDebounce(Duration debounce) {
        this.statsDebounce = nonNegative(debounce, "statsDebounce");
        return this;
    }

    /** Hard floor between two stats POSTs, manual ones included. Default: 60 seconds. */
    public AutopostOptions statsMinGap(Duration minGap) {
        this.statsMinGap = nonNegative(minGap, "statsMinGap");
        return this;
    }

    /** Default: {@code true}. */
    public AutopostOptions heartbeat(boolean enabled) {
        this.heartbeatEnabled = enabled;
        return this;
    }

    /** Starting cadence, retuned from the API's response. Default: 30 seconds. */
    public AutopostOptions heartbeatInterval(Duration interval) {
        this.heartbeatInterval = nonNegative(interval, "heartbeatInterval");
        return this;
    }

    /** Default: {@code true}. */
    public AutopostOptions commands(boolean enabled) {
        this.commandsEnabled = enabled;
        return this;
    }

    /** Posting an empty list CLEARS the listing's commands, so it is skipped unless this is on. Default: {@code false}. */
    public AutopostOptions postEmptyCommands(boolean postEmpty) {
        this.postEmptyCommands = postEmpty;
        return this;
    }

    /** Names this process's cluster when it cannot be detected. See {@link Cluster}. */
    public AutopostOptions cluster(int id, int count) {
        this.cluster = new Cluster(id, count);
        return this;
    }

    /**
     * Supplies the bot's total server count, for layouts where this process cannot see every shard. A negative
     * value or an exception skips that post.
     */
    public AutopostOptions serverCount(IntSupplier serverCount) {
        this.serverCount = serverCount;
        return this;
    }

    /** Receives posted, error and disabled events. Default: logs errors and disables through {@link System.Logger}. */
    public AutopostOptions listener(Autoposter.Listener listener) {
        this.listener = listener;
        return this;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}

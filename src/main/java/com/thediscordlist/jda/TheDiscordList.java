package com.thediscordlist.jda;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.UnavailableGuildJoinedEvent;
import net.dv8tion.jda.api.events.guild.UnavailableGuildLeaveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.sharding.ShardManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * TheDiscordList integration for JDA.
 *
 * <pre>{@code
 * JDA jda = JDABuilder.createLight(System.getenv("DISCORD_TOKEN")).build();
 * TheDiscordList.autopost(jda, System.getenv("TDL_TOKEN"));
 * }</pre>
 */
public final class TheDiscordList {
    static final String USER_AGENT = "TheDiscordList-jda/" + Autoposter.VERSION
            + " (+https://github.com/TheDiscordList/integration-jda)";

    private TheDiscordList() {}

    /** Auto-posts for a single {@link JDA} instance. Safe to call before or after it is ready. */
    public static Autoposter autopost(JDA jda, String token) {
        return autopost(jda, token, new AutopostOptions());
    }

    /** Auto-posts for a single {@link JDA} instance. Safe to call before or after it is ready. */
    public static Autoposter autopost(JDA jda, String token, AutopostOptions options) {
        Objects.requireNonNull(jda, "jda");
        return attach(new JdaBot(() -> List.of(jda), () -> 0, () -> jda.getShardInfo().getShardTotal(),
                jda::addEventListener, jda::removeEventListener), token, options);
    }

    /** Auto-posts for every shard of a {@link ShardManager}, as one cluster unless the options say otherwise. */
    public static Autoposter autopost(ShardManager shardManager, String token) {
        return autopost(shardManager, token, new AutopostOptions());
    }

    /** Auto-posts for every shard of a {@link ShardManager}, as one cluster unless the options say otherwise. */
    public static Autoposter autopost(ShardManager shardManager, String token, AutopostOptions options) {
        Objects.requireNonNull(shardManager, "shardManager");
        return attach(new JdaBot(shardManager::getShards, shardManager::getShardsQueued,
                shardManager::getShardsTotal, shardManager::addEventListener, shardManager::removeEventListener),
                token, options);
    }

    private static Autoposter attach(JdaBot bot, String token, AutopostOptions options) {
        AutopostOptions resolved = options == null ? new AutopostOptions() : options;
        Autoposter poster = Autoposter.create(token, resolved, USER_AGENT, bot);
        if (poster.isInert()) {
            return poster;
        }

        Hooks hooks = new Hooks(poster, bot, resolved);
        bot.addListener.accept(hooks);
        poster.onClose(() -> bot.removeListener.accept(hooks));

        // With an explicit bot id the heartbeat starts before login, so a booting bot reads "connecting".
        if (resolved.botId != null) {
            poster.build(resolved.botId, bot.topology(resolved.cluster));
            poster.startHeartbeat();
        }
        bot.shards.get().stream()
                .filter(shard -> shard.getStatus() == JDA.Status.CONNECTED)
                .findFirst()
                .ifPresent(hooks::ready);
        return poster;
    }

    static String status(JDA.Status status) {
        return switch (status) {
            case CONNECTED -> "ready";
            case RECONNECT_QUEUED, WAITING_TO_RECONNECT, ATTEMPTING_TO_RECONNECT -> "resuming";
            case DISCONNECTED, SHUTTING_DOWN, SHUTDOWN, FAILED_TO_LOGIN -> "disconnected";
            default -> "connecting";
        };
    }

    /**
     * JDA has no cross-process primitive, so the only layout this process can prove is every shard here (cluster
     * 0 of 1). Anything else, {@code JDABuilder#useSharding} included, needs {@link AutopostOptions#cluster}.
     */
    static Topology detect(AutopostOptions.Cluster override, int localShards, int totalShards, int lowestLocalShard) {
        int total = Math.max(totalShards, 1);
        if (override != null) {
            return Topology.of(override, localShards, total);
        }
        if (localShards >= total) {
            return Topology.allLocal(total);
        }
        return new Topology(lowestLocalShard, null, lowestLocalShard == 0, total, false);
    }

    /** Discord's own command shape, minus ids, versions and localisations the API ignores. */
    static Map<String, Object> command(Command command) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (command.getType().getId() > 0) {
            out.put("type", command.getType().getId());
        }
        out.put("name", command.getName());
        putText(out, "description", command.getDescription());
        out.put("nsfw", command.isNSFW());
        Long permissions = command.getDefaultPermissions().getPermissionsRaw();
        if (permissions != null) {
            out.put("default_member_permissions", Long.toUnsignedString(permissions));
        }

        List<Map<String, Object>> options = new ArrayList<>();
        for (Command.SubcommandGroup group : command.getSubcommandGroups()) {
            List<Map<String, Object>> subcommands = new ArrayList<>();
            for (Command.Subcommand subcommand : group.getSubcommands()) {
                subcommands.add(subcommand(subcommand));
            }
            options.add(option(2, group.getName(), group.getDescription(), subcommands));
        }
        for (Command.Subcommand subcommand : command.getSubcommands()) {
            options.add(subcommand(subcommand));
        }
        for (Command.Option option : command.getOptions()) {
            options.add(option(option));
        }
        if (!options.isEmpty()) {
            out.put("options", options);
        }
        return out;
    }

    private static Map<String, Object> subcommand(Command.Subcommand subcommand) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (Command.Option option : subcommand.getOptions()) {
            options.add(option(option));
        }
        return option(1, subcommand.getName(), subcommand.getDescription(), options);
    }

    private static Map<String, Object> option(int type, String name, String description,
                                              List<Map<String, Object>> options) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("name", name);
        putText(out, "description", description);
        if (!options.isEmpty()) {
            out.put("options", options);
        }
        return out;
    }

    private static Map<String, Object> option(Command.Option option) {
        Map<String, Object> out = option(option.getTypeRaw(), option.getName(), option.getDescription(), List.of());
        out.put("required", option.isRequired());
        if (!option.getChoices().isEmpty()) {
            List<Map<String, Object>> choices = new ArrayList<>();
            for (Command.Choice choice : option.getChoices()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", choice.getName());
                entry.put("value", switch (choice.getType()) {
                    case INTEGER -> choice.getAsLong();
                    case NUMBER -> choice.getAsDouble();
                    default -> choice.getAsString();
                });
                choices.add(entry);
            }
            out.put("choices", choices);
        }
        return out;
    }

    private static void putText(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    static final class JdaBot implements Bot {
        final Supplier<List<JDA>> shards;
        final IntSupplier queued;
        final IntSupplier total;
        final Consumer<Object> addListener;
        final Consumer<Object> removeListener;

        JdaBot(Supplier<List<JDA>> shards, IntSupplier queued, IntSupplier total,
               Consumer<Object> addListener, Consumer<Object> removeListener) {
            this.shards = shards;
            this.queued = queued;
            this.total = total;
            this.addListener = addListener;
            this.removeListener = removeListener;
        }

        Topology topology(AutopostOptions.Cluster override) {
            List<JDA> running = shards.get();
            int lowest = running.stream().mapToInt(JdaBot::shardId).min().orElse(0);
            return detect(override, running.size() + queued.getAsInt(), total.getAsInt(), lowest);
        }

        @Override
        public List<Map<String, Object>> shards() {
            return shards.get().stream()
                    .sorted(Comparator.comparingInt(JdaBot::shardId))
                    .map(shard -> {
                        long ping = shard.getGatewayPing();
                        return Bot.shard(shardId(shard), status(shard.getStatus()), ping >= 0 ? ping : null, guilds(shard));
                    })
                    .toList();
        }

        /** Only once every local shard is connected: a cache mid-load undercounts. */
        @Override
        public Integer serverCount() {
            List<JDA> running = shards.get();
            if (queued.getAsInt() > 0 || running.isEmpty()
                    || running.stream().anyMatch(shard -> shard.getStatus() != JDA.Status.CONNECTED)) {
                return null;
            }
            return running.stream().mapToInt(JdaBot::guilds).sum();
        }

        @Override
        public List<Map<String, Object>> commands(String botId) {
            JDA shard = shards.get().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No shard is running yet."));
            return shard.retrieveCommands().complete().stream().map(TheDiscordList::command).toList();
        }

        private static int shardId(JDA shard) {
            return shard.getShardInfo().getShardId();
        }

        /** Unavailable guilds sit outside JDA's guild cache, but they are still servers the bot is in. */
        private static int guilds(JDA shard) {
            return Math.toIntExact(shard.getGuildCache().size()) + shard.getUnavailableGuilds().size();
        }
    }

    private static final class Hooks extends ListenerAdapter {
        private final Autoposter poster;
        private final JdaBot bot;
        private final AutopostOptions options;

        Hooks(Autoposter poster, JdaBot bot, AutopostOptions options) {
            this.poster = poster;
            this.bot = bot;
            this.options = options;
        }

        void ready(JDA shard) {
            try {
                String botId = options.botId != null ? options.botId : shard.getSelfUser().getApplicationId();
                poster.build(botId, bot.topology(options.cluster));
                poster.startAll();
            } catch (RuntimeException e) {
                poster.warnOnce("ready", "Could not start TheDiscordList auto-posting (" + ApiClient.describe(e)
                        + "). Pass AutopostOptions.botId with your Discord application id.");
            }
        }

        @Override
        public void onReady(ReadyEvent event) {
            ready(event.getJDA());
        }

        @Override
        public void onGuildJoin(GuildJoinEvent event) {
            poster.guildsChanged();
        }

        @Override
        public void onGuildLeave(GuildLeaveEvent event) {
            poster.guildsChanged();
        }

        @Override
        public void onUnavailableGuildJoined(UnavailableGuildJoinedEvent event) {
            poster.guildsChanged();
        }

        @Override
        public void onUnavailableGuildLeave(UnavailableGuildLeaveEvent event) {
            poster.guildsChanged();
        }

        @Override
        public void onStatusChange(StatusChangeEvent event) {
            if (!status(event.getOldStatus()).equals(status(event.getNewStatus()))) {
                poster.shardsChanged();
            }
        }
    }
}

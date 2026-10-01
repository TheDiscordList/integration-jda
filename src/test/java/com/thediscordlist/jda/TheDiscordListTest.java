package com.thediscordlist.jda;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TheDiscordListTest {

    @Test
    void userAgentNamesTheLibraryAndRepository() {
        assertEquals("TheDiscordList-jda/" + Autoposter.VERSION + " (+https://github.com/TheDiscordList/integration-jda)",
                TheDiscordList.USER_AGENT);
    }

    @Test
    void topologyDetection() {
        assertEquals(new Topology(1, 3, false, 6, false), TheDiscordList.detect(new AutopostOptions.Cluster(1, 3), 2, 6, 2));
        assertEquals(new Topology(0, 1, true, 4, true), TheDiscordList.detect(new AutopostOptions.Cluster(0, 1), 4, 4, 0));
        // Every shard in this process: a ShardManager, or an unsharded bot.
        assertEquals(Topology.allLocal(4), TheDiscordList.detect(null, 4, 4, 0));
        assertEquals(Topology.allLocal(1), TheDiscordList.detect(null, 1, 1, 0));
        // One JDA per process (JDABuilder#useSharding): the shard is the cluster.
        assertEquals(new Topology(2, 4, false, 4, false), TheDiscordList.detect(null, 1, 4, 2));
        assertEquals(new Topology(0, 4, true, 4, false), TheDiscordList.detect(null, 1, 4, 0));
        // A ShardManager running some of the shards: the cluster count is unknowable.
        assertNull(TheDiscordList.detect(null, 2, 8, 0).clusterCount());
        assertTrue(TheDiscordList.detect(null, 2, 8, 0).statsPublisher());
        assertFalse(TheDiscordList.detect(null, 2, 8, 4).statsPublisher());
    }

    @Test
    void statusMapping() {
        assertEquals("ready", TheDiscordList.status(JDA.Status.CONNECTED));
        for (JDA.Status status : List.of(JDA.Status.INITIALIZING, JDA.Status.INITIALIZED, JDA.Status.LOGGING_IN,
                JDA.Status.CONNECTING_TO_WEBSOCKET, JDA.Status.IDENTIFYING_SESSION,
                JDA.Status.AWAITING_LOGIN_CONFIRMATION, JDA.Status.LOADING_SUBSYSTEMS)) {
            assertEquals("connecting", TheDiscordList.status(status), status.name());
        }
        for (JDA.Status status : List.of(JDA.Status.RECONNECT_QUEUED, JDA.Status.WAITING_TO_RECONNECT,
                JDA.Status.ATTEMPTING_TO_RECONNECT)) {
            assertEquals("resuming", TheDiscordList.status(status), status.name());
        }
        for (JDA.Status status : List.of(JDA.Status.DISCONNECTED, JDA.Status.SHUTTING_DOWN, JDA.Status.SHUTDOWN,
                JDA.Status.FAILED_TO_LOGIN)) {
            assertEquals("disconnected", TheDiscordList.status(status), status.name());
        }
    }

    @Test
    void shardReportsAndServerCount() {
        Map<String, Object> second = jdaAnswers(1, 2, JDA.Status.CONNECTING_TO_WEBSOCKET, -1L, 2, Set.of());
        JDA first = stub(JDA.class, jdaAnswers(0, 2, JDA.Status.CONNECTED, 42L, 3, Set.of("10")), new CopyOnWriteArrayList<>());
        JDA other = stub(JDA.class, second, new CopyOnWriteArrayList<>());
        TheDiscordList.JdaBot bot = new TheDiscordList.JdaBot(() -> List.of(other, first), () -> 0, () -> 2, l -> {}, l -> {});

        assertEquals(List.of(
                Map.of("id", 0, "status", "ready", "latency_ms", 42L, "guilds", 4),
                Map.of("id", 1, "status", "connecting", "guilds", 2)), bot.shards());
        assertNull(bot.serverCount(), "a shard still loading means a partial cache");
        assertEquals(Topology.allLocal(2), bot.topology(null));

        second.put("getStatus", JDA.Status.CONNECTED);
        assertEquals(6, bot.serverCount());

        TheDiscordList.JdaBot booting = new TheDiscordList.JdaBot(() -> List.of(first), () -> 1, () -> 2, l -> {}, l -> {});
        assertNull(booting.serverCount(), "queued shards mean a partial cache");
        assertEquals(Topology.allLocal(2), booting.topology(null));
    }

    @Test
    void commandsMapToTheApiShape() {
        Command slash = command(Command.Type.SLASH, "config", "Configure the bot", true,
                DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER), Map.of(
                        "getSubcommandGroups", List.of(new Command.SubcommandGroup(null, DataObject.fromJson("""
                                {"type":2,"name":"logs","description":"Logging","options":[
                                  {"type":1,"name":"set","description":"Set the channel","options":[
                                    {"type":7,"name":"channel","description":"Where","required":true}]}]}"""))),
                        "getSubcommands", List.of(new Command.Subcommand(null, DataObject.fromJson("""
                                {"type":1,"name":"reset","description":"Reset everything"}"""))),
                        "getOptions", List.of()));
        Command user = command(Command.Type.USER, "Inspect", "", false, DefaultMemberPermissions.ENABLED, Map.of(
                "getSubcommandGroups", List.of(), "getSubcommands", List.of(), "getOptions", List.of()));
        Command choices = command(Command.Type.SLASH, "roll", "Roll dice", false, DefaultMemberPermissions.DISABLED, Map.of(
                "getSubcommandGroups", List.of(), "getSubcommands", List.of(), "getOptions", List.of(
                        new Command.Option(DataObject.fromJson("""
                                {"type":4,"name":"sides","description":"Sides","choices":[{"name":"d6","value":6}]}""")),
                        new Command.Option(DataObject.fromJson("""
                                {"type":10,"name":"bias","description":"Bias","choices":[{"name":"half","value":0.5}]}""")),
                        new Command.Option(DataObject.fromJson("""
                                {"type":3,"name":"label","description":"Label","choices":[{"name":"x","value":"y"}]}""")))));

        assertEquals(Map.of(
                "type", 1, "name", "config", "description", "Configure the bot", "nsfw", true,
                "default_member_permissions", "32",
                "options", List.of(
                        Map.of("type", 2, "name", "logs", "description", "Logging", "options", List.of(
                                Map.of("type", 1, "name", "set", "description", "Set the channel", "options", List.of(
                                        Map.of("type", 7, "name", "channel", "description", "Where", "required", true))))),
                        Map.of("type", 1, "name", "reset", "description", "Reset everything"))),
                TheDiscordList.command(slash));
        assertEquals(Map.of("type", 2, "name", "Inspect", "nsfw", false), TheDiscordList.command(user));
        assertEquals(Map.of(
                "type", 1, "name", "roll", "description", "Roll dice", "nsfw", false, "default_member_permissions", "0",
                "options", List.of(
                        Map.of("type", 4, "name", "sides", "description", "Sides", "required", false,
                                "choices", List.of(Map.of("name", "d6", "value", 6L))),
                        Map.of("type", 10, "name", "bias", "description", "Bias", "required", false,
                                "choices", List.of(Map.of("name", "half", "value", 0.5))),
                        Map.of("type", 3, "name", "label", "description", "Label", "required", false,
                                "choices", List.of(Map.of("name", "x", "value", "y"))))),
                TheDiscordList.command(choices));
    }

    @Test
    void aMissingTokenNeverTouchesJda() {
        List<String> calls = new CopyOnWriteArrayList<>();
        JDA jda = stub(JDA.class, jdaAnswers(0, 1, JDA.Status.CONNECTED, 42L, 3, Set.of()), calls);

        Autoposter poster = TheDiscordList.autopost(jda, "");

        assertTrue(poster.isInert());
        assertEquals(List.of(), calls);
    }

    @Test
    void autopostOnAReadyClientHeartbeatsAndDetachesOnClose() throws Exception {
        try (StubServer server = new StubServer()) {
            List<String> calls = new CopyOnWriteArrayList<>();
            Map<String, Object> answers = jdaAnswers(0, 1, JDA.Status.CONNECTED, 42L, 3, Set.of("7"));
            answers.put("getSelfUser", stub(SelfUser.class, Map.of("getApplicationId", "161660517914509312"), calls));
            JDA jda = stub(JDA.class, answers, calls);

            Autoposter poster = TheDiscordList.autopost(jda, "tdl_token", new AutopostOptions().baseUrl(server.baseUrl()));

            StubServer.Request beat = server.take();
            assertEquals("/api/bots/161660517914509312/heartbeat", beat.path());
            assertEquals(TheDiscordList.USER_AGENT, beat.headers().getFirst("User-Agent"));
            assertEquals(ApiClient.JSON.readTree("""
                    {"cluster":0,"cluster_count":1,"shards":[{"id":0,"status":"ready","latency_ms":42,"guilds":4}]}"""),
                    beat.json());
            assertTrue(calls.contains("addEventListener"));

            poster.close();
            assertTrue(calls.contains("removeEventListener"));
        }
    }

    private static Map<String, Object> jdaAnswers(int shard, int total, JDA.Status status, long ping, long cached,
                                                  Set<String> unavailable) {
        Map<String, Object> answers = new HashMap<>();
        answers.put("getShardInfo", new JDA.ShardInfo(shard, total));
        answers.put("getStatus", status);
        answers.put("getGatewayPing", ping);
        answers.put("getGuildCache", stub(SnowflakeCacheView.class, Map.of("size", cached), new CopyOnWriteArrayList<>()));
        answers.put("getUnavailableGuilds", unavailable);
        return answers;
    }

    private static Command command(Command.Type type, String name, String description, boolean nsfw,
                                   DefaultMemberPermissions permissions, Map<String, Object> options) {
        Map<String, Object> answers = new HashMap<>(options);
        answers.put("getType", type);
        answers.put("getName", name);
        answers.put("getDescription", description);
        answers.put("isNSFW", nsfw);
        answers.put("getDefaultPermissions", permissions);
        return stub(Command.class, answers, new CopyOnWriteArrayList<>());
    }

    /** A proxy answering by method name; anything unlisted returns a zero value. */
    @SuppressWarnings("unchecked")
    static <T> T stub(Class<T> type, Map<String, Object> answers, List<String> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return type.getSimpleName();
                default:
                    break;
            }
            calls.add(name);
            if (answers.containsKey(name)) {
                Object answer = answers.get(name);
                return answer instanceof Supplier<?> supplier ? supplier.get() : answer;
            }
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return false;
            }
            if (returns == long.class) {
                return 0L;
            }
            if (returns == int.class) {
                return 0;
            }
            return null;
        });
    }
}

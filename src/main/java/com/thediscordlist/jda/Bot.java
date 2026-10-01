package com.thediscordlist.jda;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the publishers need to read from the Discord library. */
interface Bot {
    /** The API rejects anything above ten minutes; a ping can spike past that after a stall. */
    long MAX_LATENCY_MS = 600_000;

    /** This process's shards, already in the heartbeat's wire shape. */
    List<Map<String, Object>> shards();

    /** The local guild count, or {@code null} while a local shard is not ready and the cache is partial. */
    Integer serverCount();

    /** The registered global application commands, already in the API's wire shape. */
    List<Map<String, Object>> commands(String botId) throws Exception;

    /** A heartbeat shard entry. Optional keys are omitted, never sent as {@code null}. */
    static Map<String, Object> shard(int id, String status, Long latencyMs, Integer guilds) {
        Map<String, Object> shard = new LinkedHashMap<>();
        shard.put("id", id);
        shard.put("status", status);
        if (latencyMs != null && latencyMs >= 0) {
            shard.put("latency_ms", Math.min(latencyMs, MAX_LATENCY_MS));
        }
        if (guilds != null) {
            shard.put("guilds", guilds);
        }
        return shard;
    }
}

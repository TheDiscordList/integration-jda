package com.thediscordlist.jda;

/**
 * Where this process sits in the bot's sharding layout.
 *
 * @param clusterId      this process's cluster index
 * @param clusterCount   how many clusters the bot runs, or {@code null} when that cannot be known; the
 *                       heartbeat publisher must then stay off, because the API deletes every cluster row
 *                       whose id is {@code >= cluster_count}
 * @param statsPublisher whether this process posts stats and commands (the one hosting shard 0)
 * @param shardCount     the bot's total shard count
 * @param localIsTotal   whether this process runs every shard, so its guild cache is the whole bot
 */
record Topology(int clusterId, Integer clusterCount, boolean statsPublisher, int shardCount, boolean localIsTotal) {

    static Topology allLocal(int shardCount) {
        return new Topology(0, 1, true, Math.max(shardCount, 1), true);
    }

    static Topology of(AutopostOptions.Cluster cluster, int localShards, int totalShards) {
        return new Topology(cluster.id(), cluster.count(), cluster.id() == 0, Math.max(totalShards, 1),
                localShards >= totalShards);
    }
}

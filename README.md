# com.thediscordlist:integration-jda

Publish your JDA bot's server count, shard heartbeats and slash commands to
[TheDiscordList](https://thediscordlist.com) automatically, and verify its
inbound webhooks.

No dependencies beyond JDA: HTTP, HMAC and JSON come from the JDK and the
Jackson that JDA already ships.

```xml
<dependency>
    <groupId>com.thediscordlist</groupId>
    <artifactId>integration-jda</artifactId>
    <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("com.thediscordlist:integration-jda:0.2.0")
```

## Quickstart

```java
import com.thediscordlist.jda.TheDiscordList;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;

JDA jda = JDABuilder.createLight(System.getenv("DISCORD_TOKEN")).build();

TheDiscordList.autopost(jda, System.getenv("TDL_TOKEN"));
```

That is the whole integration, and it is also the recommended production setup.
It starts three publishers:

| Publisher | What it sends | Cadence |
| --- | --- | --- |
| Stats | `server_count`, `shard_count` | 5s after ready, then every 5 min, debounced on guild join/leave |
| Heartbeat | per-shard status, latency and guild counts | every ~30s, plus after any shard status change, at least 10s after the previous beat |
| Commands | your registered global slash commands | once, ~10s after ready |

Your **stats token** is on the listing's manage page, under the API tab. It is
scoped to one listing, so it is the only credential this package needs.

`autopost` is safe to call before or after the bot is ready. With a missing
token it logs one warning and returns an inert `Autoposter`: a forgotten
environment variable never takes down a deploy.

## Sharding

| Layout | `server_count` comes from | Stats posted by | Heartbeats |
| --- | --- | --- | --- |
| One `JDA`, unsharded | the guild cache | this process | cluster 0 of 1 |
| `ShardManager` running every shard | the guild cache of all shards | this process | cluster 0 of 1, every shard |
| One `JDA` per process (`useSharding(id, total)`) | your `serverCount(...)` | shard 0 | only with `cluster(id, count)` |
| `ShardManager` running some shards per process | your `serverCount(...)` | the process with shard 0 | only with `cluster(id, count)` |

```java
TheDiscordList.autopost(shardManager, System.getenv("TDL_TOKEN"));
```

Every process heartbeats for **itself**, with its own shards. A manager
reporting "all fine" for a process whose host has died is exactly the failure
the status page exists to catch.

JDA has no way to ask another process for its guild count or how many
processes there are, so a bot split across processes needs to supply the total
and name its cluster:

```java
TheDiscordList.autopost(shardManager, System.getenv("TDL_TOKEN"), new AutopostOptions()
        .cluster(Integer.parseInt(System.getenv("CLUSTER_ID")), Integer.parseInt(System.getenv("CLUSTER_COUNT")))
        .serverCount(() -> myBus.sumGuildCounts()));
```

> **If the cluster count cannot be determined, heartbeats do not start.** The
> API hard-deletes every cluster row whose id is `>= cluster_count` in the
> request, so one process guessing `1` would erase all of its siblings and
> their monitoring history. The heartbeat publisher reports `disabled` with
> reason `cluster-count-unknown`, naming `cluster(id, count)` as the fix.
> Losing a status page beats losing data.

Server counts include guilds that are temporarily unavailable, which JDA keeps
outside its guild cache. The count is only read once every local shard is
`CONNECTED`, so a half-loaded cache is never published.

## Options

```java
TheDiscordList.autopost(jda, token, new AutopostOptions()
        .botId("161660517914509312")           // default: the application id from the ready event
        .baseUrl("https://thediscordlist.com/api")

        .stats(true)
        .statsInterval(Duration.ofMinutes(5))
        .statsDebounce(Duration.ofSeconds(20))
        .statsMinGap(Duration.ofSeconds(60))
        .heartbeat(true)
        .heartbeatInterval(Duration.ofSeconds(30))
        .commands(true)
        .postEmptyCommands(false)

        .cluster(0, 1)
        .serverCount(() -> 1234)
        .listener(myListener));
```

`statsMinGap` is a hard floor between two stats POSTs. A join storm, a mass
reconnect and a manual `postStats()` all route through it, so the ceiling is
structurally one request per minute. An unchanged count is not re-posted, except
every 30 minutes so the listing never looks abandoned.

`postEmptyCommands` is `false` because posting an empty list **clears** the
command list on your listing. Turn it on only if that is what you mean.

With `botId` set, the heartbeat starts before the bot is ready, so a booting
bot reads "connecting" on its status page rather than dead.

## Events and manual posting

```java
Autoposter poster = TheDiscordList.autopost(jda, token, new AutopostOptions()
        .listener(new Autoposter.Listener() {
            @Override public void onPosted(Autoposter.Kind type, int status) {}
            @Override public void onError(Autoposter.Failure failure) {} // type, status, retryable, message
            @Override public void onDisabled(Autoposter.Kind type, Integer status, String reason) {}
        }));

poster.postStats().thenAccept(result -> {});    // PostResult(ok, status, skipped)
poster.postCommands();
poster.postHeartbeat();

poster.close();
```

Nothing in this package throws at your bot. The `postX()` futures never
complete exceptionally, every timer and listener callback is guarded, and
without a listener, errors and disables are logged through `System.Logger`
(JUL by default; add `org.slf4j:slf4j-jdk-platform-logging` to route them into
SLF4J). Each `Autoposter` runs its publishers on three daemon threads, so
listener callbacks arrive there, never on JDA's event thread.

An explicit `postX()` also re-arms a publisher that disabled itself, so you can
retry after fixing a token or a listing id without restarting.

`close()` stops every timer and removes the event listener it added.

## Webhooks

`Webhooks` uses only the JDK and Jackson, so a webhook-only service can use it
without logging a bot in.

### Spring Boot

```java
@RestController
class TdlWebhookController {
    private final String secret = System.getenv("TDL_WEBHOOK_SECRET");

    @PostMapping("/webhooks/tdl")
    ResponseEntity<Void> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = Webhooks.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = Webhooks.DELIVERY_HEADER, required = false) String delivery) {
        Webhooks.Event event;
        try {
            event = Webhooks.verify(body, signature, secret);
        } catch (Webhooks.VerificationException e) {
            return ResponseEntity.badRequest().build(); // e.code() says why
        }

        switch (event.event()) {
            case "listing.upvoted" -> thank(event.data().path("user"));
            case "listing.bumped" -> announce(event.data().path("points").asInt());
            default -> { } // webhook.ping, and names this version does not know yet
        }
        return ResponseEntity.noContent().build();
    }
}
```

`body` must be the exact bytes that arrived. Bind it as `byte[]` (or read
`HttpServletRequest#getInputStream()`); a parsed object re-serialised to JSON
changes escaping and key order and can never verify: the signature covers bytes,
not meaning.

`Webhooks.verify` checks `X-TDL-Signature` (`t=<unix seconds>,v1=<hex>`),
rejects timestamps more than five minutes away in either direction, compares
in constant time, and returns the parsed envelope. Failures throw
`VerificationException` with a `code()`: `MISSING_SIGNATURE`,
`MALFORMED_SIGNATURE`, `STALE_TIMESTAMP`, `INVALID_SIGNATURE` or
`MALFORMED_BODY`.

### Events

| `event.event()` | `event.data()` |
| --- | --- |
| `listing.bumped` | `points`, `bumped_at`, `next_bump_at`, `user` |
| `listing.bump_ready` | `ready_at` |
| `listing.upvoted` | `voted_at`, `total_upvotes`, `user` |
| `server.joined` | `joined_at`, `user` (server listings only) |
| `bot.added` | `added_at`, `user` |
| `bot.stats_reported` | `guild_count`, `shard_count`, `reported_at` |
| `webhook.ping` | `message` |

`data` is a Jackson `JsonNode`, so a field added tomorrow is readable today, and
an event name this version does not know still parses.

Two of them are not what their names suggest. `bot.added` is a **click** event,
dispatched on the install hand-off: Discord never tells anyone whether the
install completed. And `bot.stats_reported` is not an echo of your own POST: it
also fires when the owner edits the listing in the web UI, and from the
heartbeat's internal stats forward.

### Rules for a healthy endpoint

- **Always answer 2xx**, including for `webhook.ping` and for event names you
  do not recognise. The sender disables an endpoint after 10 consecutive failed
  deliveries. A 500 on an unknown event is a self-inflicted outage.
- A disabled endpoint is probed with a `webhook.ping` every 6 hours for 7 days,
  and **a 2xx to that probe re-enables it**.
- **Register an exact `https://` URL with no redirect.** The sender treats 3xx
  as a failure.
- **Deduplicate on `X-TDL-Delivery`** (equal to `event.id()`). It is stable
  across all retries of one delivery; a manual "redeliver" from the UI mints a
  new one deliberately.
- **Rotating the secret has no grace window.** Deliveries already queued under
  the old secret become unverifiable.

## Plans, limits and known behaviour

- **Heartbeats need Gold or higher.** On a `403` the heartbeat publisher
  suspends and re-probes every 30 minutes rather than disabling: the plan is
  checked per request, so a bot can gain it mid-run.
- **Rate limits are shared.** Stats and commands share 60 requests a minute per
  bot, heartbeats get 120. A `429` suspends stats and commands together,
  honouring `Retry-After` and `X-RateLimit-Reset` for at most an hour;
  heartbeats are never held back by it, since a dropped heartbeat reads as
  "down".
- **`404` never means "bad token".** It means the id is unknown or belongs to a
  server listing, and disables only that publisher. A `401` is the bad token,
  and stops every publisher.
- `409` on commands means the listing is not set up for slash commands; the
  commands publisher switches off.
- Transient failures (`408`, `425`, 5xx, timeouts, network) are retried twice
  with jittered exponential backoff, then left to the next run. Any other `4xx`
  is reported once and not retried.

## Requirements

- Java 17 or newer
- JDA 5.x (`net.dv8tion:JDA`), which you add yourself; this package declares it
  `provided`. Built and tested against 5.6.1, also compiled against 5.0.0.

## Publishing (maintainers)

Releases go to Maven Central through the Sonatype Central Portal, signed with
GPG, from `.github/workflows/release.yml` on a `v*` tag.

One-time setup:

1. Sign in at [central.sonatype.com](https://central.sonatype.com), add the
   namespace `com.thediscordlist` and verify it with the DNS TXT record the
   portal shows for `thediscordlist.com`.
2. Under **View Account → Generate User Token**, create a token and store its
   two halves as the repository (or organisation) secrets
   `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.
3. Create a signing key (`gpg --quick-generate-key "TheDiscordList" rsa4096 sign 2y`),
   publish it (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`),
   and store `gpg --armor --export-secret-keys <KEY_ID>` as
   `MAVEN_GPG_PRIVATE_KEY` and its passphrase as `MAVEN_GPG_PASSPHRASE`.

Each release:

1. Set the version in `pom.xml` and `Autoposter.VERSION` (a test fails when
   they differ), and move the `Unreleased` notes in `CHANGELOG.md` under it.
2. Commit, then `git tag v0.2.0 && git push origin v0.2.0`. The workflow
   checks the tag matches the POM, runs the tests, signs, uploads and waits
   until the portal reports the release published.

## License

MIT

# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.3.0] - 2026-11-12

### Added

- JDA 6 support. The package is built and tested against JDA 6.7.0 and still
  compiles against 5.0.0, so 5.x keeps working.

### Changed

- A dropped socket reports as `resuming`. JDA's `DISCONNECTED` is every
  reconnect, and the API opened a public Down incident on it. Only
  `SHUTTING_DOWN`, `SHUTDOWN` and `FAILED_TO_LOGIN` report `disconnected`.
- A shard leaves `guilds` out of its heartbeat until it is ready, and every shard
  does while a `ShardManager` still has some queued, so a restart cannot zero or
  undercount the listing's server count.
- A `429` is retried once its window lifts, when that comes before the next
  run.
- A `429` on a heartbeat no longer suspends stats and commands: the API limits
  them separately.
- Commands are retried every 5 minutes after a 5xx, a rate limit, a plan
  suspension or a failed fetch from Discord, until they land or are rejected.
- `statsMinGap` cannot go below 60 seconds, `statsInterval` cannot go below
  `statsMinGap`, and the starting `heartbeatInterval` is clamped to 15-60
  seconds.
- Jackson, which stays `provided`, is compiled against 2.22.4 and aligned
  through its BOM, clearing the `jackson-core` CVEs in the build.
- Maven build plugins, JUnit (6.1.3) and the CI actions are on their latest
  releases.

### Fixed

- The poster closes itself when the last shard shuts down, so `jda.shutdown()`
  without `close()` no longer leaves it beating in a live JVM.
- `close()` completes manual posts still in the queue instead of leaving their
  futures pending forever.

## [0.2.0] - 2026-10-18

### Added

- `listing.bump_ready` in the documented webhook events, with `ready_at`.

### Changed

- A process running one shard of several (`JDABuilder#useSharding`) no longer
  guesses its cluster: heartbeats stay off with `cluster-count-unknown` until
  `cluster(id, count)` names it. A sibling configured with a smaller count
  deleted the guessed cluster's row on every beat.
- A shard status change beats no sooner than 10 seconds after the previous
  beat.
- `408` and `425` are retried; every other `4xx` is reported once and not
  retried.
- A `429` suspends for at most an hour.

### Fixed

- Rate-limit suspensions use the monotonic clock, so a wall-clock jump cannot
  stretch or cut them short.
- `Autoposter.close()` closes the HTTP client on Java 21 and newer.
- A token pasted with a leading `Bearer ` works.
- A blank webhook secret is rejected like an empty one.

## [0.1.0] - 2026-10-14

### Added

- `TheDiscordList.autopost(...)` for a single `JDA` or a `ShardManager`:
  stats, per-shard heartbeats and global slash commands, with topology
  detection and a `cluster(id, count)` override.
- Status handling shared by every publisher: 401 stops everything, 403
  suspends heartbeats for 30 minutes, 404/409 disable one publisher, 429
  suspends stats and commands, transient failures retry with backoff.
- `Webhooks.verify(...)` for inbound webhook signatures.

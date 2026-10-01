# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0]

### Added

- `TheDiscordList.autopost(...)` for a single `JDA` or a `ShardManager`:
  stats, per-shard heartbeats and global slash commands, with topology
  detection and a `cluster(id, count)` override.
- Status handling shared by every publisher: 401 stops everything, 403
  suspends heartbeats for 30 minutes, 404/409 disable one publisher, 429
  suspends stats and commands, transient failures retry with backoff.
- `Webhooks.verify(...)` for inbound webhook signatures.

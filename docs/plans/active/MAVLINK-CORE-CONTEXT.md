# MAVLINK-CORE — task context

**Task:** turn `adapters/adapter-mavlink` into a properly layered, reusable MAVLink component.
**Branch:** `feat/mavlink-core-plan` (this doc + the plan). Implementation waves get sub-branches off `feat/mavlink-core`.
**Plan:** [MAVLINK-CORE-PLAN.md](MAVLINK-CORE-PLAN.md) — authoritative spec.
**Date frozen:** 2026-08-15.

## Where the knowledge came from

Four Sonnet research agents, run in parallel, each writing a brief. Briefs live in the session scratchpad
(`/tmp/claude-1000/-home-vladte-IdeaProjects-vision/35aa12d7-.../scratchpad/`) — the load-bearing facts are
copied into the plan's §2 so the plan stands alone once the scratchpad is gone.

| Brief | Scope | Primary sources |
|---|---|---|
| `research-wire.md` | frame layout v1/v2, CRC_EXTRA, field reordering, truncation, signing, dialects, seq | mavlink.io/en/guide/{serialization, mavlink_2, message_signing, xml_schema, mavlink_version} |
| `research-services.md` | all ~20 MAVLink microservices: message sets, state machines, timeouts, retries | mavlink.io/en/services/* |
| `research-routing.md` | routing rules, sysid/compid addressing, transports, link health, backpressure | mavlink.io/en/guide/{routing, mavlink_ids}, mavlink-router + MAVProxy |
| `research-audit.md` | audit of the 19 existing classes + JVM MAVLink library landscape | this repo, Maven Central, GitHub |

## Starting state (audited, not assumed)

`adapters/adapter-mavlink`: 19 main classes, 3 513 lines, one flat package, 135 tests across 12 classes
(3 docker/image-gated SITL tests skip). Five classes implement project ports:
`MavlinkTelemetrySource` (RX), `MavlinkFeedTransmitter` (TX sim), `MavlinkHeartbeatScanner` (discovery),
`MavlinkFlightCommander` (command TX), `MavlinkManualControlSender` (RC override TX).
Full surface in [adapter-mavlink/MODULE.md](../../../adapters/adapter-mavlink/MODULE.md) — read it before
touching anything; do not re-derive API surface from sources.

Library underneath: `io.dronefleet.mavlink:mavlink:1.1.11`, pinned in root `pom.xml`.

## The three findings that drive the plan

1. **Layers are fused, not absent.** Every port-implementing class is simultaneously a port adapter,
   a protocol service, a message builder and its own transport. Four of five hand-roll
   `MavlinkConnection` construction directly.
2. **`MavlinkTelemetrySource` has become an accidental god-facade.** Eight package-private pass-throughs
   exist purely so the other four port classes can reach `MavlinkSocketHub` through it. They are groping
   for a peer/session object that does not exist yet.
3. **The protocol surface used is ~5 % of MAVLink.** Command, heartbeat, RC override and telemetry decode.
   No parameters, no missions, no FTP, no message-rate control, no link health, UDP-only.

## Constraints this task must respect

- Repo law: `CLAUDE.md` (layering, no magic numbers, SOLID/IoC, MODULE.md per module, one task one branch).
- Dependency rule is ArchUnit-enforced; a new `libs/` aggregator needs an explicit rule amendment (plan W1).
- `DOMAIN-SEPARATION-PLAN.md` D7 (asset lease owns the MAVLink socket) and U0 (≤50 ms, never queued)
  forbid putting a network hop in front of the RC/command path — this rules out a network microservice.
- The 135 existing tests are the refactor's safety net: they must stay green wave by wave, not be rewritten.

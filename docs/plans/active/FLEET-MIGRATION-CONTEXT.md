# FLEET-MIGRATION — task context

**Task:** sequence the migration from today's single-instance, direct-call, poll-heavy station to the
event-driven, role-scaled, fleet-capable target the assessment ranked — without colliding with the
MAVLink work already in flight.
**Plan:** [FLEET-MIGRATION-PLAN.md](FLEET-MIGRATION-PLAN.md) — authoritative sequencing spec.
**Date frozen:** 2026-08-15.

## Where the knowledge came from

- All 25 MODULE.md files surveyed 2026-08-15 (two parallel research passes: the four large docs
  read section-by-section; the remaining 21 read whole). Load-bearing findings are restated in the
  plan's §1 so it stands alone.
- The three standing authoritative specs this plan sequences but does not replace:
  [MAVLINK-CORE-PLAN.md](MAVLINK-CORE-PLAN.md), [DOMAIN-SEPARATION-PLAN.md](DOMAIN-SEPARATION-PLAN.md),
  [DRONE-INFRA-PLAN.md](DRONE-INFRA-PLAN.md).
- Competitive check (BattleBorn digital FPV ground station, drontech.com.ua, 2026-08-15): hardware
  RF layer only, zero software features — confirmed the platform's value sits above the radio and
  its gap sits below it (no RF story; FPV/ELRS-class aircraft out of protocol reach). Recorded in
  the plan's backlog, not a migration driver.

## The standing constraint: a MAVLink agent is running

MAVLINK-CORE waves W0–W6 are being executed by a separate agent on sub-branches off
`feat/mavlink-core`. Until its W4 merges, these paths belong to it exclusively:

- `libs/mavlink-core/**` (W1–W3)
- `adapters/adapter-mavlink/**` (W4)
- root `pom.xml` (`libs` aggregator, W1) and `vision-app`'s ArchUnit rule file (W1)

Its §8 frozen contract also pins the five MAVLink port-class constructors and the 135 existing
tests. Every wave in this migration plan is scoped disjoint from those paths, and the two
serialization points (root pom, vision-app wiring) are called out explicitly in the plan's §5.

## Starting state (audited, not assumed)

- **W1 of DOMAIN-SEPARATION is done and merged** — eight contexts are Maven modules, all calls
  still in-process. W2–W5 unbuilt.
- **No broker exists in the build.** `EventPublisherPort` → `LoggingEventPublisher`; five
  `*LiveUpdatePort`s fan out SSE inside one JVM; vision-api states the live plane is
  process-local/single-instance.
- **Single-instance by construction:** no JDBC connection pool (Hibernate built-in, per-call
  `EntityManager`), audit trail in-memory regardless of `vision.persistence.enabled`, SSE ring
  buffers and every registry in-heap.
- **Command path:** four blocking one-shot commands (2 s ack wait, no retry, no `confirmation`
  increment, `COMMAND_LONG` only), per-asset API only, one manual-control session app-wide.
- **No mission upload to real aircraft anywhere.** Flight-plan-shaped types are simulator input.
- **Fleet ingest is real** (shared-UDP gateway, sysid claims) but aggregation is poll-heavy
  (per-streaming-asset 2 s pollers, 5 s fleet polls) and capped (32 unclaimed vehicles, 500
  summary rows, 20 000-sample replay fetch).

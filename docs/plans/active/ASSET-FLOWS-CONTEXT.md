# ASSET-FLOWS — task context

**Started:** 2026-09-01 · **Status:** proposal delivered — awaiting user picks · **Branch:** none yet (research only)

## The ask (user, verbatim intent)

Think about the asset's flows, asset control and so on. Research, think what can be
added and/or implemented/refactored so the app is even more useful for the pilot.

Deliverable of this phase: a **ranked proposal** (what to build/refactor and why),
not code. Implementation waves come after the user picks.

## Scope

The full lifecycle a pilot touches an asset through:

```
discover/announce → add/onboard → inventory (warehouse) → assign/lease →
pre-flight readiness → fly/control → usage record → post-flight (timeline/replay) →
maintenance → retire
```

Plus asset *control* surfaces: who may control what, handoff between operators,
crew visibility, command authority vs visibility (OPS-UX shipped authority≠visibility).

## State known at start (from memory + plan index — verify in R waves)

- Warehouse UX W1–W10 merged (inventory layer, wizard, maintenance/crew page).
- Zero-config onboarding Z1–Z5 merged (announce → inbox card → one-click add).
- Drone onboarding O1–O14 merged except operator-gated O9/O10.
- MAVLINK-COMMANDS merged: keyboard ops, retries, stream negotiation, firmware hardening.
- Operator UX cycles 3–7 merged: pre-flight triage, stale-not-live, readiness honesty.
- SPECCED not built: source-onboarding (generic add-a-vehicle), crew control (OPS-UX),
  missions M1–M8, CV panel UX U1–U5.
- Platform audit 2026-08-21: live-ops surface unscoped, no DB retention, crew is not a role.
- Camera-first rework PROPOSED and DECLINED (too big); two standalone defects noted.
- Asset model: Asset (owned, categorized, 1..n devices, attributes map), Device =
  plumbing, AssetUsage = sessions + telemetry; asset-unit leases are DOMAIN-SEPARATION
  W3 **spec-only** (zero code — R1 corrected this line's original claim).

## Delegation plan

| Wave | Agent | Scope → output |
|---|---|---|
| R1 | Sonnet, local | Code truth: asset lifecycle as implemented (warehouse, identity assignment, flight AssetUsage/leases, onboarding paths, maintenance). Flow diagram + gap list → `asset-flows/R1-lifecycle-inventory.md` |
| R2 | Sonnet, local | Pilot's actual journey end-to-end through the UI (Manage→Operate→Fly→post-flight) and API; friction/dead-ends/manual steps → `asset-flows/R2-pilot-journey.md` |
| R3 | Sonnet, local | Docs mining: every asset-related proposal already specced or deferred across docs/plans + conclusions; dedupe, status, why deferred → `asset-flows/R3-existing-proposals.md` |
| R4 | Sonnet, web | Industry: QGC/Mission Planner/Auterion/DJI FlightHub/military GCS asset & fleet practice — checklists, battery/airframe hours, handoff, logs, readiness → `asset-flows/R4-industry-practice.md` |
| O1 | Opus | Synthesize R1–R4 → `asset-flows/O1-SYNTHESIS.md`: candidate list, dedup, dependency notes |
| P | Fable | Ranked proposal to the user (impact-for-pilot vs cost), then plan freeze after user picks |

## Constraints

- Research only writes under `docs/plans/active/asset-flows/` (+ this file). No product code.
- Ground every claim in file paths / plan doc citations; industry wave cites URLs.
- CLAUDE.md context rule: prefer MODULE.md + plan docs over re-reading sources.

## Outcome (2026-09-01)

All waves ran same-day. Reports: `asset-flows/R1-lifecycle-inventory.md` (15 cited gaps),
`R2-pilot-journey.md` (ranked friction), `R3-existing-proposals.md` (deduped prior specs,
contradictions, declined-item salvage), `R4-industry-practice.md` (16-pattern catalog),
`O1-SYNTHESIS.md` (merged candidate catalog: 6 safety gaps §1, ~40 candidates §2,
7 user decisions §3, 9 stale-record corrections §4, dependency graph §5).

**Ranked proposal: [asset-flows/P-PROPOSAL.md](asset-flows/P-PROPOSAL.md)** — Tier 1 safety
(S1 grounding gates commands, S4 link/battery notifications, S3 one severity source,
S6 mediamtx auth) + Tier 2 quick wins recommended as cycle 1; Tier 3 features next cycle;
Tier 4 blocked on decisions D1–D7. Nothing scheduled until the user picks.

Stale-record corrections from O1 §4 applied same day: `vision-flight/MODULE.md` broken-wiring
gotchas marked RESOLVED; memory `platform-audit` (live-ops now scoped) and `source-onboarding`
(`onTelemetryDeviceDiscovered` deleted; `DeviceOrigin` shipped-but-unconsumed) corrected.
Unverified leftover: the ops-ux Wave E dev-group conflict (O1 §4 last row) — check before citing.

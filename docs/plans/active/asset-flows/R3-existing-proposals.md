# R3 — existing asset-flow proposals (docs mining)

Deduped catalog of every asset/control proposal already specced, deferred, declined, or half-built,
mined from `docs/plans/README.md`, `docs/plans/active/*.md`, `docs/plans/done/*.md`,
`docs/conclusions/*.md`. Cross-checked against `asset-flows/R1-lifecycle-inventory.md` (code truth,
2026-09-01) where R1 corrects a doc's claimed status. Purpose: so O1/Fable don't re-propose or
contradict a decision already made. Status legend: **specced** = written, no code; **deferred** =
explicitly parked with a reason; **declined** = user said no; **half-built** = shipped partially;
**done** = merged (listed only where a doc/memory claim about it needs correcting).

## §1. Catalog

### Onboarding

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| Zero-config lobby (Z1-Z5) | ZERO-CONFIG-ONBOARDING-CONTEXT.md | done | Devices announce → inbox card → one-click add; MAVLink lobby binds :14550 at boot | — | — |
| Onboarding firmware install (Z6) | ZERO-CONFIG-ONBOARDING-CONTEXT.md §Z6 | deferred | Push a lightweight companion firmware onto bare flight controllers | Lives outside this repo | — |
| Generic add-a-vehicle unification (S2-S5) | SOURCE-ONBOARDING-CONTEXT.md §1,§7 | specced | Collapse `register/discover/listen/drone` into one flow; simulate onto an existing asset via `DeviceOrigin`; fit-out table UI; delete `simulated` category | `discover/listen/drone` never got distinct UI (S1 shipped differently); C3 "simulated on the wrong entity" unresolved | R1 confirms `DeviceOrigin{LIVE,SIMULATED}` now exists (ARCHITECTURE-AUDIT-2026-08-26 R4, merged) — **the fix vector is built, just unused for the simulate flow** |
| Drone onboarding PROBE→VERIFY loop (O9/O10) | DRONE-ONBOARDING-PLAN.md §8 | half-built | Tier-A `PARAM_SET` write + SYSID assignment; companion video/GCS installer | Operator-gated by design, needs explicit go | O1-O8,O11-O14 merged behind `vision.onboarding.*.enabled=false` |
| Flight passport / config drift | DRONE-ONBOARDING-PLAN.md; ZERO-CONFIG-ONBOARDING-CONTEXT.md §7 row 3 (OQ2) | done, opt-in off | Snapshot params at onboarding, warn on drift | `vision.onboarding.probe.enabled=false` by default (OQ2 unresolved) — R1 gap #5: a pilot flying today gets no passport unless flipped | — |
| "Works with" vehicle compatibility matrix | ANY-DRONE-PLAN.md | idea | Publish which firmware/vehicle combos are known-good before a pilot tries to add one | Not started | — |
| ONVIF Profile S (PTZ + credentialed onboarding) | PLATFORM-AUDIT-ANALOGS.md move 7 | specced | Auth'd ONVIF discovery incl. PTZ capability, not just anonymous mDNS | Not started | Would also unblock the PTZ slew-to-cue flight-ops idea below |
| Camera-first onboarding rework | CAMERA-FIRST-PLAN.md (whole doc) | **declined** | Camera-as-primary-entity rework: profile boot (camera/full), credentialed ONVIF, per-zone auth | Owner declined 2026-09-01 as too big a rework | See §3 |
| PX4 support | R4 cross-check (industry gap), no PX4 plan doc found | idea | First-class PX4 vehicle support (repo is ArduPilot-centric) | No plan exists | — |

### Inventory & maintenance

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| Warehouse UX (W1-W10) | WAREHOUSE-UX-PLAN.md | done | Inventory layer, Inventory page, 5-group rail, onboarding wizard, maintenance/crew page | — | — |
| Documents/attachments per asset | WAREHOUSE-UX-PLAN.md §E8 | specced | `documents` table: manuals, registration, insurance, history (today: one photo, replace-on-upsert) | Small scope, never scheduled | Confirmed still open by R1 gap #14 |
| Crew notes UI | WAREHOUSE-UX-CONTEXT.md "deferred flagged not built" | half-built | `AssetNoteRepositoryPort` exists, no service/endpoint/UI above it | Domain landed, application+UI didn't | Confirmed still open by R1 gap #6 |
| Category delete verb | WAREHOUSE-UX-CONTEXT.md "deferred flagged not built" | deferred | `CategorySpec` has no delete verb | Not scheduled | — |
| Maintenance doesn't gate the command path | WAREHOUSE-UX-CONTEXT.md §W5 handoff | **open safety gap** | `MaintenanceRecord` gates `engage` only, not `arm/disarm` or `UsageTracker` session-open | Explicit W5 non-goal | Distinct from the grounding gap below — both real, both open |
| Custody grounding doesn't gate MAVLink commands | R1 gap #10 | **open safety gap** | `AssetCustodyService#ground` blocks `engage` but not `arm/disarm` | Explicit W5 non-goal | — |
| True SQL aggregate for fleet stats | PLATFORM-AUDIT-DB.md | idea | Replace in-memory fleet-summary aggregation with a real query | Not started | — |
| Fleet-wide log/blackbox ingest, param-drift audit, firmware dashboard, multi-site gateways | DRONE-INFRA-PLAN.md §I-f | idea | Fleet-ops infra: pull logs off vehicles, audit param drift across the fleet, per-site edge gateways, satcom check-ins | Future-tagged, no wave | — |
| Telemetry/detection retention + partitioning | PLATFORM-AUDIT-DB.md | specced | Time-bounded retention policy + table partitioning for firehose tables; fixes `db_audit_log` amplification | No DB retention exists at all (platform-audit 2026-08-21 finding, still true) | — |

### Authority & crew

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| Control claim + crew roles (CC-1..CC-6) | CREW-CONTROL-PLAN.md (whole doc) | **specced, frozen spec** | `ControlClaim`, `AssignmentRole{PIC,OBSERVER}`, invites, self-service password change; arbitrated single-holder control per asset | Nothing built yet | Corrects OPS-UX's own suggestion — see below |
| AssetUsage-as-control-holder | OPS-UX-PLAN.md §5 Wave D | **explicitly rejected in writing** | Proposed putting the control holder on `AssetUsage` | CREW-CONTROL-PLAN §9 "For the record" gives 3 reasons this is wrong, places `ControlClaimService` in vision-flight instead | CREW-CONTROL-PLAN |
| 4th `Role` constant for crew | OPS-UX-PLAN.md | **declined** | Add a dedicated `CREW` role enum value | Declined — "crew is not a role" (platform-audit 2026-08-21, reaffirmed in memory) | `AssignmentRole{PIC,OBSERVER}` (CREW-CONTROL-PLAN) is the accepted shape instead |
| Dev-group reconciliation (Wave E) | OPS-UX-PLAN.md §Wave E | half-built | Items 2-4 of the dev-group visibility fix | Partially shipped, rest unclaimed | — |
| Shared/switch-arbitrated control | (D9/D10, referenced across FLEET-RADIO-PLAN / CONTROLLER-UX-PLAN as "OPERATOR-CONTROL") | deferred | A third, distinct control-handoff model: physical switch or UI toggle arbitrates which connected operator's sticks are live | Reaffirmed across 3 docs as the largest unbuilt control-handoff idea; never turned into its own plan | Overlaps but is **not the same** as CREW-CONTROL's claim-based arbitration — keep separate |
| Multi-operator RC ceiling removal (T3.c) | FLEET-MIGRATION-PLAN.md §T3.c | specced | Move `DefaultManualControlService` from one-RC-session-app-wide to one-per-`(operator,asset)`, so different pilots can fly different assets concurrently | Not started | **Unstated dependency**: FLEET-RADIO-PLAN F9 notes lifting the app-wide ceiling without CREW-CONTROL's arbitration "makes contention worse" — build order matters, flag for O1 |
| One-RC-session-app-wide ceiling (F9) | FLEET-RADIO-PLAN.md §F9 | deferred | Remove the app-wide singleton RC-session ceiling | Explicitly deferred pending CREW-CONTROL landing first | — |
| No per-flight command exclusivity | R1 gap #3; PLATFORM-AUDIT-FINDINGS.md item 10 | **open, confirmed live** | `DefaultFlightCommandService` (arm/disarm/mode/RTH) has zero exclusivity — two scoped users can each command the same aircraft today | Fix is CREW-CONTROL-PLAN, spec-only | — |
| 13 unscoped endpoints | ARCHITECTURE-AUDIT-2026-08-26.md §Verdict | open | 13 endpoints left without an authority/visibility decision after the OPS-UX/LIVE-SCOPE work | Needs product decisions, not just code | — |
| MVP4 candidates: manager annotations/tasking, multi-operator presence, recording | MVP3-PLAN.md (done/) | idea | Deferred MVP4 feature list | Deliberately parked at MVP3 close | Not claimed by any later doc |
| Sent-vs-received channel monitor | ARCHITECTURE-AUDIT-2026-08-26.md §4 (D7) | open | No way to see whether a command actually reached the vehicle vs was just sent | Called "the biggest missing capability" in the audit | — |

### Flight-ops (control & command)

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| Controller binding rebuild (C1-C12) | CONTROLLER-SETUP-CONTEXT.md | **built, green, not merged** | Full gamepad/TX binding rework on `feat/controller-setup-c15` | Branch unmerged | — |
| Stick calibration | CONTROLLER-SETUP-CONTEXT.md §C12 | deferred | Per-stick deadzone/range calibration UI | Out of C1-C12 scope | — |
| TX modes 1/3/4, expo curves | CONTROLLER-SETUP-CONTEXT.md | declared out of scope | Alternate stick-mode layouts, exponential response curves | Explicit non-goal of the wave | — |
| Gimbal/camera actions as first-class controls | CONTROLLER-UX-PLAN.md | deferred | Bind gimbal/camera to dedicated controls instead of the generic AUX escape hatch | AUX mapping used as a stand-in | — |
| VehicleKind-shaped ControlProfile override / stick reversal | CONTROLLER-UX-PLAN.md §P14 | deferred | Let a profile explicitly override vehicle kind; per-stick reversal | Not started | — |
| RC-CONTROL Phase 2 (real airframe) | RC-CONTROL-PLAN.md | gated | Take the SITL-proven latency fix (~39ms→~9ms) to a live vehicle | Explicit "operator's step, needs a go" gate | Phase 1 done+committed |
| Ground-side RF injection (trainer port / ELRS standalone) | FLEET-RADIO-PLAN.md | deferred | Inject RC over a trainer port or standalone ELRS module instead of only through the FC link | 2 unverified hardware questions | — |
| Web kind-picker for controller (R2 web half) | FLEET-RADIO-PLAN.md §R2 | deferred | UI half of vehicle-kind override, waiting on CONTROLLER-UX merge | Blocked on CONTROLLER-UX-PLAN landing | — |
| Web channel picker 18→16 (R3 web half) | FLEET-RADIO-PLAN.md §R3 | deferred | Same — UI-side channel reduction picker | Same block | — |
| Serial/TCP SiK radio transport (F10) | FLEET-RADIO-PLAN.md §F10 | deferred | Support SiK telemetry radios over serial/TCP, not just UDP | Hardware-gated, no radio in hand | — |
| Per-vehicle-kind readiness rows (F15) | FLEET-RADIO-PLAN.md §F15 | deferred | Readiness checklist rows that vary by vehicle kind (rover vs copter) | Not started | — |
| Geofence AMSL-vs-AGL bug (F18) | FLEET-RADIO-PLAN.md §F18 | open defect | Geofence altitude reference is ambiguous/wrong for some vehicle kinds | Needs a decision, not just a fix | Related to, but distinct from, the already-fixed AMSL-as-AGL bug in `geo-pose` (marks, not geofence) |
| Manual-control multi-adapter registry (D2) | ARCHITECTURE-AUDIT-2026-08-26.md §4 | idea | `ManualControlRegistry` to pick the right adapter when more than one control transport exists | Not built | — |
| `supports()` honesty (Betaflight false-positive) (D3) | ARCHITECTURE-AUDIT-2026-08-26.md §4 | open defect | An adapter's capability-`supports()` can claim true for a firmware that doesn't actually support the verb | Not fixed | — |
| Missions M1-M8 | MISSIONS-PLAN.md (whole doc) | **specced, CONTESTED** | Map→aircraft waypoint mission upload/execute, XL spec | README explicitly flags this "Contested — reconcile in writing before anyone starts" | See §2 — 4 docs disagree on scope |
| Mission product slice pulled forward (T4) | FLEET-MIGRATION-PLAN.md §T4 (MD2) | specced | Pull `MissionService` forward as "the largest product win," ahead of MISSIONS-PLAN's own sequencing | Contradicts MISSIONS-PLAN's own phasing and MASTER-MATRIX's M3=NO | See §2 |
| Mission *tasking* (not execution) | MASTER-MATRIX.md Group M (M1) | idea, recommended | Lighter-weight tasking/assignment surface instead of full waypoint execution (~80h) | Recommended as the safer default over M3 | Recommended alternative to MISSIONS-PLAN's XL scope |
| Fleet command fan-out (T3.a/b, RTL-all, per-vehicle outcomes) | FLEET-MIGRATION-PLAN.md §T3 | specced | One command dispatched to many assets with per-vehicle result tracking | Not started | — |
| `goto(AssetId, GeoPosition)` (T3.d) | FLEET-MIGRATION-PLAN.md §T3.d | specced | Direct a specific asset to a map-picked point | Not started | — |
| Wind field from fleet / multi-aircraft deconfliction / coverage planner (C4/C5/C10) | MASTER-MATRIX.md Group C | idea (PUNCH) | Derive wind from fleet telemetry; deconflict multiple aircraft; auto-plan area coverage | Not started, ranked "punch" (high value, not urgent) | — |
| PTZ slew-to-cue from geolocation | PLATFORM-AUDIT-ANALOGS.md move 12 | idea | Point a PTZ camera at a geolocated detection automatically | Gated on ONVIF PTZ existing (onboarding table above) | — |
| Link health / rate control (W5) | MAVLINK-CORE-PLAN.md §W5 | specced | Structured link-health signal + adaptive rate control | Not started | — |
| Parameter/Mission protocol layer (W6) | MAVLINK-CORE-PLAN.md §W6 | specced | L5 MAVLink parameter+mission sub-protocols | Not started | Feeds both onboarding's Tier-A param work and Missions |

### Post-flight data

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| After-action evidence package | AFTER-ACTION-PLAN.md (done/) | done | Replay + timeline evidence export | — | — |
| Time-bounded telemetry export | AFTER-ACTION-PLAN.md §7 follow-ups | deferred | Export only a time window of telemetry instead of the whole session | Deliberately deferred at close | — |
| Raw unthinned telemetry export | AFTER-ACTION-PLAN.md §7 follow-ups | deferred | Export without the thinning applied for display | Deliberately deferred at close | — |
| Evidence signing/hashing | AFTER-ACTION-PLAN.md §7 follow-ups; PLATFORM-AUDIT-ANALOGS.md move 3 | specced | Cryptographically sign/hash the evidence package so it's tamper-evident | Deliberately deferred at close, reiterated in ANALOGS | — |
| Account-free share link | PLATFORM-AUDIT-ANALOGS.md move 10 | specced | Share a read-only replay/evidence link without requiring the viewer to have an account | Not started | — |
| KML/KMZ/GeoJSON/GPX import-export | PLATFORM-AUDIT-ANALOGS.md move 2 | specced | Standard geo-format import/export for marks, tracks, replay | Not started | — |
| CoT egress + MIL-STD-2525/APP-6 symbology | PLATFORM-AUDIT-ANALOGS.md moves 6/11 | specced | Cursor-on-Target output feed + standard military symbology on the map | Gated on track-identity landing | track-identity is BUILT on `feat/track-identity` (unmerged) — nearly unblocked |

### Platform

| Proposal | Source | Status | What | Why stalled | Superseded by |
|---|---|---|---|---|---|
| OpenAPI + machine tokens | PLATFORM-AUDIT-ANALOGS.md move 4 | specced | Publish OpenAPI schema + issue machine (non-human) API tokens | Not started | — |
| Webhook + MQTT v5 egress | PLATFORM-AUDIT-ANALOGS.md move 1 | specced | Push events out over webhook and MQTT v5, not just SSE/UI | Not started | — |
| PMTiles / offline basemap | PLATFORM-AUDIT-ANALOGS.md move 5 | specced | Self-hosted offline tile source for the tactical map | Needs a Carto-key workaround already found in operator-ux-6 (filtered OSM tile layer) | Partial precedent exists |
| Alert rules + acknowledge | PLATFORM-AUDIT-ANALOGS.md move 8 | specced | Rule-based alerting on telemetry/detection with an ack workflow | Not started | — |
| Domain-separation broker + worker leases (W2-W5) | DOMAIN-SEPARATION-PLAN.md §13 | specced | NATS JetStream broker (W2); worker role + asset-unit leases (W3, D7); learning extraction (W4); sim node (W5) | ARCHITECTURE-AUDIT-2026-08-26 confirms: "No NATS anywhere in the tree" | W1 (module split) merged; W2-W5 open |
| Asset-unit leases | DOMAIN-SEPARATION-PLAN.md §D7,F1; R1 gap #9 | specced, design-ahead | Fleet-scale worker ownership is leased per Asset-unit, not per stream | Design detailed, zero code — R1 flags this as design-ahead, not shippable now | — |
| mediamtx open credentials | PLATFORM-AUDIT-FINDINGS.md; ZERO-CONFIG-ONBOARDING-CONTEXT.md §6; CAMERA-FIRST-PLAN.md §C2; PLATFORM-AUDIT-ANALOGS.md §6 | **open, repeatedly rediscovered** | mediamtx has no auth on ingest/playback paths | Flagged in 4 separate docs across 3 weeks (2026-08-21 → 2026-09-01), never scheduled | See §2 |
| Live-ops surface unscoped | PLATFORM-AUDIT-FINDINGS.md (2026-08-21) | **done — memory/audit stale** | `StreamController`/SSE authority scoping | Was open at audit time | `docs/plans/done/LIVE-SCOPE-PLAN.md` fixed both (`StreamAccess`/`LiveAssetAccess`), confirmed live by R1 gap #11 |
| Silent MAVLink link failure | ARCHITECTURE-AUDIT-2026-08-26.md §Verdict | **done — audit item now closed** | Dead MAVLink socket logged and silently returned instead of raising | Open at audit time (2026-08-26) | FLEET-RADIO-PLAN R4 (merged, verified 2026-08-30): `LinkHealth` now carries `PeerId` + a typed link-failure event |
| 56-class N-1-arg constructor debt | ARCHITECTURE-AUDIT-2026-08-26.md §Verdict | half-built | Convenience-overload constructor sprawl across ~56 classes (e.g. `DefaultFleetSummaryService`) | Partially remediated, most left | CLAUDE.md §10 now bans the pattern going forward |

## §2. Contradictions & superseded items

1. **Missions — the 4-way disagreement (unresolved, highest priority for O1 to settle).**
   `MISSIONS-PLAN.md` specs a full XL waypoint-execution feature (M1-M8). `docs/conclusions/MOAT.md`
   §6 says stop considering mission/waypoint planning at all. `MASTER-MATRIX.md` Group M rates
   full execution (M3) **NO** and recommends lighter tasking (M1) instead. `FLEET-MIGRATION-PLAN.md`
   §T4 (MD2) says pulling `MissionService` forward is "the largest product win," contradicting both
   MISSIONS-PLAN's own sequencing and MATRIX's M3=NO. `docs/plans/README.md` itself already flags
   this doc as "Contested — reconcile in writing before anyone starts." **Do not let O1 silently
   pick a side by citing only one of these four.**

2. **OPS-UX's control-holder placement was wrong, and CREW-CONTROL-PLAN corrects it in writing.**
   `OPS-UX-PLAN.md` §5 Wave D suggested `AssetUsage` as the natural home for the control holder.
   `CREW-CONTROL-PLAN.md` §9 "For the record" rejects this with 3 named reasons and places
   `ControlClaimService` in vision-flight instead. Not a live disagreement — CREW-CONTROL-PLAN is
   the authoritative, newer correction. Cite CREW-CONTROL-PLAN, not OPS-UX, for this shape.

3. **Three distinct "who controls the vehicle" proposals exist — do not merge them.**
   (a) CREW-CONTROL-PLAN's `ControlClaim`/`AssignmentRole` — single-holder arbitration per asset,
   spec-only. (b) FLEET-MIGRATION-PLAN §T3.c — removes the *app-wide* one-RC-session ceiling so
   different operators can fly different assets concurrently; FLEET-RADIO-PLAN's F9 note implies
   this has an **unstated build-order dependency on (a) landing first**. (c) The
   shared/switch-arbitrated control idea referenced across FLEET-RADIO-PLAN/CONTROLLER-UX-PLAN —
   physical/UI toggle hands live sticks between two co-present operators on the *same* asset, a
   different problem from both (a) and (b). All three are open; none has been built.

4. **mediamtx open credentials — a persistent, never-scheduled gap.** Independently flagged in
   `PLATFORM-AUDIT-FINDINGS.md` (2026-08-21), `ZERO-CONFIG-ONBOARDING-CONTEXT.md` §6 (2026-08-30,
   "noted, not scheduled"), `CAMERA-FIRST-PLAN.md` §C2 (2026-09-01), and `PLATFORM-AUDIT-ANALOGS.md`
   §6. Four docs, three weeks, same open gap. Worth a standalone row in O1 regardless of what else
   ships — it keeps getting rediscovered instead of fixed.

5. **Two audit findings are stale — don't re-propose them.** Live-ops surface scoping and the
   silent-MAVLink-link-failure finding, both raised in `ARCHITECTURE-AUDIT-2026-08-26.md`, are now
   closed by `docs/plans/done/LIVE-SCOPE-PLAN.md` and `FLEET-RADIO-PLAN.md` R4 respectively —
   confirmed independently by `R1-lifecycle-inventory.md` gap #11 and by the FLEET-RADIO status in
   project memory. See §1 Platform rows.

6. **`AssetUsage` still has no pilot field — do not assume WAREHOUSE-UX closed this.** Earlier
   research in this session (pre-compaction) believed WAREHOUSE-UX's D7 had added
   `asset_usages.pilot_id`, closing `PLATFORM-AUDIT-DB.md` item 6. `R1-lifecycle-inventory.md`
   (code truth, checked 2026-09-01) says the 10-field canonical `AssetUsage` shape still has **no**
   pilot field — "who flew it" is unrecorded. Trust R1's code-truth read over the earlier claim.

7. **`DeviceOrigin{LIVE,SIMULATED}` is built, but the thing it was meant to fix isn't.**
   `SOURCE-ONBOARDING-CONTEXT.md` lists `DeviceOrigin` as an open proposal (S2). It actually shipped
   via `ARCHITECTURE-AUDIT-2026-08-26.md` R4 (merged). But the *consuming* fix — unifying the
   `simulate` flow so it marks a device simulated instead of forking a whole new `Asset` — is still
   open (coupling C3, `SOURCE-ONBOARDING-CONTEXT.md` §12: "S3/S4/S5 remain open"). Propose the S3-S5
   consumption, not another `DeviceOrigin`-shaped enum.

## §3. Declined items and salvage notes

- **Camera-first onboarding rework** (`CAMERA-FIRST-PLAN.md`, whole doc) — **declined** 2026-09-01
  by the owner as too large a rework (camera-as-primary-entity, full C1-C10 phased plan). **Do not
  re-propose the rework as a whole.** Salvage two standalone defects the plan surfaced independently
  of its own scope: (1) mediamtx credential/auth gap (wave C2) — real, independently confirmed
  4 times, see §2 item 4; (2) the discovery-inbox "mediamtx down vs nothing plugged in"
  ambiguity (also independently confirmed by `R1-lifecycle-inventory.md` gap #8) — both collapse to
  an empty list distinguished only by WARN-level logs.

- **4th `Role` constant for crew** (`OPS-UX-PLAN.md`) — **declined**. "Crew is not a role" is a
  standing decision (platform-audit 2026-08-21, reaffirmed in project memory). Salvage: the accepted
  shape is `AssignmentRole{PIC,OBSERVER}` plus `ControlClaim`, already fully specced in
  `CREW-CONTROL-PLAN.md`. Any future crew-visibility proposal should extend that enum, not add a
  `Role`.

- **`AssetUsage` as control-claim holder** (`OPS-UX-PLAN.md` §5 Wave D) — **declined in writing** by
  `CREW-CONTROL-PLAN.md` §9. Salvage: none needed — the correct placement (vision-flight,
  `ControlClaimService`) is already the spec. Re-proposing the `AssetUsage` placement would
  contradict a frozen decision.

## §4. Top 5 most pilot-valuable proposals

1. **Ship `CREW-CONTROL-PLAN.md` (CC-1..CC-6).** The single highest-leverage item in this catalog:
   it closes a *live, confirmed-today* safety gap — two scoped users can each command the same
   aircraft with zero exclusivity (R1 gap #3, `PLATFORM-AUDIT-FINDINGS.md` item 10) — and it
   unblocks two other stalled proposals at once (F9's RC-session ceiling, FLEET-MIGRATION T3.c).
   Fully spec-frozen already; the cost here is implementation, not design.

2. **Close the two command-path gating gaps together (maintenance-gates-command, custody-gates-command).**
   `MaintenanceRecord` only gates `engage`, not `arm/disarm` or `UsageTracker` session-open
   (`WAREHOUSE-UX-CONTEXT.md` §W5); `AssetCustodyService#ground` blocks `engage` but not
   `arm/disarm` (R1 gap #10). Both are small, both are safety-relevant, both were explicit
   non-goals of prior waves rather than oversights — a natural single wave.

3. **Finish the simulate-flow unification (S3-S5), now that its blocker is gone.**
   `DeviceOrigin{LIVE,SIMULATED}` already shipped (§2 item 7) — the hard part is done. The
   remaining work is deleting the parallel `simulated`-category `Asset` fork and routing
   `POST /api/simulations` onto the real asset's device instead. Small, unblocks training/testing
   without polluting the real fleet inventory.

4. **Resolve the missions contradiction in writing before building anything (§2 item 1).** Not a
   build item itself, but the highest-leverage *decision*: four documents disagree on scope from
   "full XL execution" to "don't build this at all." Whichever way it resolves — even
   `MASTER-MATRIX`'s lighter M1 tasking — the resolution unblocks or retires `MISSIONS-PLAN.md`,
   `FLEET-MIGRATION-PLAN.md` T4, and `MAVLINK-CORE-PLAN.md` W6 all at once.

5. **Fix mediamtx credentials.** Small, well-scoped, and the only item in this catalog independently
   flagged by four different docs across three weeks without ever being scheduled (§2 item 4). Low
   cost relative to how many other proposals (camera-first's salvage, zero-config's push registry,
   the eventual public share-link idea) sit downstream of "the media plane has no auth."

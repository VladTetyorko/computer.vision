# MISSIONS — context

Gathered before planning, per CLAUDE.md ("Before starting - create a file with a context"). This file
records **what already exists** so the plan does not re-invent it, and states the gap precisely.
The plan itself is `MISSIONS-PLAN.md`.

**Ask (verbatim):** *"plan a plan for missions integration / Start from UI need-to-have features. /
Think about how to add custom commands/plan parts / Then — think about integrating missions and map. /
Use the microservices and structure we already have"*

---

## 1. The gap, in one sentence

The tactical map can draw a route and the aircraft can be commanded — but **nothing carries a plan from
one to the other**. There is no mission concept in any context, no `MissionController`, and the one
route editor that exists feeds the **simulator**, never a real vehicle.

Proof (not inference):
- `station/vision-api/.../controller/` — 33 controllers, none named Mission. Map is three controllers
  (`MapLayersController`/`MapMarksController`/`MapDrawingsController`), flight is `FlightCommandController`.
- `FlightCommandPort` (`contexts/vision-flight/.../domain/port/`) exposes exactly four verbs —
  `setMode`, `arm`, `disarm`, `returnToHome` — plus `capabilities`. All one-shot, none positional.
- `docs/plans/active/MAVLINK-CORE-PLAN.md` §6 lists `MissionService` under **W6, gated**, and §9 open
  question 2 asks the operator directly: *"Mission upload priority — W6 or sooner? It is the largest
  product win in this plan (tactical map → aircraft) and the reason to care about the seam at all."*
  **This plan is the answer to that question.**

---

## 2. What already exists (four seams the plan must reuse, not rebuild)

### 2.1 A route editor — but wired to simulation only
`station/vision-web/src/app/shared/map/flight-plan-logic.ts` + `shared/map/fleet-plan-dialog/flight-plan-dialog.{ts,html,css}`
(docs/main/CYCLES-PLAN.md §7 CT-b). Pure, unit-tested logic: `EditorWaypoint {latitude, longitude,
altitudeMeters: number|null}`, `FlightPlanForm {waypoints, speedMps, routeMode}`, plus
`addWaypoint`/`moveWaypoint`/`updateWaypointAltitude`/`removeWaypoint`/`seedTriangle`/`parseManualWaypoints`.
A **modal dialog**, already reused by two unrelated pages (Devices' Simulate step, Command's
"Add a test drone"), following the `shared/map/` home precedent.

Its output goes to `buildTelemetryRequest(plan)` → `POST /api/simulations` → `TelemetryPlanRequest
{speedMps?, routeMode?, route: WaypointRequest[]}`. **Destination: a synthetic telemetry generator.**
This is the single largest piece of existing UI work the plan can lean on — the drawing interaction is
solved; only the destination is missing.

### 2.2 A route domain model — in the wrong context
`contexts/vision-simulation/.../application/` owns `Waypoint(latitude, longitude, altitudeMeters)`,
`TelemetryPlan(speedMps, mode, route)`, `RouteMode {LOOP, BOUNCE, ONCE}`. Range-validated in compact
constructors. Deliberately **not** the same type as `adapter-simulation`'s own `RoutePlan.RouteMode`.

These are simulation's input types. A real mission is a different thing (it has per-item commands, an
upload lifecycle, a vehicle-side sequence number, ArduPilot's seq-0-is-home quirk) — the plan must
decide whether missions get their own model or generalize this one. **Recommendation for the plan to
argue: their own, in flight; simulation keeps its types.** The overlap is the *shape* of a route, not
its meaning.

### 2.3 The MAVLink microservice seam — already built, mission slot already reserved
`drone-link/mavlink-core/` (**W1–W4 done**). This is the "microservices we already have" the ask
refers to — and W4 means the seam is not merely built but **proven in production**: `adapter-mavlink`
was rebuilt on it (155/155 green, both SITL tests run un-skipped), `MavlinkSocketHub` and the two UDP
stream classes are deleted. MAVLINK-CORE-PLAN §6's gate — *"only after W4 proves the seam"* — is
therefore **already satisfied**; W6/Mission is unblocked today, not pending. (Corrected after drafting:
mavlink-core's own MODULE.md still read "Used by: nobody yet" and misled the first pass of this file.
Fixed in that doc.) What matters here:

- **`RequestResponse`** (`com.drones.mavlink.service`) — the shared Family-A machine: *send X, expect Y
  matching key K within T, retry N times*. Continuation-based. `MissionService` is meant to be built on
  this, not beside it. Two real `CompletableFuture` bugs were already found and fixed inside it, and its
  MODULE.md says so explicitly: *"worth remembering for W6's Mission/FTP if they ever re-register the
  same key without going through `RequestResponse` itself — reuse this class rather than re-deriving
  the same race."*
- **`MavlinkCoreSettings` already carries `Mission(Duration timeout, Duration itemTimeout, int retries)`** —
  the config record exists and is unused. Defaults pinned by spec: **1500 ms** timeout, **250 ms per
  item**, **5** retries. MAVLINK-CORE-PLAN §2.2 calls Mission *"the only numerically pinned service"* —
  every other service's timeouts are implementation-defined. Per CLAUDE.md rule 1, these stay
  configuration; they are already modelled as such.
- **`com.drones.mavlink.api`** (L4½) — the broker seam: `CommandGateway`/`CommandRequest`/
  `CommandOutcome`/`VehicleKeyResolver`, value-typed records + caller-supplied correlation ids, so a
  NATS/Kafka driving adapter is a translation, not a redesign (D9). A `MissionGateway` belongs here,
  shaped the same way.
- **Two known costs the plan must budget for, both already documented:**
  1. `DefaultCorrelator.extractKey` recognizes exactly one ack type (`CommandAck`, wire id 77). Mission
     needs another `instanceof` branch — **an L3 change**, called out in mavlink-core's MODULE.md as an
     "accepted, documented coupling" that W6 would trigger. Whether to generalize it to a registry is an
     open design call the plan should settle, not discover.
  2. Mission is the one service needing real per-service logic on top of Family A: **server-driven
     lock-step** ("re-request the expected seq, drop out-of-order"), unlike Command's simple send/ack.

- **Correctness traps, pinned in MAVLINK-CORE-PLAN §2.2 — non-negotiable:**
  - `MISSION_ITEM_INT` **must** use `MAV_FRAME_GLOBAL_*_INT`. Non-INT global frames get silently rounded
    into the int32 lat/lon fields and **corrupt the position**. Silent, not an error.
  - **ArduPilot deviates from spec on atomicity** — a partial upload can leave mixed state on the
    vehicle. There is no transaction. Any UI that says "uploaded" must be able to say "partially
    uploaded" too, and a read-back is the only ground truth.
  - **ArduPilot uses seq 0 = home**, so item indices on the wire are not the operator's waypoint indices.
  - `COMMAND_INT` is preferred over `COMMAND_LONG` for anything positional (D7) — already supported by
    `CommandService.sendInt`.

### 2.4 The map — layers, grants, marks, drawings, scoped SSE
`contexts/vision-map/` (MAP-REWORK done). `MapLayer(kind: COP|TEAM|PERSONAL, ownership, grants)`,
`LayerGrant(subjectType, subjectId, AccessLevel VIEW|CONTRIBUTE|MANAGE)`, `Mark(... MarkSource
MANUAL|DETECTION, Verification, Affiliation)`, `Drawing(DrawKind LINE|POLYGON|ARROW|TEXT, points,
label, colorToken)`, `MapEvent` for live updates.

**This is the integration surface the third part of the ask is about.** A mission route is
geometrically a `LINE`/`POLYGON`'s cousin and shares the layer-visibility question, but a drawing is
annotation and a mission is an instruction — the plan must decide whether a mission *is* a layer
entity, *projects onto* one, or merely *renders beside* them. That decision determines whether mission
visibility inherits `LayerGrant`/`AccessLevel` (which already solves "who can see whose plan") or needs
its own authorization, and it is the single highest-leverage architectural call in this whole plan.
**Priced honestly, not "for free"**: `LayerId` is declared in **map's own domain**
(`contexts/vision-map/.../domain/model/LayerId.java`), not in `vision-kernel` — so a mission carrying a
`LayerId` costs either a new flight→map dependency edge or relocating the id into the kernel. Neither is
fatal; both are real, and the choice must be made with that cost visible.

---

## 3. Constraints the plan must respect

| # | Constraint | Source |
|---|---|---|
| C1 | Dependency rule: kernel ← platform ← contexts ← adapters ← app. Adapters never depend on each other. Spring only in app/api/adapters. | CLAUDE.md, ArchUnit-enforced |
| C2 | Missions are **command TX** — the deliberate, narrow break in the RX-only doctrine. Every widening needs explicit operator go. | `docs/main/CYCLES-PLAN.md` §0; DRONE-INFRA I-e |
| C3 | Scope gate + audit on every command attempt; a *command* denial is **403 and audited**, a scoped *read* is 404. Asymmetric on purpose. | vision-flight MODULE.md Gotchas |
| C4 | No magic numbers. Runtime-variable values → database + cache, not properties. | CLAUDE.md rule 1 |
| C5 | Newest data wins; failsafe and up-to-date are priorities. A stale mission shown as current is the same class of defect as the frozen detection boxes just fixed. | CLAUDE.md rule 9 |
| C6 | mavlink-core has **zero project dependencies** and must never learn what a `Device` is. Translation happens in `drone-link/mavlink`. | MAVLINK-CORE-PLAN D2 |
| C7 | Port class constructors frozen; the 135 adapter tests are the safety argument. | MAVLINK-CORE-PLAN §8 |
| C8 | Angular: three-file components, Component→Facade→Store→Service, feature-responsibility folders, design tokens. | memory + UI-ARCHITECTURE/STYLE-TOKENS plans |
| C9 | SITL is the only honest acceptance gate for anything that reaches a vehicle. A skipped SITL run proves nothing. | MAVLINK-CORE-PLAN §9 |

---

## 4. What the plan must answer, in the ask's own order

**(a) UI need-to-have features — first, and the plan starts here.**
Not "what could a mission UI have" but what is *needed* to fly one safely. The just-completed
CV-UX/MAP-UX research established the house method: inventory the controls, count how many are
answerable, name the honesty defects. Apply it here **before** designing, because a mission UI's failure
mode is not clutter — it is an operator believing a plan is on the aircraft when it is not (§2.3's
atomicity trap). Minimum questions the UI must answer at rest: *what is planned, what is actually on the
vehicle, are they the same, what happens at the end, and can I stop it.*

**(b) Custom commands / plan parts — the extensibility model.**
A mission is a sequence of heterogeneous items: navigate-to, loiter, change speed, trigger camera,
start/stop CV, return. The plan must pick an extension shape that lets a new item type be added **as
data plus one small class**, not as edits across five layers — and must say where the boundary sits
between MAVLink-native items (`MAV_CMD_NAV_*`, executed by the flight controller) and **station-side
items** (start recording, enable detection on a stream, drop a mark) that this platform executes itself
because the FC has never heard of them. That split is the interesting part of the design and the thing
that makes this platform's missions worth more than a bare GCS's. Reuse the discriminated-record idiom
this codebase already uses; reuse `MapDrawingsController`'s own kind-plus-payload precedent.

**(c) Missions × map integration — last.**
Editing a route on the tactical map, seeing the flown track against the planned one, promoting a
verified mark into a mission item, mission visibility inheriting `LayerGrant`. See §2.4 for the call
that gates all of it.

---

## 5. Open questions for the operator (the plan should surface, not silently pick)

1. **Real upload, or plan-and-simulate first?** Everything in §2.1/§2.2 already works against the
   simulator. A wave that ends at "mission uploads to SITL" is a genuine product step and de-risks the
   FC-facing half; a wave that ends at a real aircraft needs C2's explicit go.
2. **MAVLink-native vs station-side item execution** — how far does the station's own sequencer go? A
   station-side executor is strictly more capable and strictly less failsafe (it needs a live link;
   the FC does not).
3. **Does a mission belong to a map layer?** §2.4. "Yes" inherits a working permission model but costs a
   flight→map edge or a `LayerId` relocation into the kernel; "no" means missions need their own scoping.
4. **Kafka vs NATS** for mission lifecycle events, if any — still unresolved repo-wide (MAVLINK-CORE
   memory note: user wants Kafka, DOMAIN-SEPARATION says NATS). Missions should not be where this gets
   decided by accident.

---

## 6. Branch and delegation

One task, one branch: `feat/missions` off the current work. Sub-branch per wave.
Fable authors `MISSIONS-PLAN.md` (architecture only — no code, no tests). Opus owns flow and module-level
decisions. Sonnet implements per wave with disjoint file scopes, each wave ending green + MODULE.md updated.

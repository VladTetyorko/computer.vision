# DRONE-ONBOARDING — context

Gathered before planning, per CLAUDE.md ("Before starting - create a file with a context"). This file
records **what already exists** so the plan does not re-invent it, states the gap precisely, and
separates what was *verified by reading code* from what is *inferred*. The plan itself is
`DRONE-ONBOARDING-PLAN.md`.

**Ask (verbatim):** *"how our system will interact with a drones before flight, in flight and after
flight. I need a good, proper flow of adding a drone to our app not only from video and telemetry part,
but the automatic pipeline where I can connect the drone, and app will setup all internally and on
drone's side. Something like an auto-software integration. Don't implement, but think how we can do
that and add a plan"*

Two distinct asks, both in scope:
1. **The lifecycle** — before / in / after flight, owned and explicit.
2. **The onboarding pipeline** — plug the drone in, the platform configures *both sides*.

---

## 1. The gap, in two sentences

**Lifecycle:** the platform has no flight lifecycle at all — what it calls a flight (`AssetUsage`) is
opened by *a video stream starting* and closed by the last one stopping
(`contexts/vision-perception/src/main/java/com/drones/vision/perception/application/pipeline/UsageTracker.java`
class javadoc, "Lifecycle" section), so an aircraft that arms, flies and lands with no video open
records nothing, and one parked on a bench with a stream running records a "flight".

**Onboarding:** the platform *tells* the operator what to configure and never *observes, repairs or
verifies* it — I-g's wizard is open-loop by construction (docs/conclusions/ANY-DRONE-PLAN.md §0), and
nothing in the repo writes a single setting to a vehicle or a companion computer.

---

## 2. What already exists — verified by reading code

The main failure mode for this plan is rebuilding one of these. Every row below was confirmed against
a real file, not against a doc's claim about a file.

### 2.1 Discovery → asset is already four clicks (I-b + I-g, both DONE)

- `drone-link/mavlink/src/main/java/com/drones/vision/adapter/mavlink/MavlinkHeartbeatScanner.java`
  exists — "what drones can I hear right now" is solved.
- `drone-link/mavlink/.../MavlinkGateway.java` is the fleet gateway: one shared `UdpListenLink` +
  `MavlinkSession` per bind address, reference-counted, with `VehicleClaimPolicy` splitting *project*
  policy (which `Device` owns which sysid) from *protocol* facts (who is on the air). Rebuilt on
  `mavlink-core` in W4.
- The wizard is `station/vision-web/src/app/features/onboarding/**`, five steps —
  `profile → connect → test → create → assign` (`onboarding-logic.ts#nextStep`), five connect methods
  (`register | discover | listen | drone | simulate`).
- `drone-config-logic.ts` renders the firmware × link picker (`ardupilot|inav|betaflight` ×
  `elrs|esp32|companion`) with copy-paste blocks parameterized by the server's real address, sourced
  from `infra/edge/*.md` — "never contradicted", per its own javadoc.
- Backing endpoints exist: `POST /api/discovery/scan` (`DiscoveryController`), `POST /api/devices/probe`
  (`DeviceProbeController`), `GET /api/system/network` (`SystemNetworkController`).

**But the pipeline dead-ends at the browser.** No Java code anywhere calls `DeviceService#register` or
`AssetService#create` from a scan result — the only exit from `DiscoveredDevice` is
`DiscoveryController` → JSON → a prefilled form the operator confirms. Worse,
`station/vision-api/.../dto/DiscoveredDeviceResponse.java` **drops `suggestedStream.options()` on the
wire**; the MAVLink path only survives because `MavlinkHeartbeatScanner` duplicates `sysid` into
`details`, which `drone-scan-logic.ts` then reconstructs. And there is no identity or dedup on
`Device`: no unique constraint on `(protocol, uri)` or `options.sysid` in the domain or in
`storage/persistence/.../db/migration/V1__baseline.sql`; duplicate prevention exists only as
`MavlinkGateway`'s runtime claim registry, surfaced advisorily as `details["claimed"]`.

**Conclusion: the discovery and guidance halves are built and good. The plan must close the loop
around them — a server-side registration command, a lossless wire shape, and a real identity — not
build another wizard.**

### 2.2 mavlink-core already contains most of the probe/remediate transport

Read `drone-link/mavlink-core/MODULE.md` (W1–W4 done, 102 tests, `adapter-mavlink` rebuilt on it):

- **`MessageIntervalService` already exists** — `MAV_CMD_SET_MESSAGE_INTERVAL` **and**
  `MAV_CMD_REQUEST_MESSAGE`, both as plain `COMMAND_LONG`s over `CommandService`, microsecond
  conversion already handled. This is ANY-DRONE §1.3 *Mechanism A* and the `AUTOPILOT_VERSION` request
  of §1.1, **already built at the library level and unused by the platform.** That single fact changes
  the plan's cost model more than anything else found.
- `RequestResponse` — the shared "send X, expect Y, retry N" machine, with two real
  `CompletableFuture` races already found and fixed inside it. Reuse, never re-derive.
- `com.drones.mavlink.api` — the broker seam (`CommandGateway`/`CommandRequest`/`CommandOutcome`/
  `VehicleKeyResolver`), value records only. A parameter gateway belongs here, shaped identically.
- **The one real cost:** `DefaultCorrelator.extractKey` recognizes exactly one ack type, `CommandAck`
  (wire id 77). `PARAM_VALUE` correlation needs another branch — an **L3 change**, documented in that
  MODULE.md as an accepted coupling. MISSIONS-PLAN **D6** already decides the shape: a compiled-in
  class→`MatchKey` table in `session`, public seam unchanged. This plan must *consume* D6, not
  re-decide it, and must be sequenced against whoever lands it first.

### 2.3 The flight-command seam and its capability seed

`contexts/vision-flight/MODULE.md`:
- `FlightCommandPort` — `supports(Device)`, `setMode`, `arm`, `disarm`, `returnToHome`,
  `capabilities(Device)`. One implementation: `drone-link/mavlink/.../MavlinkFlightCommander`.
- `record FlightCapability(boolean commandable, boolean armSupported, boolean modeSelectSupported,
  List<String> selectableModes)` — **this is the capability model's seed**, and it is already the
  thing the UI is supposed to gate buttons on.
- `DefaultFlightCommandService(AssetService, FlightCommandPort, AuditTrailPort)` — the gate idiom this
  plan must copy verbatim: out-of-scope **command** → 403 **and audited**; out-of-scope **read** →
  404; one `AuditEntry` per actual attempt; resolving no commandable device is not an attempt.

### 2.4 Sessions live in warehouse, not flight — and CLAUDE.md is stale on this

`AssetUsage` is `contexts/vision-warehouse/src/main/java/com/drones/vision/warehouse/domain/model/AssetUsage.java`;
it moved there in DOMAIN-SEPARATION **W1.6c**, and `contexts/vision-flight/MODULE.md` says so
explicitly ("What it deliberately does not do"). CLAUDE.md's module index still reads
*"vision-flight | Flight sessions (AssetUsage)"* — **stale**. The plan's §2 must not repeat it.

`AssetUsage(id, assetId, startedAt, endedAt, startPosition, lastPosition, sampleCount, streamId)` —
no phase, no verdict, no outcome, no configuration snapshot. It is a *stream session summary* that has
been called a flight.

### 2.5 The readiness rule is in the browser

`station/vision-web/src/app/core/telemetry/flight-state-logic.ts#derivePreflight` computes the five
pre-flight rows (video, telemetry, GPS, battery, armable), and the battery bar is a TypeScript
constant — `telemetry-logic.ts#BATTERY_LOW_PERCENT` (45%), documented as such at
`flight-state-logic.ts:188`. `features/preflight/preflight-logic.ts` says in its own javadoc that
saved/editable checklist templates are **not built** because no checklist entity or endpoint exists.

So today: **no server-side readiness verdict, no GO/NO-GO, no recorded override, no fleet readiness
board** — and a threshold that CLAUDE.md rule 1 says should not be a hardcoded literal. The rule is in
the wrong layer.

### 2.6 The edge kit is documentation, not an install channel

`infra/edge/` holds `companion-rpi.md`, `elrs-backpack.md`, `esp32-bridge.md`,
`mavlink-router/main.conf`, `systemd/mavlink-router.service`, `systemd/vision-rtsp-push.service`.
`companion-rpi.md` §Setup is four manual steps ending in "edit the `CHANGE-ME` placeholders" and a
hand-written `POST /api/devices`. `infra/sitl/` holds the SITL farm (I-c). **There is no agent on the
companion, no install script served by us, no credential issuing, and nothing that reports back.**

### 2.7 How an adapter gets selected — and the asymmetry

Everything is discriminated on one lower-case string: `Device.stream().protocol()`.

- **Video** has a real registry: `contexts/vision-perception/.../pipeline/VideoSourceRegistry.java`
  — `sources.stream().filter(s -> s.supports(descriptor)).findFirst().orElseThrow(new
  UnsupportedProtocolException(...))`. Beans collected in
  `station/vision-app/.../wiring/VideoSourceWiring.java`; **bean order decides first-match.**
- **Telemetry has no registry at all**: an inline `for` loop with `break` inside
  `UsageTracker#subscribeTelemetry`, and **a silent no-op when nothing matches**. Two
  implementations only: `MavlinkTelemetrySource` (`"mavlink"`) and `SimulatedTelemetrySource`
  (`"sim"`).
- `FlightCommandPort` is injected as a **single bean**, and
  `DefaultFlightCommandService#firstCommandableDevice` picks the first device it `supports`.

That asymmetry — "no video adapter for this protocol" throws and is visible, "no telemetry source for
this device" is silent — is directly relevant to §5's capability model and to C7.

### 2.8 The thinking document this plan executes

`docs/conclusions/ANY-DRONE-PLAN.md` (2026-08-09) is the design: **PROBE → DIAGNOSE → REMEDIATE →
VERIFY**, the Tier A/B/C parameter-write policy, the vehicle passport, the video half. It is explicitly
*"not yet an authoritative spec"*. `docs/conclusions/MOAT.md` §4 says this loop is the funnel in front
of every other pillar and ranks it the next investment.

**This plan is that document's engineering successor.** It realises §1.1–§1.4, §2, §4 and §5; it does
not restate them, and where it deviates it says so.

---

## 3. Verified vs inferred

| Claim | Status |
|---|---|
| `UsageTracker` opens/closes `AssetUsage` on stream start/stop | **verified** — class javadoc, `onStreamStarted`/`onStreamStopped` |
| `AssetUsage` lives in warehouse, has no phase/verdict field | **verified** — read the record |
| `MessageIntervalService` exists and **nothing outside mavlink-core references it** | **verified** — `grep -rn "MessageIntervalService" --include=*.java` excluding that module returns zero hits |
| `DefaultCorrelator` matches only `CommandAck` | **verified** — mavlink-core MODULE.md Gotchas |
| Preflight thresholds are frontend constants | **verified** — `flight-state-logic.ts:188` |
| **Nothing in the repo writes a parameter to a vehicle** | **verified** — zero `PARAM_SET` references in any `src/main`; `MavlinkFlightCommander` sends exactly two commands, `MAV_CMD_DO_SET_MODE` and `MAV_CMD_COMPONENT_ARM_DISARM` |
| `AUTOPILOT_VERSION` (#148) is never requested or decoded | **verified** — zero Java references; `MavlinkTelemetryDecoder` decodes 17 message types, not that one |
| `FlightCapability` is derived from the HEARTBEAT firmware string + mavType, not from a capability bitmask | **verified** — `MavlinkFlightCommander#capabilities` |
| Discovery results never become inventory server-side | **verified** — full consumer set of `DiscoveredDevice` enumerated; no call into `AssetService`/`DeviceService` |
| No unique constraint exists on a device's endpoint or sysid | **verified** — `V1__baseline.sql` `devices` has only a UUID PK |
| DJI/closed-SDK vehicles are ingest-only for us | **verified as a documented position** — `ARCHITECTURE.md:221` routes digital FPV/DJI through RTSP/UDP "from ground station", with no control column |
| The companion recipe's step count | **verified** — `infra/edge/companion-rpi.md` §Setup |

---

## 4. Constraints inherited

| # | Constraint | Source |
|---|---|---|
| C1 | Dependency rule kernel ← platform ← contexts ← adapters ← app; adapters never depend on each other; Spring never in a context | CLAUDE.md, ArchUnit-enforced |
| C2 | The measured context DAG: warehouse is the pure leaf, 14 surviving edges. A **new edge is expensive and must be priced** | DOMAIN-SEPARATION-W1 §16 |
| C3 | Command TX is the one deliberate break in the RX-only doctrine; every widening needs explicit operator go | CYCLES-PLAN §0, DRONE-INFRA I-e |
| C4 | Command denial = 403 + audited; scoped read denial = 404. Asymmetric on purpose | vision-flight MODULE.md Gotchas |
| C5 | Authority ≠ visibility: `canManage(Ownership)` / `canAdminister()` are the *authority* predicates; `includes(...)` is only visibility | `core/vision-platform/.../VisibilityScope.java`, merged 2026-08-17 |
| C6 | No magic numbers; runtime-variable values → database + cache, not properties | CLAUDE.md rule 1 |
| C7 | Never fake a read. "Unknown" is a first-class state and must say *why* | MOAT §3, repo-wide poka-yoke doctrine |
| C8 | Newest data wins; a stale reading shown as current is a defect | CLAUDE.md rule 9 |
| C9 | mavlink-core must never learn what a `Device` is; translation stays in `drone-link/mavlink` | MAVLINK-CORE-PLAN D2 |
| C10 | SITL is the only honest acceptance gate for anything that reaches a vehicle. A skipped SITL run proves nothing | MAVLINK-CORE-PLAN §9, MISSIONS C9 |
| C11 | A hand-written synthetic vehicle may test *more* than SITL, never *instead* | MISSIONS-PLAN D14 |
| C12 | Angular: three-file components, Component→Facade→Store→Service, feature folders, design tokens | UI-ARCHITECTURE / STYLE-TOKENS plans |
| C13 | Missions are **not** this plan's concern. Onboarding hands off to `MISSIONS-PLAN`; it must not re-decide mission model, upload protocol, or station-item execution | MISSIONS-PLAN §4 |
| C14 | **A concurrent workstream is changing the persistence floor.** `docs/plans/active/POSTGRES-ONLY-CONTEXT.md` (branch `fix/postgres-only-auth`, uncommitted in the tree as of 2026-08-17) is removing the in-memory devsupport repositories and the `vision.persistence.enabled` opt-in in favour of Postgres + Flyway only. Any wave of this plan that adds a repository must target **whatever that workstream leaves behind**, not today's dual in-memory/JPA shape | observed in `git status`; `station/vision-app/.../PersistenceWiringConfiguration.java`, `VisionPersistenceProperties.java` modified, `AuthSeedRunner` deleted |

---

## 5. How many manual steps onboarding takes today

Counted against `infra/edge/companion-rpi.md` (the recipe that carries both video and telemetry) plus
the wizard's own step list. **20 steps, of which the app performs 0 on the drone side.**

| Where | Steps |
|---|---|
| Physical / vendor tooling (unavoidable) | 3 — wire FC UART ↔ companion, connect camera, flash/prepare the OS image |
| Flight-controller config, in someone else's GCS | 2 — `SERIALx_PROTOCOL` + baud; stream rates (`SRx_*`) if messages are missing |
| Companion computer, by hand over SSH | 6 — install `mavlink-router` + `ffmpeg`; copy `main.conf` and edit `Device`/`Address`; copy 2 systemd units and edit `CHANGE-ME`; `daemon-reload`; `enable --now` |
| In the app | 6 — wizard `profile`, `connect` (firmware×link), `listen` (scan+pick), `test` (probe), `create`, `assign` |
| Still by hand after the wizard | 3 — register the RTSP video device as a second device on the asset; set `SYSID_THISMAV` if a second aircraft exists; start the stream so a session opens |

The wizard's own six are good and stay. **The other fourteen are the plan's target**, and roughly nine
of them are genuinely automatable (see the plan's §4 capability matrix).

---

## 6. Open questions the plan must surface, not silently answer

1. **Does a companion agent exist, or does the platform stay agentless?** An agent is the difference
   between "we generate a script you paste" and "we install and manage the edge". It is also new
   attack surface on a machine that flies.
2. **Which parameter tier is authorized?** ANY-DRONE's Tier A (reporting) / B (link+failsafe) / C
   (flight-critical, never). The plan proposes A only; B needs an explicit go; C is not negotiable.
3. **Does a readiness verdict ever *block* a flight, or only advise?** A blocking gate is a safety
   feature and a support burden; an advisory one changes nothing today.
4. **Is `AssetUsage` extended, or does a separate flight-phase record appear?** Extending touches
   warehouse, which every context reads.

---

## 7. Branch and delegation

One task, one branch: `feat/drone-onboarding`, sub-branch per wave. Fable authors
`DRONE-ONBOARDING-PLAN.md` (architecture only). Opus owns module-level flow and the two waves that
touch a live vehicle. Sonnet implements per wave with disjoint file scopes, each wave ending with its
scoped build green and MODULE.md updated.

# vision-simulation

Synthetic asset and flight-plan orchestration (docs/main/CYCLES-PLAN.md §1b, §3, §5, §7, §9): the
one-call, zero-hardware "simulate a drone" entry point — registers a synthetic asset with a video
device and a telemetry device, wires them to whichever transport (in-process, RTSP/MJPEG push for
video; in-process or MAVLink UDP for telemetry) the caller asked for, and tears the whole thing down
again. Owns simulation-specific orchestration only — it composes `vision-warehouse`'s `AssetService`
and `vision-perception`'s stream/feed machinery rather than reimplementing asset CRUD or the video
pipeline. **Has no domain layer of its own** — every domain type this context touches (`Asset`,
`Device`, `PipelineConfig`, `FeedSpec`, …) is owned by warehouse or perception; this context is
application-layer only.

Extracted from the flat `vision-domain`/`vision-application` modules in **W1.7b**
(docs/plans/active/DOMAIN-SEPARATION-W1.md §16) — a directory move
(`com/drones/vision/simulation/**`), no package rename, no behavior change.

**Depends on:**
- `vision-kernel` — every typed id, `Ownership`, `StreamDescriptor`
- `vision-platform` — declared in the POM (transitively required by `vision-warehouse`/
  `vision-perception`'s own published types) but **not imported directly** by any class in this
  context today — no service here writes an audit line or checks a `VisibilityScope` itself
  (`AssetService#create`, which this context calls into, does both on warehouse's behalf)
- `vision-warehouse` — `Asset`/`Device`, `warehouse.application.asset.AssetService`/`AssetSpec`/
  `AssetSummary`, `warehouse.application.device.DeviceService`/`DeviceRegistration` (builds one
  `AssetSpec` and delegates creation/streaming to `AssetService`, so every rule it enforces —
  category validation, audit, device registration — applies here without duplication). No longer
  depends on `CategoryRepositoryPort` (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R4 —
  see Status: swapped for `DeviceService`, a net-zero-parameter change on `DefaultSimulationService`'s
  constructor)
- `vision-perception` — `FeedId`/`FeedSpec`/`PipelineConfig`, `FeedTransmitterPort`,
  `perception.application.pipeline.FeedTransmitterRegistry` (RTSP/MJPEG video TX),
  `perception.application.stream.AssetStreamService` (stream-starting orchestration, split out of
  `AssetService` in W1.6e — a CRUD context should not import the runtime's own config types into its
  published interface, so this context calls the runtime directly for that one step)

**Used by:** `vision-api` (`/api/simulations/**`), `vision-app` (wiring, `SimulationResumeRunner`)
**Build/test:** `./mvnw -B -pl contexts/vision-simulation test` — **72 tests green** (R4 added
`fitSimulatedDevice`, +1 net over W1.7b's 71)

## Package shape

```
com.drones.vision.simulation.application   — the whole context; no domain package (see Purpose)
```

Single-feature context — collapses straight to `<context>.application` with no feature subpackage,
same rule `events`/`flight` follow (no `simulation.application.simulation`).

## API surface

### `com.drones.vision.simulation.application`
- **Command/read-model records**: `SimulationSpec(displayName, videoPath, latitude, longitude, autoStart, transport, plan, telemetryTransport)`
  (`transport` a `SimulationTransport`, non-null-validated in the canonical ctor; `videoPath` **nullable**
  — `null` means a fully synthetic simulation with no video file at all, docs/main/CYCLES-PLAN.md §9 —
  the compact ctor still rejects a genuinely blank non-null string, and cross-validates
  `videoPath`×`transport`: a `null` path is only valid with `transport=DIRECT`, since `RTSP`/`MJPEG`
  have no in-process renderer output to push over the wire; `telemetryTransport` is a
  `TelemetryTransport` — nullable *at the constructor* purely so pre-existing shorter-arity call
  sites keep compiling, but the compact ctor immediately normalizes `null`→`TelemetryTransport.SIM`,
  so the accessor is never actually `null` once the record exists; orthogonal to `transport` — video
  and telemetry are two independent devices, so every combination, including a `null` `videoPath`
  synthetic sim, is valid); `SimulationTransport` enum `{DIRECT, RTSP, MJPEG}` (video); `TelemetryTransport`
  enum `{SIM, MAVLINK}` (telemetry, mirrors `SimulationTransport`'s own top-level-enum convention);
  `SimulatedAsset(assetId, streamId)` (`streamId` nullable when `!autoStart`); `TelemetryPlan(speedMps, mode, route)`
  (a configurable flight plan for the synthetic telemetry track — converted into `SimulatedTelemetrySource`'s
  (`adapter-simulation`) `route`/`speedMps`/`routeMode` device options, in place of bare `lat`/`lon`
  home-point options, once `SimulationSpec#plan()` is non-null; `speedMps` positive when given,
  `route` null-or-≥2 waypoints, defensively copied); `RouteMode` enum `{LOOP, BOUNCE, ONCE}` (**not**
  the same type as `adapter-simulation`'s own `RoutePlan.RouteMode` — see that module's `MODULE.md`);
  `Waypoint(latitude, longitude, altitudeMeters)` (one route checkpoint; range-validated like kernel's
  `GeoPosition` despite the shape match — deliberately a distinct type: `GeoPosition` is a derived
  "where is it now" reading, `Waypoint` is ordered route input).
- **`SimulationService`** (interface) → **`DefaultSimulationService`** — the one-call, zero-hardware
  simulation entry point.
  - `DefaultSimulationService(AssetService assetService, AssetStreamService assetStreamService, DeviceService deviceService, FeedTransmitterRegistry feedTransmitters, URI mediamtxRtspBase)`
    — production 5-arg ctor, defaulting `SimulationServiceSettings.defaults()`; a 6th public overload
    takes an explicit `SimulationServiceSettings` instead. `mediamtxRtspBase` is this app's own
    configured mediamtx RTSP push target (the same `URI` `vision-app` already hands
    `RtspFeedTransmitter`), used only by `#resumeAll` to recognize which persisted `rtsp` devices are
    this app's own TX-fed feeds. **`categoryRepository` → `deviceService` (R4, docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md)**:
    a net-zero-parameter swap — `CategoryRepositoryPort` had no remaining use once `simulate()`'s
    category-seeded guard was removed (see below), and `fitSimulatedDevice` needs `DeviceService` to
    register the fitted device.
  - `SimulatedAsset simulate(SimulationSpec, Ownership, UserId actor)`:
    - Validates `spec.videoPath()` via `java.nio.file` (must exist, be a regular file, readable) —
      **skipped entirely when `videoPath()` is `null`** (a fully synthetic simulation, which `spec`'s
      own compact ctor guarantees only happens with `transport=DIRECT`).
    - **No longer requires the `simulated` category to be seeded** (R4 removed the
      `requireSimulatedCategorySeeded()` guard and its `IllegalStateException`) — every new
      fully-synthetic asset is still tagged category `simulated` by default (`SIMULATED_CATEGORY`,
      unchanged), but it is now a plain, unenforced label rather than a validated precondition. See
      Status for why the category row itself was deliberately left in the database rather than
      deleted.
    - Derives the display name from the video file's name (extension stripped) when
      `spec.displayName()` is null/blank, or the constant `SYNTHETIC_DISPLAY_NAME` ("Synthetic drone")
      for a `null`-`videoPath` spec.
    - Registers exactly two devices, independent of each other (any `SimulationTransport`×`TelemetryTransport`
      combination is valid, no illegal combination to reject):
      - **Telemetry — SIM (default)**: `"<name> · telemetry"`, protocol `"sim"`, `sim://<slug>-telemetry`
        URI, `Capability.TELEMETRY`. When `spec.plan()` carries a route, the route wins over bare
        `lat`/`lon` entirely (the route's first waypoint doubles as the start position); otherwise
        bare `lat`/`lon` options are set only when `spec` provides them.
      - **Telemetry — MAVLINK**: allocates a free loopback UDP port (bind-then-immediately-close, a
        small accepted TOCTOU race), registers a `"mavlink"`-protocol device at `udp://127.0.0.1:<port>`
        with a fixed `sysid=1` (safe — every MAVLINK-transport simulation gets its own exclusive port),
        starts a matching feed via `FeedTransmitterRegistry` whose `FeedSpec#source()` is that *same*
        URI (**`MavlinkFeedTransmitter` treats `source()` as the transmit destination, not a source
        file** — unlike `RtspFeedTransmitter`/`MjpegFeedTransmitter`, see `adapter-mavlink`'s own
        `MODULE.md`). `route` is always set (required by that transmitter, unlike SIM's optional one)
        — a plan's route serialized identically to the SIM path, or a minimal synthesized two-point
        route when absent (`MavlinkRoute`'s `LOOP`-only engine turns two points into a moving
        oscillation, not a frozen point). `TelemetryPlan#mode()` naming anything but `LOOP` is a real,
        logged (`WARNING`) drop.
      - **Video — DIRECT with a path**: `"<name> · video"`, protocol `"file"`, `uri = videoPath.toUri()`,
        options `{"loop":"true"}`.
      - **Video — DIRECT with no path**: protocol `"sim"` (the same `SimulatedVideoSource` renderer
        the SIM telemetry device uses), `uri = "sim://<slug>"` (no `-telemetry` suffix), no `loop`
        option (the renderer runs forever on its own).
      - **Video — RTSP/MJPEG**: always a non-`null` `videoPath`; a fresh `FeedId.random()`,
        `FeedSpec(protocol, videoPath.toUri(), {"loop":"true"})`, `feedTransmitters.transmitterFor`
        selects the adapter, `transmitter.start` registers the returned `StreamDescriptor` — for
        `RTSP` only, augmented with `options.put("timeout","2000000")` (the RX-side contention fix,
        see Gotchas); `MJPEG`'s descriptor is registered as-is.
    - `autoStart` → (for `RTSP` only) a short fixed delay (see Gotchas), then
      `assetStreamService.startStream(assetId, null, PipelineConfig.defaults())`.
    - Tracks `AssetId -> (FeedTransmitterPort, FeedId)` in **two** separate `ConcurrentHashMap`s —
      `feedByAsset` (video, wired transports only) and `telemetryFeedByAsset` (MAVLINK telemetry
      only), since video and telemetry transports are orthogonal. Rollback on failure covers both:
      whichever of the two feeds actually started are stopped before rethrowing.
  - `Device fitSimulatedDevice(AssetId assetId, Capability capability, UserId actor)` (R4,
    docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md — "the drone has no camera yet"): fits a
    synthetic device onto an **existing** asset, the case `simulate()` cannot cover since that always
    creates a whole new one. Looks the asset up via `assetService.details(assetId)` first (its
    `NoSuchElementException` surfaces for an unknown asset before anything is registered), builds a
    `DeviceRegistration("<displayName> · <capability>", {capability}, sim://<slug>-<capability>,
    DeviceOrigin.SIMULATED)`, registers it via `deviceService.register`, then assigns it via
    `assetService.assignDevice(assetId, device.id(), actor)`. Always an in-process `sim`-protocol
    device — never RTSP/MJPEG (those are demo-grade, ephemeral-port transports, a poor fit for a
    fitting meant to sit on an otherwise-permanent asset indefinitely).
  - `void stop(AssetId)` — `assetService.stopStream(assetId)` always, then, independently for **each**
    of `feedByAsset`/`telemetryFeedByAsset`, stops and untracks any tracked feed. Idempotent.
  - `List<AssetId> resumeAll()` — simulated-feed resume-on-boot: **since R4, checked over every
    active asset regardless of category** (not only `simulated`-category ones — `fitSimulatedDevice`
    can fit a simulated device onto any real asset, so origin, not category, is what now distinguishes
    "one of our own TX-fed simulation feeds"), for each one not already tracked whose devices include
    an active `VIDEO`-capable, `rtsp`-protocol device with `DeviceOrigin.SIMULATED` whose `uri`
    host:port matches the injected `mediamtxRtspBase`, parses the trailing `feed-<uuid>`
    path segment back into a `FeedId` (deliberately coupled to `adapter-rtsp`'s `RtspFeedTransmitter#targetUri`
    URL shape), rebuilds a `FeedSpec` from `attributes.source` (re-validated), resolves a transmitter,
    and starts it — tracking the result exactly like a fresh `simulate()` call. Every candidate that
    fails any step is logged at `WARNING` and skipped, never thrown. **`transport=MJPEG` is never a
    candidate** (its TX side's ephemeral HTTP port is freshly randomized every JVM start, so a
    persisted `mjpeg` device's URI is already stale). **A MAVLINK telemetry feed is never a candidate
    either**, same reasoning (freshly-allocated loopback port every JVM start) — `telemetryFeedByAsset`
    isn't consulted here at all. Returns the ids actually resumed.
- **`SimulationServiceSettings`** — `DefaultSimulationService`'s MAVLink-transport fallback tunables
  (`mavlinkLoopbackHost`, `fallbackLatitude`/`fallbackLongitude`); `static defaults()` byte-identical
  to the literals it replaces (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 config extraction).

## Conventions
- Every domain-shaped record here (`SimulationSpec`, `TelemetryPlan`, `Waypoint`) validates in its
  compact constructor with manual `if (…) throw new IllegalArgumentException(…)`, the same idiom
  every other context's own domain records use, even though these particular records live in the
  application layer (this context owns no domain package — see Purpose).
- `N-1-arg convenience constructor` idiom: `SimulationSpec`'s 8-arg canonical has 7-/6-/5-arg
  convenience ctors chained on top, each defaulting one trailing component to its "pre-existing
  behavior" value — the same pattern `warehouse`'s `Asset`/perception's `PipelineConfig` use.
- The acting user is a method parameter (`UserId actor`), never a constructor dependency — this
  context calls straight through to `AssetService#create`, which enforces that convention on its own.
- This context composes existing services (`AssetService`, `AssetStreamService`,
  `FeedTransmitterRegistry`) rather than reaching past them to repositories directly — every rule
  those services already enforce (category validation, audit, device registration, stream-start
  resolution) applies here for free, without duplication.

## Gotchas
- **`feedByAsset`/`telemetryFeedByAsset` are pure in-memory bookkeeping — a `transport=RTSP`/`MJPEG`
  simulation's TX feed never survives a JVM restart on its own.**
  A restart starts with empty maps; the persisted `Device(protocol="rtsp", uri=...)` row survives, but
  nothing is pushing to that URI anymore. **Fixed by `#resumeAll()`** (see the API surface entry
  above) — `vision-app`'s `SimulationResumeRunner` (an `ApplicationRunner`, gated on
  `vision.simulation.resume-on-boot` — the `vision.persistence.enabled` half of that gate is gone,
  docs/plans/done/POSTGRES-ONLY-CONTEXT.md W2b) calls it once the context is up. Nothing distinguishes
  "this RTSP device is one of our own TX-fed feeds" from "a real external
  RTSP camera" in storage — the only reliable signal is structural: an RTSP device whose `uri` host:port
  matches this app's own configured mediamtx push target is always one of this app's own feeds, since
  this architecture never registers a real external camera against mediamtx's own ingest port.
- **The RX-side timeout augmentation is not optional, and is RTSP-only.** `FfmpegVideoSource`'s
  default RTSP `timeout`/`rw_timeout` is 10s; running it (RX) in the same JVM as `RtspFeedTransmitter`
  (TX) against the same mediamtx path — exactly what `transport=RTSP` creates — causes the
  transmitter's own grab/record loop to stall for ~10s before mediamtx drops the connection and the
  transmitter's next write fails with EPIPE. `RTSP_RX_TIMEOUT_MICROS` ("2000000" = 2s) is added to the
  transmitter-returned `StreamDescriptor`'s options for exactly this reason — see `adapter-rtsp`'s
  `MODULE.md` Gotchas for the full empirical writeup. `transport=MJPEG` never gets this augmentation —
  the mjpeg TX/RX pair has no same-JVM contention.
- **`RTSP_FEED_ESTABLISH_DELAY_MILLIS` (1000ms) is an empirically-required fixed delay, also
  RTSP-only**, paid only when `transport=RTSP` and `autoStart=true`, between the feed transmitter
  starting and the RX side opening: `RtspFeedTransmitter`'s transmit thread takes tens of milliseconds
  to reach mediamtx's ANNOUNCE/SETUP/RECORD handshake, and mediamtx answers an RX `DESCRIBE` for a
  not-yet-published path with a bare 404, not a "retry me" signal — and `FfmpegVideoSource` has no
  reconnect logic (a single failed connect permanently kills that `StreamPipeline`). A manually-started
  stream (`autoStart=false`) never pays this delay. `transport=MJPEG` never needs an equivalent delay —
  `MjpegFeedTransmitter#start` only returns once its HTTP context is already listening.
- **`resumeAll()`'s `parseFeedId` is deliberately coupled to `adapter-rtsp`'s own URL shape** — it
  parses a `feed-<uuid>` trailing path segment out of a persisted device's `uri`, a convention
  `FeedTransmitterPort`'s own contract does not require. If `adapter-rtsp`'s target-URI shape ever
  changes, this parsing breaks silently (a candidate simply fails to resume, logged at `WARNING`, not
  a compile error) — documented, not enforced by any type.
- **MAVLINK telemetry's `sysid=1` is safe only because every MAVLINK-transport simulation gets its own
  exclusive loopback port** — sysid uniqueness only matters for disambiguating several vehicles
  sharing *one* real port (`adapter-mavlink`'s fleet gateway concern), which never applies here.
- **`AssetStreamService`, not `AssetService`, starts the stream** — this context depends on
  `perception.application.stream.AssetStreamService` for `startStream`, a dependency this doc's
  predecessor (`vision-application/MODULE.md`, pre-W1.6e/W1.7) did not have: warehouse's `AssetService`
  stopped owning stream-starting orchestration in W1.6e (docs/plans/active/DOMAIN-SEPARATION-W1.md
  §15) precisely so a CRUD context wouldn't have to import perception's `PipelineConfig`/
  `TrackingConfigPatch` into its own published interface. If you're looking for `startStream` on
  `AssetService` here, it moved.

## Status

**ARCHITECTURE-AUDIT-2026-08-26 wave R5b — re-verified, already done**: R5b's brief listed this
module's `DefaultSimulationService` as one of six classes reading a foreign context's repository
port directly (`warehouse`'s `CategoryRepositoryPort`). Re-checked against this worktree: R4 (below)
already made this swap — `DefaultSimulationService` imports no `*RepositoryPort` from any other
context (only `perception`'s `FeedTransmitterPort`, a legal driven port). No code changed this wave;
72/72 tests green, unchanged (`./mvnw -B -pl contexts/vision-simulation test`).

**ARCHITECTURE-AUDIT-2026-08-26 wave R4 done, with one deliberate judgment call**: added
`SimulationService#fitSimulatedDevice(AssetId, Capability, UserId)` (see API surface above) and
swapped `resumeAll()`'s asset selection from "active asset with category `simulated`" to "active
asset with a `DeviceOrigin.SIMULATED` video device" — a genuine correctness improvement, not just a
rename: a real vehicle asset with a fitted simulated RTSP device is now correctly resumable, whereas
before it never could be (its asset category was never `simulated`). `DefaultSimulationService`'s
constructor swapped `CategoryRepositoryPort` for `DeviceService` (net-zero parameter count).
**Judgment call, flagged**: the `simulated` category row was **not** deleted from the database and
`SIMULATED_CATEGORY`/`simulate()`'s tagging of new fully-synthetic assets was **not** removed —
only the `requireSimulatedCategorySeeded()` *guard* was removed, so the category is now a plain,
unenforced default label rather than a validated precondition (an unseeded category now surfaces as
`AssetService#create`'s own `requireCategory` failure — `IllegalArgumentException`/400 — instead of
the old `requireSimulatedCategorySeeded()`'s `IllegalStateException`/409, a deliberate exception-
mapping change for that edge case). Full deletion of the category was judged out of R4's actual
scope: `fitSimulatedDevice` is the real fix for "the drone has no camera yet" (it bypasses
`simulate()`'s default category entirely, fitting a device onto any real asset regardless of its
category), and `simulate()`'s own fully-synthetic path — a whole new asset with no real hardware at
all — is still a legitimate case where a `simulated`-labeled category remains a reasonable default,
even though nothing enforces it anymore. Revisit if a later wave wants the category gone entirely.
Devices built by every `simulate()` path (`videoDevice`/`syntheticVideoDevice`/`wireVideoDevice`/
`wireMavlinkTelemetryDevice`/`telemetryDevice`) now register with `DeviceOrigin.SIMULATED`. **71 →
72 tests** (`./mvnw -B -pl contexts/vision-simulation test`, green); `station/vision-app`'s full
dependency-reactor build (`-pl station/vision-app -am test`, 248 tests in `vision-app` itself) also
confirmed green after the constructor swap, since a `DeviceService` bean already existed for Spring
to resolve by type.

**W1.7b extraction** (docs/plans/active/DOMAIN-SEPARATION-W1.md §16): this context moved out of the
flat `vision-domain`/`vision-application` modules into its own Maven module, `contexts/vision-simulation`
— a directory move (`com/drones/vision/simulation/**`), no package rename, no behavior change.
Depends on `vision-kernel`, `vision-platform` (transitive only), `vision-warehouse`,
`vision-perception`. **71/71 green**, unchanged from the pre-extraction combined count.

**Backend follow-up batch: telemetry-OSD input + MAVLINK telemetry transport for simulations**
(pre-extraction; the MAVLINK-telemetry half of this batch is this context's, the telemetry-OSD-input
half is perception's `StreamPipeline` — see `vision-perception/MODULE.md`): `SimulationSpec` gained
its 8th component, `telemetryTransport`. `DefaultSimulationService#simulate` gained the whole MAVLINK
telemetry-device/feed wiring path (see the API surface entry above for the full mechanism — loopback
port allocation, `MavlinkFeedTransmitter`'s destination-not-source `FeedSpec#source()` semantics,
route synthesis, the two independent tracking maps, and rollback covering both). **Confirmed, not
just predicted, at the time**: `vision-app`'s `feedTransmitterRegistry` bean already picked up
`mavlinkFeedTransmitter` via Spring's automatic `List<FeedTransmitterPort>` collection injection, with
zero wiring change needed; `vision-api`'s `StartSimulationRequest` gained the matching
`telemetryTransport` DTO field.

**Backend follow-ups batch (pre-extraction)**: `SimulationService#resumeAll()` — simulated-feed
resume-on-boot, closing a previously-flagged gap (see Gotchas above for the full mechanism).
`DefaultSimulationService`'s constructor grew a 4th parameter at the time, `URI mediamtxRtspBase`
(both call sites — `vision-app`'s wiring bean and this module's own tests — updated directly, no
legacy overload kept, since this is an internal application-layer class, not a wire contract). One
judgment call: `resumeAll()` additionally requires the candidate asset to be `ACTIVE` (not just
non-deleted) — deliberately narrower than "every persisted simulated-category asset," since resuming
a feed for an asset the user explicitly deactivated seemed like the wrong default;
`DEACTIVATED`/`DELETED` assets are skipped, restorable normally.

Fully implemented pre-extraction, including the original one-call simulation entry point
(docs/main/CYCLES-PLAN.md §1b, §3, §5, §7, §9 — RTSP/MJPEG TX/RX simulation via
`FeedTransmitterRegistry`, `stop(AssetId)` teardown, configurable telemetry flight plans via
`SimulationSpec#plan()`) and (docs/main/CYCLES-PLAN.md §9, CU-a) the fully synthetic simulation path
(`SimulationSpec#videoPath()` nullable, registering a `"sim"`-protocol video device instead of `"file"`
and deriving `SYNTHETIC_DISPLAY_NAME` when no display name is given either, `transport`
cross-validated to reject `RTSP`/`MJPEG` against a `null` path).

**Judgement calls made assembling this document** (W1.7c, docs/plans/active/DOMAIN-SEPARATION-W1.md
§16): the `LAYERING-REFACTOR-PLAN.md` Wave A entry (feature-first packaging + config extraction) was
**not** brought across as its own Status entry, for the same reason given in `vision-learning`'s
`MODULE.md`: it is genuinely cross-cutting, touching every context's services in one commit. Its
simulation-relevant piece — `SimulationServiceSettings` extracted from what were previously bare
literals, and the move from a flat `com.drones.vision.application.simulation` package into
`com.drones.vision.simulation.application` — is folded into the `SimulationServiceSettings` API
surface entry and the package-shape note above instead of being cited as a separate wave. The
`DefaultSimulationService` constructor's current 5-/6-arg shape (with `AssetStreamService` and
`SimulationServiceSettings`) reflects the *current* code, verified directly against source rather than
copied from the pre-extraction `vision-application/MODULE.md`, which still described a 4-arg
constructor predating both the W1.6e stream-orchestration split and the settings-extraction wave —
that text was stale even before this context was extracted, and is not carried forward here.

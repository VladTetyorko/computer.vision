# vision-events

Flight replay: a scrubbable telemetry/detection window over one finished (or still-open)
`AssetUsage`, plus recording/clip-export URL resolution. The **smallest** of the eight bounded
contexts (24 tests, 7 source files) and structurally the simplest: **a pure downstream sink.**

**The rule that must not be broken**: `events` may read `flight`, `perception` and `warehouse` —
it exists to narrate their history — but nothing may read `events` back except `vision-learning`
(and only for `ReplaySources`, the bundle `DefaultLabelingService#captureFromReplay` composes it
through). No adapter-facing feature, no other context, and no future addition to this module should
ever be reached from outside that one door. This asymmetry is not a style preference: W1
(docs/plans/active/DOMAIN-SEPARATION-W1.md §14) measured that a cross-cutting write-seam sitting
inside `events` (the old god-port `LiveUpdatePublisherPort`, plus `DetectionRepositoryPort`/
`DetectionEvent*`, both filed here by first-mover rather than by owner) caused **four of the
module graph's seven cycles** — `events ↔ map`, `events ↔ perception`, `events ↔ flight`, and
`events ↔ learning`. W1.6b paid all four off by moving every write-seam out (the god-port deleted
and split per-context, the detection family moved to `perception`, `ReplayCaptureSpec` moved to
`learning`) and leaving this module with exactly one port, `ReplayFrameExtractionPort`, which only
this module implements-against and only `learning` consumes. Adding a second thing another context
must write to, or a new outbound reference this module doesn't already have, reopens exactly the
class of cycle W1.6b closed — see docs/plans/active/DOMAIN-SEPARATION-W1.md §15 (W1.6b) for the
full incident writeup before adding anything here.

**Depends on:** `vision-kernel`, `vision-platform` (every context's baseline) · `vision-warehouse`
(`AssetUsage`/`AssetUsageRepositoryPort` — a replay window belongs to a finished flight session)
· `vision-flight` (`TelemetryRepositoryPort` — the telemetry series) · `vision-perception`
(`DetectionRepositoryPort`/`DetectionQuery`/`DetectionResult`/`VideoFrame`/`StreamPublisherPort` —
the detection series, the frame-extraction contract's payload type, and the recording/playback URL
source)

**These three repository-port reads are a deliberate exception to
docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5** ("cross-context reads go through the owning
context's application service, not its repository port") — see Status, wave R5b, for the full
judgment call and why forcing them onto a service would make this module's read genuinely worse,
not just differently shaped.

**Used by:** `vision-learning` only (`ReplaySources`, for `DefaultLabelingService#captureFromReplay`
— see the rule above), `vision-api`, `vision-app`
**Build/test:** `./mvnw -B -pl contexts/vision-events test`

## Package shape

```
com.drones.vision.events.domain.model   — empty (see Status: the last models left in W1.6b)
com.drones.vision.events.domain.port    — ReplayFrameExtractionPort, the module's only port
com.drones.vision.events.application    — ReplayService + DefaultReplayService, ReplaySources,
                                           UsageTimeline, UsageRecording, ReplayServiceSettings
                                           (no feature subpackage — "replay" collapses into the
                                           context root, since it's this context's only feature)
```

## API surface

### domain.port — `com.drones.vision.events.domain.port` (driven — implemented by adapters)
- `ReplayFrameExtractionPort` (docs/plans/done/CV-TRAINING-V2-PLAN.md §3) — pull one decoded frame out of a stream's durable recording at a specific instant, the "capture a training frame from replay" counterpart to live capture's `StreamService#latestRawFrame` (`vision-perception`): `Optional<VideoFrame> frameAt(StreamId, Instant)` — `Optional.empty()` is **honest absence** (no recording configured, recording disabled on the media server, or nothing recorded at that instant), never an error, mirroring `StreamPublisherPort#playbackUrl`'s own posture toward a missing recording. Implementations **must** stamp the returned `VideoFrame`'s `capturedAt` with the requested `at` and its `sequence` with `0`, so callers never have to reconcile two notions of "when". Threading: implementations must be safe for concurrent use — a request-thread, on-demand fetch, not a hot/live subscription. No implementation yet — `MediamtxReplayFrameExtractor` (`video-output/publish-hls`) is a later wave

### application — `com.drones.vision.events.application`
- `record ReplaySources(AssetUsageRepositoryPort usages, DetectionRepositoryPort detections, ReplayFrameExtractionPort frames)` (docs/plans/done/CV-TRAINING-V2-PLAN.md §4) — **the one door out of this context**: the three replay-sourced collaborators `vision-learning`'s `DefaultLabelingService#captureFromReplay` needs, bundled into one constructor parameter for the same java-clean-code SKILL.md §3 "keep the constructor at the five-parameter ceiling" reason `TrainingStores` (`vision-learning`) exists for. Each component is a genuine, independently-substitutable port — none of the three is speculative
- `record UsageTimeline(AssetUsage usage, Instant from, Instant to, List<Telemetry> telemetry, List<DetectionResult> detections)` — `ReplayService#timeline`'s read model; `from`/`to` are the *resolved* window bounds, never `null` even when the caller passed `null`; `telemetry` ascending by `Telemetry#at()`, thinned to the requested `maxPoints`, defensively copied; `detections` likewise ascending by `capturedAt`, thinned, defensively copied — real since docs/plans/done/MVP2-PLAN.md R-a2 (see Gotchas: the type's own field javadoc still says "always empty today," which is **stale** — read `DefaultReplayService`'s class javadoc and `detectionsFor`, not that one line, for the actual behavior)
- `record UsageRecording(URI url, Instant start, long durationSeconds)` (docs/plans/done/OPS-CORE-PLAN.md §R, R-b) — `ReplayService#recordingFor`'s read model; `durationSeconds` whole seconds via `Duration#getSeconds()`, not negative; `url`/`start` non-null
- `record ReplayServiceSettings(int defaultMaxPoints, int maxPointsCeiling, int fetchLimit)` (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3 config extraction) — `defaultMaxPoints` positive; `maxPointsCeiling`≥`defaultMaxPoints`; `fetchLimit` positive, **one key shared by both** the telemetry and detection fetch calls (not two, matching the frozen `vision.application.replay.fetch-limit` singular property). `static defaults()` = `(500, 2_000, 20_000)`, byte-identical to the literals it replaced
- **`ReplayService`** (interface) → **`DefaultReplayService`** — the read side behind `GET /api/usages/{usageId}/timeline` and `GET /api/usages/{usageId}/recording`
  - `DefaultReplayService(AssetUsageRepositoryPort, TelemetryRepositoryPort, DetectionRepositoryPort, StreamPublisherPort)` — 4-arg, delegates to a 5-arg canonical ctor with `ReplayServiceSettings.defaults()`; `StreamPublisherPort` used **read-only**, purely to resolve `playbackUrl` for `recordingFor` — required, not nullable, since `vision-app` always wires a real bean (mediamtx-backed or a no-op fallback)
  - `Optional<UsageRecording> recordingFor(UsageId)` — resolves usage → `streamId` (`null` → `Optional.empty()`, no port call at all) → `start = usage.startedAt()`, `duration = Duration.between(start, usage.endedAt() != null ? usage.endedAt() : Instant.now())` → `streamPublisherPort.playbackUrl(streamId, start, duration)` (empty → empty). An honest-cheap check (configuration presence, not a media-server round trip) — `NoSuchElementException` for an unknown usage id, same as `timeline`
  - `UsageTimeline timeline(UsageId, Instant from, Instant to, int maxPoints)` — `NoSuchElementException` for an unknown usage; `IllegalArgumentException` for non-positive `maxPoints` or a resolved `to` before `from`. `from`/`to` each independently nullable, defaulting to the usage's own `startedAt`/`endedAt` — `to` defaults to `Instant.now()` when the usage is still open. `maxPoints` silently clamped to `settings.maxPointsCeiling()` if the caller asks for more — never an error, just a cap
  - **Telemetry**: `TelemetryRepositoryPort.findByUsage(usageId, settings.fetchLimit())` — see Gotchas for why this is a fetch-then-filter workaround, not a real time-bounded query — filtered to `[from, to]` inclusive both ends, sorted by `Telemetry#at()`, thinned
  - **Detections**: when `usage.streamId()` is non-`null`, queried directly via `DetectionRepositoryPort.query(new DetectionQuery(streamId, windowFrom, windowTo, null, settings.fetchLimit()))` — a genuinely time-bounded query, unlike telemetry — then re-sorted ascending by `capturedAt()` (the port's real implementations return newest-first) and thinned. A `null` `streamId()` (legacy/streamless usage) short-circuits to `List.of()` without touching the port
  - `static <T> List<T> thin(List<T> items, int maxPoints)` (package-private, generic) — equidistant-index thinning: `items.size() <= maxPoints` returns `items` unchanged (lossless identity); otherwise selects `maxPoints` indices via `round(i * (n-1) / (maxPoints-1))`, which always lands exactly on index `0` and `n-1`, deterministic. `maxPoints <= 1` returns a single-element list holding just the first item

## Conventions
- No Spring/framework imports; `Objects.requireNonNull` for every constructor collaborator (application-layer validation idiom), manual `if (…) throw new IllegalArgumentException(…)` in every record's compact constructor (domain-record idiom — this module's records live in `.application`, not `.domain.model`, but follow the same validation style throughout the codebase).
- `List` components are defensively copied/returned as immutable views (`UsageTimeline#telemetry`/`#detections`, `DefaultReplayService#thin`'s own return).
- Settings extraction (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3): every magic number lives in `ReplayServiceSettings`, injected via constructor, never a literal in `DefaultReplayService` itself. `DEFAULT_MAX_POINTS`/`MAX_POINTS_CEILING`/`TELEMETRY_FETCH_LIMIT`/`DETECTION_FETCH_LIMIT` still exist as package-visible constants on the class purely so same-package tests (and any external reader) can read a stable name — each is a *derived* reference (`= ReplayServiceSettings.defaults().field()`), not a second independent literal, so it cannot drift from the settings record's own default.

## Gotchas
- **`DefaultReplayService`'s telemetry fetch is a workaround, not a real time-bounded query** — `TelemetryRepositoryPort#findByUsage(usageId, limit)` has no `from`/`to` parameters at all, and its `limit` selects the usage's **earliest** samples (see the port's javadoc, `vision-flight`, and `adapter-persistence`'s `JpaTelemetryRepository`, which deliberately preserves this exact semantic). This class fetches up to `settings.fetchLimit()` (20,000) samples and filters/thins the requested window out of that fetch in memory. For a usage with more than 20,000 total telemetry samples (at 1Hz, ~5.5 hours of continuous flight), any window whose data falls after that cutoff is silently missing, **even if the caller's `to` explicitly asks for it** — there is no total-count or "more available" signal to detect this from inside the method. The real fix is a time-bounded query added to `TelemetryRepositoryPort` — not attempted here, out of this module's own scope to change another context's port.
- **Detections are real (docs/plans/done/MVP2-PLAN.md R-a2), and the truncation direction is the *mirror image* of telemetry's.** `DetectionRepositoryPort#query` *is* genuinely time-bounded, so it doesn't share telemetry's "silently drops whatever's past the fetch cutoff" failure mode — but its real implementations return **newest-first** with `limit` already applied, so a stream with more than `fetchLimit` (20,000) results *inside* the requested window loses its **earliest** ones, not its latest. `DetectionQuery#to()`'s own javadoc calls the bound exclusive, but `DefaultReplayService` passes the resolved window's `to` straight through as inclusive, relying on every real `DetectionRepositoryPort` implementation actually treating it as inclusive in practice (see `adapter-persistence`'s `JpaDetectionRepository`) — exactly the inclusive-both-ends semantics this class already uses for telemetry, so the two series stay consistent at the window edges.
- **`UsageTimeline#detections`'s own field javadoc is stale** — it currently reads "always empty today," a leftover from before R-a2 shipped. `DefaultReplayService`'s class javadoc and `detectionsFor` are the source of truth: detections are populated whenever `usage.streamId()` is non-null. Worth fixing the next time this module's file scope is touched — flagged here rather than silently propagated.
- **`recordingFor`'s "no recording" and "no video stream at all" are indistinguishable** — `Optional.empty()` covers both `streamId() == null` (this usage never had a video device) and a configured `StreamPublisherPort` with no recording/playback endpoint for that stream. Deliberate: this is an honest-cheap presence check, not a mediamtx round trip, so there is nothing further to distinguish the two cases with.
- **A usage with a `null` `streamId()`** (opened before docs/plans/done/MVP2-PLAN.md R-a2 landed, or by an asset with no video device) yields an honestly empty detections list and an empty `recordingFor` result — there is still no reliable fallback join for those, and none is planned.
- **`timeline`'s `to` default for an open usage is resolved eagerly, once, at call time** (`Instant.now()`) — no clock/`Supplier<Instant>` injection seam (unlike `vision-perception`'s `StreamPipeline#nanoTimeSource`), since a single wall-clock read per request has no meaningful test-flakiness risk; tests bracket the call with `Instant.now()` calls before/after instead of injecting a fake clock.

## Status

**Genesis (docs/plans/active/DOMAIN-SEPARATION-W1.md §15–16, W1.6b, "events becomes a sink"):** before W1.6b this context held three families that didn't belong to it — `DetectionRepositoryPort`/`DetectionEvent`/`DetectionEventId`/`DetectionEventState`/`DetectionEventRepositoryPort` (moved to `perception`, the only context that ever constructs a `DetectionResult`/`DetectionEvent`), `ReplayCaptureSpec` (moved to `learning`, **C8** — it names `DatasetId`, so `learning` was always its real owner), and the god-port `LiveUpdatePublisherPort` (**deleted outright**, split into five per-context ports: `FleetLiveUpdatePort` in warehouse, `TelemetryLiveUpdatePort` in flight, `DetectionLiveUpdatePort` in perception, `MapLiveUpdatePort` in map, `EventLiveUpdatePort` in platform — none of the five landed here, since none of the six methods that port bundled actually belonged to `events`: every one named another context's payload). What was left — `ReplayFrameExtractionPort` alone — is this module's entire domain layer today. This one wave is why `events ↔ map`, `events ↔ perception`, and `events ↔ flight` all disappeared from the module graph in one step, and it is the reason this context reads everywhere and is read from nowhere but `learning`. Extracted into its own Maven module, one context = one module holding both layers, in **W1.7b** — a pure directory move, no behavior change.

**docs/plans/done/MVP2-PLAN.md §R, R-a done**: `ReplayService`/`DefaultReplayService` added — flight replay's windowing+downsampling read side (`AssetUsageRepositoryPort`, `TelemetryRepositoryPort`, `DetectionRepositoryPort`). At this point detections were always empty (no join key yet).

**docs/plans/done/MVP2-PLAN.md R-a2 done**: closed that gap — `AssetUsage` (`vision-warehouse`, moved there itself in W1.6c) gained a nullable `streamId`, recorded once at usage-open time by `UsageTracker.onStreamStarted`'s new `StreamId` parameter (`vision-perception`). `DefaultReplayService.timeline`'s `detections[]` became a real, time-windowed `DetectionRepositoryPort` query per usage/stream instead of always empty. Also closed: `vision-app`'s `WiringConfiguration` gained a `replayService` `@Bean` (this service had no wiring at all before R-a2).

**docs/plans/done/OPS-CORE-PLAN.md §R, R-b done** (recording/clip-export resolution): `ReplayService` gained `Optional<UsageRecording> recordingFor(UsageId)`, `DefaultReplayService`'s constructor grew a 4th collaborator, `StreamPublisherPort` (read-only, purely to call `playbackUrl`). `NoSuchElementException` for an unknown usage id mirrors `timeline`'s own contract exactly.

**docs/plans/done/CV-TRAINING-V2-PLAN.md §3/§4, Waves W1/W5 — a net loss for this context, not a gain**: W1 added `ReplayFrameExtractionPort` here (the frame-extraction contract this context still owns). W5 then built `LabelingService#captureFromReplay`/`ReplaySources` — but both live in **`vision-learning`**, not here: `ReplaySources` merely *bundles* two of this context's neighbors' ports (`AssetUsageRepositoryPort` from warehouse, `DetectionRepositoryPort` from perception) plus this context's own `ReplayFrameExtractionPort`, for `learning`'s constructor-size benefit — it is filed in this module's package only because doing so keeps the "one door out" rule enforceable as a single reviewable type. No behavior in this module changed for either wave.

**docs/plans/active/LAYERING-REFACTOR-PLAN.md Wave A done** (config extraction): `ReplayServiceSettings` added — `defaultMaxPoints`/`maxPointsCeiling`/`fetchLimit`, the last **one key shared by both** the telemetry and detection fetch calls (the frozen `vision.application.replay.fetch-limit` singular, not two separate keys) where the pre-extraction code had used two separately-named constants (`TELEMETRY_FETCH_LIMIT`/`DETECTION_FETCH_LIMIT`) for what was always the same tuning value. `DefaultReplayService` gained a 5-arg canonical constructor taking an explicit `ReplayServiceSettings`; the 4-arg constructor now delegates to it with `ReplayServiceSettings.defaults()`. Package moved from the flat `com.drones.vision.application.replay` to `com.drones.vision.events.application` in the later W1.5a/b context-first reorganization — this wave's own file split is superseded by that move; only the settings-record outcome (and the fetch-limit key unification) survives as current fact.

**ARCHITECTURE-AUDIT-2026-08-26 wave R5b — judgment call: this module's three cross-context
repository-port reads are kept, deliberately, not converted.** R5's rule ("a context reads another
through its published application service, not its repository port") was applied everywhere else
in this wave (`vision-identity`'s `DefaultAssignmentService` swapped `AssetRepositoryPort` for
`AssetService`, net-zero parameters). This module is the one place the audit itself flagged as
possibly deserving an exception ("if routing makes it materially worse... say so and leave those
specific reads alone"), and, having actually tried the swap for all three, that is the honest
conclusion:

- **`AssetUsageRepositoryPort#findById(UsageId)`** (`DefaultReplayService#timeline`/`#recordingFor`,
  `ReplaySources#usages`) — no published warehouse service exposes an unscoped, uncapped read of one
  usage by id. `UsageService` (the closest fit) only offers `recent(scope, assetIdOrNull, limit)`
  and `byStream(scope, streamId)`, both scoped and both returning `UsageSummary`, which additionally
  **lacks `streamId`** — the one field `timeline`/`recordingFor` need most (it is the join key into
  `DetectionRepositoryPort` and `StreamPublisherPort`). Routing through `UsageService` would require
  both a new by-id method and a new field on its read model, and would force `timeline`/`recordingFor`
  to take a `VisibilityScope` they deliberately don't have today — `UsageTimelineController`'s own
  javadoc documents that gap as a known, out-of-scope-to-close limitation, not an oversight this wave
  should silently paper over by threading `VisibilityScope.unbounded()` through. See "Needed if this
  is ever revisited" below.
- **`DetectionRepositoryPort#query(DetectionQuery)`** (`DefaultReplayService`'s `detectionsFor`,
  `ReplaySources#detections`, and `DefaultLabelingService#captureFromReplay`'s nearest-detection
  lookup, `vision-learning`) — perception publishes no time-windowed, bulk detection-history read
  service; its `StreamService` is about the *live* stream registry, not historical query. A service
  method here would exist for exactly one caller (this module), the anti-pattern the task's own
  instructions call out by name ("a service method that exists only for events").
- **`TelemetryRepositoryPort#findByUsage(UsageId, int)`** (`DefaultReplayService`'s telemetry fetch)
  — same shape of gap on the flight side: no published flight service reads a raw telemetry series.
  This read is already a documented workaround (fetch-then-filter, no true time-bounded query — see
  Gotchas above); wrapping it in a new flight service method would still carry the same limitation,
  one layer further from the port that actually needs fixing.

All three are genuinely bulk, historical, time-windowed reads — the shape a repository port exists
for — not "one fact" lookups the way `DefaultAssignmentService`'s old `AssetRepositoryPort#findById`
was. Forcing them onto an application service would mean: inventing service methods whose only
caller is this module, widening a warehouse read-model record for one downstream reader, or quietly
adding scope-checking to two endpoints whose unscoped contract is deliberately frozen elsewhere.
None of that is "the code gets cleaner"; all of it is "the number goes down." Declining, per the R5b
task's own explicit invitation to make this call.

**Needed if this is ever revisited** (not requested this wave — recorded so a future wave doesn't
have to re-derive it): warehouse's `UsageService` would need an unscoped
`Optional<UsageSummary> byId(UsageId)` (mirroring `AssetService#details(AssetId)`'s unscoped/scoped
pair), and `UsageSummary` would need a `StreamId streamId` field. Even with both, the
`DetectionRepositoryPort`/`TelemetryRepositoryPort` reads would still have nowhere to go without a
new bulk-query service on perception/flight respectively — so closing this module's repository-port
reads is a three-context change, not a one-line swap. **Confirmed still true by wave R5c**, which
added exactly this kind of unscoped warehouse method for a different caller
(`vision-flight`'s `DefaultVehicleProfileService`) and deliberately did *not* build the
`UsageService#byId(UsageId)`/`UsageSummary.streamId` shape sketched above: `DefaultVehicleProfileService`
only needed a yes/no membership check against a `usageId` it already held, so R5c added
`UsageSessionService#usageBelongsToAsset(UsageId, AssetId)` — a boolean, not a data read, filed on
`UsageSessionService` rather than `UsageService` precisely because every `UsageService` method is
scope-checked and an unscoped one beside them would be a footgun (see that method's own javadoc in
`vision-warehouse`). It does not carry `streamId` and cannot serve `timeline`/`recordingFor`; this
module's actual unblock is still the `byId`/`streamId` pair above, unbuilt by either wave.

No code changed in this module this wave. `./mvnw -B -pl contexts/vision-events test` — 24/24 green,
unchanged.

**ARCHITECTURE-AUDIT-2026-08-26 wave R5c — the other two of R5's eight sites fixed; this module's
three left exactly as wave R5b decided.** R5c fixed `vision-flight`'s `DefaultVehicleProfileService`
and `vision-learning`'s `DefaultLabelingService` (see those modules' own MODULE.md) and added the
ArchUnit guard R5 asked for (`station/vision-app`'s `ContextArchitectureTest
#noContextImportsAnotherContextsRepositoryPort`) — a context's application/domain code importing
another context's `*RepositoryPort` now fails the build everywhere except a small, explicitly named
allow-list. This module's three reads (`AssetUsageRepositoryPort`/`DetectionRepositoryPort` on
`ReplaySources`, plus `TelemetryRepositoryPort` on `DefaultReplayService`) are that allow-list's one
named group, cited by class and target port, with the R5b judgment call above as the reasoning — see
`REPOSITORY_PORT_EXEMPTIONS` in `ContextArchitectureTest`. Nothing in this module changed; the guard
now makes the R5b decision an enforced, visible exception instead of an implicit one no test could
see.

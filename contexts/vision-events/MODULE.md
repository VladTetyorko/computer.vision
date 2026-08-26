# vision-events

Flight replay: a scrubbable telemetry/detection window over one finished (or still-open) `AssetUsage`, plus recording/clip-export URL resolution. The smallest of the eight bounded contexts and structurally the simplest: **a pure downstream sink.**

**The rule that must not be broken**: `events` reads `flight`, `perception` and `warehouse` — it exists to narrate their history — but nothing reads `events` back except `vision-learning` (only for `ReplaySources`, via `DefaultLabelingService#captureFromReplay`). No adapter-facing feature, no other context, and no future addition to this module should ever be reached from outside that one door. See docs/plans/active/DOMAIN-SEPARATION-W1.md §15 for the incident that made this rule (a cross-cutting write-seam that used to live here caused four of the module graph's seven cycles) before adding anything here.

**Depends on:** `vision-kernel`, `vision-platform` · `vision-warehouse` (`AssetUsage`/`AssetUsageRepositoryPort`) · `vision-flight` (`TelemetryRepositoryPort`) · `vision-perception` (`DetectionRepositoryPort`/`DetectionQuery`/`DetectionResult`/`VideoFrame`/`StreamPublisherPort`)

**Five cross-context repository-port reads are a deliberate, recorded exception** to "read another context through its published service, not its repository port": `ReplaySources` (`AssetUsageRepositoryPort`, `DetectionRepositoryPort`) and `DefaultReplayService` (`AssetUsageRepositoryPort`, `DetectionRepositoryPort`, `TelemetryRepositoryPort`) are all genuinely bulk, time-windowed historical queries — "what happened during this finished flight" — not one-fact lookups. All five are named individually in `station/vision-app`'s `ContextArchitectureTest#REPOSITORY_PORT_EXEMPTIONS` as **deliberate design**, not a gap to close — do not "fix" these. See Gotchas for why routing them through an application service would make the read worse, not cleaner, and Status for what closing them would actually require.

**Used by:** `vision-learning` only (`ReplaySources`), `vision-api`, `vision-app`
**Build/test:** `./mvnw -B -pl contexts/vision-events test`

## Package shape
```
com.drones.vision.events.domain.model   — empty (no models this context owns)
com.drones.vision.events.domain.port    — ReplayFrameExtractionPort, the module's only port
com.drones.vision.events.application    — ReplayService + DefaultReplayService, ReplaySources,
                                           UsageTimeline, UsageRecording, ReplayServiceSettings
```

## API surface

### `domain.port`
- `ReplayFrameExtractionPort` — pull one decoded frame out of a stream's durable recording at a specific instant: `Optional<VideoFrame> frameAt(StreamId, Instant)` — `Optional.empty()` is honest absence (no recording configured, disabled on the media server, or nothing recorded at that instant), never an error. Implementations must stamp the returned `VideoFrame`'s `capturedAt` with the requested `at` and `sequence` with `0`. Threading: safe for concurrent use — a request-thread on-demand fetch, not a hot subscription. Expected implementation `MediamtxReplayFrameExtractor` (`video-output/publish-hls`) not built yet.

### `application`
- `record ReplaySources(AssetUsageRepositoryPort usages, DetectionRepositoryPort detections, ReplayFrameExtractionPort frames)` — the one door out of this context: the three replay-sourced collaborators `vision-learning`'s `DefaultLabelingService#captureFromReplay` needs, bundled per `.claude/skills/java-clean-code/SKILL.md` §3 (constructor-size ceiling). Each component is a genuine, independently-substitutable port.
- `record UsageTimeline(AssetUsage usage, Instant from, Instant to, List<Telemetry> telemetry, List<DetectionResult> detections)` — `ReplayService#timeline`'s read model; `from`/`to` are the resolved window bounds, never `null`; `telemetry` ascending by `Telemetry#at()`, thinned to `maxPoints`, defensively copied; `detections` likewise ascending by `capturedAt`, thinned, defensively copied. **The `detections` field's own javadoc is stale** — see Gotchas.
- `record UsageRecording(URI url, Instant start, long durationSeconds)` — `ReplayService#recordingFor`'s read model; `durationSeconds` whole seconds, not negative; `url`/`start` non-null.
- `record ReplayServiceSettings(int defaultMaxPoints, int maxPointsCeiling, int fetchLimit)` — `defaultMaxPoints` positive; `maxPointsCeiling` ≥ `defaultMaxPoints`; `fetchLimit` positive, one key shared by both the telemetry and detection fetch calls. `static defaults()` = `(500, 2_000, 20_000)`.
- `ReplayService` (interface) → `DefaultReplayService(AssetUsageRepositoryPort, TelemetryRepositoryPort, DetectionRepositoryPort, StreamPublisherPort)` — 4-arg, delegates to a 5-arg canonical ctor with `ReplayServiceSettings.defaults()`; `StreamPublisherPort` used read-only, only to resolve `playbackUrl` for `recordingFor`
  - `Optional<UsageRecording> recordingFor(UsageId)` — resolves usage → `streamId` (`null` → empty, no port call) → `start = usage.startedAt()`, `duration = Duration.between(start, usage.endedAt() != null ? usage.endedAt() : Instant.now())` → `streamPublisherPort.playbackUrl(streamId, start, duration)`. `NoSuchElementException` for an unknown usage id.
  - `UsageTimeline timeline(UsageId, Instant from, Instant to, int maxPoints)` — `NoSuchElementException` for an unknown usage; `IllegalArgumentException` for non-positive `maxPoints` or `to` before `from`. `from`/`to` independently nullable, defaulting to the usage's own `startedAt`/`endedAt` (`to` → `Instant.now()` for a still-open usage). `maxPoints` silently clamped to `settings.maxPointsCeiling()`.
  - Telemetry: `TelemetryRepositoryPort.findByUsage(usageId, settings.fetchLimit())`, filtered to `[from, to]` inclusive, sorted, thinned — a fetch-then-filter workaround, not a real time-bounded query (see Gotchas).
  - Detections: when `usage.streamId()` is non-`null`, a genuinely time-bounded `DetectionRepositoryPort.query(new DetectionQuery(streamId, windowFrom, windowTo, null, settings.fetchLimit()))`, re-sorted ascending by `capturedAt()` and thinned (real implementations return newest-first). A `null` `streamId()` short-circuits to `List.of()`.
  - `static <T> List<T> thin(List<T> items, int maxPoints)` (package-private, generic) — equidistant-index thinning; `items.size() <= maxPoints` returns unchanged; otherwise selects `maxPoints` indices via `round(i * (n-1) / (maxPoints-1))`, always landing on index `0` and `n-1`. `maxPoints <= 1` returns a single-element list.

## Conventions
- No Spring/framework imports; `Objects.requireNonNull` for every constructor collaborator; manual `if (…) throw new IllegalArgumentException(…)` in every record's compact constructor.
- `List` components are defensively copied/returned as immutable views.
- Every magic number lives in `ReplayServiceSettings`, injected via constructor, never a literal in `DefaultReplayService` itself.

## Gotchas
- **Telemetry fetch is a workaround, not a real time-bounded query** — `TelemetryRepositoryPort#findByUsage(usageId, limit)` has no `from`/`to` parameters, and `limit` selects the usage's **earliest** samples. This class fetches up to `fetchLimit` (20,000) samples and filters/thins the requested window in memory. For a usage with more telemetry than that, any window falling after the cutoff is silently missing, even if the caller's `to` explicitly asks for it — no total-count/"more available" signal exists to detect this. The real fix is a time-bounded query on `TelemetryRepositoryPort`, out of this module's own scope to add.
- **Detections are real and truncate the opposite direction from telemetry.** `DetectionRepositoryPort#query` is genuinely time-bounded, but real implementations return newest-first with `limit` applied, so a stream with more than `fetchLimit` results inside the window loses its **earliest** ones, not its latest. `DetectionQuery#to()`'s own javadoc calls the bound exclusive; this class passes the resolved `to` through as inclusive, relying on every real implementation actually treating it that way.
- **`UsageTimeline#detections`'s field javadoc is stale** ("always empty today") — detections are populated whenever `usage.streamId()` is non-null; read `DefaultReplayService`/`detectionsFor`, not that javadoc line.
- **`recordingFor`'s "no recording" and "no video stream at all" are indistinguishable** — `Optional.empty()` covers both `streamId() == null` and a configured `StreamPublisherPort` with no recording/playback endpoint for that stream. Deliberate: an honest-cheap presence check, not a mediamtx round trip.
- **A usage with a `null` `streamId()`** (opened before detections were joined, or by an asset with no video device) yields an honestly empty detections list and an empty `recordingFor` result — no fallback join exists or is planned.
- **`timeline`'s `to` default for an open usage is resolved eagerly, once, at call time** (`Instant.now()`) — no clock/`Supplier<Instant>` seam, since one wall-clock read per request has no meaningful flakiness risk.
- **The five repository-port reads are deliberate design, not a gap** — `ReplaySources`→`AssetUsageRepositoryPort`/`DetectionRepositoryPort` and `DefaultReplayService`→`AssetUsageRepositoryPort`/`DetectionRepositoryPort`/`TelemetryRepositoryPort`, all five named individually in `ContextArchitectureTest#REPOSITORY_PORT_EXEMPTIONS`. Do not "fix" these by inventing a service method whose only caller is this module, widening a warehouse read-model for one downstream reader, or quietly adding scope-checking to two endpoints whose unscoped contract is deliberately frozen elsewhere — all three were tried and rejected (see Status).

## Status
Fully implemented: replay timeline (telemetry + detections, windowed/downsampled), recording/clip-export URL resolution. `ReplayFrameExtractionPort` has no implementation yet (`MediamtxReplayFrameExtractor`, `video-output/publish-hls`, not built).

**If the repository-port exemptions are ever revisited**, closing them needs: warehouse's `UsageService` gaining an unscoped `Optional<UsageSummary> byId(UsageId)` (mirroring `AssetService#details(AssetId)`'s unscoped/scoped pair) plus a `streamId` field on `UsageSummary`, **and** a new bulk-query service on perception (for `DetectionRepositoryPort`) and flight (for `TelemetryRepositoryPort`) — a three-context change, not a one-line swap. A narrower need of the same shape (a yes/no usage-ownership check) was closed for a different caller by adding `UsageSessionService#usageBelongsToAsset(UsageId, AssetId)` on warehouse (see `vision-flight`'s `MODULE.md`) — that method does not carry `streamId` and cannot serve `timeline`/`recordingFor`.

# vision-domain

Framework-free domain: immutable models plus driven (`port.out`) ports.

**There is no `port.in` package.** Driving interfaces collapsed into one service interface + one implementation per area, which live in `vision-application`; their commands and read models are top-level records there. Driven `*Port` interfaces stay, because each genuinely has several implementations. See `.claude/skills/java-clean-code/SKILL.md`.

**Depends on:** nothing but `java.base` (no third-party/framework imports anywhere in this module) · **Used by:** vision-application, all adapters, vision-app, vision-api
**Build/test:** `./mvnw -B -pl vision-domain test`

## API surface

### `com.drones.vision.domain.model`
- `record AnnotatedFrame(VideoFrame frame, List<Detection> detections, Telemetry telemetry)` — telemetry nullable; detections defensively copied
- `record Asset(AssetId id, String displayName, CategoryId category, Ownership ownership, Set<DeviceId> devices, Map<String,String> attributes, LifecycleState state)` — devices must be non-empty; 6-arg convenience ctor defaults `state=ACTIVE`; `isActive()`, `isDeleted()`, `withDevices(Set)`, `withAttributes(Map)`, `withDetails(String,CategoryId,Map)`, `withState(LifecycleState)` return copies
- `record AssetId(UUID value)` — `static random()`, `static of(String)`
- `record AssetUsage(UsageId id, AssetId assetId, Instant startedAt, Instant endedAt, GeoPosition startPosition, GeoPosition lastPosition, long sampleCount)` — endedAt/startPosition/lastPosition nullable, endedAt≥startedAt if present; `closed(Instant)`, `withPositions(GeoPosition,GeoPosition)`, `withSampleCount(long)`
- `enum AuditAction` — CREATED, UPDATED, DEACTIVATED, ACTIVATED, DELETED, RESTORED
- `record AuditEntry(AuditId id, Instant occurredAt, UserId actor, AuditAction action, AuditTargetType targetType, String targetId, String summary, Map<String,String> details)` — immutable trail line; `static of(...)` stamps id + now; `details` carries `before → after` for edits
- `record AuditId(UUID value)` — `static random()`, `static of(String)`
- `enum AuditTargetType` — ASSET, DEVICE (opaque `targetId` string, so new auditable kinds need no storage change)
- `record BoundingBox(double x, double y, double width, double height)` — each component in [0,1]
- `enum Capability` — VIDEO, TELEMETRY, PTZ, AUDIO
- `record CategoryId(String slug)` — must match `[a-z0-9]+(-[a-z0-9]+)*`; no random-id factories (reference-data key, not a generated id)
- `record Detection(String label, double confidence, BoundingBox box, ModelRef model)` — confidence [0,1]
- `record DetectionQuery(StreamId streamId, Instant from, Instant to, String label, int limit)` — all but `limit` nullable = "don't filter on this"; limit must be positive
- `record DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt, List<Detection> detections, Duration inferenceLatency)` — frameSequence≥0, latency≥0
- `record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints)` — parent nullable (top-level)
- `record DeviceId(UUID value)` — `static random()`, `static of(String)`
- `record Device(DeviceId id, String name, Set<Capability> capabilities, StreamDescriptor stream, LifecycleState state)` — 4-arg convenience ctor defaults `state=ACTIVE`; `isActive()`, `isDeleted()`, `withDetails(String,Set,StreamDescriptor)`, `withState(LifecycleState)`
- `record DiscoveredDevice(String method, String name, URI address, CategoryId suggestedCategory, StreamDescriptor suggestedStream, Map<String,String> details)` — suggestedCategory/suggestedStream nullable (mechanism may lack enough info, e.g. ONVIF needs creds)
- `record Event(String id, StreamId streamId, Instant at, EventType type, String message, Map<String,String> attributes)` — **streamId nullable** (device-level events); `static of(StreamId,EventType,String)` generates id+now()
- `enum EventType` — DETECTION, DEVICE_ONLINE, DEVICE_OFFLINE, STREAM_STARTED, STREAM_STOPPED, PIPELINE_ERROR, TRAINING
- `record GeoPosition(double latitude, double longitude, Double altitudeMeters)` — lat [-90,90], lon [-180,180], altitude nullable
- `record GroupId(UUID value)` — `static random()`, `static of(String)`
- `enum LifecycleState` — ACTIVE, DEACTIVATED, **DELETED (soft)**. Nothing is ever destroyed: a deleted asset/device is hidden from listings and refuses to stream, but its record, usages and telemetry survive and the removal is reversible. Transitions: ACTIVE⇄DEACTIVATED; ACTIVE|DEACTIVATED→DELETED; DELETED→DEACTIVATED (restore — never straight back to ACTIVE)
- `record ModelRef(String id, String version)`
- `record Ownership(UserId ownerId, GroupId groupId)`
- `record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter)` — inferenceFps/maxInFlightInferences>0; `static defaults()` = yolo/latest, 0.4, 5fps, 2 in-flight, telemetry on, no filter
- `enum PixelFormat` — BGR24, RGB24, YUV420P, JPEG, H264_PACKET, UNKNOWN
- `record StreamDescriptor(String protocol, URI uri, Map<String,String> options)` — **protocol must be lower-case** (ctor throws otherwise)
- `record StreamId(UUID value)` — `static random()`, `static of(String)`
- `record Telemetry(DeviceId deviceId, Instant at, Double latitude, Double longitude, Double altitudeMeters, Double headingDegrees, Double batteryPercent, Map<String,Double> extra)` — all Double fields nullable
- `record TrackedObject(long trackId, Detection detection, Instant firstSeen, Instant lastSeen)` — trackId≥0, lastSeen≥firstSeen
- `record UsageId(UUID value)` — `static random()`, `static of(String)`
- `record UserId(UUID value)` — `static random()`, `static of(String)`
- `record VideoFrame(StreamId streamId, long sequence, Instant capturedAt, int width, int height, PixelFormat format, ByteBuffer data)` — sequence≥0, width/height>0; ctor stores `data.asReadOnlyBuffer()`; `data()` overridden to return `data.duplicate()` fresh each call

### `com.drones.vision.domain.port.out` (driven — implemented by adapters)
- `AssetRepositoryPort`: `Asset save(Asset)`; `Optional<Asset> findById(AssetId)`; `List<Asset> findAll()`; `Optional<Asset> findByDeviceId(DeviceId)` — a device belongs to ≤1 asset; `void deleteById(AssetId)` — idempotent
- `AssetUsageRepositoryPort`: `AssetUsage save(AssetUsage)` — upsert by id; `Optional<AssetUsage> findById(UsageId)`; `List<AssetUsage> findRecentByAsset(AssetId, int limit)` — newest first; `Optional<AssetUsage> findOpenByAsset(AssetId)` — ≤1 open per asset
- `CategoryRepositoryPort`: `DeviceCategory save(DeviceCategory)`; `Optional<DeviceCategory> findById(CategoryId)`; `List<DeviceCategory> findAll()`
- `DetectionPort`: `CompletionStage<DetectionResult> detect(VideoFrame, PipelineConfig)` — **must not block**; performs no internal queuing/limiting — caller bounds in-flight count and skips (never queues) once at the bound
- `AuditTrailPort`: `AuditEntry record(AuditEntry)` — append-only, never updated or deleted, not even when its target is soft-deleted; `List<AuditEntry> findRecent(int limit)`, `List<AuditEntry> findByTarget(AuditTargetType, String targetId, int limit)`, both newest-first
- `DetectionRepositoryPort`: `void save(DetectionResult)` — append-only; `List<DetectionResult> query(DetectionQuery)`
- `DeviceDiscoveryPort`: `String method()` — stable lower-case key; `List<DiscoveredDevice> scan(Duration timeout)` — **blocking**, must self-time-box to ~timeout, empty list ≠ error
- `DeviceRepositoryPort`: `Device save(Device)`; `Optional<Device> findById(DeviceId)`; `List<Device> findAll()`; `void deleteById(DeviceId)` — idempotent
- `EventPublisherPort`: `void publish(Event)` — must not throw on ordinary delivery failure; called on hot pipeline path, must return quickly
- `ModelRegistryPort`: `List<ModelRef> models()`; `void promote(ModelRef)` — must apply atomically (no partial-promotion reads)
- `OverlayPort`: `VideoFrame render(AnnotatedFrame)` — synchronous, input frame never mutated, returns a distinct instance
- `RecordingPort`: `void streamStarted(StreamId, Device)`; `void publish(StreamId, VideoFrame)` — must be cheap, no blocking I/O; `void streamEnded(StreamId)` — idempotent; method shape mirrors StreamPublisherPort but is a distinct port (durable storage vs live egress)
- `StreamPublisherPort`: `void streamStarted(StreamId, Device)`; `void publish(StreamId, VideoFrame)` — called for every frame, adapter owns latest-wins drop policy; `void streamEnded(StreamId)` — idempotent; `default Optional<URI> viewUrl(StreamId)` — empty unless overridden
- `TelemetryRepositoryPort`: `void save(UsageId, Telemetry)` — append-only; `List<Telemetry> findByUsage(UsageId, int limit)`
- `TelemetrySourcePort`: `boolean supports(Device)`; `Flow.Publisher<Telemetry> open(Device)` — per-open, hot/live, latest-wins backpressure; `void close(DeviceId)` — idempotent
- `VideoSourcePort`: `boolean supports(StreamDescriptor)`; `Flow.Publisher<VideoFrame> open(StreamId, StreamDescriptor)` — per-open, hot/live, latest-wins backpressure, unrecoverable failure → `onError`; `void close(StreamId)` — idempotent

## Conventions
- **Validation:** every record validates in its compact constructor with manual `if (…) throw new IllegalArgumentException(…)` per field (no Bean Validation, no `Objects.requireNonNull` — that idiom is application-layer only).
- **Defensive copies:** every `List`/`Set`/`Map` component is reassigned in the compact ctor via `List.copyOf`/`Set.copyOf`/`Map.copyOf`; `VideoFrame` reassigns `data` to `data.asReadOnlyBuffer()`.
- **UUID id pattern:** `AssetId`/`DeviceId`/`GroupId`/`StreamId`/`UsageId`/`UserId` all wrap `UUID` with the same pair of factories — `random()` (`UUID.randomUUID()`) and `of(String)` (`UUID.fromString`, rethrows as `IllegalArgumentException`). `CategoryId` is the exception: a validated kebab-case `String` slug, not a UUID — categories are reference data with human-authored keys, not generated ids.
- `with*`/`closed` copy methods (`Asset.withDevices/withAttributes`, `AssetUsage.closed/withPositions/withSampleCount`) return new record instances — records have no setters.
- Enums (`Capability`, `EventType`, `PixelFormat`) are pure marker sets, no behavior.
- Every port interface's javadoc documents **Contract** and **Threading**; ports with backpressure concerns (`VideoSourcePort`, `TelemetrySourcePort`) add a **Backpressure** section describing the latest-wins drop policy.

## Gotchas
- `VideoFrame.data()` never returns the buffer stored on the record — every call returns a fresh `data.duplicate()`, so two readers (or two calls from the same reader) never share position/limit/mark state, and writes always throw `ReadOnlyBufferException`.
- `Event.streamId` is nullable — device-level events (`DEVICE_ONLINE`/`DEVICE_OFFLINE`) have no stream.
- `StreamDescriptor.protocol` must already be lower-case; the compact ctor throws `IllegalArgumentException` if it isn't — callers cannot rely on normalization happening for them.
- `CategoryId.slug` must match `[a-z0-9]+(-[a-z0-9]+)*` (lower-case-kebab), enforced here rather than left to callers, because slugs double as stable, human-readable reference-data keys.
- `Asset.devices` must be non-empty — an asset with zero devices cannot be constructed at all.
- `RecordingPort`'s method shape is identical to `StreamPublisherPort` (`streamStarted`/`publish`/`streamEnded`) but the two are deliberately separate interfaces/adapters (durable recording vs live viewer egress), enabled/disabled independently per stream.
- `DetectionPort` itself applies no concurrency limit or queuing — `PipelineConfig.maxInFlightInferences` is enforced entirely by the caller (`vision-application`'s `StreamPipeline`), not this port.
- `ScanRequest.methods()` (now in `vision-application`) empty means "all registered mechanisms", not "none".

## Status
Fully implemented. 127 tests, all passing (`./mvnw -B -pl vision-domain test`).

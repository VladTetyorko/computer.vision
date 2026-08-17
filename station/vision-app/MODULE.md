# vision-app

Spring Boot assembly: the only module that knows about every adapter, wires ports to implementations, and holds runtime configuration/ArchUnit rules.

**Depends on:** vision-domain, vision-application, adapter-simulation, adapter-rtsp, adapter-mjpeg, adapter-mavlink, adapter-v4l2, adapter-publish-hls, adapter-cv-grpc, adapter-overlay, adapter-discovery, adapter-persistence, vision-api, vision-web (static-only jar), spring-boot-starter, **spring-boot-starter-security** (docs/plans/done/U-AUTH-PLAN.md wave 3 — Spring Security lives ONLY here, never in vision-api) · test: spring-boot-starter-test, archunit-junit5, **testcontainers-postgresql, flyway-core** (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4 — see "Test infrastructure" below)
**Used by:** nothing (leaf/assembly module; produces the runnable jar via spring-boot-maven-plugin)
**Build/test:** `./mvnw -B -pl station/vision-app test` — green. Always `clean` first (see Gotchas). Requires the rest of the reactor already installed to the local repo (e.g. `./mvnw -B clean install -DskipTests -DskipWeb=true` once beforehand) since `-pl` alone doesn't build sibling modules from source. **Needs a reachable Docker daemon since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4** (every `@SpringBootTest` needs a real Postgres; since W2b, Postgres is not just the default but the *only* store — there is no `vision.persistence.enabled` flag left to turn it off) — see "Test infrastructure" below for what a Docker-less run does instead of failing.

## Package shape (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D — current, authoritative)

The 825-line, 44-`@Bean` `WiringConfiguration` this file used to describe throughout (see below — every historical section is left as-is, since it's an accurate record of what was true *at the time of that task*) **no longer exists as one file**. It was split into six per-concern classes, alongside the three sibling `@Configuration` classes that already existed one level up and are now moved into the same package for consistency:

```
com.drones.vision.app
  config/
    properties/            every @ConfigurationProperties record (~15, see table below)
    wiring/                per-concern @Configuration bean factories
      VideoSourceWiring          rtsp/mjpeg/v4l2/simulation VideoSourcePort beans + videoSourceRegistry
      TelemetryWiring            mavlink/simulation TelemetrySourcePort + FlightCommandPort/ManualControlPort beans
      PublishWiring              mediamtx publish/replay, overlay renderer, snapshotJpegEncoder, hlsProxyUpstreamBase
      CvWiring                   cvGrpcChannel, detectionPort, cvModelRoster
      FeedTransmitterWiring      rtsp/mjpeg/mavlink FeedTransmitterPort beans + feedTransmitterRegistry
      ApplicationServiceWiring   every vision-application DefaultXService bean, event/audit/live-update
                                 decorator chains, manualControlService, simulationResumeRunner (the
                                 largest piece — this is what most of the old WiringConfiguration was)
      DiscoveryWiringConfiguration   unchanged name, moved here (scanner beans, now config-driven — see below)
      PersistenceWiringConfiguration unchanged name, moved here (repository-port selection, untouched internally)
      TrainingWiringConfiguration    unchanged name, moved here (datasetUploadPort/modelRegistryPort now
                                     build a GrpcCvSettings/Duration from VisionCvProperties instead of
                                     calling adapter constructors that no longer exist)
      AuthWiringConfiguration        unchanged name, moved here (no internal changes beyond imports)
    SecurityConfig.java    the one cross-cutting @Configuration left directly under config/
  security/                BcryptPasswordHasher, VisionUserDetails, DevPrincipalResolver,
                            SecurityContextPrincipalResolver, SecuritySessionAuthenticator,
                            NoopSessionAuthenticator
  events/                  DetectionSessionCleanupEventPublisher, LiveUpdateEventPublisher,
                           LiveUpdateAuditTrail, LiveUpdateDetectionEventRepository
  bootstrap/               SimulationResumeRunner (the one remaining ApplicationRunner —
                           AuthSeedRunner deleted docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1, see
                           "Auth / session security" and the W1 narrative section below)
  devsupport/              unchanged, no sub-structuring (deferred, §8 of the plan)
```

**Why split this way, not some other grouping**: the plan named six illustrative buckets (`VideoSourceWiring`/`TelemetryWiring`/`PublishWiring`/`CvWiring`/`ApplicationServiceWiring`/`FeedTransmitterWiring`) and left exact assignment to judgment; the mapping above followed the adapter/protocol boundary each bean's *type* belongs to (a `VideoSourcePort` bean → `VideoSourceWiring`, a `vision-application` service bean → `ApplicationServiceWiring`, regardless of which adapter it happens to depend on). `DiscoveryWiringConfiguration`/`PersistenceWiringConfiguration`/`TrainingWiringConfiguration`/`AuthWiringConfiguration` kept their existing names rather than being renamed to match the new `*Wiring` convention — a deliberate, bounded call: renaming them would have touched ~20 test files' `@SpringBootTest`/javadoc references for zero behavioral gain (Spring resolves beans by type, not by which file declares them, and `@SpringBootTest` in this module boots the whole component-scanned context rather than naming explicit `classes = {...}`), so only the *package* move (mechanical import-path fix) was made, not a name churn.

**Properties records now in `config/properties/`** (docs/plans/active/LAYERING-REFACTOR-PLAN.md §2.2 — the frozen key table this wave implements almost in full): the 9 pre-existing records moved here unchanged, plus `VisionApplicationProperties` (Wave A) — 10 moved total — plus 6 new records this wave added, plus 5 of the 10 moved ones extended with new nested fields. Every new/extended `@DefaultValue` is byte-identical to the adapter-side literal it now sources from (see each record's own javadoc for the exact provenance) — commented-out documentation lines for every new key live in `application.yaml`.

| Record | Status | Prefix | Maps onto |
|---|---|---|---|
| `VisionApplicationProperties` | moved (Wave A) | `vision.application` | `vision-application`'s own settings records (`StreamPipelineSettings`, `ReplayServiceSettings`, `SimulationServiceSettings`) |

`vision.application.pipeline.camera-hfov-degrees` (default `0.0` = unknown) is the newest key here and the only one that is a **physical** property rather than a tuning knob: it is the camera's horizontal field of view, the scale that turns a telemetry attitude delta into a pixel shift, and therefore what lets cv-service's `pose` ego-motion compensator run at all (docs/conclusions/CV-RATE-BUDGET.md §2). `0` ships deliberately rather than a plausible-looking `60`: a wrong FOV produces a confidently wrong pixel shift, which is worse than the `flow` fallback it would displace. It is **one value per instance, not per camera** — a deployment mixing lenses needs it lifted onto the asset, which is the recorded follow-up.

`vision.application.pipeline.adaptive-rate.{enabled,max-fps,ewma-alpha}` (defaults `true`, `30.0`, `0.2` — mapped onto `AdaptiveRateSettings`) is the rate control loop of docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R2. It raises the sampler's rate toward what a tracked target's own motion demands against its association budget, and leaves it exactly at `inferenceFps` whenever nothing is tracked or nothing is moving. **On by default, unlike `camera-hfov-degrees`**, and the difference is not inconsistency: a missing FOV means the deployment has stated nothing, so inventing one would be a guess, whereas the loop derives everything it uses from facts already measured. It can only ever *raise* the configured rate, and is bounded at runtime by the source rate and by measured detector capacity, so the worst case of enabling it is that it does nothing. The ego-motion half still needs `camera-hfov-degrees`; the target-motion half does not.
| `VisionPersistenceProperties` | moved; extended docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 with `seedDevUsers` | `vision.persistence` | `adapter-persistence`'s `PersistenceUnit` |
| `VisionLiveProperties` | moved, unchanged | `vision.live` | selects `LiveUpdateRegistry` (vision-api) vs. `NoopLiveUpdatePublisher` |
| `VisionTrainingProperties` | moved, unchanged | `vision.training` | gates `TrainingWiringConfiguration`'s whole bean cluster |
| `VisionCvProperties` | moved + **extended** | `vision.cv` | `adapter-cv-grpc`'s `GrpcCvSettings` (new: `response-timeout`/`keepalive-*`/`channel-shutdown-timeout`/`plaintext`/`upload.*`) + `GrpcModelRegistryPort`'s call-timeout (new: `registry.call-timeout`, wired directly, not via `GrpcCvSettings`) |
| `VisionDiscoveryProperties` | moved + **extended** | `vision.discovery` | `adapter-discovery`'s `ScanBudget` (new: `mdns.*`) and `V4l2Scanner`'s 2-arg ctor (new: `v4l2.dev-base`/`v4l2.sys-base`) |
| `VisionSimulationProperties` | moved + **extended** | `vision.simulation` | `adapter-simulation`'s `VideoSettings`/`TelemetrySettings` (new: `video.*`/`telemetry.*`) |
| `VisionPublishProperties` | moved + **extended** | `vision.publish` | `adapter-publish-hls`'s new `PublishSettings` (new: `encoder.*`/`resilience.*`/`cadence.*`/`replay.*`) |
| `VisionRcProperties` | moved + **extended** | `vision.rc` | `adapter-mavlink`'s `MavlinkSettings.Rc` (new: `override-hz`/`min-override-hz`/`max-override-hz`/`release-frames` — these are what replaced the deleted `VISION_RC_OVERRIDE_HZ`/`VISION_RC_RELEASE_FRAMES` env vars) |
| `VisionRtspProperties` | **new** | `vision.rtsp` | `adapter-rtsp`'s `FfmpegSettings` (+ nested `Transmit`) |
| `VisionMjpegProperties` | **new** | `vision.mjpeg` | `adapter-mjpeg`'s `MjpegSettings` (+ nested `Transmit`; `transmit.max-viewer-threads` is a nullable `Integer`, absent by default, mapped to `OptionalInt`) |
| `VisionV4l2Properties` | **new** | `vision.v4l2` | `V4l2VideoSource`'s 2-arg ctor directly (only 2 tunables, no dedicated adapter settings record — §1.3 rule 4) |
| `VisionMavlinkProperties` | **new** | `vision.mavlink` | `adapter-mavlink`'s `MavlinkSettings` minus `Rc` (which is `vision.rc`, see above) |
| `VisionOverlayProperties` | **new** | `vision.overlay` | `adapter-overlay`'s `OverlaySettings` |
| `VisionApiProperties` (`com.drones.vision.app.config.properties`) | **new** | `vision.api` | maps across to `com.drones.vision.api.support.VisionApiProperties` (vision-api's own framework-free mirror) — the **only** consumer today is `PublishWiring#snapshotJpegEncoder`; `HlsProxyController`/`LiveUpdateRegistry`/`AssetImageController`/per-controller paging still read their own local constants, not this record's `hls-proxy`/`live`/`paging`/`upload` fields (a documented gap, not an oversight) |

**Naming collision, deliberate**: `com.drones.vision.app.config.properties.VisionApiProperties` (Spring-bound, this module) and `com.drones.vision.api.support.VisionApiProperties` (plain, vision-api) are two distinct classes sharing a simple name in two different packages — see `PublishWiring#snapshotJpegEncoder`'s own javadoc and the vision-app-side `VisionApiProperties`'s own javadoc for why they stay separate (vision-api may not depend on Spring's `@ConfigurationProperties` machinery) and how the one file that needs both (`PublishWiring`) tells them apart (fully-qualifies the vision-api one, imports the Spring one plain).

**`adapter-mavlink`/`adapter-cv-grpc` compile-break fixes this wave also made** (upstream E-phase waves had already split those adapters' internals but left two real compile breaks for this wave to close, per the plan's own Part 1):
- `MavlinkSettings` (record) and its nested `Scan`/`Transmit`/`Rc` were widened from package-private to `public` — a cross-module properties-mapping caller (this module) cannot otherwise name a package-private type at all (JLS 6.6.1), regardless of its members' own modifiers.
- `MavlinkSocketHub`/`MavlinkTelemetrySource`/`MavlinkFeedTransmitter`/`MavlinkHeartbeatScanner`/`MavlinkFlightCommander` each gained a new settings-taking constructor overload (`MavlinkSettings`, `MavlinkSettings.Scan`, or `Duration` — whichever slice each actually needs), with the pre-existing no-arg/raw-param constructor kept, now delegating to `MavlinkSettings.defaults()`/`Scan.defaults()` — no existing call site (adapter-mavlink's own 135 tests) needed to change.
- `GrpcModelRegistryPort` gained a `(ManagedChannel, Duration callTimeout)` constructor (the 1-arg one now delegates to `Duration.ofSeconds(CALL_TIMEOUT_SECONDS)`), so `vision.cv.registry.call-timeout` actually reaches it.
- `adapter-publish-hls` gained a new `PublishSettings` record (encoder/resilience/cadence nested records) and settings-taking overloads on `MediamtxStreamPublisher`/`H264RecorderFactory`/`PublishBackoff`/`CadenceEstimator`/`MediamtxReplayFrameExtractor` — none of these existed before this wave (the earlier E3 wave split the classes internally but left every tunable as a `static final` constant); every existing test in that module still passes unmodified since the old no-settings constructors/overloads are kept, delegating to the same literals.
- `vision-api`'s `StreamController`/`DeviceProbeController` no longer self-construct `SnapshotJpegEncoder` from `VisionApiProperties.defaults()` — both now take it as a constructor-injected collaborator (`PublishWiring#snapshotJpegEncoder` supplies the real bean); their own unit tests updated to construct one explicitly (`new SnapshotJpegEncoder(VisionApiProperties.defaults())`) rather than relying on the controller's own field initializer.

**Everything below this section is the pre-existing MODULE.md content**, describing each feature/bean by its *original* location and task history; cross-references of the shape `WiringConfiguration#beanName` have been mechanically updated to name the class that bean actually lives in today (e.g. `PublishWiring#streamPublisherPort`), but the surrounding historical prose (what was added, when, why) is left as written — it remains an accurate record of that task, not a description of today's file layout.

## Bean inventory

Historically wired directly in `WiringConfiguration` (`@EnableConfigurationProperties({VisionPublishProperties.class, VisionCvProperties.class, VisionLiveProperties.class, VisionSimulationProperties.class, VisionRcProperties.class})` — `VisionRcProperties` added by docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4, see below) — see "Package shape" above for where each bean actually lives now:

| Bean | Type | Implementation |
|---|---|---|
| `simulatedVideoSource` | `VideoSourcePort` | `SimulatedVideoSource` (adapter-simulation) |
| `ffmpegVideoSource` | `VideoSourcePort` | `FfmpegVideoSource` (adapter-rtsp) — covers both `rtsp` and `file` protocols |
| `mjpegVideoSource` | `VideoSourcePort` | `MjpegVideoSource` (adapter-mjpeg) — RX half of the mjpeg TX/RX pair (docs/main/CYCLES-PLAN.md §5) |
| `v4l2VideoSource` | `VideoSourcePort` | `V4l2VideoSource` (adapter-v4l2) — docs/plans/done/MVP2-PLAN.md X-b, USB/V4L2 local camera ingest, RX only; supports protocol `v4l2` with a `file:`-scheme URI, the exact shape `adapter-discovery`'s `V4l2Scanner` emits (**not** the plan's originally-proposed `usb`/`v4l2://` shape — see adapter-v4l2/MODULE.md) |
| `videoSourceRegistry` | `VideoSourceRegistry` | `new VideoSourceRegistry(List<VideoSourcePort>)` — collects all four `VideoSourcePort` beans above |
| `deviceRepositoryPort` | `DeviceRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaDeviceRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b |
| `detectionRepositoryPort` | `DetectionRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaDetectionRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b; append-only ring, capped per stream (docs/plans/done/MVP1-PLAN.md §C8 bullet 3) |
| `overlayRenderer` | `OverlayPort` | `Java2DOverlayRenderer` (adapter-overlay) — docs/plans/done/MVP1-PLAN.md §C8 bullets 1-2; stateless, no config |
| `eventPublisherPort` | `EventPublisherPort` | `LoggingEventPublisher` (devsupport), wrapped in `DetectionSessionCleanupEventPublisher` when `vision.cv.enabled=true` and `detectionPort` resolved to a `GrpcDetectionPort` — see "CV inference wiring" below; further wrapped in `LiveUpdateEventPublisher` when `vision.live.enabled=true` (default) — see "Server-push data plane" below |
| `streamPublisherPort` | `StreamPublisherPort` | **docs/plans/active/MEDIA-SOT-PLAN.md wave M7**: `NoopStreamPublisher` (devsupport) if `vision.publish.enabled=false`, else a `PublisherRouter` (adapter-publish-hls) wrapping `MediamtxStreamPublisher` (unchanged 4-arg construction: `mediamtx.rtspBase()` to push, `VisionPublishProperties#viewBase()` — **not** `mediamtx.hlsBase()` — as `hlsViewBase`, `mediamtx.whepBase()` as `whepViewBase`, `mediamtx.playbackBase()` as `playbackViewBase`) as its direct publisher and `MediamtxProxyPublisher` (`mediamtx.apiBase()`, same view bases, `MediamtxProxySettings` mapped from `VisionPublishProperties.SourceProxy` + `mediamtx.apiUser()`/`apiPassword()`) as its proxy publisher, routed by `vision.publish.source-proxy.enabled` (default `false`, D1) — see "Media source-of-truth wiring" below |
| `mediamtxLiveFrameGrabber` | `MediamtxLiveFrameGrabber` | **docs/plans/active/MEDIA-SOT-PLAN.md wave M7**: `new MediamtxLiveFrameGrabber(mediamtx.rtspBase())` (adapter-publish-hls) — unconditional (cheap, no I/O at construction); consumed by `LiveFrameFallbackStreamService` when wired — see "Media source-of-truth wiring" below |
| `pulledDetectionPort` | `PulledDetectionPort` | **docs/plans/active/MEDIA-SOT-PLAN.md wave M7**: `CvWiring` — `GrpcPulledDetectionPort(cvGrpcChannel, toGrpcCvSettings(cvProperties))` present only when `VisionCvProperties#pullEnabled()` (`vision.cv.frame-transport=pull`); absent by default — see "Media source-of-truth wiring" below |
| `replayFrameExtractionPort` | `ReplayFrameExtractionPort` | `MediamtxReplayFrameExtractor(mediamtx.playbackBase())` if `vision.publish.enabled` else `NoopReplayFrameExtractor` (devsupport) — docs/plans/done/CV-TRAINING-V2-PLAN.md §7; same if/else split as `streamPublisherPort`, consumed by `TrainingWiringConfiguration#replaySources` |
| `hlsProxyUpstreamBase` | `URI` | `properties.mediamtx().hlsBase()` — the collaborator `HlsProxyController` (`vision-api`, component-scanned) needs; see Gotchas for why this is a bean rather than `HlsProxyController` being hand-constructed here |
| `cvModelRoster` | `List<CvModelResponse>` | a static, in-source constant (docs/plans/done/CV-CONTROL-PLAN.md §4) — the collaborator `CvModelsController` (`vision-api`, component-scanned) needs, the same "raw collaborator, not a domain port" pattern as `hlsProxyUpstreamBase`; see "Detection-model roster" below |
| _(no seed bean)_ | — | `TrackingWiring#streamStartTrackingSeed` maps `vision.tracking.*` to a `TrackingConfigPatch` that rides `StreamPipelineSettings` into `DefaultStreamService` (docs/extracts/TRACKING-ORCHESTRATION.md §4.1). It is **not** a bean and no controller is injected with it: a deployment default belongs where streams are started — one place every start path (device, asset, simulation, demo fleet) goes through — not at two REST endpoints. Was a `TrackingConfig` bean in wave T6; see the follow-up section at the end of this file |
| `cvTrackerRoster` | `List<CvTrackerResponse>` | `TrackingWiring`: a static, in-source constant (docs/plans/done/TRACKING-PLAN.md §4.F) — the roster `CvTrackersController` serves at `GET /api/cv/trackers`, mirroring `cvModelRoster`'s frozen decision exactly (deploy-time, not runtime) |
| `cvGrpcChannel` | `ManagedChannel` | one shared gRPC connection to cv-service, present whenever `vision.cv.enabled` or `vision.training.enabled` is `true` (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) — consumed by both `detectionPort` below and `TrainingWiringConfiguration#modelRegistryPort`; see "CV inference wiring" below |
| `detectionPort` | `DetectionPort` | `GrpcDetectionPort` (adapter-cv-grpc) over the shared `cvGrpcChannel` if `vision.cv.enabled=true`, else `NoopDetectionPort` (devsupport) — see "CV inference wiring" below |
| `categoryRepositoryPort` | `CategoryRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaCategoryRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b; seeding now lives in Flyway (`V1__*.sql`), not a devsupport ctor arg — see "Persistence wiring" below |
| `assetRepositoryPort` | `AssetRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaAssetRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b |
| `assetUsageRepositoryPort` | `AssetUsageRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaAssetUsageRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b |
| `assetImageRepositoryPort` | `AssetImageRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaAssetImageRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b; docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 2's asset image store |
| `geofenceRepositoryPort` | `GeofenceRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaGeofenceRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b; docs/plans/done/OPS-CORE-PLAN.md §G, G-b's geofence zone store |
| `telemetryRepositoryPort` | `TelemetryRepositoryPort` | `PersistenceWiringConfiguration`: `new JpaTelemetryRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b |
| `simulatedTelemetrySource` | `SimulatedTelemetrySource` (also a `TelemetrySourcePort`) | `new SimulatedTelemetrySource()` (adapter-simulation, real 1Hz cadence); collected into `usageTracker`'s `List<TelemetrySourcePort>` |
| `mavlinkTelemetrySource` | `MavlinkTelemetrySource` (also a `TelemetrySourcePort`) | `new MavlinkTelemetrySource()` (adapter-mavlink) — docs/plans/done/MVP2-PLAN.md X-a's RX half; no-arg, listens per-device on the `udp://host:port` its `Device`'s `StreamDescriptor` names; collected into `usageTracker`'s `List<TelemetrySourcePort>` alongside `simulatedTelemetrySource`; the same bean instance is also handed to `DiscoveryWiringConfiguration#mavlinkHeartbeatScanner` (docs/plans/active/DRONE-INFRA-PLAN.md I-b) so a discovery scan can borrow whichever bind address this instance already has open |
| `mavlinkFlightCommander` | `MavlinkFlightCommander` (also a `FlightCommandPort`) | `new MavlinkFlightCommander(mavlinkTelemetrySource)` (adapter-mavlink) — docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's command-TX path ("bring it home"); reuses the exact `mavlinkTelemetrySource` bean instance, the same instance-borrowing pattern as `mavlinkHeartbeatScanner`; wired unconditionally, like `mavlinkTelemetrySource` itself — no `vision.mavlink.*`-shaped gate |
| `mavlinkManualControlSender` | `MavlinkManualControlSender` (also a `ManualControlPort`) | `new MavlinkManualControlSender(mavlinkTelemetrySource)` (adapter-mavlink) — docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4, the streaming ack-less `RC_CHANNELS_OVERRIDE` relay-TX behind `manualControlService`; same instance-borrowing pattern as `mavlinkFlightCommander`/`mavlinkHeartbeatScanner`, wired unconditionally for the same "no `vision.mavlink.*`-shaped gate" reason |
| `deviceService` | `DeviceService` | `new DefaultDeviceService(deviceRepositoryPort, assetLiveStatePort, auditTrailPort, eventPublisherPort)` — `assetLiveStatePort` replaced `streamService` directly in docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e (see the `assetLiveStatePort` row below) |
| _(removed)_ `actingOwnership` | — | **Removed** by docs/plans/done/U-AUTH-PLAN.md wave 3. The `Ownership` bean `CurrentUser` used to autowire is gone; `CurrentUser` now delegates to a `PrincipalResolver` seam wired by `AuthWiringConfiguration` (dev principal when `vision.auth.enabled=false`, session-reading when `true`) — see "Auth / session security" below and station/vision-api/MODULE.md's "Auth seams". `DevPrincipal.OWNERSHIP` is still the fixed dev principal, now reached through `DevPrincipalResolver`. |
| `auditTrailPort` | `AuditTrailPort` | `new JpaAuditTrail(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b, wrapped in `LiveUpdateAuditTrail` when `vision.live.enabled=true` (default) — see "Server-push data plane" below |
| `usageTracker` | `UsageTracker` | `new UsageTracker(assetRepositoryPort, deviceRepositoryPort, assetUsageRepositoryPort, telemetryRepositoryPort, List<TelemetrySourcePort>, telemetryLiveUpdatePort, geofenceMonitor)` — the 7-arg ctor (docs/plans/done/OPS-CORE-PLAN.md §G, on top of docs/plans/done/REALTIME-PLAN.md §4's own 6-arg bump); both `telemetryLiveUpdatePort` and `geofenceMonitor` are always real beans (never `null`), so this is unconditional wiring, not a feature-flag branch here |
| `geofenceMonitor` | `GeofenceMonitor` | `new GeofenceMonitor(geofenceRepositoryPort, eventPublisherPort, eventLiveUpdatePort)` — docs/plans/done/OPS-CORE-PLAN.md §G; breach evaluation on the telemetry hot path, threaded into `usageTracker` above |
| `geofenceService` | `GeofenceService` | `new DefaultGeofenceService(geofenceRepositoryPort, geofenceMonitor)` — docs/plans/done/OPS-CORE-PLAN.md §G; CRUD/list behind `GET/POST /api/geofences`, `PUT`/`DELETE /api/geofences/{id}` (vision-api's `GeofenceController`); a one-line assembly, mirroring `replayService`'s shape |
| `markService` | `MarkService` | `new DefaultMarkService(markRepositoryPort, usageTracker, mapLiveUpdatePort, mapAccessPolicy, layerResolver)` — docs/plans/done/TACTICAL-MARKS-PLAN.md M4, **reworked in place by docs/plans/done/MAP-REWORK-PLAN.md §3** (the 3-arg ctor grew to 5); behind `MapMarksController` (`/api/map/marks/**`), not the deleted `MarksController`. `usageTracker` still backs the cockpit "geolocate" action (`UsageTracker#latestTelemetry`); `mapLiveUpdatePort` is always a real bean so every create/patch/verify/promote/delete is announced on the `map` SSE topic unconditionally |
| `mapAccessPolicy` | `MapAccessPolicy` | `new MapAccessPolicy()` — docs/plans/done/MAP-REWORK-PLAN.md §3; the map's whole authorization model. Pure, stateless, no ports, so one shared singleton serves all three map services **and** `CurrentUser#viewer()`'s consumers |
| `layerResolver` | `LayerResolver` | `new LayerResolver(mapLayerRepositoryPort, mapLiveUpdatePort)` — docs/plans/done/MAP-REWORK-PLAN.md §3; the shared layer-lookup / default-layer / COP find-or-create collaborator. **Deliberately one bean, not three instances**: its `copLayerId()`/`defaultLayerFor()` are `synchronized` find-or-create methods whose idempotence depends on a single instance guarding a single repository |
| `mapLayerService` | `MapLayerService` | `new DefaultMapLayerService(layerResolver, markRepositoryPort, drawingRepositoryPort, mapLiveUpdatePort, mapAccessPolicy)` — docs/plans/done/MAP-REWORK-PLAN.md §3; layer CRUD + grants behind `MapLayersController`. Takes the mark/drawing **repositories** directly rather than their services, because the only thing it does with them is cascade a layer deletion — for which the services' own viewer-gated methods would be both wrong (the cascade is already authorized) and circular |
| `drawingService` | `DrawingService` | `new DefaultDrawingService(drawingRepositoryPort, mapLiveUpdatePort, mapAccessPolicy, layerResolver)` — docs/plans/done/MAP-REWORK-PLAN.md §3; lines/polygons/arrows/text behind `MapDrawingsController`; `markService`'s shape minus the telemetry collaborator a drawing has no use for |
| `streamService` | `StreamService` | **docs/plans/active/MEDIA-SOT-PLAN.md wave M7**: `ApplicationServiceWiring#streamService` now builds `DefaultStreamService`'s **12-arg ctor** — the 10-arg shape (docs/plans/done/REALTIME-PLAN.md §4: `deviceRepositoryPort, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisherPort, usageTracker, overlayPort, detectionEventRepositoryPort, detectionLiveUpdatePort`) plus `streamPipelineSettings(...)` and a `PullDetectionSettings` (`null` unless `VisionCvProperties#pullEnabled()`, built inline from `CvWiring`'s conditional `pulledDetectionPort` bean + `cvProperties.pull().rtspBase()`). The bean method then wraps the result in `com.drones.vision.app.stream.LiveFrameFallbackStreamService` when `VisionPublishProperties.SourceProxy#enabled()` is `true` — see "Media source-of-truth wiring" below for both. With every wave-M7 flag at its default, this bean is byte-identical (same type, same behaviour) to before the wave |
| `fleetLiveUpdatePort` / `telemetryLiveUpdatePort` / `detectionLiveUpdatePort` / `mapLiveUpdatePort` / `eventLiveUpdatePort` | `FleetLiveUpdatePort` / `TelemetryLiveUpdatePort` / `DetectionLiveUpdatePort` / `MapLiveUpdatePort` / `EventLiveUpdatePort` | five near-identical `@Bean` methods (docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b — replacing the one former `liveUpdatePublisherPort` bean of the now-deleted god-port `LiveUpdatePublisherPort`), each: `LiveUpdateRegistry` (vision-api, `com.drones.vision.api.live`, implements all five) if `vision.live.enabled` (default `true`) else `NoopLiveUpdatePublisher` (devsupport, also implements all five) — see "Server-push data plane" below |
| `detectionEventRepositoryPort` | `DetectionEventRepositoryPort` | `new JpaDetectionEventRepository(entityManagerFactory)` (adapter-persistence) — unconditional, docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b (was `InMemoryDetectionEventRepository`, docs/plans/done/MVP2-PLAN.md §E, E-a's "persistence explicitly deferred" is now resolved); wrapped in `LiveUpdateDetectionEventRepository` when `vision.live.enabled=true` (default — backend follow-up batch, extends the `detection-events` live topic) — see "Server-push data plane" below |
| `assetLiveStatePort` | `AssetLiveStatePort` (vision-domain, `warehouse.domain.port`) | `new StreamBackedAssetLiveState(streamService, usageTracker, detectionEventRepositoryPort)` (`perception.application.stream`, vision-application) — docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e, the inversion that killed the last module cycle (`perception <-> warehouse`); warehouse's four services below (`deviceService`, `assetService`, `assetStatsService`, `fleetSummaryService`) all take this one port instead of `streamService`/`usageTracker`/`detectionEventRepositoryPort` directly |
| `assetStreamService` | `AssetStreamService` (`perception.application.stream`) | `new DefaultAssetStreamService(assetRepositoryPort, deviceService, streamService)` — docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e; asset-level stream *starting*, split off `AssetService` since it hands perception's own `PipelineConfig`/`TrackingConfigPatch` to the runtime — resolves the asset directly, `perception -> warehouse` being the legal direction; behind `AssetStreamController` (vision-api, component-scanned) |
| `assetService` | `AssetService` | `new DefaultAssetService(assetRepositoryPort, categoryRepositoryPort, assetUsageRepositoryPort, auditTrailPort, deviceService, assetLiveStatePort)` — devices reached through `deviceService`, never the device repository directly; live runtime state (streaming status, stopping a stream) through `assetLiveStatePort`, never `streamService` directly (W1.6e). **Starting** a stream is `assetStreamService` above, not this bean |
| `categoryService` | `CategoryService` | `new DefaultCategoryService(categoryRepositoryPort)` |
| `flightCommandService` | `FlightCommandService` | `new DefaultFlightCommandService(assetService, flightCommandPort, auditTrailPort)` — docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1's guarded return-to-home service, behind `POST /api/assets/{id}/return-home` (`FlightCommandController`, vision-api); `flightCommandPort` resolves to `mavlinkFlightCommander`, the one `FlightCommandPort` bean in this context today |
| `manualControlService` | `ManualControlService` | `new DefaultManualControlService(assetService, manualControlPort, auditTrailPort, Clock.systemUTC(), rcWatchdogScheduler(), rcProperties.watchdogTimeoutMs())` — docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4, the watchdog-supervised RC-relay session service behind `/ws/manual-control` (`ManualControlWebSocketHandler`, vision-api); `manualControlPort` resolves to `mavlinkManualControlSender`, the one `ManualControlPort` bean in this context today. Uses `DefaultManualControlService`'s **6-arg canonical constructor** (not either frozen-shape convenience one) specifically so `VisionRcProperties#watchdogTimeoutMs()` actually reaches the watchdog; `rcWatchdogScheduler()` is a private helper building a fresh single-thread daemon `"rc-watchdog"` `ScheduledExecutorService` (no separate bean — nothing else in this context needs to see it) |
| `replayService` | `ReplayService` | `new DefaultReplayService(assetUsageRepositoryPort, telemetryRepositoryPort, detectionRepositoryPort, streamPublisherPort)` — docs/plans/done/MVP2-PLAN.md R-a2, the bean R-a's own writeup flagged as missing; `streamPublisherPort` (docs/plans/done/OPS-CORE-PLAN.md §R, R-b) is the same bean `streamPublisherPort` below already is, reused so `recordingFor` can resolve `playbackUrl` |
| `usageService` | `UsageService` | `new DefaultUsageService(assetUsageRepositoryPort, assetRepositoryPort)` — docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8, the fleet-wide "replay library" list behind `GET /api/usages` (vision-api's `UsageTimelineController`); a one-line assembly reusing the same `assetUsageRepositoryPort`/`assetRepositoryPort` beans already wired above, mirroring `replayService`'s shape |
| `fleetSummaryService` | `FleetSummaryService` | `new DefaultFleetSummaryService(assetService, assetLiveStatePort, fleet.maxAssets(), fleet.openEventsScanLimit())` — docs/plans/done/MVP3-PLAN.md C-a, the manager dashboard's aggregated read behind `GET /api/fleet/summary` (vision-api's `FleetController`); **down from 4 collaborators to 2** in W1.6e (`streamService`/`usageTracker`/`detectionEventRepositoryPort` collapsed into `assetLiveStatePort`) |
| `assetStatsService` | `AssetStatsService` | `new DefaultAssetStatsService(assetUsageRepositoryPort, assetLiveStatePort, applicationProperties.stats().fetchLimit())` — docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A, the manager page's per-asset flight-stats KPI aggregate behind `GET /api/assets/{id}/stats` (vision-api's `AssetStatsController`); `assetLiveStatePort` replaced `usageTracker` directly in W1.6e |
| `probeService` | `ProbeService` | `new DefaultProbeService(videoSourceRegistry, telemetrySources)` — docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 1's test-before-save connection probe behind `POST /api/devices/probe` (vision-api's `DeviceProbeController`); reuses the already-collected `videoSourceRegistry` bean and `List<TelemetrySourcePort>` (the same collection `usageTracker` takes) — a one-line assembly, mirroring `replayService`'s shape |
| `rtspFeedTransmitter` | `FeedTransmitterPort` | `new RtspFeedTransmitter(properties.mediamtx().rtspBase())` (adapter-rtsp) — docs/main/CYCLES-PLAN.md §3's TX half; reuses `VisionPublishProperties.Mediamtx#rtspBase()`, the same mediamtx RTSP push target `streamPublisherPort`'s `MediamtxStreamPublisher` already pushes viewer egress to (no new property — both users push to the same mediamtx instance) |
| `mjpegFeedTransmitter` | `FeedTransmitterPort` | `new MjpegFeedTransmitter()` (adapter-mjpeg) — docs/main/CYCLES-PLAN.md §5's TX half; no-arg, unlike `rtspFeedTransmitter` (it serves its own ephemeral HTTP port rather than pushing to an external target); `@Bean(destroyMethod = "close")` so Spring shuts its shared `HttpServer`/dispatch pool down on context close (`stop(FeedId)` alone never does — see adapter-mjpeg/MODULE.md) |
| `mavlinkFeedTransmitter` | `FeedTransmitterPort` | `new MavlinkFeedTransmitter()` (adapter-mavlink) — docs/plans/done/MVP2-PLAN.md X-a's TX half; no-arg like `mjpegFeedTransmitter` (the destination is per-feed, via `FeedSpec.source()`, not a shared constructor-injected base — see adapter-mavlink/MODULE.md); **not yet** selected by `simulationService` (`SimulationTransport` has no `MAVLINK` variant — out of X-a's scope), so it's reachable only through `feedTransmitterRegistry`'s own protocol-based resolution and the plain device/asset APIs today |
| `feedTransmitterRegistry` | `FeedTransmitterRegistry` | `new FeedTransmitterRegistry(List<FeedTransmitterPort>)` — collects `rtspFeedTransmitter` + `mjpegFeedTransmitter` + `mavlinkFeedTransmitter`, the TX-side mirror of `videoSourceRegistry` (docs/main/CYCLES-PLAN.md §5, extended docs/plans/done/MVP2-PLAN.md X-a) |
| `simulationService` | `SimulationService` | `new DefaultSimulationService(assetService, assetStreamService, categoryRepositoryPort, feedTransmitterRegistry, properties.mediamtx().rtspBase())` — docs/main/CYCLES-PLAN.md §1b, §3, §5's one-call, zero-hardware simulation entry point; builds on `assetService` (create) and `assetStreamService` (autoStart, W1.6e — see the `assetStreamService` row above) rather than the repositories directly, so it inherits every rule asset creation/streaming already enforces; `feedTransmitterRegistry` selects `rtspFeedTransmitter`/`mjpegFeedTransmitter` by protocol for `transport=rtsp`/`mjpeg` simulations; the mediamtx-base arg (backend follow-up batch) reuses `VisionPublishProperties.Mediamtx#rtspBase()` — no new property, same reuse as `rtspFeedTransmitter` above — so `DefaultSimulationService#resumeAll()` can recognize its own TX-fed feeds; see "Simulated-feed resume-on-boot" below |
| `simulationResumeRunner` | `ApplicationRunner` | `new SimulationResumeRunner(simulationService, simulationProperties.resumeOnBoot())` — backend follow-up batch, simplified in docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b (persistence is unconditional now, so only `vision.simulation.resume-on-boot` gates it); see "Simulated-feed resume-on-boot" below |

`DiscoveryWiringConfiguration`: `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner`/`mavlinkHeartbeatScanner` (`DeviceDiscoveryPort`, each `@ConditionalOnProperty(vision.discovery.enabled, default true)`) and `discoveryService` (`ScanDevicesUseCase` → `new DiscoveryService(List<DeviceDiscoveryPort>)`) — **always** registered regardless of the property, since `DiscoveryController` needs it unconditionally; `DiscoveryService` tolerates an empty port list (degrades to an empty `ScanResult`). `mavlinkHeartbeatScanner` (docs/plans/active/DRONE-INFRA-PLAN.md I-b) is `new MavlinkHeartbeatScanner(mavlinkTelemetrySource, 14550)` — the **same** `MavlinkTelemetrySource` bean `WiringConfiguration` already wires for real telemetry ingest (so the scanner's hub-borrow path actually sees devices that instance has open), autowired across the two `@Configuration` classes by type; the port is a hardcoded constant (`DiscoveryWiringConfiguration.MAVLINK_HEARTBEAT_SCAN_PORT`), not a new `vision.*` property — none of the other three scanners has a per-scanner property either, so there was no config precedent to follow. Lives in `adapter-mavlink`, not `adapter-discovery` like its three siblings — see adapter-mavlink/MODULE.md for why (adapters never depend on each other; this scanner needs to see `MavlinkTelemetrySource` directly to borrow its socket).

`VisionPublishProperties` (`@ConfigurationProperties(prefix="vision.publish")`): `enabled` (`@DefaultValue("true")`), `viewBase: URI` (`@DefaultValue("/hls")`, defaulted again in the compact ctor if null — the URL base actually handed to viewers, app-relative by default so mediamtx's own address is never exposed to browsers), `mediamtx: Mediamtx` (defaulted as a whole in the compact ctor if null); nested `Mediamtx(rtspBase: URI, hlsBase: URI, whepBase: URI, playbackBase: URI, apiBase: URI, apiUser: String, apiPassword: String)` defaulting to `rtsp://localhost:8554` / `http://localhost:8888` / `http://localhost:8889` / `http://localhost:19996` / `http://localhost:19997` / unset / unset — **`hlsBase` is now purely the internal upstream `HlsProxyController` forwards to** (see `com.drones.vision.api.proxy.HlsProxyController`, vision-api), not a viewer-facing URL; that role moved to `viewBase`. **`whepBase` (docs/plans/done/MVP2-PLAN.md L-a) gets no such split** — `WiringConfiguration` hands `mediamtx.whepBase()` straight to `MediamtxStreamPublisher` as `whepViewBase` and it reaches the viewer unchanged, because a WHEP session (POST/SDP + ICE) cannot be reverse-proxied the trivial way HLS segments are; see "WHEP viewing (docs/plans/done/MVP2-PLAN.md L-a)" below. `apiBase`/`apiUser`/`apiPassword` (docs/plans/active/MEDIA-SOT-PLAN.md wave M7) are `MediamtxProxyPublisher`'s Control API endpoint/credentials — see "Media source-of-truth wiring" below. A sibling `SourceProxy` record (`enabled`/`onDemand`/`rtspTransport`/`readyTimeout`, all defaulted, `enabled` defaulting `false`) rides alongside `mediamtx` on this same properties record — same wave, same section.

`vision.discovery.enabled` (plain `boolean`, no `@ConfigurationProperties` record — read directly via `@ConditionalOnProperty` in `DiscoveryWiringConfiguration`, default `true`).

`PersistenceWiringConfiguration` (`@EnableConfigurationProperties(VisionPersistenceProperties.class)`) — wires the `EntityManagerFactory` bean and every `Jpa*Repository`/`JpaAuditTrail` port (docs/plans/done/MVP2-PLAN.md P-a + P-b, extended through docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) straight-line, unconditionally; see "Persistence wiring" below for why it's still a separate `@Configuration` class rather than folded into `ApplicationServiceWiring` (one bean-graph seam per module boundary, not the old toggle mechanics — the `vision.persistence.enabled` flag and its in-memory branch are gone).

## devsupport (`com.drones.vision.app.devsupport`)

As of docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b, `devsupport/` holds only genuinely-optional-feature no-op fallbacks — every repository-shaped port (fleet **and** history) is unconditionally `Jpa*` (adapter-persistence), so the 19 `InMemory*` classes that used to live here (one per repository port, plus `InMemoryAuditTrail`/`InMemoryDetectionEventRepository`) are **gone**, along with their dedicated unit tests. What remains:

| Class | Port | Notes |
|---|---|---|
| `DevPrincipal` | — | not a port implementation, a wiring-only constant holder — see "Dev principal" below |
| `LoggingEventPublisher` | `EventPublisherPort` | message-broker-backed (MQTT/Kafka) replacement still Phase 7 |
| `NoopDetectionPort` | `DetectionPort` | fallback when `vision.cv.enabled=false`; real path is `GrpcDetectionPort` (adapter-cv-grpc) — see "CV inference wiring" below |
| `NoopStreamPublisher` | `StreamPublisherPort` | fallback when `vision.publish.enabled=false`; real path is `PublisherRouter`/`MediamtxStreamPublisher` (adapter-publish-hls) |
| `NoopReplayFrameExtractor` | `ReplayFrameExtractionPort` | fallback when `vision.publish.enabled=false`; real path is `MediamtxReplayFrameExtractor` (adapter-publish-hls), docs/plans/done/CV-TRAINING-V2-PLAN.md §7; always `Optional.empty()`, same "honest absence" posture `NoopStreamPublisher`'s own unconfigured case already has |
| `NoopLiveUpdatePublisher` | `FleetLiveUpdatePort`+`TelemetryLiveUpdatePort`+`DetectionLiveUpdatePort`+`MapLiveUpdatePort`+`EventLiveUpdatePort` (the five ports the former god-port `LiveUpdatePublisherPort` split into, docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6b; incl. `publishMapEvent`, docs/plans/done/MAP-REWORK-PLAN.md Wave A) | fallback when `vision.live.enabled=false`; real path is `LiveUpdateRegistry` (vision-api, default) — see "Server-push data plane" below |

Every repository port's `Jpa*` implementation (adapter-persistence) is documented in adapter-persistence/MODULE.md's API surface, not duplicated here — see "Persistence wiring" below for how `PersistenceWiringConfiguration` assembles them. The COP layer's pre-seeding, formerly split between `V12__map_layers.sql` (Postgres) and `mapLayerBootstrapRunner` (in-memory), is now Postgres-only: `V12__map_layers.sql` always runs before any `ApplicationRunner`, so the runner (and its "timing, not correctness" rationale) was deleted as dead code — see the W2b narrative section near the end of this file for the equivalence check.

`DevPrincipal` (not a port implementation — a wiring-only constant holder): `USER_ID`/`GROUP_ID`/`OWNERSHIP` fixed dev-mode identity, see "Dev principal" below.

## application.yaml

**YAML, not `.properties`** (the file was converted wholesale — same keys, same values, same explanatory comments, nothing added or dropped). The reason is structural: `vision:` holds one second-level block per owner — a Maven module from the module index (`publish:` → adapter-publish-hls, `cv:` → adapter-cv-grpc, `rtsp:`/`mjpeg:`/`v4l2:`/`mavlink:`/`overlay:`/`discovery:`/`persistence:`/`simulation:`, `application:` → vision-application, `api:`/`live:` → vision-api) or a cross-module feature (`tracking:`, `training:`, `auth:`, `rc:`) — so an editor folds one module's configuration away without a text search, and a key's owner is visible from its indentation rather than from a comment banner.

**Blocks whose keys are all commented have their module key commented too** (`# rtsp:`, `# mavlink:`, `# tracking:`, `# application:`, `# api:`, `# rc:`, …). That is deliberate, not cosmetic: an uncommented-but-empty block (`rtsp:` with nothing under it) is a YAML **null**, which reaches the binder as `vision.rtsp=""` and risks a conversion failure against a record target — a documented default must cost nothing at runtime, so it stays entirely inside comments. The trade-off is that those modules don't fold as blocks; uncomment the module key the moment you set anything under it.

**Keys are named here in dotted form** (`vision.publish.enabled`) because that is Spring's canonical name — the form used by `@ConfigurationProperties(prefix=…)`, by `@SpringBootTest(properties=…)`, and by the `VISION_*` environment overrides `docker-compose.yml` sets. YAML nesting is a file-layout choice; it changes nothing about binding, relaxed binding, or precedence (env/CLI > this file > each record's `@DefaultValue`).

`spring.application.name=vision` · `vision.publish.enabled=true` · `vision.publish.mediamtx.rtsp-base=rtsp://localhost:8554` · `vision.publish.mediamtx.hls-base=http://localhost:18888` (internal upstream mediamtx address; viewers never see it — see HLS proxy below; host-mode value, not mediamtx's own default 8888, for the collision-avoidance reason documented inline in `application.yaml`) · `vision.publish.mediamtx.whep-base=http://localhost:18889` (docs/plans/done/MVP2-PLAN.md L-a — mediamtx's WebRTC/WHEP egress; unlike `hls-base`, handed to viewers verbatim, so this must already be browser-reachable — see "WHEP viewing" below) · `vision.publish.view-base=/hls` (app-relative URL base actually handed to viewers) · `vision.discovery.enabled=true` · `vision.cv.enabled=false` (default — no cv-service required; see "CV inference wiring" below) · `vision.cv.endpoint=localhost:50051` (cv-service's `DetectStream` gRPC endpoint, only read when `vision.cv.enabled=true`) · `vision.cv.detect-width`/`vision.cv.jpeg-quality` (docs/plans/done/REMOTE-CV-PLAN.md P1 item 5, `GrpcDetectionPort`'s wire-tuning knobs — see "CV inference wiring" below; commented out in `application.yaml`, documenting their `640`/`0.8` defaults rather than setting them, only read when `vision.cv.enabled=true`) · `vision.persistence.jdbc-url=jdbc:postgresql://localhost:5432/vision` / `vision.persistence.username=vision` / `vision.persistence.password=vision` (always read — JPA fleet + history repositories against a real Postgres are the only store since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b removed the `vision.persistence.enabled` toggle entirely; a reachable Postgres is required to start at all; see "Persistence wiring" below) · `vision.live.enabled=true` (default — server-push `/api/live` SSE endpoint + all five live-update ports wired to the real registry; see "Server-push data plane" below) · `vision.simulation.resume-on-boot=true` (default — backend follow-up batch; whether `SimulationResumeRunner` calls `SimulationService#resumeAll()` once at boot; unconditional on persistence since W2b — see "Simulated-feed resume-on-boot" below) · `vision.tracking.default-mode=ASSOCIATE` (wave T8 — was `OFF`; set `OFF` for the pre-tracking behavior) / `.follow-fps=15` / `.verify-every-millis=2000` / `.capability-level=0` (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2 wave J3 — auto-probe, a ceiling not a demand) / `.reupdate-max-gap-millis=0` (same wave — server default) / `.stats-window-seconds=30` / `.track-retention-seconds=5` (all defaults, all commented out in `application.yaml` — docs/plans/done/TRACKING-PLAN.md, docs/extracts/TRACKING-ORCHESTRATION.md §4.3; see "Tracking engine wiring" below for which of them seed a new stream and which configure the read models) · `vision.rc.watchdog-timeout-ms=300` (default, commented out in `application.yaml` — docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4; `manualControlService`'s input-loss watchdog timeout; SITL-tune against measured glass-to-stick latency before relying on it, per the plan's own verification steps).

**New in docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D** (all commented out, documenting rather than overriding every default — see "Package shape" above for the full record table): `vision.rtsp.*` (transport/timeouts/probesize/`transmit.*`), `vision.mjpeg.*` (read-timeout/buffer-capacity/`transmit.*`), `vision.v4l2.*` (buffer-capacity/close-join-timeout only — no `default-video-size`/`framerate`/`input-format`, see `VisionV4l2Properties`'s own javadoc for why), `vision.mavlink.*` (bind-host/silence-window/timeouts/`scan.*`/`transmit.*`), `vision.rc.override-hz`/`.min-override-hz`/`.max-override-hz`/`.release-frames` (extends the pre-existing `vision.rc.watchdog-timeout-ms`), `vision.overlay.*` (jpeg-quality/stroke/font/OSD tunables), `vision.cv.response-timeout`/`.keepalive-*`/`.channel-shutdown-timeout`/`.plaintext`/`.upload.*`/`.registry.call-timeout` (extends the pre-existing `vision.cv.enabled`/`.endpoint`/`.detect-width`/`.jpeg-quality`), `vision.discovery.mdns.*`/`.v4l2.*` (extends the pre-existing `vision.discovery.enabled`/`.mavlink-port`), `vision.simulation.video.*`/`.telemetry.*` (extends the pre-existing `vision.simulation.resume-on-boot`), `vision.publish.encoder.*`/`.resilience.*`/`.cadence.*`/`.replay.*` (extends the pre-existing `vision.publish.*`), and `vision.api.*` (Spring-bound counterpart of vision-api's own framework-free `VisionApiProperties`, wiring a real `SnapshotJpegEncoder` bean).

## Persistence wiring (docs/plans/done/MVP2-PLAN.md P-a fleet + P-b history)

**Since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b, there is no toggle: Postgres is the only store.** `VisionPersistenceProperties` (`@ConfigurationProperties(prefix="vision.persistence")`) is now just `jdbcUrl`/`username`/`password` (all always read) and `seedDevUsers` (`@DefaultValue("false")`, added W1, see the W1 Status entry below); the `enabled` field is gone.

`PersistenceWiringConfiguration` is still a **separate `@Configuration` class** from `ApplicationServiceWiring` — same split-out-by-concern precedent as `DiscoveryWiringConfiguration` — but is now straight-line: the `EntityManagerFactory` bean (`adapter-persistence`'s `PersistenceUnit.start`, `@Bean(destroyMethod="close")`) is an ordinary unconditional bean, and every port bean is a one-line `new Jpa*Repository(entityManagerFactory)` taking the concrete `EntityManagerFactory` directly — no `@ConditionalOnProperty`, no `ObjectProvider`, no if/else. It wires `categoryRepositoryPort`, `deviceRepositoryPort`, `assetRepositoryPort`, `assetUsageRepositoryPort`, `telemetryRepositoryPort`, `detectionRepositoryPort`, `assetImageRepositoryPort`, `geofenceRepositoryPort`, `userRepositoryPort`, `groupRepositoryPort`, `assignmentRepositoryPort`, `markRepositoryPort`, `mapLayerRepositoryPort`, `drawingRepositoryPort`, `datasetRepositoryPort`, `trainingSampleRepositoryPort`, `sampleImageStorePort` — seventeen repository ports, same set as before W2b, just without the branch. `auditTrailPort` and `detectionEventRepositoryPort` are **not** here — those two are assembled in `ApplicationServiceWiring` because they need to be wrapped in their `LiveUpdate*` decorator when `vision.live.enabled=true` (see the Bean inventory above and "Server-push data plane" below); `ApplicationServiceWiring` now also takes `EntityManagerFactory` as a bean parameter for that reason. The three P-b beans (`assetUsageRepositoryPort`/`telemetryRepositoryPort`/`detectionRepositoryPort`) still use each `Jpa*Repository`'s one-argument constructor (its generous default retention cap — see adapter-persistence/MODULE.md's Retention section) rather than the two-argument override; no `vision.persistence.*` property surfaces the cap yet. `DatasetUploadPort` (adapter-cv-grpc's `GrpcDatasetUploadPort`) is still wired in `TrainingWiringConfiguration`, not here — it needs the shared `cvGrpcChannel` (gated by `vision.training.enabled`, an orthogonal flag) — see "CV training loop wiring" below.

**docker-compose.yml**: the `vision-app` service depends on `postgres` (`condition: service_healthy`, alongside its existing `mediamtx`/`cv-service` dependencies) and sets `VISION_PERSISTENCE_JDBC_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}` (compose-internal hostname/port — mirrors the existing `VISION_PUBLISH_MEDIAMTX_*_BASE` container-vs-host addressing split), `VISION_PERSISTENCE_USERNAME`/`VISION_PERSISTENCE_PASSWORD` from the same `.env`-sourced `${POSTGRES_USER}`/`${POSTGRES_PASSWORD}` the `postgres` service itself already uses — `VISION_PERSISTENCE_ENABLED` is gone (W2b), there is nothing left to toggle. A host-run `./mvnw spring-boot:run` (or any other non-compose run) needs a reachable Postgres at `jdbc-url`'s default (`localhost:5432/vision`) to start at all — there is no in-memory escape hatch for an environment with no database.

## Test infrastructure (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4 — a shared Postgres for `@SpringBootTest`)

Since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b removed the `vision.persistence.enabled` toggle, every one of this module's `@SpringBootTest` classes unconditionally builds a real `EntityManagerFactory` at context startup — a reachable Postgres is what "the default configuration" means for this suite; there is no devsupport in-memory fallback left to fall back to. Four small classes in `com.drones.vision.app.testsupport` (`src/test/java`, wired entirely through Spring/JUnit SPI files, zero per-class annotations) make that work:

- **`SharedPostgresContainer`** — one `org.testcontainers.postgresql.PostgreSQLContainer("postgres:16")` (the new `testcontainers-postgresql` package, not the deprecated `org.testcontainers.containers` one — matches storage/persistence's own `PostgresDockerIntegrationTest` precedent) per **JVM**, not per test class: 34 containers started/stopped in turn was measured to be the wrong shape before it was even tried (Testcontainers' own "singleton container" pattern exists precisely for this). Started eagerly by a static initializer, never stopped explicitly — Testcontainers' Ryuk reaper cleans it up when the Surefire fork exits, the same unmanaged lifecycle every other Testcontainers use in this repo already relies on. `postgres:16` to match `docker-compose.yml`'s pinned production image, not whatever `:latest` resolves to today.
- **`PostgresContextCustomizerFactory`** (registered via `src/test/resources/META-INF/spring.factories`, key `org.springframework.test.context.ContextCustomizerFactory`) — points every context this module builds at `SharedPostgresContainer`'s `jdbc-url`/`username`/`password` via `TestPropertyValues`, with zero per-class annotation. Its `ContextCustomizer` is deliberately stateless and `equals()`-to-every-other-instance: `MergedContextConfiguration`'s context-cache key includes the customizer set, and a default (identity) `equals` would make all 34 classes look like distinct configurations — 34 full Spring Boot boots instead of the handful of distinct `properties` combinations this module actually has — silently, with no test failing to explain the slowdown.
- **`DockerGatedExecutionCondition`** (registered via `src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`, auto-detected because `src/test/resources/junit-platform.properties` sets `junit.jupiter.extensions.autodetection.enabled=true`) — a JUnit 5 `ExecutionCondition` that disables any `@SpringBootTest`-annotated class (`AnnotatedElementUtils.hasAnnotation`, so meta-annotated too) when `DockerAvailability`'s cached `DockerClientFactory.instance().isDockerAvailable()` probe says no daemon is reachable; every other test class (plain unit tests not annotated `@SpringBootTest`) is unaffected. `ExecutionCondition`s are evaluated before any other JUnit extension callback, so a disabled verdict here means `SpringExtension` never gets a chance to build that class's context at all — **a Docker-less run reports those 34 classes as JUnit-skipped (not failed, not silently passing)**, with a message naming this plan and explaining why, and every plain-unit/devsupport test still runs and still needs to pass. This was verified by code review of JUnit 5's `BeforeAllCallback`/`ExecutionCondition` ordering rather than by an actual docker-less CI run: Docker was reachable throughout this task's own verification, and forcing a clean "no Docker" state to test the branch empirically (e.g. via a bogus `DOCKER_HOST`) does not work — Testcontainers' `DockerClientProviderStrategy` chain falls back to other discovery paths (the default unix socket among them) regardless of `DOCKER_HOST`, so genuinely faking "no Docker" would require disabling the daemon at the OS level, out of scope for a shared sandbox.
- **`PostgresResetTestExecutionListener`** (registered via `spring.factories`, key `org.springframework.test.context.TestExecutionListener`, merged with Spring's defaults since no test class here declares `@TestExecutionListeners`) — resets the shared database to a clean, freshly-migrated state once per test **class** (a static `Set<Class<?>>` guard keyed by test class, not by `ApplicationContext`, since several classes with byte-identical `@SpringBootTest` `properties` share one cached context and each still needs its own clean slate) via Flyway `clean()` then `migrate()`. `clean()+migrate()` was chosen over a table-by-table `TRUNCATE` allow-list specifically so seeded rows a test legitimately depends on (V2's categories, V12's COP layer, V13's fixed-id root group, …) survive every reset exactly as freshly as a first boot, with no "protected tables" list to hand-maintain here as storage/persistence adds more seed migrations. Uses the same `VisionPersistenceProperties` bean (so the same `seed-dev-users`-conditional `classpath:db/seed/dev` location) the context's own `persistenceEntityManagerFactory` was built from.

  **Runs from `beforeTestMethod`, guarded to fire once, deliberately not from `beforeTestClass`** — this is the one real bug this wave's own new infrastructure caused and had to fix in itself, not in a test: `TestExecutionListener.beforeTestClass` fires from `SpringExtension`'s `BeforeAllCallback`, which JUnit 5 always runs *before* a test class's own `@BeforeAll` methods. Calling `TestContext#getApplicationContext()` that early forced this module's first-ever build of a given `@SpringBootTest` configuration to happen before `@BeforeAll` had run — and `TrackingAssociateE2ETest`/`CvDetectionE2ETest`/`CvDetectionEndpointE2ETest` each start an in-process gRPC server in `@BeforeAll` and read its port back out through `@DynamicPropertySource` (evaluated during that same context build), so forcing the build early made those three read a still-null static `server` field and fail with `Failed to load ApplicationContext` / `NullPointerException`. `beforeTestMethod` is a `BeforeEachCallback`, which JUnit 5 only ever invokes after both `@BeforeAll` and test-instance construction (the point that actually triggers context loading, via `SpringExtension#postProcessTestInstance`) have completed, so by the time this listener can see the context, every `@BeforeAll` side effect is already applied, cached context or not.

- **`DevAccountSeeder`** (`src/test/java/.../app/DevAccountSeeder.java`, added at W1, see that Status entry below) needed one further fix at W4 for the same underlying reason: once these tests run against a real, Flyway-migrated database for the first time, `V13__identity_baseline.sql` has *already* seeded a "Root" group at a fixed id before `DevAccountSeeder.seedIfAbsent` ever runs. Its original unconditional `groupService.create(new GroupSpec("Root", null), ...)` minted a second, differently-id'd "Root" group every time — harmless in the old pure-in-memory profile (nothing else pre-existed to collide with), but a real bug once a migrated V13 row is also in the table: `ScopedAssetReadAuthEnabledTest#groupOf`'s by-name lookup could resolve either one, and resolving the wrong one made every group-scoped assertion see an empty fleet. Fixed by listing existing groups first and only creating one on a genuine miss (`groupService.list(system).stream().filter(g -> g.name().equals("Root")).findFirst().orElseGet(() -> groupService.create(...))`), which reuses V13's row in the real-Postgres profile and falls back to creating one in the (still-supported) pure in-memory profile.

**Cost measured**: `RtspSimulationDockerE2ETest` (mediamtx-in-docker) was seen to fail once in a full-suite run under the added resource pressure of a second Docker container competing for CPU/IO — re-run in isolation immediately after, it passed cleanly (18.65s), confirming a contention flake rather than a regression; nothing about this wave's own logic touches that test. Wall-clock for `./mvnw -B -pl station/vision-app test -DskipWeb`, before (in-memory, no Docker/Testcontainers) vs. after (this wave): **238/238 green in both**, ~1:07 min before (this module's own portion of an earlier `-pl storage/persistence,station/vision-api,station/vision-app` combined 2:14 min run) vs. **1:28 min** after (a clean standalone run of this exact command, 0 skipped since Docker was reachable) — roughly +20s, attributable to one-time container startup plus 34 × (Flyway `clean()`+`migrate()` of 16 migrations, ~90-100ms each) reset cycles. Not free, but not the module's dominant cost either; no further optimization was attempted since the brief's bar is "report the number," not "hit a target."

## Tracking engine wiring (docs/plans/done/TRACKING-PLAN.md, docs/extracts/TRACKING-ORCHESTRATION.md §4.1/§4.3)

`VisionTrackingProperties` (`@ConfigurationProperties(prefix="vision.tracking")`, same
record-plus-`@DefaultValue` idiom as `VisionCvProperties`) and `TrackingWiring` (its own
`@Configuration`, split by concern like `PersistenceWiringConfiguration`/`DiscoveryWiringConfiguration`
— tracking is configured independently of whether the gRPC detection channel is even built).

**Seven keys, two disjoint jobs** (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2/§4 wave J3 added
the last two). This distinction is the whole point of the plan's configuration layering, so it is
spelled out rather than left to the reader:

| Key | Default | What it does |
|---|---|---|
| `vision.tracking.default-mode` | `ASSOCIATE` (wave T8; was `OFF`) | seeds **new** streams (`TrackingWiring#streamStartTrackingSeed` → `StreamPipelineSettings#trackingSeed` → `DefaultStreamService#start`, i.e. *every* start path). Set `OFF` for the pre-tracking behavior |
| `vision.tracking.follow-fps` | `15` | same — the *Java*-side sampler's rate while `FOLLOW` is active (cv-service has no such knob and must never care how often it is fed) |
| `vision.tracking.verify-every-millis` | `2000` | same — `FOLLOW`'s detector re-verify cadence |
| `vision.tracking.capability-level` | `0` (auto-probe) | same — the capability-ladder ceiling (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2) requested for new streams, `[0,5]`. **A ceiling, not a demand** (invariant B5): cv-service serves `min(requested, affordable)` and reports what it actually served on `TrackingTelemetry#capability()`/`FrameTrackingResponse#capability()` — this key can only cap a strong host down, never make a weak one run a level it cannot afford |
| `vision.tracking.reupdate-max-gap-millis` | `0` (server default) | same — the longest gap ORU (Observation-Centric Re-Update) may reconstruct for new streams, milliseconds; must not be negative |
| `vision.tracking.stats-window-seconds` | `30` | configures the per-stream **read model** `TrackingStatsWindow`, via `ApplicationServiceWiring#streamPipelineSettings` → `StreamPipelineSettings#trackingStatsWindow` |
| `vision.tracking.track-retention-seconds` | `5` | same, for `TrackBook#retention` |

**The seeds never reach a running stream.** A running stream's tracking configuration is its own
state, changed only by `PATCH /api/streams/{id}/config`; restarting the stream is how a changed
deployment default is picked up, and that is deliberate — a config edit must not silently re-steer a
flight in progress. `vision-domain` keeps pure literals (`TrackingConfig.off()`/`.defaults()`), which
is exactly why this layer lives here.

**The five seed keys are mapped as a `TrackingConfigPatch`, not a whole `TrackingConfig`** — a
deployment states only the knobs it owns, and the rest fall through to `vision-domain`'s literals
when `DefaultStreamService` folds it at stream start. That is what keeps `redetectIouPercent`/
`maxAgeFrames`/`minHits` single-owner (see the paragraph below) without this class restating them.
`capability-level`/`reupdate-max-gap-millis` are stated **unconditionally** in the seed, exactly like
`default-mode`/`verify-every-millis`/`follow-fps` — at their own `0`/`0` default this folds to
byte-identical to `TrackingConfig.off()`/`.defaults()` (invariant B2, `TrackingWiringTest` pins it).

**`track-retention-seconds` is not in the plan's own knob inventory** (docs/extracts/TRACKING-ORCHESTRATION.md
§4.3 lists four keys). It exists because `StreamPipelineSettings`' canonical constructor requires
*both* durations stated once you set either, and its own default is `private` — so the choice was a
documented knob or a magic `5s` literal duplicated in wiring. The default is byte-identical to the
literal it mirrors, asserted by `TrackingWiringTest`.

**No `vision.tracking.enabled` flag, deliberately** (docs/plans/done/TRACKING-PLAN.md §5.G): `vision.cv.enabled`
already kills CV wholesale, and `TrackingMode.OFF` is a per-stream off-switch strictly better than a
JVM-wide one — the same reasoning `detectionEnabled` used.

**The three remaining wire knobs — `redetectIouPercent`/`maxAgeFrames`/`minHits` — have no Java
property on purpose.** They are resolved inside cv-service from `CV_TRACK_IOU`/`CV_TRACK_MAX_AGE`/
`CV_TRACK_MIN_HITS`; duplicating them here would give one number two owners. The seed passes
`TrackingConfig`'s own defaults for them, which are the same values cv-service resolves its `<=0`
sentinels to.

**Roster drift is answered, not prevented** (risk R11): `cvTrackerRoster` is a static list, so it can
name an engine cv-service failed to construct. The fix is not a roster RPC — cv-service logs the
roster it really got at startup, every response carries `tracker_engine_id` (the engine that
*actually* served the frame), and the SPA displays that.

## HLS proxy (browsers never talk to mediamtx directly)

mediamtx's own HLS port (default `8888`) routinely collides with other services already bound to that well-known port on a user's machine, which used to force per-machine `hls-base` configuration just to view a stream. As of this feature, `com.drones.vision.api.proxy.HlsProxyController` (vision-api, plain component-scanned `@RestController`) serves `GET /hls/{streamId}/**` by reverse-proxying to `hlsProxyUpstreamBase` (the `URI` bean above, sourced from `VisionPublishProperties.Mediamtx#hlsBase()`) — following mediamtx's cookie-pinning `302` redirects server-side and relaying its `Set-Cookie` back, so the browser only ever talks to this app's own origin. `PublishWiring#streamPublisherPort` hands `MediamtxStreamPublisher` `VisionPublishProperties#viewBase()` (default the app-relative `/hls`, matching `HlsProxyController`'s mapping) as `hlsViewBase` instead of `mediamtx.hlsBase()`, so `StreamPublisherPort#viewUrl` now returns app-relative URLs like `/hls/<streamId>/index.m3u8` by default rather than `http://<mediamtx-host>:<mediamtx-port>/...`.

**Why `HlsProxyController` is wired via a plain `URI` bean, not hand-constructed in `WiringConfiguration`**: every other vision-api controller (`AssetController`, `DeviceController`, etc.) is a plain component-scanned `@RestController` with its use-case-port constructor args autowired by type from beans this configuration class defines — `WiringConfiguration` never constructs a controller instance itself. `HlsProxyController` follows that same pattern for consistency: if `WiringConfiguration` instead had an explicit `@Bean public HlsProxyController hlsProxyController(...)` method, Spring's classpath component-scan (which already covers `com.drones.vision.api`, since it's a sub-package of `@SpringBootApplication`'s base package) would *also* auto-register the same `@RestController`-annotated class as a second, competing bean definition — a `BeanDefinitionStoreException` at startup (self-conflicting bean name), since `@RestController`'s stereotype annotation must stay on the class for Spring MVC's `RequestMappingHandlerMapping` to recognize its `@GetMapping` method at all, regardless of how the bean instance is actually created. Supplying only its `URI` collaborator as a bean (autowired by type, exactly one candidate) sidesteps this entirely.

## WHEP viewing (docs/plans/done/MVP2-PLAN.md §L, L-a)

Unlike HLS, WHEP gets **no proxy controller and no app-relative view base**. `PublishWiring#streamPublisherPort` passes `properties.mediamtx().whepBase()` straight through as `MediamtxStreamPublisher`'s `whepViewBase` constructor argument, and `StreamPublisherPort#whepUrl` returns that address to the caller verbatim — so `StartStreamResponse#whepUrl`/`ActiveStreamResponse#whepUrl`/`SimulationResponse#whepUrl` (vision-api) are always mediamtx's own absolute origin URL (e.g. `http://localhost:18889/<streamId>/whep`), never this app's own origin.

**Why no proxy, unlike HLS**: `HlsProxyController` works because HLS is a plain byte-fetch protocol (GET the playlist, GET each segment) that a stateless reverse proxy can forward transparently. WHEP is not: a viewer `POST`s an SDP offer to `{whepBase}/{streamId}/whep` and gets an SDP answer back, then the browser and mediamtx negotiate ICE and exchange RTP directly over UDP — there is no byte stream after the initial POST for a proxy to sit in the middle of, so `vision.publish.mediamtx.whep-base` must already be an address the *viewer's browser* can reach, which is the opposite assumption from `hls-base` (an address only this app's own JVM needs to reach).

**CORS**: mediamtx's own default (`webrtcAllowOrigins: ["*"]`, verified against the upstream `mediamtx.yml` default) is permissive, so a browser calling `whepUrl` cross-origin (e.g. `vision-web`'s dev-server origin, or this app's own origin at a different port) needs no CORS configuration from this codebase.

**docker-compose.yml** (see its own comments for the full reasoning): the `mediamtx` service gains a third host port mapping, `18889:8889` (WHEP HTTP/signaling, same collision-avoidance rationale as HLS's `18888:8888`), a fourth, `8189:8189/udp` (WebRTC media/ICE — **must** be a 1:1 host↔container mapping, unlike the other two, because mediamtx bakes its own listening port number into the ICE candidates it sends the browser and Docker's port remapping cannot rewrite SDP/ICE payload contents), and an `environment: MTX_WEBRTCADDITIONALHOSTS: "${VISION_WEBRTC_HOST:-127.0.0.1}"` (docs/plans/done/MVP2-PLAN.md V-e — parametrized, was hardcoded `"127.0.0.1"`; mediamtx's default `webrtcIPsFromInterfaces: true` would otherwise advertise the *container's* own bridge-network IP in ICE candidates, unreachable from a browser on the host). The `vision-app` service's own environment gains `VISION_PUBLISH_MEDIAMTX_WHEP_BASE: http://localhost:18889` — deliberately **not** mirroring `VISION_PUBLISH_MEDIAMTX_HLS_BASE`'s compose-internal-hostname pattern (`http://mediamtx:8888`), because `hls-base` is consumed server-side by `vision-app` itself (so the compose-internal DNS name `mediamtx` correctly resolves inside that container), while `whep-base` is consumed by the *browser* (unreachable via `mediamtx`, a name that only exists on the compose network) — this is the one property in the `rtsp-base`/`hls-base`/`whep-base` trio where literally mirroring the existing pair's compose override would have been wrong, not just redundant; it happens to equal `application.yaml`' own default host-mode value, but is still set explicitly so the reasoning is visible at the compose level, not only in source comments.

**docs/plans/done/MVP2-PLAN.md V-e — LAN WHEP via `VISION_WEBRTC_HOST`**: `MTX_WEBRTCADDITIONALHOSTS` was hardcoded to `"127.0.0.1"` at L-a time (below); this task parametrizes it via a new `.env`/`.env.example` variable, `VISION_WEBRTC_HOST` (compose interpolation, `${VISION_WEBRTC_HOST:-127.0.0.1}` — unset still defaults to same-host-only, unchanged behavior). **How an operator finds their LAN IP**: `hostname -I` (Linux), `ipconfig getifaddr en0` (macOS, Wi-Fi; `en1`/etc. for other interfaces), or `ipconfig` (Windows — read the "IPv4 Address" under the active adapter), then set `VISION_WEBRTC_HOST=192.168.x.x` in `.env` before `docker compose up`. **Env-list encoding, VERIFIED, not assumed**: mediamtx's own env-var loader splits list-typed fields on commas (`internal/conf/env/env.go`'s generic `strings.Split(ev, ",")` handling for string-slice fields) — confirmed directly by the mediamtx maintainer for this exact variable, [github.com/bluenviron/mediamtx discussion #3360](https://github.com/bluenviron/mediamtx/discussions/3360): *"in order to do it with environment variables it's enough to use commas: `MTX_WEBRTCADDITIONALHOSTS=192.168.x.x,example.org`"*. So `VISION_WEBRTC_HOST` can itself be a comma-separated list (e.g. `"127.0.0.1,192.168.0.104"`) to keep both same-host and LAN viewers working simultaneously — a bare single value **replaces** the compose default `127.0.0.1` outright, it does not add to it, so an operator who wants both must list both explicitly. **What still stays on HLS**: any viewer off the configured LAN/host entirely (a different network, a phone on cellular data, a friend joining remotely) — WHEP's ICE candidates are never going to be reachable for them regardless of `VISION_WEBRTC_HOST`, and they transparently keep getting `viewUrl` (HLS), which has no such reachability requirement; this is exactly the tradeoff the try-WHEP-then-HLS player logic (U3) exists for, unaffected by this task (vision-web out of scope).

**Known limitation before this task, now resolved by configuration**: `MTX_WEBRTCADDITIONALHOSTS: "127.0.0.1"` only made WHEP work for a browser on the *same machine* as the Docker host — the "friends demo" scenario (docs/plans/done/MVP1-PLAN.md) of a friend's laptop on the same LAN reaching this host's real IP got a `viewUrl` (HLS) that worked fine but a `whepUrl` whose ICE candidates still said `127.0.0.1`, resolving to *that other machine's own loopback*, not this host — the WebRTC session simply failed to connect. `VISION_WEBRTC_HOST` (above) is exactly the fix: setting it to the demo host's real LAN IP is still a manual, host-specific step (the correct value genuinely cannot be baked into a committed compose file — it's this machine's own address), but it's now a documented `.env` variable rather than requiring a hand-edit of `docker-compose.yml` itself. HLS remains the cross-host-reliable fallback for anyone this isn't configured for.

## RC manual-control relay wiring (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4)

`VisionRcProperties` (`@ConfigurationProperties(prefix="vision.rc")`, mirrors `VisionCvProperties`'s record/`@DefaultValue` idiom): `watchdogTimeoutMs` (`@DefaultValue("300")`, matching `DefaultManualControlService.DEFAULT_WATCHDOG_TIMEOUT_MS` exactly) — validated positive in the compact ctor.

`TelemetryWiring#mavlinkManualControlSender(MavlinkTelemetrySource)` and `#manualControlService(AssetService, ManualControlPort, AuditTrailPort, VisionRcProperties)` are both unconditional beans (no `vision.mavlink.*`/`vision.rc.enabled`-shaped gate) — see the Bean inventory table above for the exact construction. The one thing worth calling out beyond that table: `manualControlService` calls `DefaultManualControlService`'s **6-arg canonical constructor** directly (not either of its two frozen-shape convenience ones), because both convenience constructors hard-code `DEFAULT_WATCHDOG_TIMEOUT_MS=300` — only the 6-arg ctor actually takes the resolved `VisionRcProperties#watchdogTimeoutMs()` value. This means the wiring layer also has to build its own daemon `"rc-watchdog"` `ScheduledExecutorService` (a private `rcWatchdogScheduler()` helper, not a bean — nothing else in this context needs to see it), duplicating the four-line daemon-thread-factory idiom `DefaultManualControlService`'s own (otherwise-private) default scheduler already uses, the same "wiring layer pays a small duplication cost to honor a property the frozen-shape constructors can't" trade-off `flightCommandService`/etc. don't need (they have no comparable tunable).

**`vision-api`'s `ManualControlWebSocketHandler` reads the identical `vision.rc.watchdog-timeout-ms` property key independently**, via its own `@Value`, purely to echo the configured timeout on the `watchdog` frame it pushes (`ManualControlService`/`ManualControlSession` expose no getter for the timeout — there was nothing else to autowire it from). The two readers stay in sync only because they read the same key with the same default; there is no shared bean carrying the value across the module boundary. See station/vision-api/MODULE.md's own `/ws/manual-control` subsection for the full transport-side writeup, including the same "no shared bean" caveat for the `engaged` frame's `rateHz` (a fixed constant there, not property-driven at all, since `adapter-mavlink`'s actual configured rate has no seam to reach vision-api through at all).

`SecurityConfig`'s secured chain now includes `/ws/**` alongside `/api/**` in its `authenticated()` rule — the only change to that class this wave made; the permit-all (auth-disabled) chain needed no change since it already matched `anyRequest()`.

`AssetWiringTest` extended (two more `assertNotNull` calls in the existing bean-inventory test, no new test method — same judgment call `ReplayService`/`FleetSummaryService`/`cvModelRoster`/etc. all made for their own small, focused beans): asserts `manualControlService` and `mavlinkManualControlSender` are both registered. Two new dedicated test classes (mirroring `AuthDisabledSecurityTest`/`AuthEnabledFlowTest`'s own split-by-property-set precedent, since JUnit doesn't let one class carry two different `@SpringBootTest` property sets): `ManualControlSecurityDisabledTest` (default `vision.auth.enabled=false` — a plain `GET /ws/manual-control` is not blocked by security, `400` from the WS handshake handler itself rather than `401`/`403`) and `ManualControlSecurityEnabledTest` (`vision.auth.enabled=true` — the same request is `401` unauthenticated, and no longer `401` once carrying a session from the seeded `admin`/`admin` login, proving `/ws/**` really did join the `authenticated()` rule rather than merely appearing to because every other path already required auth).

## Detection-model roster (docs/plans/done/CV-CONTROL-PLAN.md §4)

`CvWiring#cvModelRoster()` returns a hardcoded `List<CvModelResponse>` (`vision-api` DTOs, built directly since this module already depends on `vision-api` and the DTO is a plain framework-free record) — the exact three-entry roster the frozen wire contract pins: `yolo26n.pt` (general, fast, the default), `orion12l.pt` (specialized), `yoloe-26s-seg-pf.pt` (open-vocab, opt-in, empty `defaultLabelFilter` by design — see the bean's own javadoc for why a fixed preset would silently drop most buildings). `CvModelsController` (`vision-api`, component-scanned) takes this bean as its one constructor collaborator and just wraps it under `{"models": [...]}}`.

**Deliberately a static, in-source constant — not the dormant `ModelRegistryPort`, not a new cv-service RPC** (docs/plans/done/CV-CONTROL-PLAN.md §D, a frozen decision): that port models versioned promote/rollback (a Phase-3 training-studio concern) and has no implementation; wiring it now for a picker that only needs a display list would be over-building for v1. This roster changes at deploy time (edit `cvModelRoster()`, rebuild), not at runtime — a documented future seam, not built now, is either that port or a small `cv-service` roster RPC (its own `ModelRegistry` already knows the local checkpoint set).

`AssetWiringTest` extended (two more `assertNotNull` calls plus one roster-content assertion in the existing bean-inventory test, no new test method — same judgment call `ReplayService`/`FleetSummaryService`/etc. made for their own small, focused beans): asserts `cvModelRoster` is registered and includes the default `yolo26n.pt` model, and that `CvModelsController` resolves its one constructor dependency.

## CV training loop wiring (docs/plans/done/CV-TRAINING-PLAN.md §3, Wave T4, delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §7)

`VisionTrainingProperties` (`@ConfigurationProperties(prefix="vision.training")`, mirrors `VisionLiveProperties`'s trivial-record idiom): just `enabled` (`@DefaultValue("false")`). `exportDir`/`DEFAULT_EXPORT_DIR` are **gone** (docs/plans/done/CV-TRAINING-V2-PLAN.md §A/§3) — the manual filesystem export step (`FilesystemDatasetExport`, adapter-persistence) they configured is deleted; dataset delivery to the training host is now an implicit part of training itself, over a gRPC upload with no on-disk artifact.

`TrainingWiringConfiguration` — a **separate `@Configuration` class**, same split-by-concern precedent as `DiscoveryWiringConfiguration`/`PersistenceWiringConfiguration` — wires the whole capture→label→train loop's application-layer beans, each individually `@ConditionalOnProperty(prefix="vision.training", name="enabled", havingValue="true")` (the `persistenceEntityManagerFactory`/scanner-bean idiom, not the port-selection `if/else` idiom, since none of these beans has a no-op fallback — they simply don't exist when the flag is off, the same "absent entirely" posture `LiveController` takes for its own controller, applied here to a whole small cluster):

- `datasetUploadPort` → `new GrpcDatasetUploadPort(cvGrpcChannel)` (adapter-cv-grpc, docs/plans/done/CV-TRAINING-V2-PLAN.md §3/§6) — the replacement for the deleted `datasetExportPort`/`FilesystemDatasetExport` bean; ships a composed YOLO dataset to cv-service over the same shared `cvGrpcChannel` `modelRegistryPort`/`trainingPort` below already reuse, not a filesystem write.
- `trainingStores` → `TrainingStores(datasetRepositoryPort, trainingSampleRepositoryPort, sampleImageStorePort, datasetUploadPort)` — the four Wave-T1 ports bundled for `DefaultLabelingService`'s constructor (see `TrainingStores`'s own javadoc); its fourth component's *type* changed (`DatasetExportPort exports` → `DatasetUploadPort uploads`), nothing else about the bundle did. The three repository-port arguments are already unconditional beans in `PersistenceWiringConfiguration` (docs/plans/done/CV-TRAINING-PLAN.md Wave T3).
- `replaySources` → `ReplaySources(assetUsageRepositoryPort, detectionRepositoryPort, replayFrameExtractionPort)` (docs/plans/done/CV-TRAINING-V2-PLAN.md §4) — the three replay-sourced collaborators `DefaultLabelingService#captureFromReplay` needs, bundled for the same five-parameter-ceiling reason `trainingStores` is. `assetUsageRepositoryPort`/`detectionRepositoryPort` are already unconditionally-wired beans (usage tracking and detection history both shipped in the product long before this loop existed — see `WiringConfiguration`/`PersistenceWiringConfiguration`); `replayFrameExtractionPort` is `PublishWiring#replayFrameExtractionPort` below (real when `vision.publish.enabled`, a no-op otherwise) — only that one collaborator is genuinely new, feature-flagged infrastructure, the rest is "consume what already exists".
- `datasetService` → `new DefaultDatasetService(datasetRepositoryPort, auditTrailPort)` — behind `DatasetController` (vision-api, component-scanned).
- `labelingService` → `new DefaultLabelingService(trainingStores, replaySources, streamService, assetRepositoryPort, auditTrailPort)` — behind `LabelingController` (vision-api, component-scanned); gained the `replaySources` collaborator (docs/plans/done/CV-TRAINING-V2-PLAN.md §4) alongside its three pre-existing ones.
- `modelRegistryPort` → `new GrpcModelRegistryPort(cvGrpcChannel)` (adapter-cv-grpc, docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) — `cvGrpcChannel` is `WiringConfiguration`'s shared gRPC connection (see "CV inference wiring" below), consumed here as a **plain, unconditional constructor parameter** rather than an `ObjectProvider` — safe because `cvGrpcChannel`'s own `@ConditionalOnExpression` matches whenever `vision.training.enabled=true` (the same condition gating this bean), so its presence is guaranteed on every branch where this bean is even constructed. `ModelRegistryPort` was dormant before this task — see docs/plans/done/CV-CONTROL-PLAN.md §D's own note that the detection-model roster (`cvModelRoster` above) deliberately does **not** use it.
- `modelRegistryService` → `new DefaultModelRegistryService(modelRegistryPort, auditTrailPort)` — behind `ModelRegistryController` (vision-api, component-scanned); a one-line assembly, mirroring `datasetService`'s shape.
- `trainingPort` → `new GrpcTrainingPort(cvGrpcChannel)` (adapter-cv-grpc, docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2's last backend wave) — the **same** `cvGrpcChannel` instance `modelRegistryPort`/`datasetUploadPort` above and `detectionPort` (`WiringConfiguration`) already share, taken as a plain, unconditional constructor parameter for the identical reason `modelRegistryPort` does. `GrpcTrainingPort#startTraining` blocks for the lifetime of the whole training run (potentially many epochs); nothing about that is Spring's concern — `trainingJobService` below is what keeps it off a request thread.
- `trainingJobService` → `new DefaultTrainingJobService(trainingPort, labelingService, auditTrailPort)` — behind `TrainingJobController` (vision-api, component-scanned); gained `labelingService` as a collaborator (docs/plans/done/CV-TRAINING-V2-PLAN.md §4/§E) — `start`'s synchronous "does this dataset have `LABELED` samples" pre-check and `runJob`'s upload-then-train sequence both call back into it. `DefaultTrainingJobService`'s own production constructor still submits each run to its own internal cached daemon-thread executor (not a bean — nothing else in this context needs to see it), so the blocking `trainingPort.startTraining` call never holds an HTTP request thread.

**Default-off guardrail** (docs/plans/done/CV-TRAINING-PLAN.md §G): with `vision.training.enabled=false` (the default), every bean above and all four controllers are entirely absent from the context — `GET/POST /api/datasets`[/{id}], `POST /api/streams/{id}/samples`, `POST /api/usages/{id}/samples`, `GET /api/samples/{id}/image`, `PUT /api/samples/{id}/annotations`, `GET /api/cv/registry/models`/`POST /api/cv/registry/models/{id}/promote`, and `POST /api/datasets/{id}/train`/`GET /api/training/jobs`[/{jobId}] all 404 like any unmapped route, exactly as before this feature existed — the manual `/api/datasets/{id}/export`* routes are simply gone, not part of this guardrail anymore. `TrainingDisabledWiringTest` proves this via `ApplicationContext#getBeansOfType` (the same "a plain `@Autowired` would fail the context on zero candidates" reasoning `LiveDisabledWiringTest`/`DiscoveryDisabledWiringTest` already document) — now also asserting `DatasetUploadPort`/`ReplaySources` are absent (in place of the deleted `DatasetExportPort` check), plus the pre-existing `ModelRegistryController`/`ModelRegistryService`/`ModelRegistryPort`/`TrainingJobController`/`TrainingJobService`/`TrainingPort`/shared-`ManagedChannel` absence assertions; `TrainingEnabledWiringTest` (`vision.training.enabled=true` — no `@TempDir`/`@DynamicPropertySource` needed anymore, since `exportDir` is gone) proves the opposite — every bean resolves to its real implementation (`GrpcDatasetUploadPort`, not `FilesystemDatasetExport`) and all four controllers resolve.

`application.yaml` keeps its pre-existing `vision.training.enabled=false` line (unchanged, still the guardrail default); the commented-out `vision.training.export-dir=data/training-exports` documentation line is deleted along with the property itself (docs/plans/done/CV-TRAINING-V2-PLAN.md §A). `.gitignore`'s `/data/` entry (added for the now-deleted export directory) is stale but harmless — nothing writes under `data/` anymore.

## Replay frame extraction wiring (docs/plans/done/CV-TRAINING-V2-PLAN.md §7)

`VisionPublishProperties.Mediamtx` gained a fourth component, `playbackBase` (`@DefaultValue("http://localhost:19996")`) — the value `MediamtxStreamPublisher`'s old 3-arg constructor used to *derive* from `whepBase`'s host at a fixed port, now an explicit property instead of a guess (docs/plans/done/CV-TRAINING-V2-PLAN.md §I: byte-identical default behavior for a localhost deployment; a non-localhost `whepBase` now needs this set explicitly rather than getting a same-stack guess — the one deliberate behavior change this delta makes, named in both this file and adapter-publish-hls/MODULE.md).

`PublishWiring#streamPublisherPort` switched from `MediamtxStreamPublisher`'s 3-arg constructor to its 4-arg one, passing `mediamtx.playbackBase()` — closing the follow-up adapter-publish-hls/MODULE.md already named.

New bean, `PublishWiring#replayFrameExtractionPort(VisionPublishProperties)` — the same if/else split `streamPublisherPort` makes, since a replay frame can only ever come from a recording mediamtx publishing itself produced: `true` → `new MediamtxReplayFrameExtractor(mediamtx.playbackBase())` (adapter-publish-hls); `false` → `new NoopReplayFrameExtractor()` (`vision-app` devsupport, always `Optional.empty()` — honest absence, mirroring `NoopStreamPublisher`'s own "no mediamtx configured" posture). Unconditional on `vision.publish.enabled` alone, **not** on `vision.training.enabled` — `TrainingWiringConfiguration#replaySources` just consumes whichever implementation this bean already resolved to, the same "wire the port ahead of its consumer" precedent `markRepositoryPort` set.

`PublishWiringTest` (default config, `vision.publish.enabled=true`) asserts `playbackUrl`'s formatted host:port matches the property's default (`http://localhost:19996/get?path=...`) and that `replayFrameExtractionPort` resolves to `MediamtxReplayFrameExtractor` — proving the "byte-identical default" claim in test form, not just in a comment.

## CV inference wiring (docs/plans/done/MVP1-PLAN.md §C7 bullet 4)

`VisionCvProperties` (`@ConfigurationProperties(prefix="vision.cv")`, mirrors `VisionPublishProperties`'s record-plus-`@DefaultValue` idiom): `enabled` (`@DefaultValue("false")` — today's behavior, no cv-service required), `endpoint: String` (`@DefaultValue("localhost:50051")`, e.g. `host:port`; an optional `scheme://` prefix is tolerated and stripped), `detectWidth: int` (`@DefaultValue("640")`, docs/plans/done/REMOTE-CV-PLAN.md P1 item 5) `jpegQuality: float` (`@DefaultValue("0.8")`), `wireFormat: String` (`@DefaultValue("auto")`, validated in the compact ctor via `WireFormat.parse` so a typo fails at context startup rather than silently falling back — docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R3) and `frameTransport: String` (docs/plans/active/MEDIA-SOT-PLAN.md wave M7, `@DefaultValue("push")`, validated `push|pull`; `pullEnabled()` is the boolean convenience every wiring decision reads) — the first three thread straight into `GrpcDetectionPort`'s own wire-tuning knobs (see adapter-cv-grpc/MODULE.md's "Payload shrinking" section) so per-network tuning (e.g. `vision.cv.detect-width=480` over a slow VPN link) needs no rebuild; `frameTransport` is switch B, see "Media source-of-truth wiring" below. `host()`/`port()` parse `endpoint` on demand (not cached — cheap, called once per bean construction) and throw `IllegalArgumentException` for a malformed value; there is no `URI`-typed field like `VisionPublishProperties.Mediamtx`'s bases because `"localhost:50051"` is not a valid absolute `java.net.URI` (a bare `host:port` string parses as an opaque URI with scheme `localhost` and scheme-specific-part `50051` — not what's wanted), so this property stays a plain validated `String` instead. The compact constructor validates all non-`enabled` fields the same manual `if (...) throw new IllegalArgumentException(...)` way: `endpoint` non-blank, `detectWidth >= 64`, `jpegQuality` in `(0, 1]`, `frameTransport` in `{push, pull}` — the first three are the same bounds `GrpcDetectionPort`'s own canonical constructor enforces (adapter-cv-grpc), so an invalid value fails fast at Spring context startup rather than later inside the adapter. A `pull: Pull` record (`rtspBase: URI`, `reconnectInitialBackoff`/`reconnectMaxBackoff: Duration`, defaulted as a whole when absent) rides alongside — see "Media source-of-truth wiring" below.

**Shared channel (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 — T9, then extended by the training-job wave, docs/plans/done/CV-TRAINING-V2-PLAN.md §3's upload port, and docs/plans/active/MEDIA-SOT-PLAN.md wave M7's pull port)**: `CvWiring#cvGrpcChannel(VisionCvProperties)` builds one plaintext `ManagedChannel` to cv-service — the same HTTP/2 keepalive tuning `GrpcDetectionPort`'s own host/port convenience constructor used to build internally (20s ping / 5s timeout / pings-without-calls; duplicated as plain constants in `WiringConfiguration` since `GrpcDetectionPort`'s own constants are package-private to `adapter-cv-grpc`) — gated by `@ConditionalOnExpression("${vision.cv.enabled:false} or ${vision.training.enabled:false} or '${vision.cv.frame-transport:push}' == 'pull'")` (the third disjunct added by wave M7), so it exists whenever *any* property enables a consumer: `detectionPort` below (`vision.cv.enabled=true`), `TrainingWiringConfiguration#modelRegistryPort`/`trainingPort`/`datasetUploadPort` (`vision.training.enabled=true`, see "CV training loop wiring" above), or `pulledDetectionPort` below (`vision.cv.frame-transport=pull`). With every flag at its default (`false`/`false`/`push`), no channel is built at all — the opt-in guardrail, proven by `TrainingDisabledWiringTest`/`CvWiringTest`. `detectionPort`, `modelRegistryPort`, `trainingPort`, and (as of docs/plans/done/CV-TRAINING-V2-PLAN.md §3) `datasetUploadPort` all consume this **one** bean instance rather than each independently building/configuring their own connection to cv-service — see `GrpcModelRegistryPort`'s own javadoc ("Channel reuse") for the motivation. `CvAndTrainingSharedChannelWiringTest` (both flags `true`) is the one test that actually proves sharing: exactly one `ManagedChannel` bean exists and `detectionPort`/`modelRegistryPort`/`trainingPort` all resolved against it (`datasetUploadPort` isn't separately asserted there — `TrainingEnabledWiringTest` already proves it resolves to `GrpcDatasetUploadPort` over the shared channel).

`CvWiring#detectionPort(VisionCvProperties, ObjectProvider<ManagedChannel>)` selects `GrpcDetectionPort(cvGrpcChannel.getObject(), cvProperties.detectWidth(), cvProperties.jpegQuality())` when `enabled=true` (the channel obtained via `ObjectProvider`, the same idiom `PersistenceWiringConfiguration`'s repository-port beans use for a conditionally-present bean — safe because `cvGrpcChannel`'s own condition is guaranteed to match whenever `cvProperties.enabled()` is `true`), else `NoopDetectionPort`.

**Shutdown ownership**: the `cvGrpcChannel` bean itself owns the channel's lifecycle (`@Bean(destroyMethod = "shutdown")`), not either port. `detectionPort`'s own `@Bean` now declares an explicit **empty** `destroyMethod` (`@Bean(destroyMethod = "")`) — see Gotchas for why this changed from the previous "rely on inference" approach: `GrpcDetectionPort#close()` unconditionally shuts its channel down regardless of who built it (see that class's own javadoc, "Stream lifecycle"), which would tear down the *shared* channel out from under `GrpcModelRegistryPort` if Spring's destroy-method inference were still allowed to find and call it. `GrpcModelRegistryPort` itself never had a close method (see its own javadoc, "Channel reuse" — it documents explicitly that it never shuts the channel down), so it needs no equivalent guard.

`GrpcDetectionPort` keeps one open bidi `DetectStream` gRPC call per stream id until told the stream ended (adapter-cv-grpc/MODULE.md's "Stream lifecycle"), and neither `vision-application` nor `vision-domain` may call that hook directly (they don't know the adapter exists). The cleanup seam lives entirely in this module: `ApplicationServiceWiring#eventPublisherPort(DetectionPort, VisionCvProperties)` wraps the base `LoggingEventPublisher` in `DetectionSessionCleanupEventPublisher` — a package-private `EventPublisherPort` decorator — only when `cvProperties.enabled()` is true **and** the resolved `detectionPort` bean actually is a `GrpcDetectionPort` (an `instanceof` pattern match, not just the property check, so the decorator can never hold a stale/wrong reference). The decorator forwards every `Event` to the delegate unchanged, and additionally calls `grpcDetectionPort.streamEnded(event.streamId())` whenever `event.type() == EventType.STREAM_STOPPED` — `DefaultStreamService#stop` already publishes exactly that event, after tearing down the pipeline and source, so the gRPC session's cleanup call lands right after the stream it belonged to actually stopped. With CV disabled, `eventPublisherPort` returns the plain `LoggingEventPublisher` exactly as before this feature — no behavior change for the default path.

## Overlay wiring (docs/plans/done/MVP1-PLAN.md §C8 bullets 2-3)

`PublishWiring#overlayRenderer()` is an unconditional bean (`Java2DOverlayRenderer`, adapter-overlay — no config, no enable flag, unlike CV: the renderer is pure-Java, has no external service to be absent, and its own pass-through-never-throws rules already make it safe to always wire in). It is threaded into `streamService`'s `OverlayPort` parameter (the 8-arg `DefaultStreamService` ctor), which in turn threads it into every `StreamPipeline` it starts (the 9-arg overlay-taking ctor). See contexts/vision-perception/MODULE.md's `StreamPipeline` entry for the burn-in/failure-handling behavior itself — nothing overlay-specific lives in this module beyond the one bean and the one wiring call.

`detectionRepositoryPort` backs the `GET /api/streams/{streamId}/detections` endpoint (`StreamController`, vision-api) with no wiring changes needed beyond what already existed: the bean was already there for `StreamPipeline` to save into, and `StreamController` picks it up by type like every other read-only driven-port dependency in that module — always `JpaDetectionRepository` (docs/plans/done/MVP2-PLAN.md P-b) since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b made it unconditional.

`adapter-overlay`'s dependency in this module's `pom.xml` needed no root-pom `<dependencyManagement>` version-pin addition — unlike `adapter-mjpeg`'s gap (see Gotchas) — because the pin already existed from when the module skeleton was created (same situation as `adapter-cv-grpc`'s C7 wiring).

## Server-push data plane (docs/plans/done/REALTIME-PLAN.md §4)

`VisionLiveProperties` (`@ConfigurationProperties(prefix="vision.live")`, mirrors `VisionCvProperties`'s record/`@DefaultValue` idiom): `enabled` (`@DefaultValue("true")` — unlike CV/persistence, this feature defaults **on**).

**Five ports since W1.6b, not one** (docs/plans/active/DOMAIN-SEPARATION-W1.md §15): `ApplicationServiceWiring#fleetLiveUpdatePort`/`#telemetryLiveUpdatePort`/`#detectionLiveUpdatePort`/`#mapLiveUpdatePort`/`#eventLiveUpdatePort` (each `(VisionLiveProperties, ObjectProvider<LiveUpdateRegistry>)`, near-identical) select the bean for `FleetLiveUpdatePort`/`TelemetryLiveUpdatePort`/`DetectionLiveUpdatePort`/`MapLiveUpdatePort`/`EventLiveUpdatePort` respectively — the five ports the former god-port `LiveUpdatePublisherPort` split into. Each: `true` (default) resolves the real `LiveUpdateRegistry` (`com.drones.vision.api.live`, vision-api, component-scanned, implements all five — the same bean `GET /api/live`'s `LiveController` depends on); `false` wires `NoopLiveUpdatePublisher` (devsupport, also implements all five). `ObjectProvider` here is the same idiom `PersistenceWiringConfiguration`'s six repository-port beans already use for a *conditionally absent* bean (`LiveUpdateRegistry`'s own `@ConditionalOnProperty` means it may not exist on the `false` branch) — `getObject()` is only ever called on the branch where `VisionLiveProperties#enabled()` guarantees it does.

`telemetryLiveUpdatePort`/`detectionLiveUpdatePort` are then threaded through **unconditionally** into `usageTracker` (6-arg ctor) and `streamService` (10-arg ctor) respectively — each narrowed to exactly the one port that collaborator calls, both always receiving a real bean (never `null`), so there is no `if enabled` branch at either of those two call sites; the no-op branch simply makes every announcement a free no-op. Two more seams are wired specifically for the parts of "assets/devices/streams lifecycle" that don't flow through `StreamPipeline`/`UsageTracker` at all:

- **`auditTrailPort`** wraps the `JpaAuditTrail` (adapter-persistence, unconditional since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) in `LiveUpdateAuditTrail` (package-private `AuditTrailPort` decorator, this module, now taking `FleetLiveUpdatePort`) when `vision.live.enabled=true` — every asset/device `create`/`update`/`setState`/`delete`/`assignDevice`/`unassignDevice` already records exactly one `AuditEntry` through this port regardless of whether it also raises a domain `Event` (plain edits/renames never do), making it the one uniform seam for "an asset or device changed" without adding a new constructor parameter to `DefaultAssetService`/`DefaultDeviceService` (both already at or above the constructor-parameter ceiling, `.claude/skills/java-clean-code/SKILL.md` §3 — this was the deciding factor against threading a live-update port into either service directly).
- **`eventPublisherPort`** wraps whatever it would otherwise be (plain `LoggingEventPublisher`, or that further wrapped in `DetectionSessionCleanupEventPublisher` when CV is enabled — see "CV inference wiring" above) in `LiveUpdateEventPublisher` (package-private `EventPublisherPort` decorator, this module, taking **both** `EventLiveUpdatePort` and `FleetLiveUpdatePort` — the one class in this wiring that needs two of the five) when `vision.live.enabled=true`: every event is announced via `EventLiveUpdatePort#publishEvent`, and `DEVICE_ONLINE`/`DEVICE_OFFLINE`/`STREAM_STARTED`/`STREAM_STOPPED` additionally trigger `FleetLiveUpdatePort#publishFleetChanged()` — the "streams lifecycle" third of item 1's "assets/devices/streams lifecycle" that `LiveUpdateAuditTrail` never sees (starting/stopping a stream is never audited, only published as an `Event`).
- **`detectionEventRepositoryPort`** (backend follow-up batch, extending the channel for the events UI) wraps the `JpaDetectionEventRepository` (adapter-persistence, unconditional since docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) in `LiveUpdateDetectionEventRepository` (package-private `DetectionEventRepositoryPort` decorator, this module, now taking `DetectionLiveUpdatePort`) when `vision.live.enabled=true` — every `save` (an event opening, advancing while already open, or closing — `DetectionEventEngine`, vision-application, already calls this port at exactly those three moments) is announced via `publishDetectionEvent`, backing the `detection-events` live topic (`vision-api`). Same "decorate the port every write already goes through" reasoning as `auditTrailPort` above, applied a third time.

**Why three separate decorators instead of one bigger seam**: asset/device CRUD, stream start/stop, and detection-event upserts genuinely flow through three different existing ports (`AuditTrailPort`, `EventPublisherPort`, `DetectionEventRepositoryPort`) with no fourth port already sitting across all three — inventing one wide "control-plane change" port just to unify these orthogonal facts was judged unnecessary ceremony for what's fundamentally "wrap the things that already announce fleet-relevant change with one more announcement each."

**`devices` topic (backend follow-up batch)**: unlike the three decorators above, this one needed **no new decorator and no new port method** — `LiveUpdateRegistry#publishFleetChanged()` (vision-api, `FleetLiveUpdatePort`'s one method) itself now refreshes both the `fleet` and `devices` buffers in one dispatch, so every existing call site (`LiveUpdateAuditTrail`/`LiveUpdateEventPublisher`, both above) already refreshes `devices` too, for free. `LiveUpdateRegistry`'s own constructor grew from `ObjectProvider<AssetService>` alone to five collaborators (`ObjectProvider<DeviceService>`/`ObjectProvider<StreamService>`/`StreamPublisherPort`/`ObjectProvider<DetectionEventRepositoryPort>` added) — all beans this module already wires elsewhere, autowired into the component-scanned `LiveUpdateRegistry` bean without any change needed here beyond them already existing in the context. See station/vision-api/MODULE.md's own `com.drones.vision.api.live` subsection for the full mechanics and why three of the four new collaborators are `ObjectProvider` (the same circular-bean-dependency shape as `assetService`, described next).

`GET /api/live`'s own `LiveController` bean is gated by the **same** `vision.live.enabled` property key, read directly via its own `@ConditionalOnProperty` (matching `matchIfMissing=true`) rather than through `VisionLiveProperties` — mirroring `DiscoveryWiringConfiguration`'s scanner-bean idiom (a plain property check on the class itself) rather than `PersistenceWiringConfiguration`'s bean-selection idiom, since there's no fallback implementation for a controller the way there is for a port. **A real circular bean dependency was found and fixed while wiring this** (not by inspection — a real `UnsatisfiedDependencyException` at context startup): `assetService` → `auditTrailPort` → `fleetLiveUpdatePort` → `LiveUpdateRegistry` → `assetService` is a genuine cycle, since `LiveUpdateRegistry` needs `AssetService` for its fleet-snapshot query. Fixed on the vision-api side (`LiveUpdateRegistry`'s constructor takes `ObjectProvider<AssetService>`, deferring the actual lookup past context startup) — see station/vision-api/MODULE.md's own Gotchas entry for the full chain and exactly which `@Bean` method surfaced it. A second, unrelated bean-type-ambiguity issue (`LiveController`'s `LiveUpdateRegistry` constructor parameter needing `@Qualifier("liveUpdateRegistry")`, since `ApplicationServiceWiring#fleetLiveUpdatePort`'s declared return type doesn't stop Spring from also matching its *actual* runtime type against a plain `LiveUpdateRegistry` lookup) is likewise documented there, not here, since the fix lives entirely in `LiveController`'s own source.

**The same circular-dependency shape recurred twice more for the batch's other three new `LiveUpdateRegistry` collaborators** (found by reasoning from the already-documented pattern above, not by a fresh `UnsatisfiedDependencyException` — though the test suite would have surfaced one had a plain constructor been tried): `deviceService` → `auditTrailPort` → `fleetLiveUpdatePort` → `LiveUpdateRegistry` → (a plain `DeviceService` param) → `deviceService` again; `streamService` → `detectionLiveUpdatePort` (its own 10-arg ctor takes the port directly) → `LiveUpdateRegistry` → (a plain `StreamService` param) → `streamService` again; and `detectionEventRepositoryPort` → `detectionLiveUpdatePort` (once wrapped in `LiveUpdateDetectionEventRepository`) → `LiveUpdateRegistry` → (a plain `DetectionEventRepositoryPort` param) → `detectionEventRepositoryPort` again. All three are `ObjectProvider` in `LiveUpdateRegistry`'s constructor for exactly this reason; `streamPublisherPort` has no such cycle (neither `MediamtxStreamPublisher` nor `NoopStreamPublisher` depends on any of these five ports) and stays a plain parameter.

## Simulated-feed resume-on-boot (backend follow-up batch)

`VisionSimulationProperties` (`@ConfigurationProperties(prefix="vision.simulation")`): `resumeOnBoot` (`@DefaultValue("true")`).

`SimulationResumeRunner` (package-private, `ApplicationRunner`) calls `SimulationService#resumeAll()` once at boot — restarting the TX feed for every persisted, `ACTIVE`, `simulated`-category asset whose `rtsp` video device structurally looks like one of this app's own TX-fed feeds (see contexts/vision-simulation/MODULE.md's `DefaultSimulationService#resumeAll` entry and its Gotchas — now marked Fixed — for the full mechanism this closes: a "frozen video" bug where a persisted RTSP URL survives a restart but its TX feed does not). `enabled` is resolved once, in `ApplicationServiceWiring#simulationResumeRunner`, from `VisionSimulationProperties#resumeOnBoot()` alone — a plain boolean passed into the constructor rather than the runner re-reading the property itself, keeping it a trivial, directly-unit-testable trigger. **Simplified by docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b**: it used to also AND against `VisionPersistenceProperties#enabled()`, back when persistence itself could be off; now that Postgres is unconditional, `resumeOnBoot()` is the only gate left.

**Always registered as a bean, unlike `LiveController`'s conditionally-absent pattern**: rather than a `@ConditionalOnProperty`, the bean always exists and resolves to a no-op `SimulationResumeRunner` when `resumeOnBoot=false` — simpler, and just as testable: `SimulationResumeWiringConfigurationTest` calls `ApplicationServiceWiring#simulationResumeRunner` directly with mocks for both `resumeOnBoot` values, and `SimulationResumeRunnerTest` unit-tests the runner class itself in isolation.

`simulationService`'s own `@Bean` method grew a 4th argument, `properties.mediamtx().rtspBase()` (`VisionPublishProperties`) — the same `URI` already handed to `rtspFeedTransmitter` — reused as-is, no new property, so `resumeAll()` can recognize which persisted `rtsp` devices are this app's own TX-fed feeds structurally (host:port match) rather than a real external camera.

## Test inventory

- **ContextArchitectureTest** — 4 rules (docs/plans/active/DOMAIN-SEPARATION-W1.md §14/§15), freezing the **bounded-context graph**. Since W1.5b each context owns one package tree, `com.drones.vision.<context>..`, holding both its domain and its application layer, so this measures the **module** graph — exactly what Maven must express in W1.7. That is a change in kind, not degree: the earlier per-layer rules (`domainCrossContextReferencesMatchTheDeclaredSetExactly` and friends, removed in W1.5b) could not see an edge that crossed layers — warehouse's *application* reaching perception's *domain* — and so reported an acyclic graph that was not.
  - `crossContextEdgesMatchTheDeclaredSetExactly` asserts the observed cross-context dependencies **equal** `DECLARED_EDGES` — exact equality, so it fails both when a context reaches somewhere new *and* when a declared allowance goes stale, which is what forces the debt list to shrink instead of accumulating permanent exemptions.
  - `moduleCyclesAreOnlyTheKnownOnes` holds mutual pairs against `DECLARED_CYCLES` rather than asserting zero, because the honest count at W1.5b was seven. Every one blocks extraction for *all* contexts at once (Maven cannot express a cycle), so the set is a burn-down that must reach empty before W1.7 — tracked in the open, not hidden behind a disabled test. W1.6a took it to six (`identity <-> warehouse` gone); W1.6b (the god-port `LiveUpdatePublisherPort` deleted and split into five per-context ports, `DetectionRepositoryPort`/`DetectionEvent` family moved to perception, `ReplayCaptureSpec` moved to learning, making `events` a pure downstream reader) took it to two — `flight <-> warehouse` and `perception <-> warehouse` remained. W1.6c (`Telemetry`/`FlightState` → kernel, `AssetUsage`/`AssetUsageRepositoryPort` → warehouse, C9) paid off half of `flight <-> warehouse`'s cause without closing the cycle itself: what was left of it was `DefaultProbeService -> TelemetrySourcePort` alone. W1.6d (docs/plans/active/DOMAIN-SEPARATION-W1.md §15) closed it outright rather than paying it off behind a new port: moving the whole probe feature (`ProbeService`/`DefaultProbeService`/`ProbeResult`/`ProbeFailedException`) from `warehouse.application.device` to `perception.application.device` turned that one reference into `perception -> flight`, already a legal edge — no `warehouse -> flight` edge survives it. `perception <-> warehouse` (C3/C6, warehouse reading runtime state) is the one cycle left, `DECLARED_CYCLES` now a singleton, and it is W1.6e's job.
  - `kernelDependsOnNothingButItselfAndTheJdk` — the shared kernel (`com.drones.vision.kernel`, 17 types since W1.6c: every id, `GeoPosition`, `Ownership`, `LifecycleState`, `Capability`, `BoundingBox`, `BearingDistance`, `GeoProjection`, `StreamDescriptor`, `Telemetry`, `FlightState`) must depend on no context's domain code, because a kernel that did would smuggle that one context's edge into all eight contexts allowed to depend on it.
  - `platformDependsOnNothingButTheKernel` (new in W1.6a) — the same argument for `com.drones.vision.platform`, the second universal package: `Event`/`EventType`/`EventPublisherPort`, the `AuditEntry` family + `AuditTrailPort`, `VisibilityScope` and `AccessDeniedException`. These are the cross-cutting write-seams every context uses; filing them inside `events`/`identity` is what made those two contexts hubs, and caused four of the seven cycles.

  Contexts are declared as a package→context map in the test itself, with `kernel` and `platform` treated as universal (skipped as both origin and target). ArchUnit reads **bytecode**, so javadoc-only `{@link}` imports never registered as edges here — that cleanup (W1.4) was still real work before Maven extraction, since an unused import must resolve at compile time.
- **ArchitectureTest** — 9 ArchUnit rules over `com.drones.vision..` (prod classes only; count unchanged since wave H). The original 5, kept unmodified in *count* per docs/plans/active/LAYERING-REFACTOR-PLAN.md §6.4, but three widened in wave W1.5a (docs/plans/active/DOMAIN-SEPARATION-W1.md) to also admit `..kernel..`: `domainDependsOnlyOnDomainAndJava` now selects classes in `..domain..` **or** `..kernel..` and permits dependencies on `..domain..`, `..kernel..`, `java..` — the shared kernel is domain code (typed ids, `GeoPosition`, `BoundingBox`, etc.) that belongs to no single bounded context, so it sits at `com.drones.vision.kernel` rather than under any package the `..domain..` substring-match can reach, and needed its own explicit admission on both sides of the rule; `applicationDependsOnlyOnApplicationDomainAndJava` now also permits `..kernel..` alongside `..application..`/`..domain..`/`java..`/`javax.imageio..`; `domainAndApplicationAreSpringAnnotationFree` now also scans `..kernel..` for a Spring dependency. The other two of the original five — `adaptersDoNotDependOnEachOther` (slices `com.drones.vision.adapter.(*)..` — this already covers the plan's wave-H "no adapter depends on another adapter" ask pairwise, so no second rule was added for it) and `onlyAppMayDependOnAdapterPackages` — were left untouched, since neither pattern's meaning changes for kernel code. Plus 4 added by wave H (docs/plans/active/LAYERING-REFACTOR-PLAN.md §7 row H): `restControllersLiveOnlyInApiControllerOrProxyPackage` (`@RestController` → `com.drones.vision.api.controller` or `com.drones.vision.api.proxy` — the latter is `HlsProxyController`'s documented exception, not a violation), `configurationPropertiesClassesLiveOnlyInAppConfigPropertiesPackage` (`@ConfigurationProperties` → `com.drones.vision.app.config.properties` only — the adapter-side `*Settings` records are plain, framework-free classes that merely *mention* `@ConfigurationProperties` in javadoc, never carry the annotation), `applicationHasNoClassesLooseAtItsRootPackage` (non-recursive `resideInAPackage("com.drones.vision.application")` — verified empty, Wave A already did the move), `onlyAppMayDependOnConfigPropertiesTypes` (no class outside `..app..` may depend on `..app.config.properties..`). All 9 passed on first write with no code changes needed — the refactor left no violations for these rules to catch. No Spring context.
- **SimStreamSmokeTest** — `@SpringBootTest(vision.publish.enabled=false)` + `@Import(RecordingPublisherConfig)`: a `@TestConfiguration` supplies an `@Primary` `RecordingStreamPublisher` (records frames + a `CountDownLatch` for "first frame") so it wins autowiring over the real `MediamtxStreamPublisher`/`NoopStreamPublisher`. The extended M4 scenario (docs/plans/done/ASSET-MODEL-PLAN.md §4): creates an asset (category `drone`) with one `sim` `VIDEO` device and one `sim` `TELEMETRY` device via `AssetService#create`, starts it via `AssetService#startStream` (device `null` — resolves the single video-capable device), asserts a real frame from `SimulatedVideoSource` reaches the recorder within 5s **and** polls `TelemetryRepositoryPort.findByUsage` for ≥2 samples (bounded 15s wait at `SimulatedTelemetrySource`'s real 1Hz cadence — see Gotchas), stops via `AssetService#stopStream`, then asserts the asset's most recent `AssetUsage` is closed (`endedAt` set) with both `startPosition`/`lastPosition` populated and `sampleCount >= 2`.
- **FileSimulationSmokeTest** — docs/main/CYCLES-PLAN.md §1c: the same shape of test as `SimStreamSmokeTest`, but exercised through the real HTTP endpoint (`MockMvc` built by hand via `MockMvcBuilders.webAppContextSetup` — `@AutoConfigureMockMvc`'s web-mvc test-autoconfiguration isn't on this Spring Boot 4 classpath, see Gotchas) rather than calling `AssetService` directly, since it specifically covers `SimulationController`/`SimulationService`. Generates a tiny (~1s, 64×48) H.264 mp4 with JavaCV's `FFmpegFrameRecorder` into `@TempDir` (adapter-rtsp's native FFmpeg libraries are already on this module's test classpath), `POST`s it to `/api/simulations`, asserts 201 with `assetId`/`streamId`, awaits a frame at the recording publisher, polls for the opened `AssetUsage` and ≥2 telemetry samples (same bounded-wait style as `SimStreamSmokeTest`), then `DELETE /api/assets/{id}/stream` and asserts the usage closed.

  A second `@Test`, `postSimulationsWithARouteFliesTelemetryTowardTheFinalCheckpoint` (docs/main/CYCLES-PLAN.md §7, CT-a, docker-free like the rest of this class), posts the same generated video plus a `telemetry` object — a 3-waypoint north-heading route (300m legs, so 600m total), `speedMps=100`, `routeMode="once"` — then polls `TelemetryRepositoryPort` for ≥8 samples (chosen so the last few observed samples are reliably past the route's exact 6-tick completion, not landing on it) and asserts each successive sample's equirectangular-approximated distance to the final checkpoint trends toward zero (5m tolerance between consecutive samples) and the last sample is within 50m of it — `routeMode=once` (rather than the `loop` default) keeps this assertion robust regardless of which tick the poll happens to observe last, since the drone holds at the end instead of retracing. **Gotcha this test itself proves**: `SimulatedTelemetrySource`'s route ticks in this module at the real, unthrottled 1Hz cadence (the faster test-only constructor isn't reachable here, see below) — `distanceMeters` still advances deterministically per tick regardless of wall-clock jitter, but the test must budget real seconds (`ROUTE_TELEMETRY_TIMEOUT`=30s) for enough ticks to elapse, not milliseconds.
- **DeviceProbeSmokeTest** (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, CONTRACT 1) — end-to-end proof that `POST /api/devices/probe` really resolves a real `VideoSourcePort` adapter and grabs a real frame, over the full production wiring (no test doubles needed at all — a probe never touches `StreamPublisherPort`). `MockMvc` built by hand (same `webAppContextSetup` technique as `FileSimulationSmokeTest`). Three tests: a `sim`-protocol probe (`SimulatedVideoSource`, zero hardware) asserting 640×720→640×480 dims, `codec="mjpeg"`, and — a real, wired assertion — `telemetryDetected=true` (adapter-simulation's `SimulatedTelemetrySource` genuinely claims a `sim`-protocol `TELEMETRY`-capable synthetic probe device, see `DefaultProbeService`'s own javadoc for why this is a fair read, not a false positive); a `file`-protocol probe through the real FFmpeg ingest path against a tiny JavaCV-generated mp4 (duplicated video-generation helper, same precedent as `MjpegSimulationSmokeTest`/`RtspSimulationDockerE2ETest`), asserting the generated video's own 64×48 dimensions, an absent `codec` (BGR24 carries no wire-codec memory), and `telemetryDetected=false` with the UX-DESIGN §5.1 warning; and a plain 400 for an unrecognized protocol. Deliberately does **not** re-test failure-message translation here (already covered by `DefaultProbeServiceTest`/`DeviceProbeControllerTest` with mocks) — this class exists to prove the wiring/seam, not to re-prove logic already unit-tested elsewhere.
- **AssetWiringTest** — `@SpringBootTest(vision.publish.enabled=false)`: asserts every asset-model service/repository-port bean exists (`AssetService`, `CategoryService`, `DeviceService`, `SimulationService`, `SimulationController`, `CategoryRepositoryPort`, `AssetRepositoryPort`, `AssetUsageRepositoryPort`, `TelemetryRepositoryPort`, `AuditTrailPort`, the `actingOwnership` `Ownership` bean) via `@Autowired` + `assertNotNull` — mirrors `PublishWiringTest`/`DiscoveryWiringTest`'s per-concern wiring-test style; a missing bean would already fail context startup, so this is mostly a readable inventory. Extended for docs/main/CYCLES-PLAN.md §1c to also cover `SimulationService`/`SimulationController` rather than adding a separate wiring-test class, since they're asset-model beans built directly on `AssetService`. Further extended for §5 (superseding the §3-era single `@Autowired FeedTransmitterPort` field, which stopped working once a second implementation existed): autowires the whole `List<FeedTransmitterPort>` and asserts both `RtspFeedTransmitter` and `MjpegFeedTransmitter` are present, and separately asserts the `FeedTransmitterRegistry` bean resolves an `rtsp`/`mjpeg` `FeedSpec` to the matching adapter. Further extended for docs/plans/done/MVP2-PLAN.md **R-a2**: `ReplayService` is asserted as a registered bean in the same inventory test, since it's a small, asset-model-adjacent one-liner (mirrors `categoryService`'s own shape) — no new wiring-test class needed. Further extended for docs/plans/done/MVP2-PLAN.md **X-a**: autowires `List<TelemetrySourcePort>` and asserts `MavlinkTelemetrySource` is present (2 new test methods, mirroring the §5 `FeedTransmitterPort` pattern exactly): `mavlinkTelemetrySourceIsWiredAsATelemetrySourcePortBean` and `mavlinkFeedTransmitterIsWiredAsAFeedTransmitterPortBeanAndResolvesByProtocol` (asserts both `List<FeedTransmitterPort>` membership and `FeedTransmitterRegistry` resolving a `mavlink` `FeedSpec`) — up from 3 test methods to 5. Further extended for docs/plans/done/MVP2-PLAN.md **E-a**: `DetectionEventRepositoryPort` and `EventController` both added to the same bean-inventory test (no new test method, two more `assertNotNull` calls) — the same "small, focused, asset-model-adjacent bean" reasoning as `ReplayService`/R-a2 above; `EventController`'s single constructor dependency resolving is itself proof the bean graph is wired correctly, mirroring `simulationController`'s own assertion. Further extended for docs/plans/done/MVP3-PLAN.md **C-a**: `FleetSummaryService` and `FleetController` added to the same bean-inventory test (no new test method, two more `assertNotNull` calls) — same reasoning again, this time mirroring `EventController`'s own pair exactly. Further extended for docs/plans/done/UX-REWORK-PLAN.md **§U-d item 3**: `ProbeService`/`DeviceProbeController` and `AssetImageRepositoryPort`/`AssetImageController` all added to the same bean-inventory test (no new test method, four more `assertNotNull` calls) — same "small, focused" reasoning yet again. Further extended for docs/plans/active/DRONE-INFRA-PLAN.md **I-e Stage 1 wave 2**: `FlightCommandService`/`FlightCommandController` added to the same bean-inventory test (two more `assertNotNull` calls, same reasoning) plus one new test method, `flightCommandServiceIsWiredAgainstTheMavlinkCommander` — autowires both the `FlightCommandPort` interface and the concrete `MavlinkFlightCommander` bean and asserts `assertSame`, proving the one `FlightCommandPort` bean in this context really is the mavlink commander (there being exactly one implementation makes an interface-vs-concrete-type identity check the simplest honest proof that `flightCommandService`'s constructor argument resolved correctly, short of exposing a getter purely for testing) — up from 5 test methods to 6. Further extended for docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md **Wave A**: `AssetStatsService`/`AssetStatsController` added to the same bean-inventory test (two more `assertNotNull` calls, no new test method — same "small, focused, asset-model-adjacent bean" reasoning as `ReplayService`/`FleetSummaryService` above) — still 6 test methods. Further extended for docs/plans/done/TACTICAL-MARKS-PLAN.md **M4**: `MarkRepositoryPort`/`MarkService`/`MarksController` added to the same bean-inventory test (three more `assertNotNull` calls, no new test method — same "small, focused, asset-model-adjacent bean" reasoning as `AssetStatsService`/`FleetSummaryService` above) — still 6 test methods.
- **RtspSimulationDockerE2ETest** — docs/main/CYCLES-PLAN.md §3's C3 done-criterion in test form, in the `FileSimulationSmokeTest` mould but for `transport=rtsp` and a real mediamtx container: docker-gated (`@EnabledIf("dockerAvailable")`, same idiom as adapter-rtsp's/adapter-publish-hls's `MediamtxDockerIntegrationTest` — skips cleanly, not a failure, whenever the `docker` CLI isn't usable) rather than docker-free like `FileSimulationSmokeTest`. Starts a real mediamtx container once in a static `@BeforeAll` (which — because `@EnabledIf` gates the whole class, including `@BeforeAll` — never runs when docker is unavailable) and feeds its randomized host RTSP port into `vision.publish.mediamtx.rtsp-base` via `@DynamicPropertySource` before the Spring context loads, since `rtspFeedTransmitter` reads that same property. `vision.publish.enabled=false` plus the same `@Import(FileSimulationSmokeTest.RecordingPublisherConfig.class)` (reusing `FileSimulationSmokeTest`'s package-private `RecordingPublisherConfig`/`RecordingStreamPublisher` nested types directly, rather than duplicating them, since both classes live in this same `com.drones.vision.app` package) swaps in a recording `StreamPublisherPort`. `POST /api/simulations` with `"transport":"rtsp"` → 201; awaits a real frame at the recording publisher (generous timeout — this is a genuine encoder→wire→demuxer→pipeline round trip through a real container, on top of the native-library-extraction cost `FileSimulationSmokeTest` already documents paying once) and ≥2 telemetry samples; `DELETE /api/simulations/{assetId}` → 204; asserts the usage closed **and** polls `Thread.getAllStackTraces()` for `rtsp-feed-`-prefixed thread names to confirm the TX thread actually stopped (bounded past `RtspFeedTransmitter`'s own 20s join timeout).
- **MjpegSimulationSmokeTest** — docs/main/CYCLES-PLAN.md §5's C5 done-criterion in test form, in the `FileSimulationSmokeTest`/`RtspSimulationDockerE2ETest` mould but for `transport=mjpeg` — and, unlike the RTSP E2E test, **needs no docker**: `MjpegFeedTransmitter` serves its own in-process HTTP server, so the whole TX→wire→RX round trip happens inside this one JVM (the same property adapter-mjpeg's own `MjpegRoundTripIntegrationTest` already proves at the adapter level). Reuses `FileSimulationSmokeTest.RecordingPublisherConfig`/`RecordingStreamPublisher` the same way `RtspSimulationDockerE2ETest` does. `POST /api/simulations` with `"transport":"mjpeg"` → 201; awaits a frame at the recording publisher and ≥2 telemetry samples; `DELETE /api/simulations/{assetId}` → 204; asserts the usage closed **and** polls `Thread.getAllStackTraces()` for any `mjpeg-`-prefixed thread name (covers both `MjpegVideoSource`'s RX read thread and `MjpegFeedTransmitter`'s per-viewer TX thread — see adapter-mjpeg/MODULE.md) to confirm both sides actually stopped. No RTSP-style fixed "feed establish" delay is needed: `MjpegFeedTransmitter#start` only returns once its HTTP context is already registered on an already-listening server.
- **CvWiringTest** — `@SpringBootTest(vision.publish.enabled=false, vision.live.enabled=false)`, default `vision.cv.*` (disabled): asserts `detectionPort` is a `NoopDetectionPort` and `eventPublisherPort` is the plain `LoggingEventPublisher` (not wrapped) — the pre-C7-behavior counterpart to `CvEnabledWiringTest`. `vision.live.enabled=false` (docs/plans/done/REALTIME-PLAN.md §4) isolates this test from the server-push feature's own `EventPublisherPort` decorator, which would otherwise also wrap the result by default — see `LiveWiringTest`/`LiveDisabledWiringTest` for that feature's own dedicated coverage. Extended (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) with one more assertion: no `ManagedChannel` bean exists at all in this both-flags-off default context — see "CV inference wiring" above. Extended (docs/plans/active/CV-DEMAND-PLAN.md wave D2) with `defaultConfigurationWiresTheDetectionDemandPortIntoStreamDetectionSupport`: with every flag at its default (`vision.cv.demand.enabled=true`, `vision.live.enabled=false` here so no real SSE registry exists), `LiveAndPollDetectionDemand`/`StreamDetectionSupport` beans both resolve and `StreamDetectionSupport#demand()` is the same singleton — proving the demand port wires even when the SSE half it could otherwise delegate to is absent.
- **CvEnabledWiringTest** — `@SpringBootTest(vision.publish.enabled=false, vision.cv.enabled=true, vision.cv.endpoint=localhost:59321, vision.cv.detect-width=480, vision.cv.jpeg-quality=0.6, vision.live.enabled=false)`: asserts `detectionPort` resolves to a `GrpcDetectionPort` and `eventPublisherPort` resolves to `DetectionSessionCleanupEventPublisher`. The configured endpoint is never actually connected to here (a `ManagedChannel` only connects lazily on first use, so an arbitrary port number is fine) — the real round trip against an actual gRPC server is `CvDetectionE2ETest`'s job. `vision.live.enabled=false` for the same isolation reason as `CvWiringTest` above (otherwise `LiveUpdateEventPublisher` would wrap `DetectionSessionCleanupEventPublisher` one layer further out, breaking this test's `instanceof` assertion). The non-default `detect-width`/`jpeg-quality` (docs/plans/done/REMOTE-CV-PLAN.md P1 item 5) prove Spring binds those kebab-case keys onto `VisionCvProperties` and the bean graph still constructs cleanly with them — `GrpcDetectionPort` exposes no getter for either (only wire behavior, covered by adapter-cv-grpc's own `GrpcDetectionPortTest`), so a clean context load plus the existing `instanceof` assertion is the full extent of what this class can observe about them. Extended (Phase 2 T9) with two more assertions: the shared `cvGrpcChannel` bean still resolves (a CV-only deployment gets a channel too), and `ModelRegistryController`/`ModelRegistryService`/`ModelRegistryPort` all stay absent (`vision.training.enabled` left at its own default here) — a CV-only deployment doesn't accidentally light up the training endpoints.
- **CvAndTrainingSharedChannelWiringTest** (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9, extended by the training-job wave) — `@SpringBootTest(vision.publish.enabled=false, vision.cv.enabled=true, vision.cv.endpoint=localhost:59322, vision.training.enabled=true, vision.live.enabled=false)` (no `@TempDir`/`@DynamicPropertySource` needed since docs/plans/done/CV-TRAINING-V2-PLAN.md §7 removed `vision.training.export-dir`): the one test that actually exercises channel *sharing*, since `CvEnabledWiringTest`/`TrainingEnabledWiringTest` each only flip one flag at a time. Asserts exactly one `ManagedChannel` bean exists via `ApplicationContext#getBeansOfType` (size `1`), that `detectionPort`/`modelRegistryPort`/`trainingPort` resolve to `GrpcDetectionPort`/`GrpcModelRegistryPort`/`GrpcTrainingPort` respectively, and that the `ManagedChannel` autowired directly is `assertSame` to `applicationContext.getBean(ManagedChannel.class)` — by Spring singleton-bean semantics that's sufficient proof all three ports were built against the identical instance, since there is provably only the one bean definition to build from.
- **VisionCvPropertiesTest** (docs/plans/done/REMOTE-CV-PLAN.md P1 item 5) — plain unit test (no Spring context), same package as the class under test, mirroring this module's other no-context record-validation style: `host()`/`port()` parsing, blank-`endpoint` rejection, `detectWidth`/`jpegQuality` boundary validation (`< 64` / `<= 0` / `> 1` rejected, the respective boundary values `64`/`1.0` accepted), and a pin on the `640`/`0.8` default values `application.yaml`' commented-out lines document. Extended (docs/plans/active/CV-DEMAND-PLAN.md wave D2) +2: `detectionDefaultEnabledDefaultsToFalseThroughTheConvenienceConstructor` (the pre-wave-D2-shape convenience constructor still yields `false`, the plan's own pinned default) and `demandDefaultsToEnabledWithTheDocumentedTunablesWhenAbsent` (an absent `demand` block binds `enabled=true`/`pollInterval=2s`/`grace=30s`/`pollTtl=10s`, pinning `Demand`'s own `@DefaultValue`s against the compact constructor's null-substitution path).
- **TrainingDisabledWiringTest** (docs/plans/done/CV-TRAINING-PLAN.md §3/§G, Wave T4, delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §7) — `@SpringBootTest(vision.publish.enabled=false)`, default `vision.training.*` (disabled): the guardrail proof — asserts `DatasetController`/`LabelingController` and `DatasetService`/`LabelingService`/`TrainingStores`/`ReplaySources`/`DatasetUploadPort` are all **zero**-count via `ApplicationContext#getBeansOfType` (same "`@Autowired` would fail the context on zero candidates" reasoning as `LiveDisabledWiringTest`/`DiscoveryDisabledWiringTest`) — every training route 404s exactly as before this feature existed (`DatasetUploadPort`/`ReplaySources` checks replace the deleted `DatasetExportPort`/`FilesystemDatasetExport` ones). Extended (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9) with two more zero-count assertions: `ModelRegistryController`/`ModelRegistryService`/`ModelRegistryPort` (the T9 registry beans), and `ManagedChannel` itself (proving the shared gRPC channel — see "CV inference wiring" above — isn't built at all with both `vision.cv.enabled` and `vision.training.enabled` at their defaults). Extended again by the training-job wave with one more zero-count assertion: `TrainingJobController`/`TrainingJobService`/`TrainingPort`.
- **TrainingEnabledWiringTest** — `@SpringBootTest(vision.publish.enabled=false, vision.training.enabled=true)` (no `@TempDir`/`@DynamicPropertySource` needed anymore — docs/plans/done/CV-TRAINING-V2-PLAN.md §7 deleted `vision.training.export-dir`, so there is no on-disk export path left to redirect): the opposite counterpart — asserts `datasetService`/`labelingService` resolve to `DefaultDatasetService`/`DefaultLabelingService`, `datasetUploadPort` resolves to `GrpcDatasetUploadPort` (over the shared `cvGrpcChannel`, in place of the deleted `datasetExportPort`/`FilesystemDatasetExport` assertion), and `trainingStores`/`replaySources`/`DatasetController`/`LabelingController` all resolve (`replaySources` is new, docs/plans/done/CV-TRAINING-V2-PLAN.md §4 — its own `replayFrameExtractionPort` collaborator resolves against the *no-op* branch here, since `vision.publish.enabled=false` in this test). Extended (Phase 2 T9) with three more assertions: `modelRegistryPort` resolves to `GrpcModelRegistryPort` (over the shared `cvGrpcChannel`, also asserted non-null — `vision.cv.enabled` stays at its own default `false` here, proving a training-only deployment needs no detection wiring), `modelRegistryService` resolves to `DefaultModelRegistryService`, and `ModelRegistryController` resolves. Extended again by the training-job wave with three more: `trainingPort` resolves to `GrpcTrainingPort` (over the same shared channel), `trainingJobService` resolves to `DefaultTrainingJobService`, and `TrainingJobController` resolves.
- **LiveWiringTest** (docs/plans/done/REALTIME-PLAN.md §4; updated W1.6b for the five-port split) — `@SpringBootTest(vision.publish.enabled=false)`, default `vision.live.*` (enabled, the default): asserts each of the five live-update ports (`FleetLiveUpdatePort`/`TelemetryLiveUpdatePort`/`DetectionLiveUpdatePort`/`MapLiveUpdatePort`/`EventLiveUpdatePort`) resolves to the real `LiveUpdateRegistry` (not the no-op), the `LiveController` bean exists, and `eventPublisherPort`/`auditTrailPort`/`detectionEventRepositoryPort` (the last, backend follow-up batch) are each wrapped in their respective live-update decorators.
- **LiveDisabledWiringTest** (updated W1.6b for the five-port split) — `@SpringBootTest(vision.live.enabled=false, vision.publish.enabled=false)`: the opposite counterpart — asserts each of the five live-update ports falls back to `NoopLiveUpdatePublisher`, **zero** `LiveController`/`LiveUpdateRegistry` beans exist (looked up via `ApplicationContext#getBeansOfType`, same "`@Autowired` would fail the context entirely on zero candidates" reasoning as `DiscoveryDisabledWiringTest`), and neither `eventPublisherPort`/`auditTrailPort` nor `detectionEventRepositoryPort` (backend follow-up batch) is wrapped.
- **DetectionDemandDisabledWiringTest** (docs/plans/active/CV-DEMAND-PLAN.md wave D2) — `@SpringBootTest(vision.cv.demand.enabled=false, vision.publish.enabled=false, vision.live.enabled=false)`: the guardrail proof for the escape hatch — asserts **zero** `LiveAndPollDetectionDemand` beans exist (`ApplicationContext#getBeansOfType`, same "`@Autowired` would fail the context entirely on zero candidates" reasoning as `LiveDisabledWiringTest`/`DiscoveryDisabledWiringTest`) and that `StreamDetectionSupport#demand()` is `null` — proving `CvWiring#detectionDemandPort`'s `@ConditionalOnExpression` really keeps the bean from being created at all (not merely swapped for a no-op), so `DefaultStreamService`'s demand-poll task is never scheduled and every stream stays fail-open on demand, unchanged from before this plan existed.
- **LiveUpdateAuditTrailTest**/**LiveUpdateEventPublisherTest**/**LiveUpdateDetectionEventRepositoryTest** — plain unit tests (no Spring context), same package as the decorators under test: `LiveUpdateAuditTrailTest` proves `record` both delegates and announces `publishFleetChanged`, while `findRecent`/`findByTarget` are pure pass-throughs with zero live-update interaction; `LiveUpdateEventPublisherTest` proves every event is delegated + announced via `publishEvent`, and that only the four fleet-lifecycle event types (`DEVICE_ONLINE`/`DEVICE_OFFLINE`/`STREAM_STARTED`/`STREAM_STOPPED`) additionally trigger `publishFleetChanged` (looped over `EnumSet`/its complement rather than `@ParameterizedTest`, since this codebase has no existing precedent for that JUnit feature); `LiveUpdateDetectionEventRepositoryTest` (backend follow-up batch) mirrors `LiveUpdateAuditTrailTest`'s own shape — `save` both delegates and announces `publishDetectionEvent` with the saved event, `findRecent`/`findByStream` are pure pass-throughs.
- **SimulationResumeRunnerTest** (backend follow-up batch) — plain unit test (no Spring context): `run` calls `SimulationService#resumeAll()` when constructed with `enabled=true`, never touches the mock at all when `enabled=false`.
- **SimulationResumeWiringConfigurationTest** (backend follow-up batch; simplified docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) — plain unit test, mirroring `PersistenceWiringConfigurationTest`'s own "call the `@Bean` method directly, no Spring context" reasoning: both values of `VisionSimulationProperties#resumeOnBoot()` (down from four combinations, since `VisionPersistenceProperties#enabled()` no longer exists to cross), asserting the returned `ApplicationRunner`'s `run()` calls (or never touches) a mocked `SimulationService#resumeAll()` exactly when `resumeOnBoot=true`.
- **CvDetectionE2ETest** — the echo round-trip proof for docs/plans/done/MVP1-PLAN.md §C7 bullet 4: a package-private `RecordingServicer` (`InferenceGrpc.InferenceImplBase`) is bound to a real ephemeral **TCP** port via plain `io.grpc.ServerBuilder.forPort(0)` (not an in-process channel like `GrpcDetectionPortTest` — this test needs a real network endpoint since it exercises `vision.cv.endpoint` through the full production wiring) in a static `@BeforeAll`, and its port fed into `vision.cv.enabled=true`/`vision.cv.endpoint` via `@DynamicPropertySource` (same "start it first, then read the field in the dynamic property method" ordering `RtspSimulationDockerE2ETest` already relies on — Spring's context refresh, and therefore `@DynamicPropertySource` evaluation, happens during test-instance preparation, which JUnit always runs *after* class-level `@BeforeAll`). Creates a `sim` `VIDEO`-device asset/stream directly through `AssetService` (the `SimStreamSmokeTest` style, not HTTP), reusing `FileSimulationSmokeTest.RecordingPublisherConfig`/`RecordingStreamPublisher` for the video-frame assertion. Asserts: (1) frames keep reaching the recording publisher; (2) the echo server's `firstRequest` latch counts down within 15s and every `FrameRequest` it saw carries this stream's id — proving `StreamPipeline`'s inference sampling actually drove a real `GrpcDetectionPort` call over the wire; (3) after `AssetService#stopStream`, the server's `requestStreamCompleted` latch counts down within 10s — proving `DetectionSessionCleanupEventPublisher` called `GrpcDetectionPort#streamEnded` on `STREAM_STOPPED`, half-closing the client's request stream, which the server observes as its `StreamObserver<FrameRequest>#onCompleted` firing.
- **CvDetectionResilienceSmokeTest** — the degradation half of the same done criterion ("killing the service mid-stream leaves video/telemetry running"): `vision.cv.enabled=true` but `vision.cv.endpoint` points at a port obtained by binding a `ServerSocket(0)` and immediately closing it (so nothing listens there), fed in via the same `@DynamicPropertySource` technique. Starts a `sim` stream and asserts frames still reach the recording publisher within 10s — proving a real, wired `GrpcDetectionPort` whose every `detect()` call fails fast (connection refused) degrades exactly like `NoopDetectionPort` once `StreamPipeline`'s detection-outage/backoff policy (vision-application, docs/plans/done/MVP1-PLAN.md §C7 bullet 3) takes over; never asserts anything about the gRPC side itself, since there is no server to observe.
- **OverlayWiringTest** — `@SpringBootTest(vision.publish.enabled=false)`: asserts the `OverlayPort` bean resolves to `Java2DOverlayRenderer` (docs/plans/done/MVP1-PLAN.md §C8 bullet 3's wiring half) — the collaborator `streamService` threads into every `StreamPipeline` it starts.
- **CvDetectionEndpointE2ETest** — closes docs/plans/done/MVP1-PLAN.md §C8 bullet 3's API-side done criterion: a package-private `DetectingServicer` (distinct from `CvDetectionE2ETest`'s echo servicer — this one always responds with one non-empty `Detection`, label `"person"`, confidence `0.87`) is bound the same real-ephemeral-TCP-port way as `CvDetectionE2ETest`. Creates a `sim` `VIDEO`-device asset/stream directly through `AssetService`, awaits a frame at the recording publisher (proves video keeps flowing), then polls `GET /api/streams/{streamId}/detections` via a hand-built `MockMvc` (same `webAppContextSetup` technique as `FileSimulationSmokeTest` — no `@AutoConfigureMockMvc` here, see Gotchas) until it returns a non-empty array (detection persistence is asynchronous off the gRPC response callback, so this can't be a single immediate assertion) and asserts the mapped JSON fields (`streamId`, `label`, `confidence`, `box.x`, `modelId`, `modelVersion`) — proving the full frame→gRPC→`DetectionResult`→`DetectionRepositoryPort`→REST loop, not just the wiring in isolation.
- **PublishWiringTest** — `@SpringBootTest` (default properties, mediamtx *enabled*): asserts `streamPublisherPort` is a `MediamtxStreamPublisher` and all `VideoSourcePort` beans (`sim`/`rtsp`/`mjpeg`, extended for docs/main/CYCLES-PLAN.md §5) are registered (`List<VideoSourcePort>` autowired). Never calls `publish()`, so it stays green without mediamtx actually running (lazy RTSP connection). Also asserts the `HlsProxyController` bean exists (`@Autowired`, `assertNotNull`) and that `streamPublisherPort.viewUrl(streamId)` is app-relative (`/hls/<id>/index.m3u8`, a pure function of `VisionPublishProperties#viewBase()` — no mediamtx connection needed for this assertion either). Extended for docs/plans/done/MVP2-PLAN.md **L-a**: `whepUrlIsMediamtxsOwnAddressPerHostModeDefault` asserts `streamPublisherPort.whepUrl(streamId)` is `http://localhost:18889/<id>/whep` — mediamtx's own address (not app-relative, unlike `viewUrl`), a pure function of `application.yaml`' `vision.publish.mediamtx.whep-base` default, so this too needs no running mediamtx. Extended for docs/plans/done/MVP2-PLAN.md **X-b**: `videoSourcesIncludeTheV4l2AdapterUsingDiscoverysActualDescriptorShape` (up from 5 to 6 test methods) asserts `v4l2` is a fourth registered protocol, using the descriptor shape `adapter-discovery`'s `V4l2Scanner` actually emits (`protocol="v4l2"`, `uri=file:/dev/video0`) rather than the plan's originally-proposed `usb`/`v4l2://` shape. Extended for docs/plans/done/CV-TRAINING-V2-PLAN.md **§7** (up from 6 to 8 test methods): `playbackUrlUsesThePlaybackBasePropertysDefaultHostAndPort` asserts `streamPublisherPort.playbackUrl(...)` is built against `http://localhost:19996` — `VisionPublishProperties.Mediamtx#playbackBase()`'s default, proving the 4-arg-constructor switch reproduces the old 3-arg overload's derived guess exactly; `defaultConfigurationSelectsTheMediamtxReplayFrameExtractor` asserts `replayFrameExtractionPort` resolves to `MediamtxReplayFrameExtractor`, neither needing a running mediamtx (both are pure URL-formatting/bean-resolution checks).
- **DiscoveryWiringTest** — `@SpringBootTest(vision.publish.enabled=false)`, default discovery config: `@Autowired List<DeviceDiscoveryPort>`, asserts all 4 methods (`onvif`,`mdns`,`v4l2`,`mavlink` — the last added by docs/plans/active/DRONE-INFRA-PLAN.md I-b) present.
- **DiscoveryDisabledWiringTest** — `@SpringBootTest(vision.discovery.enabled=false, vision.publish.enabled=false)`: asserts the context still loads with **zero** `DeviceDiscoveryPort` beans and `ScanDevicesUseCase` degrades to an empty result. **Gotcha:** looks these up via `ApplicationContext.getBeansOfType(DeviceDiscoveryPort.class)`, not `@Autowired List<DeviceDiscoveryPort>` — a plain `@Autowired` collection field is required-by-default and throws `NoSuchBeanDefinitionException` at zero candidates (unlike a `@Bean` factory-method `List<>` parameter, which Spring happily supplies empty), which would defeat the point of asserting "nothing registered."
- **VisionApplicationTests** — `@SpringBootTest(vision.publish.enabled=false)`, bare `contextLoads()`.
- **PersistenceWiringTest** (docs/plans/done/MVP2-PLAN.md P-a + P-b, extended docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, docs/plans/done/OPS-CORE-PLAN.md G-b, docs/plans/done/TACTICAL-MARKS-PLAN.md M2; flipped docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4; simplified W2b) — `@SpringBootTest(vision.publish.enabled=false)`: asserts `categoryRepositoryPort`/`deviceRepositoryPort`/`assetRepositoryPort`/`assetUsageRepositoryPort`/`telemetryRepositoryPort`/`detectionRepositoryPort`/`assetImageRepositoryPort`/`geofenceRepositoryPort`/`markRepositoryPort`/`datasetRepositoryPort`/`trainingSampleRepositoryPort`/`sampleImageStorePort` all resolve to their `Jpa*Repository` implementations, **and** `applicationContext.getBeansOfType(EntityManagerFactory.class)` is non-empty — proving `persistenceEntityManagerFactory` really did build a real `EntityManagerFactory` (against `testsupport.SharedPostgresContainer` — see "Test infrastructure" above). Since W2b there is only one branch left to prove — the message asserted on `defaultConfigurationCreatesExactlyOneEntityManagerFactory` no longer talks about `VisionPersistenceProperties#enabled()`/`@ConditionalOnProperty`, just "exactly one `EntityManagerFactory` bean, unconditionally."
- **PersistenceWiringConfigurationTest** (docs/plans/done/MVP2-PLAN.md P-a + P-b, extended docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, docs/plans/done/OPS-CORE-PLAN.md G-b, docs/plans/done/TACTICAL-MARKS-PLAN.md M2; collapsed docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b) — deliberately **not** a `@SpringBootTest`, same "call the `@Bean` methods directly as plain Java" reasoning as before: with the toggle gone there is only one branch to prove, so this now takes a single mocked `EntityManagerFactory entityManagerFactory` field (no `ObjectProvider`, no `VisionPersistenceProperties`) and asserts each `PersistenceWiringConfiguration` `@Bean` method resolves to its `Jpa*Repository` (`selectsJpaCategoryRepository`/`selectsJpaDeviceRepository`/`selectsJpaAssetRepository`/`selectsJpaAssetUsageRepository`/`selectsJpaTelemetryRepository`/`selectsJpaDetectionRepository`/`selectsJpaAssetImageRepository`/`selectsJpaGeofenceRepository`/`selectsJpaMarkRepository`/`selectsJpaDatasetRepository`/`selectsJpaTrainingSampleRepository`/`selectsJpaSampleImageStore` — 12 methods; a pre-existing gap unrelated to this wave, `userRepositoryPort`/`groupRepositoryPort`/`assignmentRepositoryPort`/`mapLayerRepositoryPort`/`drawingRepositoryPort` were never added to this test even before W2b, so 5 of the 17 port beans have no dedicated coverage here). The "disabled branch" test that used to sit alongside the other 12 is gone, since it existed only to prove the now-deleted in-memory branch. No Spring context, no Docker, no database.

## Dev principal

docs/plans/done/ASSET-MODEL-PLAN.md §0.3/§4: until the identity phase (ARCHITECTURE.md §6), a constant dev principal (`UserId`, `GroupId`) owns every asset — applied at the API edge, never hard-coded in domain or threaded through a service constructor (`AssetService`/`SimulationService` take ownership as a method parameter, per `.claude/skills/java-clean-code/SKILL.md` §4). `com.drones.vision.app.devsupport.DevPrincipal` holds the fixed constants: `USER_ID` wraps `new UUID(0, 0)`, `GROUP_ID` wraps `new UUID(0, 1)`, and `OWNERSHIP` is `new Ownership(USER_ID, GROUP_ID)`. `ApplicationServiceWiring#actingOwnership` publishes `DevPrincipal.OWNERSHIP` as an `Ownership` bean; `vision-api`'s `CurrentUser` (component-scanned) autowires it by type as its one constructor dependency and hands it to every controller (`AssetController`, `SimulationController`) that needs to attribute a change — every asset created through the running app is owned by this one fixed principal until Phase 6 introduces real accounts.

## Gotchas

- **Stale `target/` can lie.** This module's and its dependencies' `target/` directories can hold classes compiled *before* the asset-model refactor. A non-`clean` `mvn test`/`test-compile` here can report "Nothing to compile — all classes are up to date" and silently reuse pre-refactor classes, masking real compile failures. Always `mvn clean` before trusting a build result here; if a dependency (e.g. `vision-application`, `vision-api`) changed since the last full build, also re-run `./mvnw -B clean install -DskipTests -DskipWeb=true` at the repo root first so `-pl station/vision-app`'s local-repo-resolved dependencies aren't stale either.
- `SimStreamSmokeTest` needs `vision.publish.enabled=false` for determinism even though `RecordingStreamPublisher`'s `@Primary` would already win autowiring — belt-and-suspenders against ever depending on mediamtx being reachable.
- `SimulatedTelemetrySource`'s faster-than-1Hz test seam (`SimulatedTelemetrySource(long periodMillis)`, package-private in `com.drones.vision.adapter.simulation` — see adapter-simulation/MODULE.md) is **not reachable** from this module's `com.drones.vision.app` test package, and `WiringConfiguration` always wires the real public no-arg (1Hz) constructor. `SimStreamSmokeTest`'s telemetry assertions therefore poll `TelemetryRepositoryPort` at the real cadence with a bounded 15s wait rather than a fast interval — still fast in practice (the source's first sample fires with zero initial delay, the second ~1s later), but don't assume sub-second telemetry assertions are achievable here the way `SimulatedTelemetrySourceTest` achieves them in adapter-simulation.
- **No `@AutoConfigureMockMvc` on this Spring Boot 4.1.0 classpath**: `spring-boot-starter-test` here resolves `spring-boot-test`/`spring-boot-test-autoconfigure`, but the latter no longer bundles web-mvc test support (verified by inspecting both jars — no `AutoConfigureMockMvc` class in either, and `spring-boot-webmvc` itself, the module that now hosts the split-out `WebMvcAutoConfiguration`, has no test-support classes either). `FileSimulationSmokeTest` builds `MockMvc` by hand instead: `@Autowired WebApplicationContext` + `MockMvcBuilders.webAppContextSetup(context).build()` in a `@BeforeEach`, which needs no extra dependency and works identically for a full `@SpringBootTest` context.
- **The root `pom.xml`'s `<dependencyManagement>` needed a version pin for `adapter-mjpeg`** before this module's `pom.xml` could declare an unversioned dependency on it (every internal module dependency here relies on the parent's `dependencyManagement` for its version, per house style) — the module-creation task that added `video-input/mjpeg` (and its row in the `adapters/pom.xml` aggregator) hadn't added this entry yet, since nothing depended on the module at the time. Added alongside the other adapter entries; watch for the same gap with any future adapter module until something outside `adapters/` first depends on it. **Not needed for `adapter-cv-grpc`** (docs/plans/done/MVP1-PLAN.md §C7 bullet 4): the root pom already carried its version-pin entry from when the module skeleton was created, so this module's `pom.xml` only needed the plain dependency declaration.
- **An explicit `@Bean(destroyMethod = "close")` fails hard, at bean-creation time, on this Spring version if the actual runtime type doesn't have that method.** Originally discovered on `CvWiring#detectionPort` (whose return type varies: `GrpcDetectionPort` has a public no-arg `close()`, but the `vision.cv.enabled=false` branch's `NoopDetectionPort` does not) — every context that resolved the `NoopDetectionPort` branch failed to start with `BeanDefinitionValidationException: Could not find a destroy method named 'close' on bean with name 'detectionPort'`; this Spring Boot 4.1/Framework 7 version validates an *explicitly named* destroy method eagerly, unlike the description of `hasDestroyMethod`'s leniency in older Spring versions. `@Bean`'s **default** destroy-method value, `"(inferred)"` (i.e. simply omitting the attribute), tolerates this instead: it reflectively looks for a public no-arg `close()`/`shutdown()` on the concrete instance at shutdown time and silently does nothing if neither exists.
- **FIXED-FORWARD (docs/plans/done/CV-TRAINING-PLAN.md §7/§8, Phase 2 T9): relying on that same destroy-method *inference* stopped being correct once `detectionPort`'s channel became shared.** `detectionPort` used to omit `destroyMethod` entirely, letting inference find and call `GrpcDetectionPort#close()` at context shutdown (which shut its own privately-built channel down — fine when nothing else used it). Now that `detectionPort` and `TrainingWiringConfiguration#modelRegistryPort` share one `cvGrpcChannel` bean, that same inferred `close()` call would shut the *shared* channel down out from under `GrpcModelRegistryPort` (which has no close method of its own and expects the channel to outlive it) whenever both `vision.cv.enabled` and `vision.training.enabled` are `true`. Fixed by giving `detectionPort` an **explicit empty** `@Bean(destroyMethod = "")` — Spring's own documented way to opt a bean out of destroy-method inference entirely (distinct from the *naming* problem the bullet above describes: an empty string disables the search altogether rather than naming a method that must exist) — and moving channel ownership onto `cvGrpcChannel`'s own `@Bean(destroyMethod = "shutdown")` instead. Safe for the `NoopDetectionPort` branch too, which never had a destroy method to infer in the first place. If a bean's *cleanup* is ever handed off to something else it doesn't own (a shared collaborator, a bean built elsewhere), prefer an explicit empty `destroyMethod` over trusting inference to stay harmless — inference doesn't know the difference between "this instance's own resource" and "a resource it was merely lent."
- **FIXED (docs/plans/done/REMOTE-CV-PLAN.md P0 item 1): a gRPC channel's graceful shutdown (GOAWAY) used to be able to throw `NoSuchMethodError` internally** — previously observed as `Caught Throwable from listener onGoAwayReceived` / `NoSuchMethodError: io.grpc.internal.ManagedClientTransport$Listener.transportShutdown` logged (at ERROR, by netty's own internal exception guard) during `CvDetectionE2ETest`'s teardown, when the in-test server's `Server#shutdownNow()` sends the client a GOAWAY. Root cause was the same `io.grpc:grpc-core` version skew documented in full in adapter-cv-grpc/MODULE.md's Gotchas: this repo's `dependencyManagement` pinned `io.grpc:grpc-netty-shaded`/`grpc-protobuf`/`grpc-stub` to `${grpc.version}`=1.64.0 but did not pin `io.grpc:grpc-core` itself, so `spring-boot-starter-parent`'s imported `grpc-bom` (which manages `grpc-core` to 1.80.0) won, and `grpc-netty-shaded`'s 1.64.0-compiled bytecode broke against the mismatched 1.80.0 `grpc-core` internal API on this one shutdown notification path. **Fixed at the root pom**: the root `pom.xml`'s `dependencyManagement` now imports `io.grpc:grpc-bom:${grpc.version}` (scope `import`) before the individual artifact pins, aligning the whole `io.grpc` family to `1.64.0` everywhere, including here — `vision-app` is the first module whose effective BOM chain includes both `spring-boot-dependencies`' `grpc-bom` *and* a real network `GrpcDetectionPort` channel in the same JVM, so it was the first place this specific GOAWAY-path manifestation was observed, but the fix lives at the root and applies to every module uniformly. **Verified** via `./mvnw -B -pl station/vision-app dependency:tree -Dverbose | grep grpc`: every `io.grpc:*` artifact (`grpc-core`, `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`, `grpc-api`, `grpc-context`, …) resolves to `1.64.0` with no `(version managed from …)` skew, and the full `-pl station/vision-app test` suite (including `CvDetectionE2ETest`'s real-TCP-server teardown) stays green. Even before this fix, no test here actually *failed* because of it — the exception was caught by netty's own listener-invocation guard and logged, not propagated — but it was a latent correctness risk for a real `cv-service`'s graceful restart that is now closed.
- **This module's locally-installed `adapter-cv-grpc` jar can be stale/empty** if it was ever `mvn install`ed before real classes existed in it (e.g. at module-skeleton-creation time) and never reinstalled afterward — `~/.m2/repository/com/drones/adapter-cv-grpc/<version>/adapter-cv-grpc-<version>.jar` can contain only `META-INF/` with zero compiled classes, which surfaces here as `package com.drones.vision.adapter.cvgrpc does not exist` / `cannot find symbol: class GrpcDetectionPort` when compiling `WiringConfiguration`/`DetectionSessionCleanupEventPublisher` — a confusing error since the source obviously exists and the dependency is declared correctly. Fix: `./mvnw -B -pl cv/grpc install` (scoped, not reactor-wide) to rebuild and reinstall it with its real classes before retrying `-pl station/vision-app`.

## Status

*Entries below predate docs/plans/active/DOMAIN-SEPARATION-W1.md §15's W1.6b and cite the god-port*
*`LiveUpdatePublisherPort`/its single `liveUpdatePublisherPort` bean by the names that were correct*
*when each wave landed. That port no longer exists — deleted and split into five per-context ports*
*(`FleetLiveUpdatePort`, `TelemetryLiveUpdatePort`, `DetectionLiveUpdatePort`, `MapLiveUpdatePort`,*
*`EventLiveUpdatePort`), each with its own `@Bean` method on `ApplicationServiceWiring`; see "Bean*
*inventory"/"Server-push data plane" above for the current shape. The behavioral claims in every*
*entry below are otherwise unaffected by the split.*

docs/plans/done/NAV-IA-REDESIGN-PLAN.md **Wave 4, F8 done** (replay library, vision-app half): `InMemoryAssetUsageRepository`
(devsupport) gained `findRecent(int)` — see its own devsupport-table entry above — and a new
`usageService` `@Bean` (`ApplicationServiceWiring`, see the bean-inventory entry above) assembles
`DefaultUsageService` from the two already-wired `assetUsageRepositoryPort`/`assetRepositoryPort`
beans, no new property/gate. This is the one implementation the running dev app actually exercises
(`vision.persistence.enabled=false` by default) — verified end to end via `POST /api/assets` +
start/stop a stream, then `GET /api/usages`, see this task's report for the real `curl` output. New
`InMemoryAssetUsageRepositoryTest` (2 tests: fleet-wide newest-first-bounded-by-limit, empty when
nothing stored) — no dedicated test existed for this devsupport class before. `./mvnw -B -pl
station/vision-app -am -DskipWeb test`: full reactor-adjacent assembly build green (all adapters + vision-api
+ vision-app), `vision-app`'s own suite **203/203 green** (up from 201).

docs/plans/done/REMOTE-CV-PLAN.md **P0 item 1 + P1 item 5 done** (root-pom `grpc-core` version-skew fix + `GrpcDetectionPort` wire-tuning knobs promoted to `vision.cv.*` properties):

1. **P0 item 1** — the root pom's `io.grpc:grpc-bom` import (root `pom.xml`, out of this module's own file scope but reached by this module's classpath) fixes the `grpc-core`/`grpc-netty-shaded` version skew — see the rewritten Gotchas entry above and adapter-cv-grpc/MODULE.md's own Gotchas for the full mechanism/verification. No `vision-app` source change was needed for this half; verified here via `./mvnw -B -pl station/vision-app dependency:tree -Dverbose | grep grpc` (every `io.grpc:*` line resolves to `1.64.0`, no skew) and a full from-`clean` `-pl station/vision-app test` run staying green.
2. **P1 item 5** — `VisionCvProperties` gained `detectWidth`/`jpegQuality` (validated in the compact constructor, same manual-`if`/`throw` style as `endpoint`); `CvWiring#detectionPort` threads both into `GrpcDetectionPort`'s new `(host, port, detectWidth, jpegQuality)` constructor; `application.yaml` documents `vision.cv.detect-width`/`vision.cv.jpeg-quality` (commented out, defaults `640`/`0.8`) next to the existing `vision.cv.*` block. New `VisionCvPropertiesTest` (8 tests, no Spring context) covers the validation directly; `CvEnabledWiringTest` extended with non-default `vision.cv.detect-width=480`/`vision.cv.jpeg-quality=0.6` overrides to prove the kebab-case Spring binding and bean construction both work end to end (see Test inventory for what it can and can't observe without a `GrpcDetectionPort` getter).
3. adapter-cv-grpc's own `GrpcDetectionPortTest` grew from 16 to 24 tests for this task (constructor validation/configured-width coverage, plus the real dead-endpoint-then-server-starts recovery test P0 item 1 unblocked) — see adapter-cv-grpc/MODULE.md, out of this module's own test suite but relevant context for why `vision.cv.*`'s defaults are safe to trust.

**Deviations from the brief**: none. `GrpcDetectionPort` exposes no getter for `detectWidth`/`jpegQuality` (by design — they're wire-tuning internals, not part of `DetectionPort`'s contract), so this module's own wiring test can only prove the property values bind and the bean constructs without throwing, not that the exact numbers reached the adapter; the adapter's own test suite is what actually exercises the values' effect on wire behavior.

**Green.** Verified with a from-`clean` `./mvnw -B -pl station/vision-app test`: 59 tests, all passing (up from 53 — docs/plans/done/MVP2-PLAN.md E-a added `devsupport.InMemoryDetectionEventRepositoryTest` (6 new) and extended `AssetWiringTest` with 2 more `assertNotNull` calls, no new test method) — `VisionApplicationTests` 1, `DiscoveryDisabledWiringTest` 2, `FileSimulationSmokeTest` 2 (docs/main/CYCLES-PLAN.md §1c, plus §7/CT-a's route smoke test), `CvDetectionResilienceSmokeTest` 1, `DiscoveryWiringTest` 1, `ArchitectureTest` 5, `RtspSimulationDockerE2ETest` 1 (docs/main/CYCLES-PLAN.md §3, docker-gated — ran green, not skipped, in the environment this was last verified in), `OverlayWiringTest` 1 (new, docs/plans/done/MVP1-PLAN.md §C8), `CvWiringTest` 2, `CvDetectionEndpointE2ETest` 1 (new, docs/plans/done/MVP1-PLAN.md §C8), `SimStreamSmokeTest` 1, `AssetWiringTest` 5 (docs/plans/done/MVP2-PLAN.md X-a added 2 new test methods for the mavlink `TelemetrySourcePort`/`FeedTransmitterPort` beans, see Test inventory; `everyAssetModelServiceAndRepositoryBeanIsRegistered` also asserts `ReplayService` since R-a2 and `DetectionEventRepositoryPort`/`EventController` since E-a), `PublishWiringTest` 6 (docs/plans/done/MVP2-PLAN.md X-b: up from 5, one new `v4l2` `VideoSourcePort` wiring test — see Test inventory), `CvDetectionE2ETest` 1, `CvEnabledWiringTest` 2, `MjpegSimulationSmokeTest` 1 (docs/main/CYCLES-PLAN.md §5, no docker needed), `devsupport.InMemoryDetectionRepositoryTest` 6 (new, docs/plans/done/MVP1-PLAN.md §C8), `devsupport.InMemoryDetectionEventRepositoryTest` 6 (new, docs/plans/done/MVP2-PLAN.md §E, E-a), `PersistenceWiringTest` 7 (docs/plans/done/MVP2-PLAN.md P-a + P-b, up from 4), `PersistenceWiringConfigurationTest` 7 (docs/plans/done/MVP2-PLAN.md P-a + P-b, up from 4). Reconfirmed stable across two consecutive from-`clean` runs, plus the exact scoped command `./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app test`.

docs/plans/done/MVP2-PLAN.md **V-e done** (LAN WHEP + optional burn-in skip — two independent, small deliverables; see docker-compose.yml/.env.example for the compose half, and vision-domain/vision-application/vision-api's own MODULE.mds for the burn-in domain field/`StreamPipeline` gate/DTO ripple):

1. **LAN WHEP** — `docker-compose.yml`'s `MTX_WEBRTCADDITIONALHOSTS` parametrized via `${VISION_WEBRTC_HOST:-127.0.0.1}`; `.env.example` documents the new `VISION_WEBRTC_HOST` variable (commented out by default, so a plain `cp .env.example .env` keeps today's same-host-only behavior unless an operator deliberately uncomments and edits it). See the "WHEP viewing" section above for the full LAN-IP-discovery/env-encoding writeup. `docker compose config` (both `--env-file .env.example` as-is, and with `VISION_WEBRTC_HOST=192.168.0.104` injected) validated clean — see below.
2. **Optional burn-in skip** — **no new `vision-app` property was added.** The task brief asked to add one "if such a pattern exists (follow precedent; if none, `PipelineConfig` default + API override suffices — document)": no existing `vision-app` property overrides any other `PipelineConfig.defaults()` field either (`confidenceThreshold`/`inferenceFps`/`labelFilter`/`overlayTelemetry`/`eventRule` are all API-override-only today, nothing global-configurable at the `vision-app` layer) — `overlayBurnIn` follows that same precedent exactly rather than inventing a new one, so the whole feature is domain default (`true`) + `vision-api`'s new `StartStreamRequest#overlayBurnIn`/`StartAssetStreamRequest#overlayBurnIn` per-stream override (see station/vision-api/MODULE.md). No `WiringConfiguration`/`application.yaml` change was needed or made — `PipelineConfig` already flows through this module's wiring as an opaque per-call argument (`DefaultStreamService#start(DeviceId, PipelineConfig)`), so the new field needed no plumbing here at all, the same way `eventRule` (E-a) needed none either.
3. **No new/changed `vision-app` tests** — nothing in this module's own bean graph or wiring changed (see point 2), so `AssetWiringTest`/`PublishWiringTest`/etc. needed no new assertions; the 59-test count above is unchanged by this task.

`docker compose config` validation (both deliverables together): ran clean both with `VISION_WEBRTC_HOST` unset (resolves `MTX_WEBRTCADDITIONALHOSTS: 127.0.0.1`, unchanged from before this task) and with it set to a sample LAN IP (resolves `MTX_WEBRTCADDITIONALHOSTS: 192.168.0.104`) — confirming the interpolation syntax is valid Compose and the fallback genuinely preserves today's default. No `docker compose up` was attempted (out of scope — no runtime behavior in either deliverable needed a live mediamtx to verify; deliverable 1 is pure YAML/env substitution, deliverable 2 is proven by `vision-application`'s own `StreamPipelineTest`).

**Deviations from the brief**: none in shape. The one judgment call, made explicit above: no vision-app-level global-default property for `overlayBurnIn`, since no precedent for one exists for any other `PipelineConfig` field — inventing one here would have been the first of its kind rather than following an established pattern.

docs/plans/done/MVP2-PLAN.md **E-a done** (detection events, the `vision-app`-scoped wiring half — see vision-domain/vision-application/vision-api's own MODULE.mds for the domain records/port, rule engine, and REST surface):

1. `detectionEventRepositoryPort` (`InMemoryDetectionEventRepository`, devsupport) added as an unconditional `@Bean` — no persistence toggle, same posture as `auditTrailPort` (both out of `docs/plans/done/MVP2-PLAN.md` P-a/P-b's persistence scope); own ring cap `MAX_EVENTS_PER_STREAM`=500 events/stream (see the devsupport table above).
2. `streamService`'s `@Bean` method grew one parameter (`DetectionEventRepositoryPort`), passed through to `DefaultStreamService`'s new 9-arg canonical constructor — every `StreamPipeline` this service starts now gets a per-stream `DetectionEventEngine` built from `usageTracker` (already an existing collaborator here) plus this one new port.
3. `EventController` (vision-api, component-scanned) needs no `WiringConfiguration` entry of its own beyond `detectionEventRepositoryPort` existing as a bean — it resolves its one constructor dependency automatically, exactly like every other controller in this codebase.
4. `AssetWiringTest` extended (two more `assertNotNull` calls in the existing bean-inventory test, no new test method — same judgment call R-a2 made for `replayService`); new `devsupport.InMemoryDetectionEventRepositoryTest` (6 tests, mirroring `InMemoryDetectionRepositoryTest`'s shape) covers the ring/upsert behavior directly.

**Deviations from the brief**: none in shape. `ArchitectureTest`'s 5 rules stayed green with no changes needed — `DetectionEventRepositoryPort`/`DetectionEvent` (vision-domain), `DetectionEventEngine` (vision-application, package-private), `EventController`/`DetectionEventResponse` (vision-api), and `InMemoryDetectionEventRepository` (vision-app devsupport) all landed in exactly the layer their existing counterparts (`DetectionRepositoryPort`/`DetectionResult`/`DetectionExtrapolator`/`StreamController`/`InMemoryDetectionRepository`) already occupy, so no new dependency-direction rule was needed. One thing this task's brief explicitly asked about and could not close cheaply: **generic `Event`s (`PIPELINE_ERROR` etc.) are not exposed through `/api/events`** — `EventPublisherPort` has no read/query side at all (`LoggingEventPublisher` just logs) and `Event`'s shape shares nothing with `DetectionEvent`'s; see station/vision-api/MODULE.md's Gotchas for the full reasoning. E-b (events UI) should scope around this rather than assume pipeline errors already surface in the events feed.

docs/plans/done/MVP2-PLAN.md **X-b** ("USB/V4L2 camera RX", the `vision-app`-scoped wiring half — see adapter-v4l2/MODULE.md for the adapter itself, including the protocol/URI deviation writeup) is closed:

1. `v4l2VideoSource` (`V4l2VideoSource`, adapter-v4l2) bean added to `WiringConfiguration`, no-arg, following the exact `ffmpegVideoSource`/`mjpegVideoSource` shape — joins `videoSourceRegistry`'s `List<VideoSourcePort>` automatically, since it's a plain `@Bean`-returned instance of a type Spring already collects by interface.
2. `adapter-v4l2` dependency added to this module's `pom.xml`; the root pom's `<dependencyManagement>` version-pin entry was added in the same task (brand-new module, like `adapter-mavlink`'s X-a — no "module already existed, pin was missing" gap to hit, unlike `adapter-mjpeg`'s historical one).
3. `PublishWiringTest` extended (1 new test method, see Test inventory) rather than a new wiring-test class, mirroring `mjpeg`'s own §5 extension of the same test — a fourth `VideoSourcePort` protocol assertion added to the existing "all registered video sources" test class rather than a dedicated `V4l2WiringTest`.
4. **Deviation, corrected at the source**: `WiringConfiguration`'s doc-comment and this test both use `protocol="v4l2"`/`uri=file:/dev/videoN` (matching `adapter-discovery`'s real `V4l2Scanner` emission), not docs/plans/done/MVP2-PLAN.md X-b's originally-proposed `"usb"`/`v4l2://` shape — see adapter-v4l2/MODULE.md's "Deviation from the plan's brief" section for the full reasoning (in short: discovery results flow straight into device registration unchanged, so matching discovery's real emission, not the brief's guess, is what makes "Discover → register → stream" actually work).

**Deviations from the brief**: none beyond the protocol/URI correction above (itself explicitly invited by the task brief's own "verify against what adapter-discovery actually emits" instruction). No TX half exists or was expected — a local capture device has nothing to transmit to, the same n/a as `adapter-simulation`'s `sim`/`adapter-rtsp`'s `file` sources.

docs/plans/done/MVP2-PLAN.md **X-a** ("MAVLink telemetry RX + TX pair", the `vision-app`-scoped wiring half — see adapter-mavlink/MODULE.md for the adapter itself) is closed:

1. `mavlinkTelemetrySource` (`MavlinkTelemetrySource`, adapter-mavlink) and `mavlinkFeedTransmitter` (`MavlinkFeedTransmitter`, adapter-mavlink) beans added to `WiringConfiguration`, both no-arg, following the exact `simulatedTelemetrySource`/`mjpegFeedTransmitter` shapes — the former joins `usageTracker`'s `List<TelemetrySourcePort>` automatically, the latter joins `feedTransmitterRegistry`'s `List<FeedTransmitterPort>` automatically, since both are plain `@Bean`-returned instances of a type Spring already collects by interface.
2. `adapter-mavlink` dependency added to this module's `pom.xml`; the root pom's `<dependencyManagement>` version-pin entry was added in the same task (this is a brand-new module, so unlike `adapter-mjpeg`'s historical gap — see Gotchas — there was no "module already existed, pin was missing" situation to hit).
3. `AssetWiringTest` extended (2 new test methods, see Test inventory) rather than a new wiring-test class, mirroring the §5 `FeedTransmitterPort` precedent exactly for the RX side too (a new `List<TelemetrySourcePort>` autowired field/assertion).
4. **Not wired into `SimulationService`/`SimulationTransport`** — `SimulationTransport` (vision-application) has no `MAVLINK` variant, and vision-application was outside this task's file scope. Both new beans are fully constructed and reachable in a booted context today (register a `mavlink` telemetry device via the plain `POST /api/devices`/`POST /api/assets` APIs, or point `MavlinkFeedTransmitter`/a real SITL instance at it directly — see adapter-mavlink/MODULE.md's "Try it with a real SITL instance"); only the one-call `SimulationService.simulate(...)` convenience path doesn't yet know about `transport=mavlink`. This is the natural next task, in the same two-task shape as `adapter-mjpeg`'s own module-then-wiring split (docs/main/CYCLES-PLAN.md §5).

**Deviations from the brief**: none in shape. `scripts/demo.sh`/`docker-compose.yml` were deliberately left untouched, per this task's own brief — SITL usage is documented (adapter-mavlink/MODULE.md), not wired into the demo/compose infrastructure.

docs/plans/active/DRONE-INFRA-PLAN.md **I-b done** (plug-and-fly heartbeat discovery, the `vision-app`-scoped wiring half — see adapter-mavlink/MODULE.md for `MavlinkHeartbeatScanner` itself) is closed:

1. `mavlinkHeartbeatScanner` (`MavlinkHeartbeatScanner`, adapter-mavlink) bean added to `DiscoveryWiringConfiguration`, gated by the same `vision.discovery.enabled` property as its three siblings — constructed with the `mavlinkTelemetrySource` bean (`WiringConfiguration`, autowired across the two `@Configuration` classes by its concrete type — there's exactly one bean of that type, so no `@Qualifier` was needed) and a hardcoded well-known port, `MAVLINK_HEARTBEAT_SCAN_PORT = 14550` (`DiscoveryWiringConfiguration`'s own constant) — no new `vision.*` property, since none of `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner` has a per-scanner configuration property either.
2. No new dependency/pom change: `adapter-mavlink` was already a `vision-app` dependency since docs/plans/done/MVP2-PLAN.md X-a.
3. `DiscoveryWiringTest` extended (1 changed assertion, `Set.of("onvif","mdns","v4l2")` → `Set.of("onvif","mdns","v4l2","mavlink")`) rather than a new test class — mirrors how X-b/X-a extended `PublishWiringTest`/`AssetWiringTest` in place for a fourth/new registered instance of an existing collection-typed bean. `DiscoveryDisabledWiringTest` needed no change: it asserts zero `DeviceDiscoveryPort` beans exist when `vision.discovery.enabled=false`, which is still true with a fourth scanner sharing the same conditional.

**Deviations from the brief**: none. `./mvnw -B -pl drone-link/mavlink test` green twice consecutively (83/83, up from 79 — new `MavlinkHeartbeatScannerTest`, 4 cases). `./mvnw -B -pl station/vision-app test -DskipWeb=true` (run without `-am`, against already-installed sibling jars — see Gotchas) green, 87/87. The full `-am` build (`./mvnw -B -pl station/vision-app -am test -DskipWeb=true`) currently fails **before** reaching `vision-app` at all, in the unrelated `vision-web` module: its own `npm-test` execution is not gated by the `skipWeb` property (only `install-node-and-npm`/`npm-build` are — a pre-existing gap in `station/vision-web/pom.xml`, not touched by this task, out of file scope), and its Angular build fails on a pre-existing TypeScript error (`TS2677` in `src/app/core/telemetry/flight-state-logic.ts:308`) unrelated to any change in this task. Neither `vision-web` nor its `pom.xml` are in this task's scope; reported here rather than fixed, per this task's own brief ("a compile error elsewhere is NOT yours to fix").

docs/plans/done/MVP2-PLAN.md **R-a2** ("ReplayService wiring + usage→stream link", the `vision-app`-scoped half — see vision-domain/vision-application/adapter-persistence's own MODULE.mds for the rest) is closed:

1. `ApplicationServiceWiring#replayService` — a one-line `@Bean` (`new DefaultReplayService(assetUsageRepositoryPort, telemetryRepositoryPort, detectionRepositoryPort)`, all three collaborators already existing beans) — exactly the fix R-a's own writeup flagged as missing; `UsageTimelineController` (vision-api, component-scanned) now resolves in a booted context.
2. `AssetWiringTest` extended (no new test method, one more `assertNotNull` in the existing bean-inventory test) — the "wiring test per house style" this task's brief asked for, matching how `categoryService` itself has never needed a dedicated test class.
3. No other `vision-app` changes were needed: `AssetUsage`'s new `streamId` field, `UsageTracker`'s new `onStreamStarted` parameter, and `DefaultReplayService`'s new `DetectionRepositoryPort` constructor argument are all internal to vision-domain/vision-application — this module only had to supply the one new bean.

**Deviations from the brief**: none. One judgment call: the task brief asked for "a wiring test per house style" without specifying a new class — `AssetWiringTest` was extended rather than adding a `ReplayWiringTest`, since a single one-liner `@Bean` with no conditional branching doesn't warrant its own test class any more than `categoryService` (its closest shape-mirror) did.

docs/plans/done/MVP2-PLAN.md **L-a** (WebRTC/WHEP viewing URL beside HLS) is closed, scope confined to this module plus the small ripple through `vision-domain`/`video-output/publish-hls`/`vision-api` (see those modules' own MODULE.mds):

1. `VisionPublishProperties.Mediamtx` gained `whepBase: URI` (`@DefaultValue("http://localhost:8889")`, mirroring `rtspBase`/`hlsBase`'s `@DefaultValue`-plus-compact-ctor-fallback idiom exactly); `application.yaml`' shipped default overrides it to the host-mode `http://localhost:18889`, same collision-avoidance rationale as `hls-base`'s `18888` override (see "WHEP viewing" section above and the property's own inline comment).
2. `PublishWiring#streamPublisherPort` passes `mediamtx.whepBase()` as `MediamtxStreamPublisher`'s new third constructor argument (`whepViewBase`) alongside the pre-existing `rtspBase()`/`viewBase()` pair.
3. `docker-compose.yml` gained two new `mediamtx` port mappings (`18889:8889` WHEP HTTP, `8189:8189/udp` ICE media — the latter a mandatory 1:1 mapping, not renumbered) and an `MTX_WEBRTCADDITIONALHOSTS=127.0.0.1` env var so ICE candidates are browser-reachable at all from a containerized mediamtx; `vision-app`'s own environment gained `VISION_PUBLISH_MEDIAMTX_WHEP_BASE=http://localhost:18889`, deliberately not mirroring `hls-base`'s compose-internal-hostname override pattern (see "WHEP viewing" section above for why that pattern is specifically wrong for this one property, and the compose file's own comments for the full reasoning).
4. `PublishWiringTest` gained one new test (`whepUrlIsMediamtxsOwnAddressPerHostModeDefault`); no other test class needed changes.

**Deviations from the brief**: the brief's suggested compose env override ("mirroring the existing pair") was deliberately *not* applied literally — `VISION_PUBLISH_MEDIAMTX_WHEP_BASE` does not mirror `VISION_PUBLISH_MEDIAMTX_HLS_BASE`'s compose-internal-hostname value (`http://mediamtx:8888`) because `whep-base` is browser-facing (unlike `hls-base`, which `vision-app` itself consumes server-side) — see "WHEP viewing" above for the full reasoning; the value that's actually correct happens to equal the property's own default, so the override was added anyway, purely so the reasoning is visible at the compose layer. Full LAN/cross-host WebRTC reachability (a second IP in `MTX_WEBRTCADDITIONALHOSTS`) was deliberately left unconfigured, since the correct value is host-specific — documented as a known, honest limitation rather than guessed at. vision-web (the WHEP-consuming player, U3) was untouched, per this task's explicit scope boundary.

docs/plans/done/MVP2-PLAN.md **P-b** ("history persistence: usages, telemetry, detections", the vision-app wiring half — see adapter-persistence/MODULE.md for the adapter itself, including its Retention section) is closed:

1. `PersistenceWiringConfiguration` gained three more `@Bean` methods — `assetUsageRepositoryPort`/`telemetryRepositoryPort`/`detectionRepositoryPort` — following the exact P-a pattern (`ObjectProvider<EntityManagerFactory>` branch on `VisionPersistenceProperties#enabled()`); the corresponding `@Bean` methods were removed from `WiringConfiguration` (see "Persistence wiring" above, "Removed from `WiringConfiguration`").
2. No changes to `VisionPersistenceProperties` — the three new beans use each `Jpa*Repository`'s default-retention-cap constructor, not a new configurable property (see "Persistence wiring" above and adapter-persistence/MODULE.md's Retention section for why).
3. No `docker-compose.yml` changes needed: `vision.persistence.enabled=true` already routes all six repository ports through `PersistenceWiringConfiguration` now, so the compose stack set up for P-a already runs P-b's history persistence too — nothing to add.
4. `PersistenceWiringTest`/`PersistenceWiringConfigurationTest` both extended (3 new assertions/tests each) rather than duplicated into P-b-specific classes — same two classes, same both-directions-of-the-toggle split, now covering all six ports.

**Deviations from the brief**: none in shape. `AuditTrailPort` — mentioned as a P-b target in vision-app's devsupport table before this task, written when P-a landed and speculatively assumed P-b would cover it too — was **not** implemented here: neither docs/plans/done/MVP2-PLAN.md's P-b bullet nor this task's brief mentions it (both say "usages, telemetry, detections" only), so it was left exactly as before (unconditional `InMemoryAuditTrail`) and the devsupport table's stale forward-reference corrected instead of acted on. See adapter-persistence/MODULE.md's Status for two other faithfully-mirrored-not-fixed quirks inherited from the in-memory reference implementations (`findByUsage`'s earliest-not-latest `limit`, `DetectionQuery#to`'s actual inclusive semantics vs. its javadoc).

docs/plans/done/MVP2-PLAN.md **P-a** ("fleet persistence: categories/devices/assets/ownership in Postgres", the vision-app wiring half — see adapter-persistence/MODULE.md for the adapter itself) is closed:

1. `adapter-persistence` dependency added to this module's `pom.xml`; the root pom's `<dependencyManagement>` entry already existed (created with the module skeleton, like `adapter-cv-grpc`'s/`adapter-overlay`'s), so no root-pom change was needed.
2. `VisionPersistenceProperties` added (`vision.persistence.enabled` default `false`, `jdbc-url`/`username`/`password`), mirroring `VisionCvProperties`'s record/`@DefaultValue` idiom; `application.yaml` documents all four keys at their defaults.
3. New `PersistenceWiringConfiguration` (split out from `WiringConfiguration`, `DiscoveryWiringConfiguration`-style) wires `categoryRepositoryPort`/`deviceRepositoryPort`/`assetRepositoryPort` to `adapter-persistence`'s `Jpa*Repository`s when enabled, else the unchanged devsupport in-memory fallbacks; the `EntityManagerFactory` bean itself is `@ConditionalOnProperty`-gated so it's never even attempted while disabled — see "Persistence wiring" above for the full mechanics and why this couldn't be a single if/else `@Bean` method like `detectionPort`.
4. `docker-compose.yml`'s `vision-app` service now depends on `postgres` (healthy) and sets `VISION_PERSISTENCE_ENABLED`/`VISION_PERSISTENCE_JDBC_URL`/`VISION_PERSISTENCE_USERNAME`/`VISION_PERSISTENCE_PASSWORD`, so `docker compose up` now runs with real fleet persistence — an asset registered before `docker compose restart vision-app` is still there after.
5. Two new wiring tests (`PersistenceWiringTest`, `PersistenceWiringConfigurationTest`) cover both wiring directions — see Test inventory above for why the enabled branch is a plain unit test rather than a `@SpringBootTest`.

**Deviations from the brief**: none in shape. One judgment call: the task brief suggested the toggle test could be "a context test with the property set, JPA beans mocked/no docker needed" — a literal reading would put `vision.persistence.enabled=true` on a real `@SpringBootTest`, but that property alone can't be satisfied without also making `persistenceEntityManagerFactory` not actually run (mocking a bean *method's own body* isn't something Spring context tests can do), so `PersistenceWiringConfigurationTest` calls `PersistenceWiringConfiguration`'s `@Bean` methods directly instead — same assertions, no Spring context needed for the enabled branch, and `PersistenceWiringTest`'s real `@SpringBootTest` still proves the disabled default end to end including "no `EntityManagerFactory` bean exists at all."

docs/plans/done/MVP1-PLAN.md **§C8 bullets 2-3** ("wire the overlay renderer into the pipeline; expose recent detections over REST", the vision-app half) is closed:

1. `overlayRenderer` bean (`Java2DOverlayRenderer`, adapter-overlay) added, unconditional (no enable flag — see "Overlay wiring" above for why), threaded into `streamService`'s new `OverlayPort` parameter. `adapter-overlay` dependency added to this module's `pom.xml`; the root pom's version pin already existed, so no root-pom change was needed (like `adapter-cv-grpc`, unlike `adapter-mjpeg`'s gap).
2. `InMemoryDetectionRepository` (devsupport, already existed for C7's `StreamPipeline#detectionRepositoryPort` save path) hardened into a proper per-stream append-only ring: a `Deque<DetectionResult>` per `StreamId`, capped at `MAX_RESULTS_PER_STREAM`=1000 (oldest evicted on overflow), `query` now sorts newest-first before applying `limit` rather than returning insertion order — backs `StreamController#detections` (vision-api) with sensible "most recent N" semantics under the cap.
3. `OverlayWiringTest` (new) asserts the bean resolves correctly; `CvDetectionEndpointE2ETest` (new) closes the loop end-to-end (real gRPC server returning real detections → REST endpoint returns the mapped rows); `devsupport.InMemoryDetectionRepositoryTest` (new) unit-tests the hardened repository directly, including the ring-cap eviction.

**Deviations**: none from the brief's shape. The one judgment call: `InMemoryDetectionRepository` already existed (created during C7 for `StreamPipeline`'s save path) rather than needing to be created from scratch as the task's phrasing anticipated ("add one in the devsupport style if not") — it was hardened in place (ring cap + newest-first sort) instead of left as its pre-C8 unbounded/insertion-order shape, since serving a "recent detections" REST endpoint from an unbounded, arbitrarily-ordered in-memory list would have been a correctness gap for exactly the demo scenario this feature targets.

docs/plans/done/MVP1-PLAN.md **§C7 bullet 4** ("Wiring: `vision.cv.enabled` + `vision.cv.endpoint`; wiring tests both ways") is closed:

1. `VisionCvProperties` added (`vision.cv.enabled` default `false`, `vision.cv.endpoint` default `localhost:50051`), mirroring `VisionPublishProperties`'s record/`@DefaultValue` idiom; `application.yaml` documents both keys at their defaults, matching this file's existing per-property-block commenting style.
2. `CvWiring#detectionPort` now selects `GrpcDetectionPort` (adapter-cv-grpc) when enabled, else keeps `NoopDetectionPort` — today's behavior is the unconditional default, unchanged. `station/vision-app/pom.xml` gained the `adapter-cv-grpc` dependency; the root pom's `<dependencyManagement>` version pin for it already existed (unlike `adapter-mjpeg`'s gap, see Gotchas), so no root-pom change was needed.
3. Session cleanup: a new package-private decorator, `DetectionSessionCleanupEventPublisher` (`EventPublisherPort`), wraps the base `LoggingEventPublisher` only in the enabled branch (`ApplicationServiceWiring#eventPublisherPort`) and calls `GrpcDetectionPort#streamEnded` on every `STREAM_STOPPED` event — see "CV inference wiring" above for the full rationale. No changes were needed (or made) to vision-application or vision-domain to wire this.
4. Four new tests (`CvWiringTest`, `CvEnabledWiringTest`, `CvDetectionE2ETest`, `CvDetectionResilienceSmokeTest`) cover both wiring directions plus a real (in-test, loopback-TCP, no docker/Python) echo round trip proving frames actually reach a gRPC server and the session cleans up on stop, and a resilience smoke test proving an unreachable configured endpoint degrades exactly like the no-op port.

**Deviations from the brief**: none in shape; two things surfaced only while implementing that are worth flagging (both documented in Gotchas above): (a) an explicit `@Bean(destroyMethod = "close")` on `detectionPort` turned out to fail hard on this Spring version for the `NoopDetectionPort` branch — solved by relying on `@Bean`'s default inferred destroy method instead, which is silently tolerant; (b) this module surfaced a pre-existing `io.grpc:grpc-core` version skew (1.64.0 pinned by this repo vs. 1.80.0 from Spring Boot's imported `grpc-bom`, already flagged as an accepted risk in adapter-cv-grpc/MODULE.md for its test-only `grpc-inprocess` dependency) as a `NoSuchMethodError` logged during a real gRPC channel's graceful GOAWAY handling — caught internally, doesn't fail any test here, but is a latent risk for a real `cv-service`'s graceful restart; fixing it means pinning `io.grpc:grpc-core` in the root `pom.xml`, which is outside this task's `station/vision-app/**` scope.

docs/main/CYCLES-PLAN.md **§3** ("`FeedTransmitterPort` + RTSP TX", application/API/wiring half) is closed: `rtspFeedTransmitter` bean wired from the existing `VisionPublishProperties.Mediamtx#rtspBase()` (no new property needed — justified above), and the docker-gated `RtspSimulationDockerE2ETest` proves the full `transport=rtsp` flow (file → RTSP wire → mediamtx → `FfmpegVideoSource` RX → pipeline → recording publisher, then `DELETE` tears down both the stream and the transmit thread) actually runs green in this environment.

docs/main/CYCLES-PLAN.md **§5** ("mjpeg TX/RX pair", transport/wiring half — the module itself landed in a prior task) is closed: `mjpegVideoSource`/`mjpegFeedTransmitter` beans added (the latter with `destroyMethod = "close"`), `simulationService`'s single `FeedTransmitterPort` dependency generalized into the new `feedTransmitterRegistry` bean (`FeedTransmitterRegistry`, vision-application) collecting both transmitters, and the docker-free `MjpegSimulationSmokeTest` proves the full `transport=mjpeg` flow (file → HTTP MJPEG wire → `MjpegVideoSource` RX → pipeline → recording publisher, then `DELETE /api/simulations/{assetId}` tears down both the stream and the TX/RX threads) entirely in-process.

docs/main/CYCLES-PLAN.md **§7, CT-a** ("configurable telemetry flight plans", smoke-test half — no new beans needed here, `WiringConfiguration` is untouched) is closed: `FileSimulationSmokeTest`'s new route test (see Test inventory) proves `POST /api/simulations`'s `telemetry` object actually flies the simulated drone's telemetry along the requested route through the full production wiring, not just at the adapter/application/API unit-test level.

docs/plans/done/ASSET-MODEL-PLAN.md **M4** ("Wiring, seed data, full verify, live demo", scope `station/vision-app/src/**`) is closed:

1. `ListCategoriesUseCase` implementation added — `CategoryService` in vision-warehouse (see contexts/vision-warehouse/MODULE.md), not this module, since it holds no adapter-specific logic.
2. devsupport gained `InMemoryCategoryRepository` (seeded per §4), `InMemoryAssetRepository`, `InMemoryAssetUsageRepository`, `InMemoryTelemetryRepository`, and `DevPrincipal` (fixed dev-mode `Ownership`).
3. `WiringConfiguration` now wires all of it: the four new repository-port beans, `simulatedTelemetrySource` (a `TelemetrySourcePort` bean, collected into `usageTracker`'s `List<TelemetrySourcePort>`), `usageTracker`, `streamService` switched to `StreamService`'s 7-arg ctor (passing `usageTracker`), `assetService`, `categoryService`. `AssetController`/`CategoryController` (vision-api, component-scanned) now resolve every constructor dependency. (Superseded since: ownership moved out of `assetService`'s constructor into the standalone `actingOwnership` bean/`CurrentUser`; a separate `assetStreamService` bean existed at the time of that rewrite, was later folded into `AssetService`, and — docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e — is back as its own bean again, for a different reason this time (perception owning stream-starting, not a constructor-arity trim). See the Bean inventory table above for the current shape.)
4. `SimStreamSmokeTest` rewritten for the extended M4 scenario (asset-first create/start/observe-frames-and-telemetry/stop/assert-closed-usage) — see Test inventory. The stale `DeviceType`/4-arg-`Registration` compile failure that previously blocked all 6 test classes in this module is gone.
5. New `AssetWiringTest` asserts every M4 bean is registered.

Full reactor: `./mvnw -B clean install -DskipTests -DskipWeb=true` succeeds (all 15 modules, main+test compile). Final `./mvnw -B clean verify` (with the Angular build, no `-DskipWeb`) is the last step of the M4 task — see the task's own report for that outcome, since it covers modules beyond this one's scope (notably `adapter-publish-hls`, being fixed by a parallel task).

docs/plans/done/MVP3-PLAN.md **C-a done** (backend enablers for the command point, the `vision-app`-scoped wiring half — see vision-application/vision-api's own MODULE.mds for the two collaborators/REST surface):

1. `fleetSummaryService` `@Bean` added to `WiringConfiguration` (one-liner, mirrors `replayService`'s shape) — `FleetController` (vision-api, component-scanned) now resolves its one constructor dependency in a booted context.
2. No new bean was needed for the snapshot endpoint — `StreamController#snapshot` (vision-api) reaches the new frame straight through the already-wired `streamService` bean (`StreamService#latestFrame`, vision-application); nothing in this module's wiring changed for that half of C-a at all.
3. `AssetWiringTest` extended (two more `assertNotNull` calls in the existing bean-inventory test, no new test method — same judgment call E-a/R-a2 made for their own small, focused beans).

`./mvnw -B -pl station/vision-app test`: **59/59 green, unchanged count** — this task added zero new test *methods* anywhere in this module (only two more assertions inside `AssetWiringTest`'s existing inventory test), so the total stays exactly what it was before C-a. `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new type (`FleetSummaryService`/`DefaultFleetSummaryService`/`FleetSummary`/`CategoryCounts`/`AssetAttention` in vision-application, `FleetController`/`SnapshotJpegEncoder`/three response DTOs in vision-api) landed in exactly the layer its existing counterparts already occupy.

**Deviations from the brief**: none. The scoped ripple check (`./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app test`) confirms all four modules stay green together: vision-domain 142/142 (untouched), vision-application 285/285 (was 269), vision-api 170/170 (was 155), vision-app 59/59 (unchanged) — no domain changes were needed or made anywhere in this task.

docs/plans/done/REALTIME-PLAN.md **§4 done** (server-push data plane, `vision-app`-scoped wiring half — backend of Phase R-c; see vision-domain/vision-application/vision-api's own MODULE.mds for the port/emission seams and the SSE registry itself):

1. `VisionLiveProperties` added (`vision.live.enabled` default `true` — this feature defaults **on**, unlike CV/persistence); `application.yaml` documents the key.
2. `ApplicationServiceWiring#liveUpdatePublisherPort` selects the real `LiveUpdateRegistry` (vision-api) vs. the new devsupport `NoopLiveUpdatePublisher`, via `ObjectProvider<LiveUpdateRegistry>` (same conditionally-absent-bean idiom `PersistenceWiringConfiguration` already uses) — threaded unconditionally into `usageTracker`/`streamService`. `eventPublisherPort`/`auditTrailPort` each gained one more wrapping layer (`LiveUpdateEventPublisher`/`LiveUpdateAuditTrail`, both new, package-private, this module) applied only when `vision.live.enabled=true` — see "Server-push data plane" above for the full reasoning on why these two ports, specifically, are the seams for "assets/devices/streams lifecycle" rather than a new constructor parameter on `DefaultAssetService`/`DefaultDeviceService`.
3. **Two real bugs found and fixed while wiring this** (both documented in detail in "Server-push data plane" above and station/vision-api/MODULE.md's own Gotchas): a genuine circular bean dependency (`assetService` → `auditTrailPort` → `liveUpdatePublisherPort` → `LiveUpdateRegistry` → `assetService`), fixed on the vision-api side (`ObjectProvider<AssetService>`); and a bean-type ambiguity (`LiveController`'s `LiveUpdateRegistry` constructor parameter matching two candidate bean names), fixed with `@Qualifier("liveUpdateRegistry")` on that same class. Both were caught by this module's own `@SpringBootTest`-based wiring tests actually booting a context — the exact reason this module's tests exist as real Spring contexts rather than only mocked-port unit tests (see this file's own intro).
4. New `LiveWiringTest`/`LiveDisabledWiringTest` (context tests, mirroring `CvWiringTest`/`CvEnabledWiringTest`'s pattern exactly) and `LiveUpdateAuditTrailTest`/`LiveUpdateEventPublisherTest` (plain unit tests for the two new decorators) — see Test inventory above. `CvWiringTest`/`CvEnabledWiringTest` both gained `vision.live.enabled=false` to isolate their own CV-specific assertions from this feature's default-on decorator wrapping (a real, necessary fix — both failed with the live feature defaulting on and this property absent, since `eventPublisherPort`'s outermost type changed from what those tests expected).

`./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app test -DskipWeb`: **vision-domain 142/142** (untouched), **vision-application 291/291** (was 285), **vision-api 204/204** (was 170), **vision-app 72/72 green** (was 59) — 13 new: `LiveWiringTest` 4, `LiveDisabledWiringTest` 4, `LiveUpdateAuditTrailTest` 2, `LiveUpdateEventPublisherTest` 3. `ArchitectureTest`'s 5 rules stayed green with no changes needed — `LiveUpdatePublisherPort` (vision-domain), `LiveUpdateRegistry`/`LiveController`/five new DTOs (vision-api), `NoopLiveUpdatePublisher`/`LiveUpdateAuditTrail`/`LiveUpdateEventPublisher`/`VisionLiveProperties` (this module) all landed in exactly the layer their existing counterparts already occupy — Spring stays confined to station/vision-api/vision-app/adapters, domain/application stay annotation-free.

**Deviations from the brief**: none in shape. The two bugs above were exactly the kind of thing "wire it, boot a real context, see what breaks" is supposed to catch — neither was anticipated from reading the plan alone, both are now documented in-code (javadoc on the affected constructors) and in the relevant MODULE.mds so a future reader hits the explanation before the exception.

docs backend follow-ups batch (2026-07-24), the `vision-app`-scoped wiring half — see vision-domain/vision-application/vision-api's own MODULE.mds for the port method, the domain-layer `resumeAll`/`AssetSpec` logic, and the SSE topic/DTO surface:

1. **`devices`/`detection-events` live topics** — no new bean methods needed for `devices` (it rides `publishFleetChanged()`'s existing dispatch, entirely inside `LiveUpdateRegistry`, vision-api); `detectionEventRepositoryPort`'s `@Bean` method grew two parameters (`LiveUpdatePublisherPort`, `VisionLiveProperties`) to conditionally wrap it in the new `LiveUpdateDetectionEventRepository`, mirroring `auditTrailPort`'s existing shape exactly.
2. **`CreateAssetRequest#deviceIds`** — no `vision-app` change at all; `AssetController`/`ApplicationServiceWiring#assetService` are both untouched, since the new validation rules live entirely in `DefaultAssetService`/`AssetSpec` (vision-application) and the new field lives entirely in the DTO (vision-api).
3. **Simulated-feed resume-on-boot** — new `VisionSimulationProperties`, `SimulationResumeRunner`, and a `simulationResumeRunner` `@Bean`; `simulationService`'s own `@Bean` method gained a 4th argument (`properties.mediamtx().rtspBase()`, reused, no new property).

`./mvnw -B -pl vision-domain,vision-application,station/vision-api,station/vision-app test -DskipWeb`: **vision-domain 142/142** (unchanged), **vision-application 311/311** (was 291), **vision-api 213/213** (was 204), **vision-app 82/82 green** (was 72) — 10 new: `SimulationResumeRunnerTest` 2, `SimulationResumeWiringConfigurationTest` 4, `LiveUpdateDetectionEventRepositoryTest` 2, `LiveWiringTest`/`LiveDisabledWiringTest` +1 assertion each (no new test method), `AssetWiringTest` +1 assertion (no new test method, same "small focused bean" judgment call every prior addition to this test made). `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new/changed type (`AssetSpec#existingDeviceIds`, `DefaultSimulationService#resumeAll` in vision-application; `LiveTopicKind#DEVICES`/`#DETECTION_EVENTS`, `DevicesSnapshotResponse`, `CreateAssetRequest#deviceIds` in vision-api; `LiveUpdateDetectionEventRepository`, `SimulationResumeRunner`, `VisionSimulationProperties` in this module) landed in exactly the layer its existing counterparts already occupy.

**Deviations from the brief**: none in shape. Judgment calls (each documented at its own call site): (1) `devices` extends `publishFleetChanged()` rather than adding a new port method, since every seam that needs to refresh it already calls that one method; (2) `simulationResumeRunner` is always a registered bean (unlike `LiveController`'s conditionally-absent pattern), resolving to a no-op when either gate is off, since a two-property AND condition has no single `@ConditionalOnProperty` to express; (3) `resumeAll()` additionally requires the candidate asset to be `ACTIVE`, not just non-deleted — see contexts/vision-simulation/MODULE.md's own note on this.

## docs/plans/done/UX-REWORK-PLAN.md §U-d item 3 done (test-before-save probe + asset image, vision-app-scoped wiring half)

See vision-domain/vision-application/vision-api/adapters/adapter-persistence's own MODULE.mds for the domain type/port, the `ProbeService`, the two new REST controllers/DTOs, and the JPA/bytea storage — this module's job was purely the two new bean wires plus the in-memory dev fallback:

1. `probeService` (`DefaultProbeService`) — a one-line `@Bean` in `WiringConfiguration`, reusing the already-collected `videoSourceRegistry` bean and the `List<TelemetrySourcePort>` Spring already assembles for `usageTracker` — no new collaborator type at all.
2. `assetImageRepositoryPort` — a 7th `@Bean` method in `PersistenceWiringConfiguration`, following the exact same `VisionPersistenceProperties#enabled()` branch every other repository port there already uses; `InMemoryAssetImageRepository` (new, devsupport) is the disabled-branch fallback, a plain `ConcurrentHashMap<AssetId, AssetImage>` with no eviction/cap — mirrors `InMemoryAssetRepository`'s own triviality, so (consistent with that class and `InMemoryDeviceRepository`/`InMemoryCategoryRepository` having no dedicated unit test either) no standalone `InMemoryAssetImageRepositoryTest` was added; its behavior is exercised indirectly through `AssetWiringTest`/`AssetControllerTest`/`AssetImageControllerTest` (vision-api).
3. New `DeviceProbeSmokeTest` (3, see Test inventory above) proves the probe endpoint's real seam — a real `VideoSourcePort` adapter, a real grabbed frame — for both the zero-hardware `sim` source and the real FFmpeg `file`-ingest path, over the full production wiring with no test doubles needed (a probe never touches `StreamPublisherPort`).
4. `AssetWiringTest`/`PersistenceWiringTest`/`PersistenceWiringConfigurationTest` all extended (assertions/cases added to existing test methods where the bean is small and focused, one new `@Bean` case each where the existing test's own shape already has one row per port) — see Test inventory above for the exact additions.

`./mvnw -B -pl vision-domain,vision-application,station/vision-api,storage/persistence,station/vision-app test`: **vision-domain 150/150** (was 142), **vision-application 331/331** (was 311), **adapter-persistence 46/46** (was 42), **vision-api 236/236** (was 216), **vision-app 87/87 green** (was 82) — 5 new: `DeviceProbeSmokeTest` 3, `PersistenceWiringTest` +1 test method, `PersistenceWiringConfigurationTest` +1 test method (`AssetWiringTest` grew only in assertion count, not test-method count, same judgment call every prior small-bean addition to that class made). `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new/changed type (`AssetImage`/`AssetImageRepositoryPort` in vision-domain; `ProbeService`/`DefaultProbeService`/`ProbeResult`/`ProbeFailedException` in vision-application; `DeviceProbeController`/`AssetImageController`/their DTOs/`PayloadTooLargeException` in vision-api; `AssetImageEntity`/`JpaAssetImageRepository` in adapter-persistence; `InMemoryAssetImageRepository` in this module's devsupport) landed in exactly the layer its existing counterparts already occupy.

**Deviations from the brief**: none in shape.

## docs/plans/done/FC-INTEGRATIONS-PLAN.md F-b done (flight-controller-aware telemetry, vision-app-scoped verification half)

**Zero wiring changes, as the plan's own brief expected** — no file in this module was touched. `WiringConfiguration`/`PersistenceWiringConfiguration` needed no new `@Bean`: `UsageTracker`, `TelemetryRepositoryPort` (both branches — `InMemoryTelemetryRepository` and `adapter-persistence`'s `JpaTelemetryRepository`), and `FleetSummaryService` are all already-wired beans that simply now carry one more field through their existing types (`Telemetry#flightState()`/`AssetAttention#flightMode()` etc.) — the devsupport in-memory repositories (`InMemoryTelemetryRepository` — see the devsupport table above) store/return the domain `Telemetry` record as-is with no field-by-field mapping of their own, so a new record component needed no code change there either, confirmed by reading the class (still exactly the `Map<UsageId, List<Telemetry>>`/`CopyOnWriteArrayList` shape documented above).

**Verification, not implementation**: ran the full scoped gate (`./mvnw -B -pl station/vision-app -am test -DskipWeb`) as the plan's own final-gate instruction specifies. It failed to *build* — not because of anything in this module or its three sibling scopes (vision-application/vision-api/adapter-persistence), but because `drone-link/mavlink` was mid-edit by a parallel agent (`MavlinkSocketHub.java` calling a since-changed-to-`private` `MavlinkTelemetryDecoder#firmwareLabel(int)`) — a different phase's (F-a follow-on/F-c) in-progress work, outside this task's file scope entirely. Per the plan's own documented fallback, re-ran offline against this repo's already-`mvn install`ed local artifacts instead: `vision-domain`/`vision-application`/`vision-api`/`adapter-persistence` freshly installed with this task's own changes, every other adapter (including `adapter-mavlink`) resolved from its last-known-good installed jar (predates F-a/F-c, but binary-compatible — `Telemetry`'s pre-existing 8-arg constructor is untouched, so old adapter bytecode calling it still links) — `./mvnw -B -pl station/vision-app test -DskipWeb -o`: **87/87 green, unchanged count**, same "this task added zero new test methods anywhere in this module" shape as docs/plans/done/MVP3-PLAN.md C-a's own entry above. `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new/changed type (`FlightState` in vision-domain; `AssetAttention`'s three new fields in vision-application; `FlightStateResponse`/`TelemetrySampleResponse`/`AssetAttentionResponse`'s new fields in vision-api; `TelemetrySampleEntity#flightState` in adapter-persistence) landed in exactly the layer its existing counterparts already occupy — nothing crossed a dependency-direction boundary this task's file scope could have violated.

**Not independently re-verified**: `adapter-mavlink`'s own F-a/F-c-adjacent in-progress work (outside this task's scope) — the fallback build above proves *this task's* changes compile/wire correctly against a known-good snapshot of every other adapter, not that `adapter-mavlink`'s own current working tree is green; that is squarely whichever parallel task owns `drone-link/mavlink/**`'s responsibility to fix and verify.

**Deviations from the brief**: none.

## docs/plans/done/OPS-CORE-PLAN.md G-b + R-b done (geofencing wiring; recording endpoint wiring)

**G-b** (geofencing, the `vision-app`-scoped wiring half — see vision-domain/vision-application/vision-api/adapter-persistence's own MODULE.mds for the domain model, the monitor/service, the REST controller, and the JPA/jsonb storage):

1. `geofenceMonitor` (`GeofenceMonitor`, vision-application) and `geofenceService` (`GeofenceService` → `DefaultGeofenceService`) `@Bean`s added to `WiringConfiguration` — both one-line assemblies over already-wired collaborators (`geofenceRepositoryPort`, `eventPublisherPort`, `liveUpdatePublisherPort`), mirroring `replayService`'s/`fleetSummaryService`'s own shape.
2. `usageTracker`'s `@Bean` method grew a 7th parameter, `GeofenceMonitor`, passed through to `UsageTracker`'s new 7-arg public constructor — threaded through **unconditionally** (always a real bean, never `null`), same "no `if enabled`" posture as `liveUpdatePublisherPort` on the same constructor; an empty zone set is simply never a breach, so there is nothing to gate.
3. `geofenceRepositoryPort` — an 8th `@Bean` method in `PersistenceWiringConfiguration`, following the exact same `VisionPersistenceProperties#enabled()` branch every other repository port there already uses; `InMemoryGeofenceRepository` (new, devsupport) is the disabled-branch fallback, a plain `ConcurrentHashMap<ZoneId, GeofenceZone>` with no eviction/cap — mirrors `InMemoryAssetRepository`'s own triviality (no dedicated unit test, same precedent `InMemoryAssetImageRepository` set — exercised indirectly through `AssetWiringTest`/`GeofenceControllerTest`/`PostgresDockerIntegrationTest$GeofenceRepositoryTests`).
4. `GeofenceController` (vision-api, component-scanned) needs no `WiringConfiguration` entry of its own beyond `geofenceService` existing as a bean — it resolves its one constructor dependency automatically, exactly like every other controller in this codebase.

**R-b** (recording/clip-export resolution, the `vision-app`-scoped wiring half — see vision-application/vision-api's own MODULE.mds for `ReplayService#recordingFor` and the new `GET /api/usages/{usageId}/recording` endpoint):

1. `replayService`'s `@Bean` method grew a 4th parameter, `StreamPublisherPort` — the same bean `streamService`/`hlsProxyUpstreamBase` already resolve `viewUrl`/`whepUrl` through — passed to `DefaultReplayService`'s new 4-arg constructor so `recordingFor` can call `playbackUrl` for a usage's resolved window.
2. No wiring change was needed for `UsageTimelineController` itself — the new `recording` endpoint reuses that controller's existing sole `ReplayService` dependency.

`AssetWiringTest` extended (four more `assertNotNull` calls — `GeofenceRepositoryPort`/`GeofenceMonitor`/`GeofenceService`/`GeofenceController` — in the existing bean-inventory test, no new test method, same "small, focused, asset-model-adjacent bean" judgment call every prior addition to this test made); `PersistenceWiringTest` gained one new test method (`defaultConfigurationKeepsInMemoryGeofenceRepository`); `PersistenceWiringConfigurationTest` gained one new test method (`enabledSelectsJpaGeofenceRepository`, plus the existing `disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider` extended with one more assertion).

`./mvnw -B -pl vision-domain,vision-application,station/vision-api,storage/persistence,station/vision-app -am test -DskipWeb=true`: **vision-domain** (unchanged, G-a's own scope), **vision-application 372/372 green** (was 367), **vision-api 254/254 green** (was 239), **adapter-persistence 56/56 green** (was 49), **vision-app 89/89 green** (was 87) — 2 new test *methods* in this module (`PersistenceWiringTest`/`PersistenceWiringConfigurationTest`, one each), `AssetWiringTest` grew only in assertion count. `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new/changed type (`GeofenceService`/`DefaultGeofenceService`/`GeofenceMonitor`/`GeofenceZoneSpec`/`UsageRecording` in vision-application; `GeofenceController`/`GeofenceZoneResponse`/`GeofenceZoneRequest`/`UsageRecordingResponse` in vision-api; `GeofenceZoneEntity`/`JpaGeofenceRepository` in adapter-persistence; `InMemoryGeofenceRepository` in this module's devsupport) landed in exactly the layer its existing counterparts already occupy.

**Deviations from the brief**: none in shape.

## docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 1 wave 2 done (wiring half — "bring it home")

Two new `WiringConfiguration` `@Bean`s (see the Bean inventory table above for both): `mavlinkFlightCommander` (`MavlinkFlightCommander`, adapter-mavlink — constructed from the exact same `mavlinkTelemetrySource` bean instance already wired for real telemetry ingest, mirroring `DiscoveryWiringConfiguration#mavlinkHeartbeatScanner`'s own instance-borrowing pattern) and `flightCommandService` (`FlightCommandService` → `DefaultFlightCommandService`, vision-application — a one-line assembly over `assetService`/`flightCommandPort`/`auditTrailPort`, all already-wired collaborators, mirroring `categoryService`'s shape). Both are wired **unconditionally**, like `mavlinkTelemetrySource` itself — there is no `vision.mavlink.*` property to gate either behind, so no conditional bean/branch was needed (the task brief's own "prefer: always wire both" recommendation held up once `mavlinkTelemetrySource`'s own unconditional wiring was confirmed by reading it, not assumed).

**`FeedTransmitterRegistry` needed no change** — checked, not assumed: `feedTransmitterRegistry(List<FeedTransmitterPort> feedTransmitters)` already collects `mavlinkFeedTransmitter` today via Spring's own automatic `List<T>` bean-collection injection (every bean whose actual runtime type implements `FeedTransmitterPort`, regardless of the declared factory-method return type) — this was **already true before this wave** and already covered by `AssetWiringTest`'s pre-existing `mavlinkFeedTransmitterIsWiredAsAFeedTransmitterPortBeanAndResolvesByProtocol` test (unmodified by this wave). The prior wave's own MODULE.md note flagging this as a possible wave-2 to-do (contexts/vision-simulation/MODULE.md's "What wave-2 wiring will need to touch" paragraph) turned out to already be satisfied by the time this wave started.

`AssetWiringTest` extended — see the Test inventory entry above for the exact assertions (`FlightCommandService`/`FlightCommandController` added to the existing bean-inventory test; one new test method, `flightCommandServiceIsWiredAgainstTheMavlinkCommander`, proving `flightCommandService`'s `FlightCommandPort` constructor argument really is the `mavlinkFlightCommander` bean via `assertSame`).

`./mvnw -B -pl station/vision-app test -DskipWeb`: **98/98 green** (was 97) — `AssetWiringTest` grew 5→6 test methods (two more `assertNotNull` calls in the existing inventory test, plus the one new `assertSame` test). `ArchitectureTest`'s 5 rules stayed green with no changes needed — `MavlinkFlightCommander` (adapter-mavlink) and `FlightCommandService`/`DefaultFlightCommandService` (vision-application)/`FlightCommandController` (vision-api) all landed in exactly the layer their existing counterparts already occupy, and neither new class introduces a new cross-adapter or domain/application-Spring-dependency shape.

**Deviations from the brief**: none in shape.

## Auth / session security (docs/plans/done/U-AUTH-PLAN.md slice 1 wave 3)

Spring Security lives **only here** (added `spring-boot-starter-security` to this module's pom, nowhere else). vision-api stays free of it via two seams it defines (`PrincipalResolver`, `SessionAuthenticator`) that this module implements — the SecurityContext-reading is entirely on this side.

**`SecurityConfig`** (`@EnableWebSecurity`) — exactly one `SecurityFilterChain` bean active, gated by `vision.auth.enabled`, so Spring Boot's own default (HTTP Basic + generated password) never applies:

- **`permitAllFilterChain`** (`@ConditionalOnProperty ... havingValue="false", matchIfMissing=true`, i.e. the default) — `anyRequest().permitAll()` + **CSRF disabled**. Nothing is secured, so every existing test, every full-context MockMvc smoke test, and the running SPA behave exactly as before auth existed (the plan's prime directive). **CSRF is disabled here too, not just in the secured chain** — the real running app routes through this filter, and a default-on CSRF filter would 403 the SPA's own `POST /api/*` calls that worked pre-auth.
- **`securedFilterChain`** (`havingValue="true"`) — session required for `/api/**` **and `/ws/**`** (the latter added by docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4 — an unauthenticated `/ws/manual-control` handshake is now rejected `401` by this same rule before the upgrade ever completes, exactly as `/api/**` already was; see `ManualControlSecurityEnabledTest`/`ManualControlSecurityDisabledTest`) except `/api/auth/login`/`/api/auth/logout`; static/SPA routes public. Reads/writes the principal via a shared `securityContextRepository` bean (`HttpSessionSecurityContextRepository`, enabled-only) — the same bean `SecuritySessionAuthenticator` saves into on login. Unauthenticated `/api/**` (incl. `/api/auth/me`, which is *not* in the permit-list) → `401` via `HttpStatusEntryPoint(UNAUTHORIZED)`. `httpBasic`/`formLogin`/`logout` all disabled (login/logout run through the controller-seam). **CSRF decision: disabled for the API, documented** (see `SecurityConfig`'s javadoc) — JSON-only, same-origin, `SameSite=Lax` session cookie; cookie-to-header double-submit noted as the hardening follow-up, deferred to avoid the chicken-and-egg it adds to a custom JSON login endpoint.

**`AuthWiringConfiguration`** (separate `@Configuration`, same split-out precedent as `DiscoveryWiringConfiguration`/`PersistenceWiringConfiguration`):

| Bean | Type | Implementation |
|---|---|---|
| `passwordHasherPort` | `PasswordHasherPort` | `BcryptPasswordHasher` — **the only place BCrypt is referenced** (wraps Spring Security's `BCryptPasswordEncoder`); unconditional |
| `authService` | `AuthService` | `DefaultAuthService(userRepositoryPort, passwordHasherPort)` (vision-application); unconditional |
| `userService` | `UserService` | `DefaultUserService(userRepositoryPort, passwordHasherPort)`; unconditional |
| `groupService` | `GroupService` | `DefaultGroupService(groupRepositoryPort)`; unconditional |
| _(removed)_ `authSeedRunner` | — | **Removed** by docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 — see "Seeding" below and the W1 narrative section near the end of this file. The dev accounts it used to create on every startup are now a Flyway migration (`storage/persistence`'s `V90001__dev_accounts.sql`) gated by `vision.persistence.seed-dev-users`, not an `ApplicationRunner` here. |
| `scopeResolver` | `ScopeResolver` (vision-application) | `DefaultScopeResolver(groupRepositoryPort, assignmentRepositoryPort)` — docs/plans/done/U-SCOPE-PLAN.md slice 2; **unconditional** (auth on or off): `SecurityContextPrincipalResolver` uses it when auth is on, the dev resolver short-circuits to unbounded without touching it when off |
| `assignmentService` | `AssignmentService` (vision-application) | `DefaultAssignmentService(assignmentRepositoryPort, assetRepositoryPort)` — the scoped pilot→asset grant/revoke + the pilot's own roster read; unconditional |
| `activityService` | `ActivityService` (vision-application) | `DefaultActivityService(auditTrailPort)` — a user's own activity feed behind `GET /api/me/activity`; unconditional |
| `principalResolver` | `PrincipalResolver` (vision-api) | `DevPrincipalResolver` when auth disabled/absent (returns `DevPrincipal`; **`scope()`→`VisibilityScope.unbounded()` — the slice-2 guardrail, so the default-off build sees everything and behaves exactly as today**), `SecurityContextPrincipalResolver(scopeResolver)` (reads `SecurityContextHolder` per call; `scope()`→`scopeResolver.scopeFor(session user)`, recomputed per call — documented, a per-request cache is a small follow-up) when enabled — gated |
| `sessionAuthenticator` | `SessionAuthenticator` (vision-api) | `NoopSessionAuthenticator` when disabled (the controller never calls it in that mode), `SecuritySessionAuthenticator(authService, securityContextRepository)` when enabled — gated |

`UserRepositoryPort`/`GroupRepositoryPort`/`AssignmentRepositoryPort` are wired in `PersistenceWiringConfiguration` (now **13** port beans — `assignmentRepositoryPort` added by docs/plans/done/U-SCOPE-PLAN.md slice 2, `JpaAssignmentRepository` vs `InMemoryAssignmentRepository`; `markRepositoryPort` added by docs/plans/done/TACTICAL-MARKS-PLAN.md M2, `JpaMarkRepository` vs `InMemoryMarkRepository`) alongside every other port, gated by `vision.persistence.enabled` — orthogonal to `vision.auth.enabled`.

**`CurrentUser` rewrite**: the old `actingOwnership` `Ownership` bean was **removed** (see the struck row in the Bean inventory). `vision-api`'s `CurrentUser` now autowires the `principalResolver` seam. `VisionUserDetails` (this module) is Spring Security's view of an authenticated `User`; **Ownership derivation**: `ownerId` = the user's own id, `groupId` = the group of the user's highest-role membership, or — for a user with no membership yet — a **personal group whose id equals the user's own id** (documented, stable fallback so an unassigned user still has a valid scope until slice 2's visibility model). Roles → `ROLE_<name>` authorities.

**Seeding, current mechanism (docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 replaced the `AuthSeedRunner`
`ApplicationRunner` described in the very next paragraph — kept below as an accurate record of what
was true through that wave, not the present state)**: the root `Group` is now seeded at the database
level, unconditionally, by `storage/persistence`'s `V13__identity_baseline.sql` — at the **fixed** id
`DevPrincipal.GROUP_ID` (`UUID(0,1)`), fixing a real bug (`AuthSeedRunner` used `GroupId.random()`,
so a persistence-enabled app's actual root-group id never matched the fixed id every default-config
asset is owned by — a MANAGER scoped to the seeded group saw an empty fleet; see the W1 narrative
section near the end of this file). The three DEV-ONLY accounts (`admin`/`admin` ADMIN,
`manager`/`manager` MANAGER, `pilot`/`pilot` PILOT) are now `V90001__dev_accounts.sql`, a **second**,
conditional Flyway location this module's `PersistenceWiringConfiguration` only adds when the new
`vision.persistence.seed-dev-users` property (default `false`) is `true` — see
storage/persistence/MODULE.md's `db/seed/dev` entry and W1 narrative section for the full account,
including why a bare `ignoreMigrationPatterns("*:missing")` silently failed and what the fix needed
to be. `docker-compose.yml` sets `VISION_PERSISTENCE_SEED_DEV_USERS=true` for the friends-demo stack,
preserving the old `AuthSeedRunner`'s exact effective behavior there. There is no `bootstrap/`-package
equivalent left in this module for the dev accounts; `DevAccountSeeder`
(`station/vision-app/src/test/java/.../app/DevAccountSeeder.java`, test-only, not shipped) replicates
just enough of the old seeding logic for the `@SpringBootTest` classes below that assert against a
logged-in `admin`/`manager`/`pilot` session, since there is no in-memory equivalent of a Flyway seed
for those to fall back on.

**Seeding, as it was through docs/plans/done/U-AUTH-PLAN.md wave 3 (superseded, kept for history)** —
`AuthSeedRunner` (`ApplicationRunner`, mirrored `SimulationResumeRunner`'s shape): on first boot only (a strict no-op once `UserService#list(...)` is non-empty — idempotent across restarts and safe with a persistent store), seeded one root `Group` and three users via `UserService#create` (so hashing went through the port): `admin`/`admin` (ADMIN), `manager`/`manager` (MANAGER), `pilot`/`pilot` (PILOT). **These were DEV-ONLY seed credentials** (username == password), loudly documented in the class javadoc and logged at WARN. Ran **regardless of `vision.auth.enabled`** so flipping auth on had accounts to log in as. **Seeded as the system, not a user** — every `list`/`create` call passed `VisibilityScope.unbounded()` (docs/plans/done/U-SCOPE-PLAN.md slice-2 cleanup added the management gates to `UserService`/`GroupService`), so the ADMIN/MANAGER management gate and the ≤-own-scope / ADMIN-only-root-group rules never blocked the seeder (a blocked seeder would have meant no login users at all — creating a root group and an ADMIN-role user both require an unbounded scope).

**Tests** (`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app -am test -DskipWeb`): **vision-app 127/127 green** (was 109). New: `BcryptPasswordHasherTest` (4), `InMemoryUserRepositoryTest` (3, incl. case-insensitive `findByUsername`), `InMemoryGroupRepositoryTest` (2), `AuthSeedRunnerTest` (2, seeds-once + idempotent), `PrincipalResolverTest` (4, dev-resolver returns `DevPrincipal` / security-resolver returns the authenticated id+ownership / personal-group fallback / throws unauthenticated), `AuthDisabledSecurityTest` (3, `@SpringBootTest` default — MockMvc **with** `springSecurityFilterChain` proving a secured-looking `/api/**` succeeds unauthenticated + `me` reports the dev admin `authEnabled=false`), `AuthEnabledFlowTest` (2, `@SpringBootTest(properties="vision.auth.enabled=true")` — the full 401→login→me→logout→me(401) flow with a seeded user + carried session, plus wrong-password→401). Every pre-existing full-context test (`AssetWiringTest`, `CvDetectionE2ETest`, all wiring/smoke tests, `ArchitectureTest`) stayed green — the existing MockMvc smoke tests build MockMvc **without** the security filter, so permit-all leaves them untouched; `AssetWiringTest` swapped its removed-`Ownership`-bean assertion for a `CurrentUser` one.

**Deviations from the brief**: none in shape. Memberships are stored **jsonb on the user row** (the plan offered jsonb or a join table). Login/logout go through a thin `AuthController` + `SessionAuthenticator` seam rather than Spring Security's own JSON form-login filter — the plan's explicitly-allowed alternative, chosen to keep spring-security out of vision-api. CSRF is disabled for the API (justified above), the plan's second allowed option.

## docs/plans/done/U-SCOPE-PLAN.md slice 2 done (visibility scoping wiring, vision-app half)

Three new unconditional application-service beans in `AuthWiringConfiguration` (`scopeResolver`/`assignmentService`/`activityService` — see the table above), one new port bean in `PersistenceWiringConfiguration` (`assignmentRepositoryPort`, JPA vs the new `InMemoryAssignmentRepository` devsupport), and the `SecurityContextPrincipalResolver` bean now takes `scopeResolver` so `CurrentUser#scope()` resolves the real per-user scope when auth is on. **The guardrail is `DevPrincipalResolver#scope()`→`VisibilityScope.unbounded()`**: with `vision.auth.enabled=false` (default) every request is the dev principal with an unbounded scope, so every scoped read/command/grant sees everything and the default-off build is behavior-identical to before this slice.

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-app 135/135 green** (was 127). New: `InMemoryAssignmentRepositoryTest` (4, the port contract against the in-memory ref impl), `ScopedAssetReadAuthEnabledTest` (2, `@SpringBootTest(properties="vision.auth.enabled=true")` — a MANAGER sees their group's asset but not another group's, a PILOT sees only assigned assets not their group's, driven through the real security filter + session login; assertions are membership-based since the auth-on context/in-memory repos are shared across test classes with identical properties); `PrincipalResolverTest` +2 (dev-scope-unbounded + security-resolver-delegates-scope-to-`ScopeResolver`, and its 3 existing security-resolver constructions updated to pass a `ScopeResolver`); `AssetWiringTest` +the new bean/controller assertions. Every pre-existing test stayed green (default-off = unbounded). Docker-backed `adapter-persistence` assignment tests ran (see that module's MODULE.md).

## docs/plans/done/U-SCOPE-PLAN.md slice-2 cleanup done (management gates, 2026-07-30)

The previously-deferred ADMIN/MANAGER management gate on user/group management and the invite ≤-own-scope grant rule are now enforced. Management authority is derived from the acting `VisibilityScope` (new `canManageOrg()`/`includesGroup`/`maxGrantableRole()` methods — no new `Role` plumbing on `CurrentUser`), enforced inside `UserService`/`GroupService` (application), and reached through the existing `currentUser.scope()` seam in `UserAdminController`/`GroupAdminController` (vision-api). This module's only change: **`AuthSeedRunner` now seeds with `VisibilityScope.unbounded()`** (see Seeding above) so the system seeder is never blocked by the new gates. **Guardrail unchanged**: `DevPrincipalResolver#scope()`→`unbounded()`, so `vision.auth.enabled=false` passes every gate and the default-off build is behavior-identical.

`./mvnw -B -pl vision-application,station/vision-api,station/vision-app test -DskipWeb`: **vision-app 139/139 green** (was 135). New: `OrgManagementAuthEnabledTest` (4, `@SpringBootTest(properties="vision.auth.enabled=true")` through the real security filter + session login — a MANAGER granting ADMIN → 403, a PILOT creating a group → 403, an ADMIN creating a root group → 201, a MANAGER creating a root group → 403). `AuthSeedRunnerTest`/`ScopedAssetReadAuthEnabledTest` migrated to the scope-taking `list`/`create` signatures with `unbounded()`. Every pre-existing test stayed green (default-off = unbounded); application **484** (was 468, +16), vision-api **306** (unchanged).

## docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md Wave A done (per-asset flight stats, wiring half)

`assetStatsService` `@Bean` added to `WiringConfiguration` (one-liner, `new DefaultAssetStatsService(assetUsageRepositoryPort, usageTracker)`, mirroring `replayService`'s/`fleetSummaryService`'s shape) — `AssetStatsController`'s `GET /api/assets/{id}/stats` (vision-api, component-scanned) now resolves both of its constructor dependencies (`AssetService`, already wired; `AssetStatsService`, new) in a booted context. No new port, no persistence change — `DefaultAssetStatsService` (vision-application) aggregates over the already-wired `AssetUsageRepositoryPort`/`UsageTracker` beans.

`AssetWiringTest` extended (two more `assertNotNull` calls — `AssetStatsService`/`AssetStatsController` — in the existing bean-inventory test, no new test method, same "small, focused, asset-model-adjacent bean" judgment call every prior addition to this test made — see the Test inventory entry above).

`./mvnw -B -pl vision-application,station/vision-api,station/vision-app test -DskipWeb`: **vision-application 434/434 green** (was 427 — 7 new `DefaultAssetStatsServiceTest` cases), **vision-api 287/287 green** (was 283 — 4 new `AssetStatsControllerTest` cases), **vision-app 127/127 green, unchanged count** (only two more assertions inside `AssetWiringTest`'s existing inventory test, no new test method). `ArchitectureTest`'s 5 rules stayed green with no changes needed — every new type (`AssetStats`/`AssetStatsService`/`DefaultAssetStatsService` in vision-application; `AssetStatsController`/`AssetStatsResponse` in vision-api) landed in exactly the layer its existing counterparts already occupy.

**Deviations from the brief**: none in shape.

## docs/plans/done/CV-CONTROL-PLAN.md Wave D done (live per-stream CV control + model roster, vision-app half)

Depends on Waves B (`vision-domain`) and C (`vision-application`), both already green. This module's half of Wave D is two small pieces:

1. **`cvModelRoster` bean** (`WiringConfiguration`, see "Detection-model roster" above) — the static, config-backed detection-model roster `CvModelsController` (`vision-api`) serves at `GET /api/cv/models`.
2. **`CvDetectionEndpointE2ETest` assertion fix** (`station/vision-app/src/test/java/com/drones/vision/app/CvDetectionEndpointE2ETest.java:125`) — this end-to-end test starts a stream with `PipelineConfig.defaults()` (line 116) and asserted the resulting detection's `modelId` was the old dead `"yolo"` id; Wave B changed `defaults()`'s model id to the real `yolo26n.pt` checkpoint, so the assertion now expects `"yolo26n.pt"`. Grepped both `vision-api` and `vision-app` for any other hardcoded `"yolo"` assertion round-tripping through `PipelineConfig.defaults()` — none found; the other `"yolo"` occurrences in this codebase (`StreamControllerTest`, `LiveUpdateRegistryTest`, `devsupport.InMemoryDetectionRepositoryTest`) all construct a `ModelRef("yolo","latest")` directly as an unrelated hand-built test fixture, never through `defaults()`, so they were correctly left untouched.

`./mvnw -B -pl station/vision-api,station/vision-app test -DskipWeb`: **vision-app 139/139 green, unchanged count** (`AssetWiringTest` gained two more `assertNotNull` calls plus a roster-content assertion inside its existing bean-inventory test method, no new test method — same judgment call every prior small-bean addition to this test made; `CvDetectionEndpointE2ETest`'s one assertion updated in place, still 1 test). `RtspSimulationDockerE2ETest` ran green (docker was available in this environment, not skipped). `vision-api` grew **318→330** (+12, see its own MODULE.md) — the two modules were verified together in one scoped `-pl station/vision-api,station/vision-app` run, both green.

**Deviations from the brief**: none. No contract mismatch to reconcile — `PipelineConfig`'s 9-arg ctor, `PipelineConfigPatch`, and `UpdateOutcome` (Waves B/C) matched this wave's assumptions exactly.

## docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R4 done (WebSocket transport wiring, vision-app half)

Depends on R2 (`vision-application`, already green) and R3 (`drone-link/mavlink`, already green). This wave adds `VisionRcProperties`, the `mavlinkManualControlSender`/`manualControlService` beans, and `SecurityConfig`'s `/ws/**` addition — see "RC manual-control relay wiring" above for the full writeup, and station/vision-api/MODULE.md's own R4 entry for the transport half (`ManualControlWebSocketConfig`/`ManualControlHandshakeInterceptor`/`ManualControlWebSocketHandler` + frame DTOs, all vision-api).

`./mvnw -B -pl vision-domain,vision-application install -DskipTests` then `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-app 142/142 green** (was 139) — `AssetWiringTest` unchanged at 6 test methods (two more `assertNotNull` calls in the existing one), plus two new test classes: `ManualControlSecurityDisabledTest` (1) and `ManualControlSecurityEnabledTest` (2). `ArchitectureTest`'s 5 rules stayed green with no changes needed — the new WebSocket handler/config/interceptor classes landed entirely in vision-api (never vision-domain/vision-application), and no adapter-to-adapter or Spring-in-domain dependency was introduced. `vision-api` grew **330→351** (+21, see its own MODULE.md).

**A live race, not a design gap**: R4's vision-api half (the WebSocket handler/config/interceptor/DTOs, `VisionRcProperties`, and this module's own wiring/security changes) landed from a different session running concurrently with this one on the identical file scope — verified via `git status`/direct reads before every edit rather than assumed, per the task's own "check whether any of it is already on disk" instruction. Where the two efforts collided on the exact same file (`ManualControlWebSocketHandlerTest`), the later write won and was kept as-is (a stronger test, including a genuine multi-threaded send-lock race detector) rather than reverted; everywhere else (this module's `WiringConfiguration`/`SecurityConfig`/`application.yaml`, the `AssetWiringTest` extension, the two new security test classes, both `MODULE.md`s) was untouched by the other session at the time of writing and is this session's own, independently verified green.

**Deviations from the brief**: `manualControlService`'s watchdog scheduler is a bean-method-local `Executors.newSingleThreadScheduledExecutor(...)` call, not a shared bean — see "RC manual-control relay wiring" above for why (`DefaultManualControlService`'s own default-scheduler factory is private, and only the 6-arg canonical constructor honors the resolved property, so the wiring layer must build its own). No other deviation in shape.

## docs/plans/done/TACTICAL-MARKS-PLAN.md M2 done (tactical marks, persistence wiring half)

Wave M2 of docs/plans/done/TACTICAL-MARKS-PLAN.md: wires `MarkRepositoryPort` (vision-domain, M1, already green) to
`adapter-persistence`'s new `JpaMarkRepository` or this module's new `InMemoryMarkRepository`
devsupport fallback, exactly mirroring `geofenceRepositoryPort`'s toggle. Deliberately **not** in
scope here: `MarkService`/`MarksController` (M3/M4) — this wave wires only the repository port, so
`markRepositoryPort` currently has no consumer bean in this context (see "Persistence wiring"
above).

1. `InMemoryMarkRepository` (new, this module's devsupport, `com.drones.vision.app.devsupport`) —
   a plain `ConcurrentHashMap<MarkId, Mark>`, no eviction/cap, mirroring `InMemoryGeofenceRepository`
   field-for-field (`save`=`Map#put` upsert, `findById`=`Optional.ofNullable`, `findAll`=`List.copyOf`,
   `deleteById`=idempotent `Map#remove`).
2. `PersistenceWiringConfiguration#markRepositoryPort` — a 13th `@Bean` method, the exact same
   `VisionPersistenceProperties#enabled()` branch every other repository port there already uses:
   `JpaMarkRepository` (adapter-persistence) when `true`, `InMemoryMarkRepository` when `false`
   (default). `WiringConfiguration` itself is untouched — like `geofenceRepositoryPort`, the port bean
   lives entirely in `PersistenceWiringConfiguration`; nothing in `WiringConfiguration` consumes it
   yet.
3. `AssetWiringTest` deliberately **not** touched — it only asserts full-stack (repository + service +
   controller) bean triples (see its own entry in Test inventory above), and `MarkService`/
   `MarksController` don't exist yet; extending it now would assert nothing new. `PersistenceWiringTest`
   gained one test method (`defaultConfigurationKeepsInMemoryMarkRepository`); `PersistenceWiringConfigurationTest`
   gained one test method (`enabledSelectsJpaMarkRepository`, plus the existing
   `disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider` extended with one more assertion).
   New `devsupport.InMemoryMarkRepositoryTest` (5 tests, mirroring `InMemoryAssignmentRepositoryTest`'s
   dedicated-unit-test shape rather than `InMemoryGeofenceRepository`'s no-dedicated-test precedent —
   explicitly asked for by the task brief).

See adapter-persistence/MODULE.md's own M2 entry for the `MarkEntity`/`JpaMarkRepository`/
`V10__marks.sql` writeup (including the corrected migration number — the plan's `V8` guess was stale
by the time this wave ran; `V9__pilot_assignments.sql` already existed, so the actual next-free
version is `V10`).

`./mvnw -B -pl storage/persistence,station/vision-app test -DskipWeb`: **adapter-persistence 78/78
green** (was 71, run against a real `postgres:16` Testcontainers instance, not skipped — docker was
reachable in this environment) — **vision-app 149/149 green** (was 142) — `InMemoryMarkRepositoryTest`
(5, new), `PersistenceWiringTest` +1, `PersistenceWiringConfigurationTest` +1 (7 total new test
methods across both modules, matching the +7/+7 count exactly). `ArchitectureTest`'s 5 rules stayed
green with no changes needed — `MarkEntity`/`JpaMarkRepository` (adapter-persistence) and
`InMemoryMarkRepository` (this module's devsupport) landed in exactly the layer their existing
`GeofenceZoneEntity`/`JpaGeofenceRepository`/`InMemoryGeofenceRepository` counterparts already
occupy.

**Deviations from the brief**: the migration version (`V10`, not the plan's placeholder `V8`) — the
plan explicitly asked for this to be verified and reported, not treated as a deviation to avoid.
Otherwise none.

**For M4 (api wiring)**: `markRepositoryPort` is ready to be consumed by a `markService` `@Bean` in
`WiringConfiguration` (`new DefaultMarkService(markRepositoryPort, usageTracker,
liveUpdatePublisherPort)` per the plan's §2) the moment M3 lands `MarkService`/`DefaultMarkService`
in vision-application — `usageTracker` and `liveUpdatePublisherPort` are both already real beans in
this context (unconditional/`vision.live.enabled`-gated respectively) with nothing new to wire for
them. `MarksController` (vision-api, M4) will need no `WiringConfiguration` entry of its own beyond
`markService` existing as a bean, the same "resolves its constructor dependency automatically"
pattern every other controller here follows.

## docs/plans/done/TACTICAL-MARKS-PLAN.md M4 done (tactical marks, api + wiring half)

Wave M4 of docs/plans/done/TACTICAL-MARKS-PLAN.md: `MarksController` + DTOs + the `marks` live-envelope
extension (all vision-api, see that module's own MODULE.md for the full REST/DTO/live writeup) plus
the one-line `markService` `@Bean` this module adds (see Bean inventory above) — exactly the shape
M2's own "For M4" note above anticipated. **This wave's M3 dependency landed revised**: `MarkService`
(vision-application) is unscoped — `list()` takes no arguments and returns every `ACTIVE` mark
deployment-wide, and `update`/`delete` gate a lifecycle transition or delete to creator-or-manager via
`AccessDeniedException`, not a `VisibilityScope`-filtered read — superseding the plan's original
group-scoped `list(VisibilityScope, GroupId)` text (see contexts/vision-map/MODULE.md's own M3 entry
for the full rationale: a PILOT's scope carries no group at all, so a group-filtered picture would
have hidden a pilot's own marks from themselves).

1. **`markService`** (`WiringConfiguration`, one `@Bean` method) — `new
   DefaultMarkService(markRepositoryPort, usageTracker, liveUpdatePublisherPort)`, a one-line
   assembly mirroring `geofenceService`'s shape exactly. All three collaborators were already real
   beans in this context before this wave (`markRepositoryPort` since M2, `usageTracker`/
   `liveUpdatePublisherPort` unconditional) — no new property, no new conditional branch.
2. **`AssetWiringTest`** extended (three more `assertNotNull` calls in the existing bean-inventory
   test, no new test method — same judgment call every other "small, focused, asset-model-adjacent
   bean" addition above made): asserts `markRepositoryPort`, `markService`, and `marksController` all
   resolve — the last one doubling as proof `MarksController`'s two-argument constructor
   (`MarkService`, `CurrentUser`) wires correctly.
3. **No change to `SecurityConfig`** — `/api/marks/**` already falls under the secured chain's
   existing `/api/**` `.authenticated()` rule (docs/plans/done/U-AUTH-PLAN.md wave 3); marks needed no new
   permit-list entry or path pattern.
4. **No change to `PersistenceWiringConfiguration`** — `markRepositoryPort` was already wired by M2;
   this wave only adds a *consumer* of that existing bean.

`./mvnw -B -pl vision-domain,vision-application install -DskipTests` then `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb`: **vision-api 373/373 green** (was
351, +22: new `MarksControllerTest` — 17 tests covering every endpoint's happy path plus the 400/403/
404 error contract, the geolocate incomplete-telemetry 400, and the `kind`/`label`/`depressionDegrees`
defaulting `GeolocateMarkRequest` performs at the wire boundary; `LiveUpdateRegistryTest` — 5 new
tests proving `publishMarkCreated`/`publishMarkUpdated` broadcast a `marks` envelope with the matching
`action`, that `publishMarkCleared` forces `mark.status="CLEARED"` in the payload even when the `Mark`
passed in is still `ACTIVE` (the delete path) and leaves it `CLEARED` when already `CLEARED`, and that
the `marks` topic — like `event` — has nothing to replay on a fresh connect since it deliberately
carries no live-query seed, see below) — **vision-app 149/149 green** (unchanged from M2's own count:
this wave added zero new test *methods* here, only three more assertions inside
`AssetWiringTest`'s existing one). `ArchitectureTest`'s 5 rules stayed green with no changes needed —
`MarksController`/the DTOs/the live-stack extension all landed in vision-api (never vision-domain/
vision-application), and vision-api still imports no `org.springframework.security` type (the acting
user is reached exclusively through `CurrentUser`, per the seam docs/plans/done/U-AUTH-PLAN.md wave 3
established). **Docker**: `adapter-persistence`'s `PostgresDockerIntegrationTest` result varied
across otherwise-identical scoped-build invocations in the sandboxed environment this wave was
verified in (one run: `Tests run: 0`, its `@EnabledIf(dockerAvailable)` gate disabled the class;
another run: **78/78 green**, a real Postgres Testcontainers round-trip) — a docker-availability
flake in this sandbox either way, with no bearing on M4's own correctness since this wave touched no
persistence code at all.

**A deliberate, documented gap in the live stack, not an oversight**: unlike `fleet`/`devices`/
`detection-events`, the new `marks` topic has **no live-query seed** for a fresh connection whose
buffer is still empty. Seeding it the way those three do would need a fifth `ObjectProvider<MarkService>`
constructor parameter on `LiveUpdateRegistry` (vision-api) — the same circular-bean-dependency shape
its four existing `ObjectProvider`s already carry, since `DefaultMarkService` itself depends on
`LiveUpdatePublisherPort` — which would push that class's constructor past the five-parameter ceiling
(`.claude/skills/java-clean-code/SKILL.md` §3; that class is already at the ceiling per its own
`freshFleetEnvelope()` javadoc, which declined the identical trade for `AssetImageRepositoryPort`).
`marks` instead joins `event` in the registry's "honestly limited" bucket: a viewer's first connection
relies entirely on its own `GET /api/marks` read for the current picture, with the live topic carrying
only *deltas* from that point on — exactly the shape docs/plans/done/TACTICAL-MARKS-PLAN.md M5's own `MarksStore`
plan already calls for (initial GET, then merge live deltas), so this costs nothing in practice. See
station/vision-api/MODULE.md's own `com.drones.vision.api.live` subsection and `LiveUpdateRegistry`'s class
javadoc for the full reasoning.

**The frozen `marks` SSE envelope, verbatim** (what M5's `MarksStore` parses):

```json
{ "seq": 128, "type": "marks",
  "payload": { "action": "created",
    "mark": { "id": "<uuid>", "kind": "TARGET", "label": "Bunker", "note": null,
              "position": { "latitude": 50.45, "longitude": 30.52, "altitudeMeters": null },
              "createdBy": "<uuid>", "createdAt": "2026-07-31T10:00:00Z",
              "status": "ACTIVE", "source": "MANUAL" } } }
```

`action` is `"created"` (create/geolocate), `"updated"` (annotate/drag-to-correct), or `"cleared"`
(status→`CLEARED` or delete — `mark.status` is always `"CLEARED"` in this case, even for a delete of
a still-`ACTIVE` mark). The topic is always-on (every `GET /api/live` connection is auto-subscribed,
like `fleet`/`event`/`devices`/`detection-events`) and deployment-wide — no per-group filter, matching
`MarkService#list()`'s own unscoped shape and the pre-existing `fleet`/`event` topics' own posture
(docs/plans/done/TACTICAL-MARKS-PLAN.md's own Open Q4, accepted as-is).

**Deviations from the brief**: one, explicit and reasoned above — the plan's §5 text describes seeding
the `marks` buffer from an injected `MarkService` snapshot on an empty-buffer replay, mirroring `fleet`/
`devices`/`detection-events`; this wave omits that seed specifically to respect the five-parameter
constructor ceiling `LiveUpdateRegistry` was already at, choosing the same "honestly limited, GET
covers the gap" posture the plan's own `event` topic already accepts. Otherwise none — endpoint
shapes, status codes, DTO field names, and the live envelope match the frozen contract exactly.

## docs/plans/done/CV-TRAINING-PLAN.md Wave T3 done (CV model-improvement loop, devsupport + wiring half)

Wave T3 of docs/plans/done/CV-TRAINING-PLAN.md: in-memory fallbacks for the three new repository ports T1
(vision-domain, already green) froze, plus their wiring in `PersistenceWiringConfiguration` —
`adapter-persistence`'s half (the four JPA implementations, `V11__training_datasets.sql`,
`FilesystemDatasetExport`) is its own MODULE.md's entry.

1. **`InMemoryDatasetRepository`/`InMemoryTrainingSampleRepository`/`InMemorySampleImageStore`**
   (new, this module's devsupport, `com.drones.vision.app.devsupport`) — plain `ConcurrentHashMap`s,
   the same shape as every other devsupport fallback in this table (see the devsupport table
   above). `InMemoryTrainingSampleRepository#findByDataset` filters by `datasetId`/optional
   `status`, sorts newest-`capturedAt`-first, then bounds to `limit` — the exact order
   `JpaTrainingSampleRepository` (adapter-persistence) picks for what
   `TrainingSampleRepositoryPort#findByDataset`'s own javadoc otherwise leaves
   implementation-defined, so the two stay behavior-compatible (a Postgres round trip and an
   in-memory one return samples in the same order for the same input).
2. **`PersistenceWiringConfiguration`** gained three more `@Bean` methods —
   `datasetRepositoryPort`/`trainingSampleRepositoryPort`/`sampleImageStorePort` — each branching
   on the existing `VisionPersistenceProperties#enabled()` exactly like the thirteen port beans
   already there (no new property; see "Persistence wiring" above for the updated paragraph). Wired
   *ahead of* any consumer — `DatasetService`/`LabelingService`/their controllers are separate,
   disjoint waves (T2/T4) — the same precedent `markRepositoryPort` set at M2.
3. **No change to `SecurityConfig`, `WiringConfiguration`, or any existing bean** — this wave only
   added new `@Bean` methods to `PersistenceWiringConfiguration` and new devsupport classes; nothing
   pre-existing was touched.

`PersistenceWiringConfigurationTest` gained three enabled-branch test methods
(`enabledSelectsJpaDatasetRepository`/`enabledSelectsJpaTrainingSampleRepository`/
`enabledSelectsJpaSampleImageStore`, mirroring `enabledSelectsJpaMarkRepository`'s own shape) plus
three more assertions in `disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider`.
`PersistenceWiringTest` gained three more `@Autowired` fields + test methods
(`defaultConfigurationKeepsInMemoryDatasetRepository`/`...TrainingSampleRepository`/
`...SampleImageStore`), same shape as `defaultConfigurationKeepsInMemoryMarkRepository`. New
`devsupport.InMemoryDatasetRepositoryTest` (5 tests)/`InMemoryTrainingSampleRepositoryTest` (7
tests, incl. the `findByDataset`/`countByDataset` status-filter-and-limit contract)/
`InMemorySampleImageStoreTest` (4 tests) — each proving the exact port contract
`Jpa*Repository`/`JpaSampleImageStore` (adapter-persistence) is judged against in its own Postgres
tests, the same "same contract, two implementations" pairing every other in-memory-vs-JPA repo in
this module already has.

`./mvnw -B -pl vision-domain,vision-application install -DskipTests` then `./mvnw -B -pl
storage/persistence,station/vision-app test -DskipWeb`: **adapter-persistence 101/101 green** (was
78, docker reachable in this environment — see that module's own MODULE.md for the full breakdown)
— **vision-app 171/171 green** (was 149) — `InMemoryDatasetRepositoryTest` (5, new) +
`InMemoryTrainingSampleRepositoryTest` (7, new) + `InMemorySampleImageStoreTest` (4, new) +
`PersistenceWiringConfigurationTest` (+3 test methods, +3 assertions in the existing disabled-branch
one) + `PersistenceWiringTest` (+3 test methods) = +22. `ArchitectureTest`'s 5 rules verified green
in isolation (`-Dtest=ArchitectureTest`, 5/5) against this wave's own code — **note for whoever next
runs the full scoped build**: at the time this wave finished, a concurrent, uncommitted, in-progress
change to `vision-application` from a parallel wave (T2, `TrainingFrameEncoder.java`, not part of
this module and not touched here) was independently failing
`ArchitectureTest.applicationDependsOnlyOnApplicationDomainAndJava` (`javax.imageio.*` calls — outside
the rule's `java..` allowlist, since `javax` and `java` are different top-level packages). That
failure is T2's to resolve, not this wave's; it is called out here only so a later reader doesn't
mistake it for something T3 broke. `AssetWiringTest` deliberately **not** touched — it only asserts
full-stack (repository + service + controller) bean triples, and no `DatasetService`/`LabelingService`
controller exists yet (T2/T4).

**Deviations from the brief**: none against the T1 frozen contract. `AssetWiringTest` was left alone
per the same judgment call `InMemoryMarkRepository`'s M2 wave made — a repository-only wave adds no
service/controller triple to assert.

## docs/plans/done/CV-TRAINING-PLAN.md Wave T4 done (CV model-improvement loop, REST + wiring)

Wave T4: exposes the already-green T2 (`DatasetService`/`LabelingService`, vision-application) and
T3 (JPA + devsupport repos, adapter-persistence/vision-app) layers over HTTP — `vision-api`'s
`DatasetController`/`LabelingController` plus this module's `TrainingWiringConfiguration`/
`VisionTrainingProperties`. See "CV training loop wiring" above for the wiring itself.

1. **`VisionTrainingProperties`** (new) — `vision.training.enabled` (default `false`) / `exportDir`
   (default `data/training-exports`, blank-rejected).
2. **`TrainingWiringConfiguration`** (new, separate `@Configuration`) — four
   individually-`@ConditionalOnProperty`-gated beans: `datasetExportPort`
   (`FilesystemDatasetExport`), `trainingStores`, `datasetService`
   (`DefaultDatasetService`), `labelingService` (`DefaultLabelingService`).
3. **`PersistenceWiringConfiguration`'s own doc comment** updated — `datasetRepositoryPort`/
   `trainingSampleRepositoryPort`/`sampleImageStorePort` (wired ahead of their consumer at T3) now
   have one: `TrainingWiringConfiguration`'s `trainingStores`/`datasetService`/`labelingService`.
4. **`application.yaml`** gained `vision.training.enabled=false` plus a commented default for
   `vision.training.export-dir`. **`.gitignore`** gained `/data/` (generated export zips, never
   committed, mirroring `/clips/`).
5. **No change to any pre-existing bean, `WiringConfiguration`, or `PersistenceWiringConfiguration`
   `@Bean` method** — this wave only added `TrainingWiringConfiguration`/`VisionTrainingProperties`
   and vision-api's two controllers/dto set (see station/vision-api/MODULE.md for the REST surface itself).

New tests: `VisionTrainingPropertiesTest` (4), `TrainingDisabledWiringTest` (3, the default-off
guardrail proof), `TrainingEnabledWiringTest` (5) — see "Test inventory" above for each. No
pre-existing test was modified.

`./mvnw -B -pl vision-domain,vision-application,storage/persistence install -DskipTests`
then `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb`:
**adapter-persistence 101/101 green** (unchanged — this wave touched no adapter-persistence file,
confirms nothing regressed), **vision-api 404/404 green** (was 373, +31: `DatasetControllerTest`
12 + `LabelingControllerTest` 19 — see station/vision-api/MODULE.md), **vision-app 183/183 green** (was
171, +12: `VisionTrainingPropertiesTest` 4 + `TrainingDisabledWiringTest` 3 +
`TrainingEnabledWiringTest` 5). `ArchitectureTest`'s 5 rules verified green as part of the same
`vision-app` run (vision-api still carries no `org.springframework.security` dependency; the acting
user reaches both new controllers through the existing `CurrentUser`/`PrincipalResolver` seam,
unchanged). Docker reachable in this environment — `adapter-persistence`'s
`PostgresDockerIntegrationTest` (incl. its T3-added `DatasetRepositoryTests`/
`TrainingSampleRepositoryTests`/`SampleImageStoreTests` nested classes) ran for real, not skipped.

**Deviations from the brief**: none against the frozen contract. Two judgment calls the plan left
open, resolved here (flagged for T5, the web labeling UI, to consume as documented):
`GET /api/datasets`/`GET /api/datasets/{id}/samples` wrap their arrays under `{"datasets":[...]}`/
`{"samples":[...]}` (the plan's own distinct `DatasetsResponse`/`SamplesResponse` type names, read
as intentionally different from this codebase's usual bare-array list convention — mirrors
`CvModelsResponse`'s `{"models":[...]}` shape, the one existing precedent for a wrapped list DTO);
`SampleResponse`'s nullable fields (`assetId`/`labeledBy`/`labeledAt`) are `@JsonInclude(NON_NULL)`
(omitted when absent) per this codebase's own DTO convention, rather than the plan's illustrative
JSON example literally serializing `null`. `DatasetResponse#sampleCounts` is computed by
`DatasetController` directly from `TrainingSampleRepositoryPort#countByDataset` (a read-only driven
port taken alongside `DatasetService`, the same "controllers call a driving-port service, driven
ports only read-only" exception `AssetController` already documents for its own image/telemetry
reads) — neither `DatasetService` nor `Dataset` itself carries a notion of sample counts.

## docs/plans/done/CV-TRAINING-PLAN.md Phase 2 T9 done (model registry — REST + shared-channel wiring)

T9 exposes the already-green `ModelRegistryService`/`DefaultModelRegistryService` (vision-application)
and `GrpcModelRegistryPort` (adapter-cv-grpc) over HTTP — `vision-api`'s `ModelRegistryController`
plus this module's `TrainingWiringConfiguration#modelRegistryPort`/`modelRegistryService` and
`CvWiring#cvGrpcChannel`. See "CV training loop wiring" and "CV inference wiring" above
for the wiring itself; this section is the decision record.

**The load-bearing decision**: `GrpcDetectionPort` (already wired since docs/plans/done/MVP1-PLAN.md §C7) and
the new `GrpcModelRegistryPort` both talk to cv-service, and the plan's own §7 explicitly calls for
one shared connection, not two independently configured ones. Before this task, `detectionPort`
built its channel *internally* via `GrpcDetectionPort`'s host/port convenience constructor — there
was no channel object in `WiringConfiguration`'s own bean graph to hand to a second consumer. Fixed
by extracting channel construction into its own `cvGrpcChannel` bean (same host/port/keepalive
tuning, moved rather than duplicated in spirit — though the actual keepalive constants had to be
*duplicated* as plain values, since `GrpcDetectionPort`'s own constants are package-private to
`adapter-cv-grpc` and this task's file scope was station/vision-api/vision-app only), gated to exist
whenever *either* `vision.cv.enabled` or `vision.training.enabled` is `true`
(`@ConditionalOnExpression`), and threading it into both `detectionPort` (via `ObjectProvider`,
since it's only conditionally present relative to `detectionPort`'s own always-registered `@Bean`
method) and `modelRegistryPort` (as a plain parameter, since `TrainingWiringConfiguration`'s own
`@ConditionalOnProperty` guarantees the channel exists on every branch where this bean is even
constructed).

**Shutdown ownership** (the second load-bearing decision the plan flagged): `GrpcDetectionPort#close()`
unconditionally shuts down whatever channel it holds, "whether the channel was built by this
instance or supplied by the caller" (its own javadoc) — correct when it privately owned the channel,
wrong now that the channel is shared. Rather than touching `GrpcDetectionPort` itself (out of this
task's file scope, and the brief's own hard constraint not to change its behavior), the fix lives
entirely in wiring: `cvGrpcChannel` gets `@Bean(destroyMethod = "shutdown")` (this bean, not either
port, owns the channel's lifecycle), and `detectionPort` gets an explicit `@Bean(destroyMethod = "")`
— opting out of Spring's destroy-method *inference* entirely, so `GrpcDetectionPort#close()` is
simply never called by the container and can't shut the shared channel out from under
`GrpcModelRegistryPort` (which never had a close method to begin with — see its own javadoc,
"Channel reuse", which documents this exact division of responsibility from the adapter side).
Trade-off accepted knowingly: `GrpcDetectionPort#close()`'s own per-stream graceful half-close
(`endAndClose` on every open `StreamSession`) no longer runs at context shutdown either, since nothing
calls `close()` at all anymore — acceptable because context shutdown means the process is going away
regardless, and the channel's own graceful `shutdown()` (not `shutdownNow()`) still lets any in-flight
call complete rather than aborting it. See the Gotchas entry ("FIXED-FORWARD") for the full before/
after reasoning on the destroy-method mechanics themselves.

**Gating**: `ModelRegistryController`/`modelRegistryPort`/`modelRegistryService` are gated by the
*same* `vision.training.enabled` property `DatasetController`/`LabelingController` already use — no
new property. `GET /api/cv/registry/models`/`POST /api/cv/registry/models/{id}/promote` are a
different endpoint family from `CvModelsController`'s existing `GET /api/cv/models` (the static,
config-backed picker for the Fly cockpit's model dropdown) — see `ModelRegistryController`'s own
javadoc for why they don't share a controller.

**No change to any pre-existing bean's construction beyond `detectionPort`'s own `destroyMethod`**
— `TrainingWiringConfiguration`'s four Wave-T4 beans, `PersistenceWiringConfiguration`, and every
other `WiringConfiguration` bean are untouched.

New tests: `ModelRegistryControllerTest` (vision-api, 7), `CvAndTrainingSharedChannelWiringTest`
(vision-app, new, 3) plus extensions (no new test *classes*, only new test *methods*) to
`TrainingDisabledWiringTest` (+2), `TrainingEnabledWiringTest` (+3), `CvWiringTest` (+1),
`CvEnabledWiringTest` (+2) — see "Test inventory" above for each. No pre-existing test method was
modified or deleted.

`./mvnw -B -pl vision-domain,vision-application,cv/grpc install -DskipTests` then
`./mvnw -B -pl station/vision-api,station/vision-app test -DskipWeb`: **vision-api 411/411 green** (was 404, +7:
`ModelRegistryControllerTest`), **vision-app 194/194 green** (was 183, +11 across the five test
classes above). `ArchitectureTest`'s 5 rules verified green as part of the same `vision-app` run —
vision-api still carries no `org.springframework.security` dependency; `ModelRegistryController`
reaches the acting user through the existing `CurrentUser`/`PrincipalResolver` seam, unchanged, same
as `DatasetController`. No docker-gated test in this scope (`adapter-persistence` wasn't touched).

**Deviations from the brief**: none against the frozen contract. One judgment call the brief left
open, resolved here: `POST /api/cv/registry/models/{id}/promote`'s response body. `ModelRegistryService#promote`
returns `void`, so rather than re-querying the registry after a successful promotion,
`ModelRegistryController` constructs the response directly from the now-known-promoted `ModelRef`
(`{id, version, active: true}`) — cheaper than a round trip and always consistent with what just
happened, at the cost of not reflecting any *other* model's `active` flag flipping to `false` in the
same response (the client already knows only one model is ever active, so this is a non-issue for
T10's consumption).

## docs/plans/done/CV-TRAINING-PLAN.md Phase 2 done (training-job flow — REST + shared-channel wiring, last backend wave)

Exposes the already-green `TrainingJobService`/`DefaultTrainingJobService` (vision-application) and
`GrpcTrainingPort` (adapter-cv-grpc) over HTTP — `vision-api`'s `TrainingJobController` plus this
module's `TrainingWiringConfiguration#trainingPort`/`trainingJobService`, over the **same**
`CvWiring#cvGrpcChannel` `modelRegistryPort` already uses. See "CV training loop wiring"
and "CV inference wiring" above for the wiring itself; this section is the decision record. This
closes the training loop's backend: capture (T4) → correct (T4) → export (T4) → **train (this
wave)** → promote (T9).

**The wiring decision was the easy half this time** — `cvGrpcChannel`'s `@ConditionalOnExpression`
already covered `vision.training.enabled` (T9 built it that way specifically so a training-only
deployment gets a channel without detection), so `trainingPort` just needed to become a third
consumer of the identical bean, taken as a plain constructor parameter exactly like `modelRegistryPort`
— no change to the channel bean's gating expression, no change to its destroy-method ownership
(`cvGrpcChannel` already owns `shutdown`; `GrpcTrainingPort`, like `GrpcModelRegistryPort`, never had
a close method to guard against in the first place — see that class's own javadoc, "Channel reuse").
`CvAndTrainingSharedChannelWiringTest` was extended (not duplicated) to assert all **three** ports
now resolve against the one channel, rather than adding a fourth wiring test class.

**Off-request-thread execution needed no wiring at all.** The brief flagged this as something to
verify, not something to configure: `DefaultTrainingJobService`'s own production constructor (used
unchanged by `trainingJobService`'s one-line assembly) already submits every run to its own internal
cached daemon-thread executor before `TrainingJobService#start` returns, so `GrpcTrainingPort#startTraining`'s
long, blocking, potentially many-epoch call runs entirely off whatever thread called `start` — this
module supplies no executor, thread pool, or `@Async` annotation of its own for it.

**Gating**: `TrainingJobController`/`trainingPort`/`trainingJobService` are gated by the *same*
`vision.training.enabled` property every other training-loop bean/controller already uses — no new
property, no new `VisionTrainingProperties` field.

**No change to any pre-existing bean's construction** — `CvWiring#cvGrpcChannel`'s own
`@Bean` method, `detectionPort`, `modelRegistryPort`/`modelRegistryService`, every Wave-T4 bean, and
`PersistenceWiringConfiguration` are all untouched; this wave only *added* two new `@Bean` methods to
`TrainingWiringConfiguration`.

New tests: `TrainingJobControllerTest` (vision-api, new, 9) plus extensions (no new test *classes* in
vision-app, only new test *methods*) to `TrainingDisabledWiringTest` (+1), `TrainingEnabledWiringTest`
(+3), `CvAndTrainingSharedChannelWiringTest` (+0 methods — the existing "both ports resolve"/"same
instance" tests were widened in place to a third port rather than adding a fourth, since they already
asserted the general shared-channel property, not a fixed count of consumers) — see "Test inventory"
above for each. No pre-existing test method was modified or deleted.

`./mvnw -B -pl vision-domain,vision-application,cv/grpc install -DskipTests` then
`./mvnw -B -pl station/vision-api,station/vision-app test -DskipWeb`: **vision-api 420/420 green** (was 411, +9:
`TrainingJobControllerTest`), **vision-app 198/198 green** (was 194, +4: `TrainingEnabledWiringTest`
+3, `TrainingDisabledWiringTest` +1). `ArchitectureTest`'s 5 rules verified green as part of the same
`vision-app` run — vision-api still carries no `org.springframework.security` dependency;
`TrainingJobController` reaches the acting user through the existing `CurrentUser`/`PrincipalResolver`
seam, unchanged, same as every other training-loop controller. No docker-gated test in this scope
(`adapter-persistence` wasn't touched).

## docs/plans/done/CV-TRAINING-V2-PLAN.md W6 done (REST + wiring + persistence cleanup)

`vision-app` half of docs/plans/done/CV-TRAINING-V2-PLAN.md §7's delta on top of the sections above — see "CV
training loop wiring" and "Replay frame extraction wiring" above for the wiring itself; this section
is the decision record + before/after proof.

**Deleted**: `TrainingWiringConfiguration#datasetExportPort` (the `FilesystemDatasetExport` bean —
that class itself is deleted, adapter-persistence); `VisionTrainingProperties#exportDir`/
`DEFAULT_EXPORT_DIR`; the commented-out `vision.training.export-dir` line in
`application.yaml`; `VisionTrainingPropertiesTest` (4 tests, no validation logic left to test
once `exportDir` — the one field with a compact-constructor check — was removed, the same "trivial
boolean record needs no dedicated test file" posture `VisionLiveProperties`/`VisionRcProperties`'s
own absent test files already set).

**Added**: `TrainingWiringConfiguration#datasetUploadPort`/`#replaySources`; `PublishWiring#replayFrameExtractionPort`;
`VisionPublishProperties.Mediamtx#playbackBase`; `devsupport.NoopReplayFrameExtractor`.

**Changed**: `PublishWiring#streamPublisherPort` switched to `MediamtxStreamPublisher`'s 4-arg
constructor; `TrainingWiringConfiguration#trainingStores`/`#labelingService`/`#trainingJobService`
all took new/changed collaborators (see "CV training loop wiring" above for each).

**Fixed a pre-existing, uncommitted `vision.training.enabled=true` in `application.yaml`** —
found already sitting in the working tree at the start of this wave, not something this wave's own
edits introduced (confirmed against the last commit, where the line reads `false`). Left as `true` it
silently broke the opt-in guardrail: `TrainingDisabledWiringTest`'s "every training bean absent by
default" assertions failed, and — because `CvWiring#cvGrpcChannel`'s
`@ConditionalOnExpression` also matches on `vision.training.enabled` — so did `CvWiringTest`/
`CvEnabledWiringTest`'s "no shared channel/registry beans exist without `vision.training.enabled`
too" assertions. Per this wave's own brief ("fix the config, never the pre-existing tests"), the
config line was reverted to `false`; none of the affected tests were touched.

New tests: `PublishWiringTest` +2 (`playbackUrlUsesThePlaybackBasePropertysDefaultHostAndPort`,
`defaultConfigurationSelectsTheMediamtxReplayFrameExtractor`); `TrainingEnabledWiringTest` +1
(`replaySourcesBundlesTheThreeReplayCollaborators`, plus one pre-existing test renamed in place —
`datasetExportPortResolves...` → `datasetUploadPortResolvesToTheGrpcImplementationOverTheSharedChannel`
— not a net-new assertion); `TrainingDisabledWiringTest`/`CvAndTrainingSharedChannelWiringTest`
unchanged in test *count* (assertions renamed/simplified in place, see "Test inventory" above).
`VisionTrainingPropertiesTest` deleted (-4).

`./mvnw -q -B -pl vision-domain,vision-application,cv/vision-proto,cv/grpc,video-output/publish-hls
install -DskipTests -DskipWeb` then `./mvnw -q -B -pl station/vision-api,storage/persistence install -DskipTests`
then `./mvnw -B -pl station/vision-app test`: **197/197 green** (was 198, net -1 = +3 new test methods across
`PublishWiringTest`/`TrainingEnabledWiringTest` minus the 4 deleted `VisionTrainingPropertiesTest`
cases). No pre-existing test's *behavior* was changed, only the stale-config fix above, which made
the pre-existing guardrail tests pass again rather than altering what they assert.

**Deviations from the brief**: none against docs/plans/done/CV-TRAINING-V2-PLAN.md §5/§7's frozen contract. The
`application.yaml` fix above was not asked for explicitly but falls squarely under this wave's
own "prove the default-config suites stay green; fix the config, never the pre-existing tests" bar —
flagged here rather than silently folded into an unrelated diff.

**Deviations from the brief**: none against the frozen contract. One judgment call, matching the
brief's own explicit instruction: `POST /api/datasets/{id}/train`'s path `{id}` is threaded into
`TrainingJobSpec` as a raw string, never parsed as a `DatasetId` — see station/vision-api/MODULE.md's own
entry for this wave for the controller-side reasoning; nothing on the wiring side depends on that
choice either way.

## docs/plans/active/LAYERING-REFACTOR-PLAN.md wave D — the WiringConfiguration split + config-extraction closeout

This wave (the last "spine" wave, absorbing the F1–F4 adapter-config-extraction waves into one pass
since `vision-app` is the one file none of those adapter-scoped waves could touch) did three things,
described in full in "Package shape" at the top of this file: (1) fixed the two real compile breaks
Part 1 named (`adapter-mavlink`'s `MavlinkSettings` visibility + constructor plumbing, `adapter-cv-grpc`'s
`GrpcDetectionPort`/`GrpcDatasetUploadPort`/`GrpcModelRegistryPort` constructor shapes) plus one the
plan didn't explicitly name but blocked compilation the same way (`adapter-publish-hls` needed a new
`PublishSettings` record and settings-taking constructor overloads that didn't exist yet); (2) split
`WiringConfiguration` (825 lines, 44 beans) into six `wiring/` classes and moved the three sibling
`@Configuration` classes alongside them; (3) created 6 new + extended 5 existing `@ConfigurationProperties`
records, all moved into `config/properties/`, and moved `BcryptPasswordHasher`/`VisionUserDetails`/
`DevPrincipalResolver`/`SecurityContextPrincipalResolver`/`SecuritySessionAuthenticator`/
`NoopSessionAuthenticator` into `security/`, `DetectionSessionCleanupEventPublisher`/
`LiveUpdateEventPublisher`/`LiveUpdateAuditTrail`/`LiveUpdateDetectionEventRepository` into `events/`,
and `AuthSeedRunner`/`SimulationResumeRunner` into `bootstrap/` — closing out §3's target package shape
for this module (no `controller/`/`dto/`/`service/`/`repository/` package here, and now no stray
classes directly under the `com.drones.vision.app` root package either).

**Build gate, exactly as specified**: `./mvnw -B -pl station/vision-app,station/vision-api,vision-application,video-input/rtsp,video-input/mjpeg,drone-link/mavlink,video-input/v4l2,video-output/publish-hls,video-output/overlay,cv/grpc,device-discovery/onvif-mdns-v4l2,simulation-sources/sim,storage/persistence -am test` — **BUILD SUCCESS**, every module green: vision-domain 391/391, vision-application 658/658, adapter-simulation 30/30, adapter-rtsp 48/48 (1 docker-gated skip), adapter-mjpeg 46/46, adapter-mavlink 135/135 (3 docker-gated skips), adapter-v4l2 22/22, adapter-publish-hls 72/72, adapter-cv-grpc 60/60, adapter-overlay 15/15, adapter-persistence 97/97, adapter-discovery 19/19, vision-api 430/430, **vision-app 197/197** (this module was red — uncompilable — at the start of this wave, so there is no directly-comparable "before" count for it specifically; every one of these 197 is a pre-existing test kept green, none rewritten to assert something new, per the plan's own guardrail). No docker daemon was reachable in this run, so every docker-gated integration test (adapter-rtsp/adapter-mavlink's SITL/mediamtx-dependent cases) skipped via its own `Assumptions.assumeTrue` rather than running — consistent with this repo's existing "skip cleanly without docker" convention, not a gap introduced by this wave.

**Compatibility constructors, one subtlety**: `VisionCvProperties`/`VisionDiscoveryProperties`/`VisionSimulationProperties` each kept a convenience constructor matching their pre-extension arity (so `VisionCvPropertiesTest`/`VisionDiscoveryPropertiesTest`/`SimulationResumeWiringConfigurationTest` needed no rewrite), delegating to the full canonical constructor with every new field defaulted. This alone breaks Spring Boot's automatic "single constructor → use it for binding" detection for a `@ConfigurationProperties` record (it falls back to a no-arg-constructor lookup and fails at context startup with "No default constructor found") — fixed by annotating the canonical (compact) constructor of each with `@ConstructorBinding` (`org.springframework.boot.context.properties.bind.ConstructorBinding`), telling Spring explicitly which of the now-multiple constructors to bind `application.yaml` through. Discovered by a real `BeanCreationException` at test time, not by inspection — flagged here since it's the one non-obvious mechanism this wave's properties-record extension pattern depends on, and every future extension of a record with a compatibility constructor needs the same annotation.

**Deferred / flagged, not silently dropped**:
- `adapter-publish-hls`'s `PublishSettings.Cadence` extracted every `CadenceEstimator` tunable named in the plan; `H264RecorderFactory`'s `tune=zerolatency`/`rtsp_transport`/connect-timeout options were deliberately left as literals (out of `PublishSettings.Encoder`'s scope) — `tune` is load-bearing for that class's own documented zero-reordering-delay latency guarantee, not a deployment knob, and connect-timeout wasn't named in the plan's representative key list.
- `VisionV4l2Properties` has only 2 fields (`publisher-buffer-capacity`/`close-join-timeout`); `default-video-size`/`default-framerate`/`default-input-format` were **not** added as properties — `V4l2VideoSource` has no constructor parameters for them today (its recognized `StreamDescriptor` options are per-device, passed straight to FFmpeg's own `v4l2` demuxer with no app-level default layer to bind onto), so adding properties with no consumer would have been dead configuration.
- `MavlinkManualControlSender`'s own `RcLinkRuntime.CLOSE_JOIN_TIMEOUT_MILLIS` (a private constant duplicating `vision.mavlink.close-join-timeout`'s value, 5s, byte-identical) was left untouched — Part 1 froze that class's constructor shape at `(MavlinkTelemetrySource, MavlinkSettings.Rc)` as the E-phase wave's one deliberate, already-closed change, and widening it further to also thread `closeJoinTimeout` through was out of this wave's scope.
- Deep historical narrative elsewhere in this file (task-by-task "bean added to `WiringConfiguration`" descriptions) was left as written rather than rewritten line-by-line — see "Package shape" at the top for why, and for the mechanical `WiringConfiguration#beanName` → current-class fixups that *were* made throughout.

## docs/plans/active/LAYERING-REFACTOR-PLAN.md wave H — final ArchUnit rules + MODULE.md sweep + reactor-wide verify

Closes out the plan (§7 row H). Added 4 new rules to `ArchitectureTest` alongside the 5 pre-existing
ones (left byte-for-byte unmodified, per §6.4) — see "Test inventory" above for the full list and
per-rule reasoning. All 4 new rules passed on first write against the already-landed A–G waves; no
production code needed to change, because there were no genuine violations left, only one rule-pattern
subtlety worth recording: a naive "`@RestController` only in `..api.controller`" rule would have failed
against `HlsProxyController` (`com.drones.vision.api.proxy`) — not a leftover violation but §3's own
documented design (`proxy/` for pass-through edges that own no application service), so the rule's
package list was written to include `..api.proxy` from the start rather than "discovered and loosened"
after a false failure. The plan's fourth proposed rule ("no adapter depends on another adapter") was
**not** duplicated — `adaptersDoNotDependOnEachOther` (one of the original 5) already expresses exactly
that, generically, via `slices().matching("com.drones.vision.adapter.(*)..")`.

**MODULE.md sweep**: found and fixed three stale cross-references left over from Wave B's `vision-api`
move, all in *other* modules' docs (not vision-api's own, which already showed the right package) — a
literal `com.drones.vision.api.HlsProxyController` FQN (missing the `.proxy` segment Wave B added) in
two spots in this file's own "Bean inventory"/"HLS proxy" sections and one in adapter-publish-hls/
MODULE.md, plus one stale file-path citation in adapter-publish-hls/MODULE.md pointing at
`station/vision-app/src/main/java/com/drones/vision/app/VisionPublishProperties.java` (pre-Wave-D root path;
the class lives in `config/properties/` now) alongside a `WiringConfiguration#streamPublisherPort`
mention corrected to `PublishWiring#streamPublisherPort` in that same sentence, since (unlike this
file's own deliberately-preserved historical journal, see "Deferred/flagged" above) that sentence in
adapter-publish-hls/MODULE.md carries no historical-narrative framing of its own — it's a present-tense
description of current wiring, so the old name there was a plain error, not a preserved record.
Everything else checked (old flat `vision-application` packages, old `exceptions/` plural, other
adapters' internal-split class names, `vision-web`/`cv-service` cross-references) was already correct.

**Full reactor `./mvnw -B verify`**: **BUILD SUCCESS**, every module green, 2224 tests total across the
reactor, 0 failures/errors. 4 pre-existing skips, all environment-gated and unrelated to this
refactor — `FfmpegVideoSourceLiveManualTest` (1, needs real hardware), `MavlinkSitlReturnHomeIntegrationTest`
(2)/`MavlinkSitlSmokeIntegrationTest` (1, both need a running SITL farm). Docker **was** reachable in
this run, so every docker-gated integration test actually ran rather than skipping (`MediamtxDockerIntegrationTest`
in both adapter-rtsp and adapter-publish-hls, `PostgresDockerIntegrationTest`'s full 17-nested-class
suite in adapter-persistence, `RtspSimulationDockerE2ETest` in this module) — all green. `cv-service`'s
own gate (`scripts/test.sh`, outside the Maven reactor) also independently verified: **193/193 passed**.

## docs/plans/done/MAP-REWORK-PLAN.md Wave C done (the map as a COP — wiring, devsupport, security check)

The wiring/devsupport third of Wave C. The REST + scoped-SSE third is in station/vision-api/MODULE.md; the
JPA + `V12__map_layers.sql` third is in storage/persistence/MODULE.md.

### Wiring (`ApplicationServiceWiring`, `PersistenceWiringConfiguration`)

Six new beans in `ApplicationServiceWiring` (`mapAccessPolicy`, `layerResolver`, `mapLayerService`,
`drawingService`, `mapLayerBootstrapRunner`, plus the reworked `markService`) and two in
`PersistenceWiringConfiguration` (`mapLayerRepositoryPort`, `drawingRepositoryPort`, gated by the
existing `vision.persistence.enabled` exactly like the other fifteen — no new flag). See the Bean
inventory above for each one's rationale; the three worth repeating here:

- **`layerResolver` is one bean, not one instance per service.** `copLayerId()`/`defaultLayerFor()`
  are `synchronized` find-or-create; their idempotence is a property of a single instance guarding a
  single repository, and three private instances would have quietly reintroduced the duplicate-COP-
  layer race the `synchronized` was there to prevent.
- **`mapLayerService` takes repositories, not services**, for its delete-cascade. Going through
  `MarkService`/`DrawingService` would have re-run a viewer gate on an operation already authorized
  at the layer level, and created a service cycle.
- **`mapLayerBootstrapRunner` is about timing, not correctness** — see its Bean inventory row.

### Spring Security: no change needed, and that was checked, not assumed

`SecurityConfig#securedFilterChain` already matches `"/api/**"` → `.authenticated()`, so
`/api/map/**` is covered the moment it exists. **No per-role HTTP rules were added, deliberately**:
map authorization is `MapAccessPolicy`'s, resolved per-layer from data (kind + ownership + grants +
membership), and a `hasRole(...)` matcher on these paths could only ever be a coarser, second,
drifting copy of it. The permit-all chain (`vision.auth.enabled=false`, the default) covers the new
routes through its existing `anyRequest().permitAll()`, unchanged.

The identity seam did change, in this module: `PrincipalResolver` (vision-api) gained
`MapAccessPolicy.Viewer viewer()`, and both implementations here implement it.

- `DevPrincipalResolver` → `Viewer(DevPrincipal.USER_ID, {DevPrincipal.GROUP_ID}, ADMIN)`. The
  map-side twin of its existing `VisibilityScope.unbounded()`: `MapAccessPolicy` grants ADMIN
  `MANAGE` on every layer, so an auth-disabled deployment sees the entire picture — byte-for-byte the
  visibility the unscoped marks stack had before layers existed. **This is what keeps the
  default-config bar green.**
- `SecurityContextPrincipalResolver` → the user's own direct membership groups **unioned with**
  `scopeResolver.scopeFor(user).groups()`, and `topRole` = the highest `Role` held (`PILOT` when
  none). **The group-subtree traversal is reused, not duplicated**: `DefaultScopeResolver#subtreeOf`
  already owns it, and for a MANAGER its result *is* the expanded subtree. The union matters in both
  directions — an ADMIN resolves to `UNBOUNDED` (no groups, but ADMIN already manages everything) and
  a PILOT to `ASSIGNED_ASSETS` (no groups either), so their direct memberships are the only thing
  making their own team's layer reachable. That asymmetry is the whole point: deriving the viewer
  from `VisibilityScope` instead would hit `includesGroup`'s hard-`false` for `ASSIGNED_ASSETS` and
  make every TEAM layer structurally invisible to the primary FPV-operator persona — the trap
  docs/plans/done/MAP-REWORK-PLAN.md §1 records.

### Tests

`./mvnw -B -pl station/vision-app test -DskipWeb`: **205/205 green** (was 203, +2).

- `InMemoryMarkRepositoryTest` 5→6 for the reworked `Mark` (every fixture carries a `LayerId`,
  `Affiliation` and `Verification`); the new case covers **promotion + review surviving an upsert**,
  since `withLayer`/`withVerification` are both save-over-the-same-id operations.
- `AssetWiringTest` 6→7: its `MarksController` assertions became the three map controllers plus the
  five new service/policy/repository beans, and a new `theCopLayerExistsOnceAfterStartup` proves the
  bootstrap runner ran **and** that `copLayerId()` is idempotent (exactly one `COP` layer in the
  repository afterwards) — the in-memory half of the invariant `V12__map_layers.sql`'s fixed-id seed
  provides on the Postgres side.
- `ArchitectureTest`: **9/9 rules green, no changes needed.** The new controllers/DTOs stay inside
  vision-api and touch only vision-domain + vision-application; `MapVisibility` lives in vision-api's
  `live` package and depends on `MapLayerService` (application), not on any adapter; Spring Security
  imports stayed confined to this module.

### Default-config bar (the acceptance gate), proven

Baseline measured by building this repo's `HEAD` in a throwaway git worktree against an isolated
local Maven repo, since Waves A/B had already left these modules red in the working tree:

| module | before (HEAD) | after | delta |
|---|---|---|---|
| adapter-persistence | 98 | 113 | +15 |
| vision-api | 460 | 521 | +61 |
| vision-app | 203 | 205 | +2 |
| **total** | **761** | **839** | **+78** |

All green in both runs, all under **default configuration** — `vision.auth.enabled=false`,
`vision.persistence.enabled=false`, `vision.live.enabled=true`. No pre-existing test was weakened or
deleted to get there; the only deletions are the ones the frozen contract required
(`MarksControllerTest`, whose subject no longer exists). Docker was reachable, so
adapter-persistence's Testcontainers suite **ran** rather than skipping, in both the before and after
measurements.

**Deviations from the brief**: none in this module.

**Honest gaps / explicitly out of scope:**
- **No `LiveWiringTest` coverage of `MapVisibility`'s bean gating.** It carries the same
  `@ConditionalOnProperty(vision.live.enabled)` as `LiveUpdateRegistry`/`LiveController` and
  `LiveDisabledWiringTest` still passes (the context starts clean with live off), but there is no
  explicit assertion that the bean is absent in that mode — inferred from context startup, not
  asserted directly.
- **The COP layer is created per-process when `vision.persistence.enabled=false`.** That is the
  in-memory contract working as intended (nothing survives a restart), but it does mean a dev-mode
  restart mints a *new* COP `LayerId`, so any client that cached the old one sees an unknown layer.
  Harmless today (Wave E refetches `GET /api/map/layers` on connect); worth remembering when
  debugging a dev session that looks like it "lost" its shared marks.

## docs/plans/done/TRACKING-PLAN.md wave T8 done (the default flip + the end-to-end proof)

`./mvnw -B -pl station/vision-app test`: **220/220 green** (was 218 — +2: `TrackingAssociateE2ETest`, and
`TrackingWiringTest` split its "default seed" test into the ASSOCIATE default plus a new
`aDeploymentCanStillPinTrackingOffAndThatIsTheOnlyWayToGetThePreTrackingBehaviour`).

**`vision.tracking.default-mode` flipped `OFF` → `ASSOCIATE`, and that is not a cosmetic companion to
the domain flip — it is half of it.** `TrackingWiring#streamStartTrackingSeed` states a mode
*unconditionally*, and `DefaultStreamService#start` folds the seed **over** `PipelineConfig.defaults()`
(request > deployment > code default). A seed still saying `OFF` therefore wins over the domain's new
`ASSOCIATE` on every real start path — device, asset, simulation, demo fleet — while
`PipelineConfigTest` happily goes green proving a flip that reaches nothing. That is exactly the
failure mode this deployment layer is shaped to produce, so it is called out in
`VisionTrackingProperties`' javadoc, in `TrackingWiringContextTest`, and here. A deployment that wants
the old behavior pins `vision.tracking.default-mode=OFF`, which `TrackingWiringTest` now covers as its
own test — the honest place for that decision to live.

**`TrackingAssociateE2ETest` — the wave's headline test.** Same shape as `CvDetectionE2ETest` (real
in-test loopback-TCP `Inference/DetectStream` server, real production wiring via `vision.cv.endpoint`,
asset+stream created through `AssetService`, endpoint hit through a hand-built `MockMvc`). It drives
the whole chain — proto → `DetectionFrameCodec` → `Detection.track()` → `TrackBook`/
`TrackingStatsWindow` → `GET /api/streams/{id}/tracks` — and asserts **identity**, not field presence:

- the servicer emits track-bearing detections **only** for a `FrameRequest` that actually states
  `TRACKING_MODE_ASSOCIATE`, so the test fails loudly if either half of the flip is reverted —
  **verified, not assumed**: reverting both production literals and re-running made it fail, and its
  failure message names the modes the server actually saw rather than just "expected true";
- ids `7`/`8` are sent on ≥3 **distinct** frame sequences with a box that drifts between them;
- the endpoint reports **exactly two** tracks. Identity dropped anywhere gives an empty list
  (`track == null` is filtered); identity churned gives one entry per frame. Two is only reachable if
  every layer carried the same id through;
- each track's `firstSeen` is strictly before its `lastSeen` — the book *merged* observations from
  different frames rather than replacing an entry, which is the identity claim itself;
- `stats.mode`/`stats.lastDetectorReason`/`stats.detectorPasses` are asserted too, as an independent
  second witness off the same responses.

**Gotcha this wave paid for, worth knowing before you debug a vision-app test:** `-pl station/vision-app`
resolves every adapter from `~/.m2`, and the `adapter-cv-grpc` jar there was **five days stale**
(pre-wave-T4). The full vision-app suite was green against it while `FrameRequest.tracking` was never
being set at all — the new E2E test is what surfaced it, reporting
`modes=[TRACKING_MODE_UNSPECIFIED]`. **Install the adapters before running `-pl station/vision-app test`**:
`./mvnw -B -pl cv/grpc,video-output/overlay install -DskipTests`. A green
vision-app suite proves nothing about a cross-module contract if the other module's jar predates it.

## docs/plans/done/TRACKING-PLAN.md wave T6 done (tracking wiring + deployment properties)

`./mvnw -B -pl station/vision-app test`: **215/215 green** (was 205 — +10: `config.wiring.TrackingWiringTest`
7 pure unit tests, `TrackingWiringContextTest` 3 context tests). **ArchUnit unchanged and green** —
the new `TrackingConfig`/`List<CvTrackerResponse>` beans are a domain record and a `vision-api` DTO
list, so no rule about adapter/`@ConfigurationProperties`/package placement is touched.

**The default-config bar, as it stood at T6:** every default in `VisionTrackingProperties` left
behavior exactly as it was. `default-mode=OFF` was the same value `PipelineConfig.defaults()` carried
through waves T2–T7, so a stream started with no request-side `tracking` object got
`TrackingConfig.off()` — asserted both without Spring and through the real context. The two window
keys map to the exact durations `StreamPipelineSettings.defaults()` already used
(`theDefaultWindowsAreByteIdenticalToTheSettingsRecordsOwnDefaults`).

**Wave T8 moved `default-mode` to `ASSOCIATE`** together with the domain default; both assertions
above were rewritten to the new truth rather than weakened. See the T8 section below for why the
property *had* to move with the domain and could not simply lag it.

New/changed here: `config/properties/VisionTrackingProperties.java`, `config/wiring/TrackingWiring.java`,
`ApplicationServiceWiring#streamService`/`#streamPipelineSettings` (one more properties argument, and
the settings mapping now states both tracking durations — the method was widened from `private
static` to package-private `static` purely so the binding could be unit-tested in-package rather than
inferred through a running stream), and a documented `vision.tracking.*` block in
`application.yaml`.

**Note for whoever picks up the follow-up:** `vision-api`'s `StreamController`/`AssetController` take
the seed as a constructor argument (4→5 and 5→6 respectively — the latter one past the five-argument
ceiling, flagged in station/vision-api/MODULE.md). The shape that would pay that down is moving the seed
beside `StreamPipelineSettings` in `vision-application`, where every other stream-start setting
already lives; it would also close the one gap this wave cannot reach from here — simulation-started
streams (`DefaultSimulationService`) build their own `PipelineConfig` and never see `vision.tracking.*`.
**Done — see the next section.**

## docs/plans/done/TRACKING-PLAN.md T3/T6 follow-up done (the seed moved to the application layer)

Exactly the follow-up the note above describes.

- **`TrackingWiring#streamStartTrackingDefaults` (a `TrackingConfig` `@Bean`) → `TrackingWiring.streamStartTrackingSeed(VisionTrackingProperties)` (a package-private `static` returning `application.stream.TrackingConfigPatch`).** No bean, because nothing autowires it any more.
- **`ApplicationServiceWiring#streamPipelineSettings` states it as the 14th component of `StreamPipelineSettings`**, right beside the two window durations it already mapped from the same properties record — so `#streamService` needed **no new argument** and no new collaborator appeared anywhere. `DefaultStreamService` folds it at every stream start.
- **A patch, not a config.** `default-mode`/`verify-every-millis`/`follow-fps` are stated; `engineId` and the three cv-service-owned knobs stay `null` and fall through to `TrackingConfig`'s own literals. Wave T6 restated those literals here; it no longer does, so "one number, one owner" now holds in code and not just in the comment.
- Consequences: `StreamController` 5→4 constructor arguments, `AssetController` 6→5 (back inside the java-clean-code §3 ceiling), and simulation- and demo-fleet-started streams pick up `vision.tracking.*` for the first time.

**Behaviour with the shipped defaults is unchanged**, and that is asserted rather than argued: `TrackingWiringTest#theDefaultSeedLeavesStreamStartsByteIdenticalToBeforeTrackingExisted` folds the mapped seed onto `PipelineConfig.defaults().tracking()` and gets the identical value back.

`TrackingWiringContextTest` no longer autowires a seed bean (there is none); it asserts the shipped `vision.tracking.*` values bind in a real context, and the fold itself is proven without Spring in `TrackingWiringTest`. `ArchitectureTest` is untouched and green (9/9) — the seed type is a `vision-application` record, so no rule about adapters, `@ConfigurationProperties` placement or Spring-free layers is involved.

`./mvnw -B -pl station/vision-app test`: **218/218 green**.

## docs/plans/active/MEDIA-SOT-PLAN.md wave M7 done (media source-of-truth wiring — mediamtx as the video source of truth)

Waves M1–M6 and M8 built every piece; this wave wires them into `vision-app` and fixes the compile
break M4 left behind (`GrpcCvSettings`'s canonical constructor grew three fields — `pullRtspBase`,
`pullReconnectInitialBackoff`, `pullReconnectMaxBackoff` — and `CvWiring#toGrpcCvSettings` still called
the old 11-arg form; no `vision-app` build had passed since). Scope: `station/vision-app/**` and
`docker-compose.yml`/`mediamtx.yml`/`.env.example` only — `vision-application`'s `DefaultStreamService`
(the 12-arg constructor and `PullDetectionSettings`/`PulledDetectionPort` fields it now takes) was
built by wave M5 and is read, not edited, here.

### Media source-of-truth wiring

**Switch A — who publishes video into mediamtx (`vision.publish.source-proxy.*`).**
`PublishWiring#streamPublisherPort` now builds a `PublisherRouter` (adapter-publish-hls) whenever
`vision.publish.enabled=true`, wrapping two publishers: `MediamtxStreamPublisher` (today's, unchanged
construction) as the direct publisher, and a new `MediamtxProxyPublisher` (`mediamtx.apiBase()` +
`MediamtxProxySettings` mapped from the new `VisionPublishProperties.SourceProxy` record plus
`mediamtx.apiUser()`/`apiPassword()`) as the proxy publisher. The router itself decides per device
(RTSP protocol + `source-proxy.enabled`) which one actually handles a stream — see `PublisherRouter`'s
own javadoc — so `vision-app` still wires exactly **one** `StreamPublisherPort` bean. With
`source-proxy.enabled=false` (default, D1), the router never routes anywhere but the direct publisher,
so runtime behaviour — every URL shape, every publish call — is byte-identical to the plain
`MediamtxStreamPublisher` bean this method used to return; `PublishWiringTest` was updated to assert
`instanceof PublisherRouter` (the new top-level type) while every other assertion in that class
(view/whep/playback URL shape) is untouched and still green, proving the wrap is behaviourally
invisible by default.

**Switch B — how frames reach CV (`vision.cv.frame-transport`).** `CvWiring` gained a conditional
`pulledDetectionPort` bean (`GrpcPulledDetectionPort` over the shared `cvGrpcChannel`, present only
when `VisionCvProperties#pullEnabled()` — i.e. `vision.cv.frame-transport=pull`). `cvGrpcChannel`'s own
`@ConditionalOnExpression` grew a third disjunct (`'${vision.cv.frame-transport:push}' == 'pull'`) so
the shared channel exists whenever *any* of the three CV-consuming flags is on — without that, turning
on pull mode alone (leaving `vision.cv.enabled=false`) would NPE on a missing channel bean.
`ApplicationServiceWiring#streamService` builds a `PullDetectionSettings` (`vision-application`) from
that bean plus `cvProperties.pull().rtspBase()` only when `pullEnabled()`, and threads it into
`DefaultStreamService`'s new 12-arg constructor; `null` (the default) reproduces the pre-wave-M5 11-arg
constructor's behaviour exactly.

**The rejected legal-combination row (A=proxy, B=push).** Checked once, at wiring time, in
`PublishWiring#rejectProxyWithPushTransport` — called from the top of `streamPublisherPort`'s own bean
method, since that is the one place `vision-app` already builds both `VisionPublishProperties` and (as
a second parameter now) `VisionCvProperties` together. The check could not live inside
`DefaultStreamService#start` (the natural per-stream spot) because that class is `vision-application`,
outside this wave's file scope and concurrently owned by another wave for an unrelated fix. The
rejection message:

> `vision.publish.source-proxy.enabled=true (mediamtx dials the camera itself) requires
> vision.cv.frame-transport=pull -- a proxied source means this JVM never holds a video frame
> (docs/plans/active/MEDIA-SOT-PLAN.md D4), so leaving vision.cv.frame-transport=push (its current
> value) would start a stream that can never detect: there would be nothing for push mode to send
> cv-service. Set vision.cv.frame-transport=pull, or leave vision.publish.source-proxy.enabled=false.`

Both flags default to the legal `(A=false, B=push)` row, so this `IllegalStateException` never fires in
the default configuration.

**The live-frame fallback.** `MediamtxLiveFrameGrabber` (built by wave M6) was wired but never
consumed — in proxy mode `DefaultStreamService` opens no `VideoSourcePort` at all (D4), so its
pipeline's cached `latestFrame`/`latestRawFrame` (backing the snapshot endpoint and training-sample
capture) sit permanently empty for a proxied stream. `PublishWiring#mediamtxLiveFrameGrabber` is a new,
unconditional bean (cheap — no I/O until `grab()` is actually called); a new class,
`com.drones.vision.app.stream.LiveFrameFallbackStreamService` (a plain `StreamService` decorator, the
same wiring-layer-decorator idiom `LiveUpdateEventPublisher`/`DetectionSessionCleanupEventPublisher`
already use for other ports in this module), wraps `DefaultStreamService`'s output and falls back to a
live grab from mediamtx's own RTSP output when the delegate's `latestFrame`/`latestRawFrame` come back
empty **and** the stream is actually running (checked via `streams()` first, so an unknown/stopped
stream stays exactly as fast as it is today instead of waiting out the grabber's 5s connect timeout).
`ApplicationServiceWiring#streamService` only constructs this decorator when
`VisionPublishProperties.SourceProxy#enabled()` is `true` — with the default `false` (D1), the plain
`DefaultStreamService` is returned directly and `LiveFrameFallbackStreamService` is never even
constructed, let alone wrapped around anything, in the default configuration.

**Two silent-failure modes from M6, now respected in config comments** (`application.yaml`,
`docker-compose.yml`): (1) with `source-proxy.on-demand=true`, mediamtx never dials the camera until a
viewer connects, so `MediamtxProxyPublisher#streamStarted` skips the readiness poll entirely in that
mode — without the skip, every on-demand start would time out waiting for a camera nobody has asked to
watch yet. (2) a proxied source pointing at another path on the *same* mediamtx must use the
container-internal RTSP port (`vision.publish.mediamtx.rtsp-base`'s compose-internal value,
`rtsp://mediamtx:8554`), not the host-mapped one (`rtsp://localhost:8554`) — using the host-mapped port
from inside a sibling container fails silently as a readiness timeout, not a create error.

### Config surface added (§5.5, every default reproducing today's behaviour exactly — D1)

| Property | Default | Notes |
|---|---|---|
| `vision.publish.source-proxy.enabled` | `false` | switch A |
| `vision.publish.source-proxy.on-demand` | `false` | D10 |
| `vision.publish.source-proxy.rtsp-transport` | `automatic` | passed to mediamtx |
| `vision.publish.source-proxy.ready-timeout` | `10s` | readiness poll budget; ignored when `on-demand=true` |
| `vision.publish.mediamtx.api-base` | `http://localhost:19997` | joins the `hls-base`/`whep-base`/`playback-base` family |
| `vision.publish.mediamtx.api-user`/`.api-password` | unset | alternative to the `mediamtx.yml` mount below |
| `vision.cv.frame-transport` | `push` | switch B — `push` \| `pull` |
| `vision.cv.pull.rtsp-base` | `rtsp://localhost:8554` | the address the **worker** dials — deliberately separate from `vision.publish.mediamtx.rtsp-base` |
| `vision.cv.pull.reconnect-initial-backoff` | `500ms` | mirrors `vision.publish.resilience.initial-backoff` |
| `vision.cv.pull.reconnect-max-backoff` | `10s` | mirrors `vision.publish.resilience.max-backoff` |

No magic numbers: every default above is a named constant on its properties record (`VisionPublishProperties.SourceProxy`/`.Mediamtx`, `VisionCvProperties.Pull`), matching this module's existing `@DefaultValue` + `static final` idiom.

### `docker-compose.yml` / `mediamtx.yml` (the blocking deployment correction, §5.3)

mediamtx's Control API is IP-gated by its own baked-in `authInternalUsers`: unauthenticated `api`
action access is granted only to a caller at `127.0.0.1`/`::1`. A docker-published port does not
preserve that view, and neither does a sibling container calling over the compose network — so
`vision-app` calling from the `vision-app` service got `401` on every Control API call. `MTX_API: "yes"`
alone does not fix this, and `MTX_AUTHINTERNALUSERS` as an env-var override was tried by wave M0 and
does not work (no list-of-struct override for that field).

**Fix chosen: mount a widened `mediamtx.yml`, not API credentials.** A new file at the repo root,
`mediamtx.yml`, is bind-mounted read-only to `/mediamtx.yml` (the mediamtx image's own default
config-file lookup, at that image's own cwd `/` — `FROM scratch`, no `WORKDIR`, the same fact this
compose file's `MTX_PATHDEFAULTS_RECORD` comment already established for the recordings path). It
widens the baked-in `authInternalUsers`' "any" user's `ips` from `["127.0.0.1","::1"]` to `[]` (no
restriction), restating `publish`/`read`/`playback` alongside the widened `api`/`metrics`/`pprof` so
nothing already-unauthenticated is narrowed. Every other key mediamtx already defaults is left
unspecified (this file "is the default baked-in config with two deliberate overrides" — wave M0's own
description of the spike config this file is derived from, `cv/cv-service/spikes/pull/results/
mediamtx-spike.yml`, read for its measurement and not copied verbatim: spikes/ is scratch space, not a
deploy artifact). Every existing `MTX_*` environment variable in `docker-compose.yml` still applies as
an override on top of this mounted file, exactly as it already did against mediamtx's un-mounted
baked-in config. **Why the mount over `vision.publish.mediamtx.api-user`/`api-password`**: the mount
requires no change to how the demo stack is reached (still no auth needed inside the compose network,
matching every other mediamtx port's own posture) and needs no secret to manage; the credential pair
stays supported in `VisionPublishProperties.Mediamtx`/`MediamtxProxySettings` as the documented
alternative for a deployment that reaches mediamtx over something less trusted than a private compose
network.

`docker-compose.yml`'s `mediamtx` service also gained: `MTX_API: "yes"` / `MTX_APIADDRESS: ":9997"`
(pinned explicitly, the same "future-drift" reasoning already applied to `MTX_HLS*`/
`MTX_PLAYBACKADDRESS`), and a host port mapping for `9997` (same collision-avoidance numbering
convention as `18888`/`18889`/`19996` — **see the follow-up hardening below for its current, corrected
binding**). The `vision-app` service gained
`VISION_CV_PULL_RTSP_BASE: ${VISION_CV_PULL_RTSP_BASE:-rtsp://mediamtx:8554}` — inert while
`vision.cv.frame-transport` stays at its default `push`, and overridable via `.env` (documented in
`.env.example`, mirroring `VISION_WEBRTC_HOST`'s own pattern) to a LAN address for a remote worker (the
GB4005 box) that cannot resolve this compose network's own `mediamtx` hostname.

`docker compose config` is clean (verified — renders without error, the mount resolves, the new port
mapping and env var both appear as expected in the rendered config).

#### Follow-up hardening: the host port mapping was `19997:9997` (all interfaces), corrected to loopback-only

M7 shipped the host mapping as `"19997:9997"`, binding all host interfaces — this was the actual
security defect, found and fixed on `feat/media-sot` right after M7 landed (commit `25cd8f9`), not a
separate wave. Combined with `mediamtx.yml`'s `ips: []` widening (needed so the sibling `vision-app`
container stops 401ing), an all-interfaces publish meant: anyone who could reach the Docker host on
port 19997 — from the LAN, or the internet if the host is exposed, which matters concretely because
this stack is deployed on field servers as a drone command point (`CLAUDE.md`'s deployment section) —
could create, repoint, or delete mediamtx paths with **zero credentials**. Unlike the media ports
(`8554`/`18888`/`18889`) that mediamtx already serves openly by design, the Control API is a
path-mutation surface — qualitatively different, and not something an unauthenticated stranger should
reach.

**Fix: `"127.0.0.1:19997:9997"`.** Verified, not assumed, that this loses nothing real:
`MediamtxProxyPublisher` (used only when `source-proxy.enabled=true`, still off by default, D1) is
called exclusively from inside the `vision-app` container, which reaches mediamtx's Control API over
the compose network at `http://mediamtx:9997` — the container-internal port, never this host mapping
at all, same "container port, not host port" rule already established for `rtsp-base`/`hls-base`
above. A host-run `vision-app` (Quickstart, `application.yaml`'s own `http://localhost:19997`
default) still reaches it fine — loopback *is* localhost. `docker compose config` (re-verified after
this change) renders `host_ip: 127.0.0.1` on the `9997` mapping only. `mediamtx.yml`'s own header
comment, which had asserted "mediamtx is never directly exposed past the compose network's own port
mappings", was corrected to state the truth — that assertion was wrong (the host mapping *was* an
exposure path; that's exactly what this fix closes), and the mistake is kept documented in that file
rather than silently rewritten, so a future reader doesn't re-derive the same wrong assumption.

**Port `19996` (Playback API) was checked for the same issue and deliberately left published on all
interfaces** — a considered decision, not an oversight. It's read-only (`GET /get`/`GET /list`, no
path create/patch/delete), and `UsageRecordingResponse#url` (`UsageTimelineController#recording`,
vision-api) hands its URL straight to the *browser*, unproxied — the same "must be reachable by the
viewer's browser, not just this app's own JVM" shape as WHEP's `18889`, not the Control API's
"only vision-app itself ever calls this" shape. Binding it to loopback would silently break clip
export/replay for every viewer not on the Docker host itself. It was also already unauthenticated by
mediamtx's own stock default before M7 touched anything (the `playback` action already grants
`ips: []` in mediamtx's baked-in `authInternalUsers`, grouped with `publish`/`read` — `mediamtx.yml`
restates that grouping, it doesn't widen it).

`application.yaml`'s `api-user`/`api-password`/`api-base` comments were strengthened to say plainly
that a mediamtx **not co-located** with `vision-app` on the same Docker host (a different deployment
shape than this file, which always runs both as sibling containers) cannot lean on loopback binding at
all — that topology requires real Basic-auth credentials against a real user in the remote mediamtx's
own `authInternalUsers`, not an `ips: []` widening. `.env.example` documents the worked example:
`VISION_PUBLISH_MEDIAMTX_API_BASE` / `VISION_PUBLISH_MEDIAMTX_API_USER` /
`VISION_PUBLISH_MEDIAMTX_API_PASSWORD`, mapping onto `vision.publish.mediamtx.api-base`/`api-user`/
`api-password` via Spring's standard relaxed env-var binding (same convention as the already-wired
`rtsp-base`/`hls-base`/`whep-base` vars) — documented for a deployment that departs from this compose
file, not wired into it, since this file always co-locates mediamtx with `vision-app` and neither var
is read here today.

No Java code, application property defaults, or wiring changed — comments and the one port-binding
string only. `./mvnw -B -pl station/vision-app test -DskipWeb`: 220/220 green, unchanged from before this
correction (nothing it touches is exercised by any test).

### Tests

- **`PublishWiringTest`**: one method renamed/updated (`defaultConfigurationSelectsMediamtxPublisher` →
  `defaultConfigurationSelectsPublisherRouterWrappingMediamtxPublisher`, now asserting
  `instanceof PublisherRouter`); every other method in the class is untouched and still green — the
  strongest available proof that wrapping the router around the default-config publisher changes
  nothing observable.
- **`CvWiringTest`/`CvEnabledWiringTest`/`TrainingDisabledWiringTest`/`VisionCvPropertiesTest`**:
  untouched, still green — `cvGrpcChannel`'s widened `@ConditionalOnExpression` still evaluates `false`
  under every default (`vision.cv.enabled=false`, `vision.training.enabled=false`,
  `vision.cv.frame-transport=push`), so `noSharedCvGrpcChannelBeanExistsByDefault` still holds.
- **`ArchitectureTest`**: untouched, still green, 9/9 — the dependency rule holds: the new
  `LiveFrameFallbackStreamService` lives in `com.drones.vision.app.stream` (this module), depends only
  on `vision-application`'s `StreamService`/domain types and `adapter-publish-hls`'s
  `MediamtxLiveFrameGrabber` — both already-legal directions for `..app..` code.

### Default-config bar (the acceptance gate), proven

**Before this wave: `vision-app` did not compile at all** (M4's `GrpcCvSettings` arity change, see
above) — zero tests could run, reactor-wide or scoped. **After:** `./mvnw -B -pl station/vision-app test`:
**220/220 green**, including `ArchitectureTest` (9/9) and a real docker-gated IT
(`RtspSimulationDockerE2ETest`, ran against real docker in this environment, not skipped). Every
default (`vision.publish.source-proxy.enabled=false`, `vision.cv.frame-transport=push`) reproduces
today's behaviour exactly, proven by the untouched-and-still-green tests above rather than merely
argued.

**Reactor-wide `./mvnw -B verify`** — the first time this has been possible since wave M4 broke the
compile — was run for this wave; see the task's own final report for its result (this file is written
before that long-running command's final status was known, to keep the docs update inside the same
task turn as the code that motivated it — the actual pass/fail is not re-litigated here after the fact
if it was already reported honestly elsewhere).

### Deferred / not this wave

- **M9** (measure the real path against a real camera, amend `CV-SCALE-PLAN.md` §S5) is next and
  depends on this wave plus M8.
- The `anchor` clock-mode drift measurement (M0's spike) was against a synthetic source with no
  independent oscillator — M9 must re-measure against a real H1 camera before the 100ms budget is
  treated as settled. Nothing in this wave touches that.

## docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md wave J3 (deployment defaults for the capability
ladder + ORU)

`VisionTrackingProperties` gained `capabilityLevel`/`reupdateMaxGapMillis` (`vision.tracking.capability-level`
`[0,5]` default `0` = auto-probe; `vision.tracking.reupdate-max-gap-millis` `>=0` default `0` = server
default), validated in the compact constructor with a message naming the property, exactly like every
other knob here. `TrackingWiring#streamStartTrackingSeed` now builds the 10-arg
`TrackingConfigPatch` (J1's widened canonical constructor), stating both new fields
**unconditionally** — same treatment as `default-mode`/`verify-every-millis`/`follow-fps` — since this
deployment layer owns both (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2). Both default literals
live only as `VisionTrackingProperties.DEFAULT_CAPABILITY_LEVEL`/`DEFAULT_REUPDATE_MAX_GAP_MILLIS`
(invariant B4) and are documented, commented-out, in `application.yaml` alongside the other five
tracking keys. See the "Tracking engine wiring" section above for the full seven-key table.

**Invariant B2 (zero behavioural change at defaults) is structural, not incidental**: `0`/`0` is
`TrackingConfig.off()`/`.defaults()`'s own literal for both fields, so a deployment that configures
nothing folds to a byte-identical `TrackingConfig` whether or not this wave's two new properties
exist — `TrackingWiringTest#theDefaultCapabilityPropertiesFoldToByteIdenticalDomainDefaultsSoNothingChangesForAnUnconfiguredDeployment`
pins it directly. `TrackingWiringContextTest` additionally proves the two properties bind to `0`/`0`
in a real Spring context.

**Invariant B5 (a level is a ceiling)**: this wave's javadoc (`VisionTrackingProperties`,
`TrackingWiring#streamStartTrackingSeed`) is explicit that `capabilityLevel` here is what the
deployment is *willing to afford*, never what will be *served* — the served answer only exists once
cv-service reports `TrackingTelemetry#capability()` for a real frame, surfaced by `vision-api`'s
`FrameTrackingResponse#capability()` (see station/vision-api/MODULE.md's own wave J3 section).

**Tests**: `TrackingWiringTest` +5 (`deploymentPropertiesSeedTheCapabilityLevelAndReupdateMaxGapMillisTheyOwn`,
`theSeedFoldsTheCapabilityLevelAndReupdateMaxGapMillisOntoTheDomainDefaults`,
`theDefaultCapabilityPropertiesFoldToByteIdenticalDomainDefaultsSoNothingChangesForAnUnconfiguredDeployment`,
`anOutOfRangeCapabilityLevelIsRejectedByThePropertiesRecordItselfNamingTheProperty`,
`aNegativeReupdateMaxGapMillisIsRejectedByThePropertiesRecordItselfNamingTheProperty`) plus one
extended pre-existing assertion (`deploymentPropertiesSeedTheModeAndTheTwoCadencesTheyOwnAndNothingElse`
now also asserts the two new fields default to `0`); `TrackingWiringContextTest` +1
(`defaultConfigurationLeavesCapabilityLevelAtAutoProbeAndReupdateMaxGapAtTheServerDefault`). No
pre-existing assertion was changed — every widened call site (`VisionTrackingProperties`'s own 5-arg
test helper, kept as an overload delegating `0, 0`) is additive.

**Build status — a pre-existing, unrelated blocker, not this wave's fault.** `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` currently fails to *compile*
`vision-app` because the locally-installed `adapter-cv-grpc` artifact in `~/.m2` predates this
session (references the retired flat `vision-domain`/`vision-application` modules and
`com.drones.vision.domain.*` packages the domain-separation work removed) — `adapter-cv-grpc` is
concurrently owned by wave J2 in this same delegation round and had uncommitted changes at the time
this wave ran, so per the task's own instruction this wave did not install it or otherwise "reach
into that module." Diagnosis, to rule out any contribution from this wave's own diff:
- Refreshed (`mvn install -DskipTests`) the eight *other* adapters (`adapter-simulation`/`-rtsp`/
  `-mjpeg`/`-mavlink`/`-v4l2`/`-publish-hls`/`-overlay`/`-discovery`), none of which had any pending
  edit from any concurrent wave (`git status` confirmed) — this left `adapter-cv-grpc` as the
  *only* remaining stale dependency, and every one of `vision-app`'s 17 compile errors traces to it
  (`ManagedChannel`/`io.grpc` unresolved, `com.drones.vision.domain.port.out.*` class files missing)
  — none touch a file this wave edited.
- Hand-compiled (`javac`) this wave's two production files —
  `config/properties/VisionTrackingProperties.java` and `config/wiring/TrackingWiring.java` — against
  the real, currently-installed `vision-kernel`/`vision-platform`/`vision-perception`/`vision-api`
  jars (i.e. against J1's actual widened `TrackingConfigPatch` 10-arg constructor, not a guess at its
  shape): zero errors.
- `vision-api`'s own suite (this wave's other half) ran clean end to end: see station/vision-api/MODULE.md.

**Before/after, vision-app**: baseline **220/220** (last recorded green run, this file's own history
above); this wave adds **6** tests (`TrackingWiringTest` +5, `TrackingWiringContextTest` +1) → **226**
expected once `adapter-cv-grpc` is reinstalled by wave J2 or a maintainer. Re-run `./mvnw -B -pl
storage/persistence,station/vision-api,station/vision-app test -DskipWeb` after that lands; nothing further
is expected to change on the `vision-app` side of this wave.
- CV-SCALE §S2 demand gating and §S4 worker pooling remain open seams (§9 of the plan), not built here.

## docs/plans/active/CV-DEMAND-PLAN.md wave D2 done (system-derived detection demand — wiring + deployment properties)

The vision-app half of the plan D1 (`contexts/vision-perception`, out of scope, not touched) and D3
(`station/vision-web`, out of scope, not touched) needed: the `DetectionDemandPort` bean itself
(implementation lives in `vision-api`, see that module's own wave D2 section), the deployment defaults
that decide what a brand-new stream's detection starts at and how the demand gate behaves, and the
`ApplicationServiceWiring` plumbing that threads both into `DefaultStreamService`.

**`VisionCvProperties` gained two fields** (`vision.cv.*`, `@ConfigurationProperties`):
`detectionDefaultEnabled` (`boolean`, default `false` — the plan's own pinned "just video until an
operator turns it on" stance) and `demand` (nested `Demand` record: `enabled` default `true`,
`pollInterval` default `2s`, `grace` default `30s`, `pollTtl` default `10s`). A new N-1-arg
convenience constructor keeps every pre-wave-D2 call site compiling, defaulting both to their pinned
values — same "N-1-arg convenience ctor" idiom this record already uses for every prior extension.
Fully documented, including every default and what wires each one, in `VisionCvProperties`'s own
javadoc and the commented-out `vision.cv.detection-default-enabled`/`vision.cv.demand.*` block in
`application.yaml` (search that file for `CV-DEMAND-PLAN` to find both).

**`CvWiring` gained three beans**:
- `LiveAndPollDetectionDemand detectionDemandPort(VisionCvProperties, ObjectProvider<LiveUpdateRegistry>)`
  — `@ConditionalOnExpression("${vision.cv.demand.enabled:true}")`, the **only** wiring decision this
  wave makes with a conditional: `false` means this bean is never created at all (not swapped for a
  no-op), so `ApplicationServiceWiring#streamService`'s `ObjectProvider<DetectionDemandPort>` resolves
  to nothing, `DefaultStreamService` never schedules its demand-poll task, and every stream stays
  fail-open on demand exactly as it behaved before this plan existed — the documented escape hatch for
  a deployment that needs unattended detection running with nobody watching. Returns the **concrete**
  type (not the `perception.domain.port.DetectionDemandPort` interface) because `streamDetectionSupport`
  needs the concrete class to reach `touched(StreamId)` directly; `ApplicationServiceWiring#streamService`'s
  `ObjectProvider<DetectionDemandPort>` still resolves the same singleton by assignability, so no
  `@Qualifier` is needed on either consumer (there is only ever one bean of this type, unlike the five
  `LiveUpdateRegistry` selector beans this class's own javadoc contrasts it with). Resolves the SSE
  `Predicate<AssetId>` seam once here, from `LiveUpdateRegistry#watchingDetections`, falling back to
  `assetId -> false` when `vision.live.enabled=false` too (the SSE registry bean itself conditionally
  absent) — in which case this deployment's demand can only ever come from the poll half.
- `PipelineConfig streamDefaultConfig(VisionCvProperties)` — `PipelineConfig.defaults()` with
  `detectionEnabled` replaced by `detectionDefaultEnabled()`; every other field byte-identical to the
  domain default. The one bean four different vision-api start paths
  (`StreamController`/`AssetStreamController`/`StartAssetStreamRequest`/`DemoFleet`, see
  vision-api/MODULE.md's own wave D2 section) merge request overrides onto, so this one property key
  reaches every non-simulation stream start identically.
- `StreamDetectionSupport streamDetectionSupport(PipelineConfig, ObjectProvider<LiveAndPollDetectionDemand>)`
  — bundles the two beans above for `StreamController`'s fifth constructor parameter;
  `detectionDemandPort.getIfAvailable()` resolves to `null` exactly when the first bean was not
  created (demand gate disabled), which `StreamDetectionSupport` already treats as "nothing to
  touch."

**`ApplicationServiceWiring#streamService` gained a 17th parameter**, `ObjectProvider<DetectionDemandPort>
detectionDemandPort` — an `ObjectProvider` (not the plain type) precisely because `CvWiring
#detectionDemandPort` is itself conditionally present; resolving to `null` when absent reproduces
`DefaultStreamService`'s pre-wave-D2 constructor exactly, threaded through as
`detectionDemandPort.getIfAvailable()`. `streamPipelineSettings` (the static helper this bean calls)
gained a third parameter, `VisionCvProperties cvProperties`, purely to read `cvProperties.demand()`
and map `pollInterval()`/`grace()` onto `StreamPipelineSettings#detectionDemandPollInterval()`/
`#detectionDemandGrace()` — threaded through **unconditionally** (regardless of whether the demand
port bean actually exists) since `StreamPipelineSettings`'s own compact constructor requires both
regardless; they simply go unused by `DefaultStreamService`'s demand-poll task when that task is never
scheduled.

**Three pre-existing E2E tests needed a fix that was not this wave's own regression, but is
documented here since the fix lives entirely in this module's test tree**: `CvDetectionE2ETest`,
`TrackingAssociateE2ETest`, `CvDetectionEndpointE2ETest` all call `assetStreamService.startStream
(asset.id(), null, PipelineConfig.defaults())` directly, bypassing every controller-level default
this wave added. Two independent, compounding effects broke them once D1/D2 landed:

1. D1's already-landed `PipelineConfig.defaults()` flip (`detectionEnabled` `true`→`false`) meant
   these three tests, which start a stream **directly through `AssetStreamService`**, now started with
   detection off — no detection ever ran, so nothing these tests assert on ever arrived.
2. Even with detection re-enabled, `DefaultStreamService`'s demand-poll task seeds a newly-started
   stream's `lastDemandAt` to `Instant.EPOCH` (not `Instant.now()`) — so a stream stays fail-open only
   until the **first** poll tick (`detectionDemandPollInterval`, default 2s); after that, a stream
   nobody ever polls/subscribes to flips permanently to `detectionDemand=false`. None of these three
   tests ever open the SSE `detections:<assetId>` topic or poll `GET .../detections`
   (`CvDetectionEndpointE2ETest`'s own polling loop *does* poll it, but only after starting the
   stream — the ~2s race was not something to depend on for a deterministic test).

Fixed identically in all three: added `registry.add("vision.cv.demand.enabled", () -> "false")` to
the `@DynamicPropertySource` method (the documented escape hatch — keeps `detectionDemand` fail-open
for the whole test, sidestepping the EPOCH/poll-tick timing entirely) and added a private static
`detectionEnabledDefaults()` helper (`PipelineConfig.defaults()` with `detectionEnabled=true` via the
10-arg canonical constructor) at the `startStream(...)` call site, replacing the bare
`PipelineConfig.defaults()`. Each fix is documented in the affected test's own javadoc/inline comment,
explaining why the escape hatch — not a timing-dependent wait — is the correct fix: these tests exist
to prove the gRPC/detection-results pipe works end to end, not to also litigate demand-poll timing.

**A shared `~/.m2` local-repository hazard was hit and fixed, no source touched**: a stale/mismatched
`adapter-cv-grpc-0.0.1-SNAPSHOT.jar` (installed by a different, concurrent session sharing this
environment's Maven cache) caused a `GrpcCvSettings` constructor-arity compile error in `CvWiring`
against otherwise-correct working-tree source (`cv/grpc` itself was clean and unmodified per `git
status`/`git log`). Fixed by `./mvnw -B -pl cv/grpc install -DskipTests` (scoped reinstall from the
correct current source), not by changing any code — the same class of hazard `CvWiringTest`'s wave J3
"Build status" note above already documents for a different pair of concurrent waves.

**Tests**: `CvWiringTest` +1 (`defaultConfigurationWiresTheDetectionDemandPortIntoStreamDetectionSupport`),
`VisionCvPropertiesTest` +2 (both new-field-default pins), new `DetectionDemandDisabledWiringTest` (2
tests, the guardrail proof) — see the "Test inventory" entries above for what each asserts.
`TrackingWiringTest`'s 4 pre-existing `ApplicationServiceWiring.streamPipelineSettings(...)` call
sites already passed `cvProperties()` and needed no change.

**Before/after**: computed from source diff (this session had no clean pre-wave-D2 baseline run to
compare against directly) — **235 → 240** (+5: `CvWiringTest` +1, `VisionCvPropertiesTest` +2,
`DetectionDemandDisabledWiringTest` +2 new). `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app
test -DskipWeb` confirmed green: **vision-app: Tests run: 240, Failures: 0, Errors: 0, Skipped: 0**.
ArchUnit unaffected — `ArchitectureTest` (14 tests) and `ContextArchitectureTest` (4 tests) both still
0 failures.

**Not touched, per scope**: `contexts/vision-perception` (D1) and `station/vision-web` (D3) — this
wave's diff is contained entirely to `station/vision-api/**` and `station/vision-app/**`, confirmed via
`git status` before finishing. Docker: not exercised by this wave — no new or touched test in either
module is docker-gated.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W1 done (the auth fix, standalone — `AuthSeedRunner` deleted)

**The bug**: `AuthSeedRunner` created the root `Group` with `GroupId.random()`; `DevPrincipal.GROUP_ID`
(`UUID(0,1)`) — the fixed group every asset created under the default `vision.auth.enabled=false`
config is owned by — never matched it. A MANAGER logged into a persistence-enabled app and scoped to
the *actually-seeded* (random) root group saw an empty fleet. Fixed from the schema side, not this
module: `storage/persistence`'s `V13__identity_baseline.sql` now seeds the root group at the fixed
id directly. `AuthSeedRunner.java`/`AuthSeedRunnerTest.java` are **deleted** — see "Seeding, current
mechanism" above for the replacement, and storage/persistence/MODULE.md's own W1 section for the
Flyway-mechanism story (including the `ignoreMigrationPatterns` bug found only by decompiling
`flyway-core`, not by reading its Javadoc).

**This module's changes**:
- `AuthWiringConfiguration` — the `authSeedRunner` `@Bean` removed (see the `_(removed)_` row above);
  class javadoc rewritten to describe the Flyway-based replacement and cite the empty-fleet bug as
  why `AuthSeedRunner` is gone, not merely moved.
- `VisionPersistenceProperties` gained a 5th record component, `seedDevUsers` (`@DefaultValue("false")`)
  — a deliberately opt-in default per this codebase's own guardrail (a flag defaults to the value that
  leaves existing behavior unchanged; the friends-demo `docker-compose.yml` stack, the one environment
  that actually relied on `AuthSeedRunner`'s unconditional seeding, is the one place this flag is
  flipped `true`, restoring that exact prior behavior).
- `PersistenceWiringConfiguration#persistenceEntityManagerFactory` now passes
  `properties.seedDevUsers()` as `PersistenceUnit.start`'s new 4th argument — the only line in this
  bean changed; the 3-arg overload other callers use is untouched, kept in `PersistenceUnit` itself
  for exactly that reason.
- `application.yaml` documents the new `vision.persistence.seed-dev-users: false` key next to
  `vision.persistence.enabled`, per this file's own comment convention, and the `vision.auth.enabled`
  comment above it was rewritten to explain the new dependency on both persistence flags rather than
  reference the deleted `AuthSeedRunner`.
- `docker-compose.yml` sets `VISION_PERSISTENCE_SEED_DEV_USERS: "true"` next to the existing
  persistence/auth env vars, with a comment pointing at the replaced `AuthSeedRunner` behavior it
  preserves for that stack.
- **`DevAccountSeeder`** (new, `src/test/java/.../app/DevAccountSeeder.java`, test-only, package-
  private) — a small helper four `@SpringBootTest(properties="vision.auth.enabled=true")` classes
  (`AuthEnabledFlowTest`, `OrgManagementAuthEnabledTest`, `ScopedAssetReadAuthEnabledTest`,
  `ManualControlSecurityEnabledTest`) now call from `@BeforeEach`, replicating just enough of
  `AuthSeedRunner`'s old seeding logic (idempotent via `userService.list(unbounded()).isEmpty()`,
  same as the original) to keep these tests logging in as `admin`/`manager`/`pilot` — there is no
  Flyway seed for these `@SpringBootTest` contexts to fall back on (they run against the in-memory
  devsupport repositories, not Postgres), and no equivalent seeding already existed for the
  in-memory path. Each of the four test classes' javadoc now points at `DevAccountSeeder` instead of
  `AuthSeedRunner`.
- Two compile-breaks discovered and fixed as a direct consequence of `VisionPersistenceProperties`
  growing a 5th component: `PersistenceWiringConfigurationTest` (13 call sites) and
  `SimulationResumeWiringConfigurationTest` (1 call site) each had a positional 4-arg
  `new VisionPersistenceProperties(...)` construction; all 14 fixed to pass the new `seedDevUsers`
  argument (`false`, matching every other property's already-established "unauthenticated/off"
  default in those tests).
- `station/vision-api`'s `DemoPeople.java` (outside this module, but necessitated) — two javadoc
  comments that named `AuthSeedRunner` (the DEV-ONLY password stance, and the "Root" group parenting)
  updated to reference the Flyway seed instead.

**No new test methods** — every change above is either a `@Bean` deletion, a `@BeforeEach` seeding
call, or a constructor-argument fixup; the only test-count delta this wave produced is the loss of
`AuthSeedRunnerTest`'s 2 `@Test` methods along with the class itself.

`./mvnw -B -pl station/vision-app test -DskipWeb`: **238/238 green** (was **240**, per wave D2's own
"Before/after" note directly above — an independently-recorded baseline from the immediately-prior
wave, not just this session's own arithmetic). The −2 is exactly `AuthSeedRunnerTest`'s two methods;
every other test, including `ArchitectureTest`/`ContextArchitectureTest`, stayed green. Docker: not
required for this module's own suite (all in-memory/MockMvc); `storage/persistence`'s
`DevAccountSeedMigrationTest` (documented in that module's own MODULE.md) is where the real-Postgres
proof lives, and docker **was** reachable and used there — 133/133 green, up from 128.

**Deviations from the brief, and what was empirically discovered rather than assumed**: none in
shape — every deliverable (V13 fixed-id seed, V90001 Flyway-gated dev-account seed,
`seedDevUsers`/`seed-dev-users` threaded end to end, `AuthSeedRunner` deleted, docker-compose wired)
landed as specified. What the brief's own phrasing did not anticipate, and had to be found by testing
against a real Postgres rather than reasoned about (per the brief's own explicit instruction): a
`V13.1`-style "next free slot" migration version was tried first and measured to fail once
`db/migration` progressed past it (the concurrently-landing W3 wave's `V14`/`V15`), so `V90001` (a
reserved-high band) replaced it; and the seemingly-obvious `ignoreMigrationPatterns("*:missing")` for
the flag-on→off case compiled and looked right but silently matched nothing, because that same
high-band version choice makes the orphaned migration's Flyway state `FUTURE_SUCCESS`, not
`MISSING_SUCCESS` — found only by decompiling `flyway-core-12.4.0.jar`. Full account in
storage/persistence/MODULE.md's own W1 section. No API/DTO/endpoint work in this wave — it is wiring
and persistence only, nothing for a UI wave to consume.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W4 done (Postgres becomes the default; the test suite gets a real database)

**The flip**: `VisionPersistenceProperties#enabled`'s `@DefaultValue` changed from `"false"` to
`"true"`; javadoc rewritten (it previously described `false` as "today's behavior, no database
required" — now `true` is the deployed shape and `false` is the documented in-memory escape hatch).
`application.yaml`'s `vision.persistence.enabled` comment block rewritten to match, per that file's
own convention (a commented key documents a default; an uncommented one overrides it) — `enabled:
true` is stated uncommented, same as before, just with the opposite value and an updated comment.
Nothing about `PersistenceWiringConfiguration`'s own structure changed (still a
`@ConditionalOnProperty`-gated `EntityManagerFactory` bean plus sixteen branching port beans, as
described above) — flipping the default was sufficient per this wave's own brief; collapsing that
file to a single unconditional branch is explicitly a follow-up (docs/plans/active/POSTGRES-ONLY-CONTEXT.md
W2), not done here, and no `InMemory*` class was touched or deleted (also a follow-up, once this
wave has proven them provably dead).

**This module's test suite needed its own real Postgres as a direct consequence** — see "Test
infrastructure" above for the full mechanism (`SharedPostgresContainer`/
`PostgresContextCustomizerFactory`/`DockerGatedExecutionCondition`/`PostgresResetTestExecutionListener`,
all in the new `com.drones.vision.app.testsupport` package, wired entirely through
`META-INF/spring.factories` + `META-INF/services` + `junit-platform.properties`, zero per-class
annotations across all 34 `@SpringBootTest` classes). Two `test`-scoped dependencies added to
`pom.xml`: `org.testcontainers:testcontainers-postgresql` and `org.flywaydb:flyway-core` (both
version-managed by `spring-boot-dependencies`, the same convention `adapter-persistence`'s own
`pom.xml` uses).

**Keeping the suite honest** (the brief's own explicit ask): `PersistenceWiringTest`'s 13 test
methods were rewritten in place — `InMemory*` assertions → `Jpa*` assertions, the
`EntityManagerFactory`-empty assertion inverted to non-empty — since the *default* configuration it
proves changed meaning, not the test's purpose. `PersistenceWiringConfigurationTest` needed **no
changes**: it constructs `VisionPersistenceProperties` explicitly for both the enabled and disabled
branches (never relying on the record's own `@DefaultValue`), so it already covered — and still
covers — both branches of `PersistenceWiringConfiguration`'s bean-selection logic without a Spring
context, Docker, or database; W4 did not touch it. `LiveDisabledWiringTest` was checked and found
**unrelated**: its `InMemoryAuditTrail`/`InMemoryDetectionEventRepository` assertions are about
`ApplicationServiceWiring`'s own unconditional beans (never gated by `vision.persistence.enabled`),
confirmed both by reading `ApplicationServiceWiring` and by the class staying green, unmodified,
under the new default. No test's false-branch coverage was silently dropped anywhere in this module.

**One genuine bug this wave's own new infrastructure caused, found and fixed in itself** (not in a
pre-existing test — the opt-in guardrail's "fix the config, never the pre-existing tests" does not
apply to a bug in code this same wave just wrote): `PostgresResetTestExecutionListener` originally
reset from `beforeTestClass`, which — because `TestExecutionListener.beforeTestClass` fires from
`SpringExtension`'s `BeforeAllCallback`, always invoked before a test class's own `@BeforeAll` — forced
`TrackingAssociateE2ETest`/`CvDetectionE2ETest`/`CvDetectionEndpointE2ETest` (each starts an
in-process gRPC server in `@BeforeAll` and reads its port via `@DynamicPropertySource`) to build their
Spring context before that server existed, failing with a `NullPointerException` on the still-null
static `server` field. Moved to `beforeTestMethod` (a `BeforeEachCallback`, always after `@BeforeAll`
and after the context-loading test-instance-construction step) with a per-test-class guard so the
reset still fires exactly once per class, not once per method — see "Test infrastructure" above for
the full explanation. **A second, unrelated bug surfaced the same way**: `DevAccountSeeder` (added at
W1) unconditionally created a new "Root" `Group` every time, which only became observable once these
tests ran against a real, Flyway-migrated database whose `V13__identity_baseline.sql` had already
seeded a "Root" group at a fixed id — two same-named groups, and `ScopedAssetReadAuthEnabledTest`'s
by-name lookup could resolve either. Fixed by having `DevAccountSeeder` look an existing "Root" group
up first, falling back to `create` only on a genuine miss (see "Test infrastructure" above for the
exact change) — both this and the reset-ordering fix are inside this wave's own owned files
(`station/vision-app/src/test/**`), not a `storage/persistence` change.

**`RtspSimulationDockerE2ETest`** failed once, in a full-suite run, under the added resource pressure
of a second Docker container (the shared Postgres) competing for CPU/IO alongside its own mediamtx
container — re-run immediately afterward in isolation, it passed cleanly in 18.65s. Treated as a
contention flake, not a regression: nothing in this wave's diff touches RTSP/mediamtx, and the test's
own logic is unchanged.

**`./mvnw -B -pl station/vision-app test -DskipWeb`: 238/238 green, 0 skipped** (Docker was reachable
throughout this task) — same count as the pre-W4 baseline (238/238, established earlier the same
session, before this wave's own changes). No test was added or removed; the two fixes above are both
inside pre-existing test-support files, and `PersistenceWiringTest`'s rewrite kept its method count
(13) unchanged. **Postgres use was verified genuine, not a silent in-memory fallback**, three ways:
(1) `PersistenceWiringTest`'s rewritten `assertInstanceOf(Jpa*Repository.class, ...)` assertions
themselves only pass against the real JPA beans; (2) the full-suite log shows Flyway actually running
16 migrations against `jdbc:postgresql://localhost:<ephemeral-port>/test` — the Testcontainers-assigned
port — once per boot and again once per test class via the reset listener; (3) `PersistenceWiringTest`'s
`EntityManagerFactory` assertion is non-empty, which is only reachable through
`persistenceEntityManagerFactory`'s `@ConditionalOnProperty`-gated `@Bean` method actually running
(constructing a `EntityManagerFactory` opens a real JDBC connection eagerly, per that bean's own
javadoc — see "Persistence wiring" above).

**Wall-clock, before vs. after**: ~1:07 min before (this module's own portion of an earlier combined
`-pl storage/persistence,station/vision-api,station/vision-app` 2:14 min run, measured pre-W4) vs.
**1:28 min** after (a clean standalone `-pl station/vision-app test -DskipWeb` run, this wave's own
final measurement) — roughly **+20s**, both runs 238/238 green. Reported plainly per the brief's own
"state the number if it worsens" instruction; not treated as disqualifying (the increase is explained
entirely by one-time Testcontainers startup plus 34 Flyway `clean()`+`migrate()` reset cycles, not by
anything scaling with the suite itself), and no further optimization was attempted since the brief
set no target beyond "measure and report."

**Docker-less run**: verified by code review of JUnit 5's `ExecutionCondition`/`BeforeAllCallback`
ordering, not by an actual docker-less execution — Docker was reachable throughout this sandbox
session, and an attempt to fake unavailability via a bogus `DOCKER_HOST` did not work (Testcontainers'
provider-strategy chain finds the real daemon through other paths regardless), so genuinely disabling
Docker would require an OS-level change judged out of scope here. Stated plainly per the brief's own
requirement to say so rather than imply it was exercised.

**Deviations from the brief**: none in shape — every deliverable (default flip, javadoc rewrite,
`application.yaml` comment rewrite, one-container-per-JVM Testcontainers wiring, Docker-less clean
skip via a globally-registered `ExecutionCondition`, a measured no-cross-class-contamination reset
strategy, `PersistenceWiringTest` kept honest rather than deleted/weakened, the ~19
`devsupport.InMemory*RepositoryTest` unit tests left untouched, cost measured and reported) landed as
specified. What the brief did not — and could not — anticipate: the `beforeTestClass`-vs-`@BeforeAll`
ordering hazard and the `DevAccountSeeder` duplicate-root-group bug were both real defects this wave's
own change surfaced, found only by actually running the full suite against a real database rather than
reasoning about it, exactly the kind of thing the brief's own exit criteria (real, pasted command
output) exist to catch. Forbidden files were respected throughout: `storage/persistence`,
`docker-compose.yml`, `.env.example`, and every `InMemory*` class were read where needed for context
but never modified; `PersistenceWiringConfiguration`'s structure is untouched. No commit was made —
every change here is an uncommitted working-tree modification on `fix/postgres-only-auth`. No
API/DTO/endpoint work in this wave — it is wiring, test infrastructure, and two test-support bug
fixes only, nothing new for a UI wave to consume.

## docs/plans/active/POSTGRES-ONLY-CONTEXT.md W2b done (the deletion wave — flag removed, 19 `InMemory*` classes gone)

The wave W4 (above) deliberately deferred: with W3's `JpaAuditTrail`/`JpaDetectionEventRepository`
built but unwired and W4's default flip proving Postgres works as the default, this wave closes the
loop — wires the last two ports, deletes the now-unreachable in-memory branch entirely, and removes
`vision.persistence.enabled` since a flag with exactly one legal value is a lie, not a feature.

**Wiring.** `ApplicationServiceWiring#auditTrailPort`/`#detectionEventRepositoryPort` now build
`new JpaAuditTrail(entityManagerFactory)`/`new JpaDetectionEventRepository(entityManagerFactory)`
(both take `EntityManagerFactory` as a plain `@Bean`-method parameter now that it's unconditional),
still wrapped in `LiveUpdateAuditTrail`/`LiveUpdateDetectionEventRepository` when
`vision.live.enabled=true` — the decorator wrapping is byte-identical to before, only the delegate
changed. `PersistenceWiringConfiguration` collapsed to straight-line: no `@ConditionalOnProperty`,
no `ObjectProvider<EntityManagerFactory>`, no if/else — the `EntityManagerFactory` bean and all
seventeen repository-port beans it owns (fleet/history/identity/scope/marks/map/training — the full
list is in "Persistence wiring" above) are ordinary unconditional `@Bean` methods, each a one-line
`new Jpa*Repository(entityManagerFactory)`. `VisionPersistenceProperties` drops `enabled` entirely
(`jdbcUrl`/`username`/`password`/`seedDevUsers` only). `simulationResumeRunner` simplified to gate on
`VisionSimulationProperties#resumeOnBoot()` alone (the `VisionPersistenceProperties#enabled() &&`
half of its old AND-condition has nothing left to AND against).

**`mapLayerBootstrapRunner` deleted — equivalence verified, not assumed.** Its body
(`args -> mapLayerService.copLayerId()`) and `V12__map_layers.sql`'s `INSERT` were compared field by
field before deletion: both agree on id (`00000000-0000-0000-0000-000000000002`), name ("Common
picture"), kind (`COP`), ownership (`Ownership(UUID(0,0), UUID(0,1))`, the system user/group), and an
empty grant list. `LayerResolver#copLayerId()`'s own creation-fallback path constructs the identical
`MapLayer` if the row is ever missing, so the runner's only real job — "make sure a demo run's very
first `GET /api/map/layers` doesn't race its own `CREATED` SSE event against its own response,
because in-memory mode had nothing pre-seeding the row" — is structurally unreachable now: Postgres
is unconditional, so `V12` has always already run (as part of `persistenceEntityManagerFactory`
construction) before any `ApplicationRunner` gets a chance to execute. Deleting it removed dead code,
not a behavior.

**Deletions.** 29 files removed (`git rm`, staged, not committed): 19 `InMemory*Repository`/
`InMemoryAuditTrail`/`InMemoryDetectionEventRepository` classes under `devsupport/`, plus 10 dedicated
unit-test files (8 under `devsupport/`, plus `InMemoryGroupRepositoryTest`/`InMemoryUserRepositoryTest`
which lived directly under `com.drones.vision.app` rather than the `devsupport` subpackage). Kept, as
scoped: `DevPrincipal`, `LoggingEventPublisher`, `NoopDetectionPort`, `NoopLiveUpdatePublisher`,
`NoopReplayFrameExtractor`, `NoopStreamPublisher` — no-op fallbacks for genuinely optional features
(`vision.cv.enabled`/`vision.live.enabled`/`vision.publish.enabled`), not persistence, out of scope.

**Config surface.** `application.yaml`'s `vision.persistence` block dropped its `enabled: true` line
and had its explanatory comment rewritten (now: "Postgres unconditional, here's why, here's what used
to gate it"); the `vision.auth.enabled`/`vision.simulation.resume-on-boot` comments nearby were
touched too, since both used to cross-reference the now-gone flag. `docker-compose.yml` dropped
`VISION_PERSISTENCE_ENABLED: "true"` and had its header/inline comments rewritten to match — there is
nothing left to toggle, `vision-app`'s dependency on `postgres` (`condition: service_healthy`) already
says everything the removed env var used to. `.env.example`/root `README.md` were checked and already
carried no reference to the flag — no change needed. `CLAUDE.md`'s module-index row for
`adapter-persistence` changed from "opt-in via `vision.persistence.enabled`" to "unconditional;
Postgres is the only store" — the one line that row owns; no other `CLAUDE.md` content touched.

**Tests.** `storage/persistence` unaffected by this wave (no source changes there — migrations,
entities, and `Jpa*Repository` classes were all already correct from W3): re-measured **137/137
green**, unchanged from the W3 baseline. `vision-app`: **190/190 green**, down from the W3/W4
baseline of **238/238** — reconciled exactly: −45 (10 fully-deleted `InMemory*Test` files' own
`@Test` methods), −1 (`PersistenceWiringConfigurationTest` lost its
`disabledSelectsInMemoryRepositoriesWithoutTouchingTheProvider` test — it proved exactly the branch
that no longer exists, so deleting it is correct per this task's own "the test's whole purpose was
proving the now-deleted branch" carve-out, not a weakened guardrail), −2
(`SimulationResumeWiringConfigurationTest` lost 2 of its 4 combinations — the
`VisionPersistenceProperties#enabled()` axis is gone, so only `resumeOnBoot`'s two values remain to
cross). `238 − 45 − 1 − 2 = 190`, confirmed by an actual `./mvnw -B -pl station/vision-app test
-DskipWeb` run, not computed and assumed. No test was weakened to reach green — every deletion
removed a test whose entire purpose was proving code that no longer exists; every other touched test
(`LiveDisabledWiringTest`, `PersistenceWiringTest`, `AssetWiringTest`, `VisionCvPropertiesTest`,
`DevAccountSeeder`) kept its assertions, only updated which concrete class/message they name. Docker
was reachable throughout (`docker info` succeeded) — the full 34-class `@SpringBootTest` set ran
against a real Testcontainers Postgres, none skipped.

**Deferred:** nothing from this task's own brief. One scope note: the plan's own §7 "Remaining" list
(written before this task was scoped) had originally split this work into a separate "W2b" (deletion)
and "W5" (docker-compose/`.env.example`/README/`CLAUDE.md`/authoritative-MODULE.md-sections) — this
task's actual brief folded W5's config/doc items into W2b, so both are closed by this one wave; see
§7's updated "Remaining" section.

**Deviations from the brief:** none. One judgment call, consistent with every prior wave's own
precedent: the `contexts/vision-simulation` module's `SimulationService#resumeAll()` javadoc (one
paragraph) and its own `MODULE.md` Gotchas entry were touched, even though that module is outside
this agent's usual `vision-api`/`vision-app`/`storage/persistence` scope — both described the
now-gone `vision.persistence.enabled` gating in prose whose accuracy this wave's own change directly
broke, and leaving a doc-only paragraph stale in a module this task's own change made inaccurate
seemed worse than a small, clearly-scoped drive-by fix.

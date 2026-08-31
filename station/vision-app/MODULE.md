# vision-app

Spring Boot assembly: the only module that knows about every adapter, wires domain ports to
concrete implementations, and holds runtime configuration + the ArchUnit/context-boundary rules.

**Depends on:** vision-kernel, vision-platform, all 8 context modules (vision-warehouse,
vision-identity, vision-flight, vision-perception, vision-map, vision-events, vision-learning,
vision-simulation), adapter-simulation, adapter-rtsp, adapter-mjpeg, adapter-mavlink (+ mavlink-core,
test scope), adapter-v4l2, adapter-publish-hls, adapter-cv-grpc, adapter-tiles, adapter-discovery,
adapter-persistence, vision-api, vision-web (static-only jar), spring-boot-starter,
spring-boot-starter-security, spring-boot-starter-actuator · test: spring-boot-starter-test,
archunit-junit5, testcontainers-postgresql
**Used by:** nothing (leaf/assembly module; produces the runnable jar via spring-boot-maven-plugin)
**Build/test:** `./mvnw -B -pl station/vision-app test` — needs the rest of the reactor already
installed to `~/.m2` (`-pl` doesn't build siblings from source) and a reachable Docker daemon
(every `@SpringBootTest` needs a real Postgres via Testcontainers — Postgres is the only store, no
toggle exists to turn it off). Always `mvn clean` first — see Gotchas.

## API surface

### Package shape

```
com.drones.vision.app
  config/
    properties/        every @ConfigurationProperties record (21 — see table below)
    wiring/             21 per-concern @Configuration classes (see table below)
    SecurityConfig      the 2 mutually-exclusive SecurityFilterChain beans
  security/             BcryptPasswordHasher, VisionUserDetails, DevPrincipalResolver,
                         SecurityContextPrincipalResolver, Security/NoopSessionAuthenticator
  events/                DetectionSessionCleanupEventPublisher, LiveUpdateEventPublisher,
                         LiveUpdateAuditTrail, LiveUpdateDetectionEventRepository (decorator chains)
  onboarding/            PassportCaptureObserver (UsagePhaseObserver impl)
  geo/                   TrackProjectionRunner, VisualGeoRunner (poller ApplicationRunners)
  usage/                 UsageIdleCloseRunner (self-scheduled sweep, same shape as geo/'s runners)
  discovery/             DiscoveryInboxRunner (self-scheduled sweep, same shape as usage/'s runner)
  stream/                LiveFrameFallbackStreamService (StreamService decorator)
  bootstrap/             SimulationResumeRunner (the one remaining ApplicationRunner-as-bean)
  devsupport/            8 Noop*/constant-holder classes — see table below
```

### Configuration classes (wiring map)

One row per `@Configuration` class in `config/wiring/`. "Gated" beans are absent entirely (not
swapped for a no-op) when their condition is false, unless a Noop fallback is named.

| Class | `@EnableConfigurationProperties` | Beans |
|---|---|---|
| `VideoSourceWiring` | Simulation, Rtsp, Mjpeg, V4l2 | `simulatedVideoSource`/`ffmpegVideoSource`/`mjpegVideoSource`/`v4l2VideoSource` (all unconditional `VideoSourcePort`), `videoSourceRegistry`. Exposes static `toFfmpegSettings`/`toMjpegSettings` mappers reused by `FeedTransmitterWiring` |
| `TelemetryWiring` | Simulation, Mavlink, Rc, Onboarding | `simulatedTelemetrySource`, `mavlinkTelemetrySource`, `mavlinkFlightCommander`, `mavlinkManualControlSender` — all unconditional. Static `toMavlinkSettings(...)` reused by `FeedTransmitterWiring`/`DiscoveryWiringConfiguration`/`OnboardingWiringConfiguration` |
| `PublishWiring` | Publish, Api, Cv | `mediamtxStreamPublisher` (COP `vision.publish.enabled`, default true — own bean so `SystemStatusWiring` observes the *same* instance `streamPublisherPort` routes through), `streamPublisherPort` (unconditional `StreamPublisherPort`; `NoopStreamPublisher` if disabled, else a `PublisherRouter` wrapping the direct publisher + a `MediamtxProxyPublisher`, routed by `vision.publish.source-proxy.enabled`; **fails fast** if source-proxy is on while `vision.cv.frame-transport` is still `push` — see Gotchas), `mediamtxLiveFrameGrabber` (unconditional, cheap/lazy), `replayFrameExtractionPort` (unconditional; `NoopReplayFrameExtractor` if publish disabled), `hlsProxyUpstreamBase: URI`, `snapshotJpegEncoder`, `hlsProxySettings`, `liveSettings` (last 3 bridge Spring-bound `VisionApiProperties` → vision-api's plain mirror of the same simple name — see Gotchas) |
| `CvWiring` | Cv | `cvGrpcChannel: ManagedChannel` `@Primary` (COE: `cv.enabled` OR `training.enabled` OR `frame-transport=pull` OR `geo.visual.enabled`; built via `CvChannels.forTargets`), `cvTrainingChannel` (COP `vision.cv.training.target` present — independent shutdown), `cvChannelSupervisor` (COE = cvGrpcChannel's expression AND `cv.reconnect.enabled` default true; `@Qualifier("cvGrpcChannel")`), `detectionPort` (unconditional bean, internal branch on `enabled`: `GrpcDetectionPort` w/ `@Qualifier("cvGrpcChannel")` else `NoopDetectionPort`; `destroyMethod=""`), `pulledDetectionPort` (COE `frame-transport=pull`; `@Qualifier("cvGrpcChannel")`), `cvModelRoster` (static constant), `detectionDemandPort` (COE default true, returns concrete `LiveAndPollDetectionDemand`), `streamDefaultConfig`, `streamDetectionSupport`. Static `toGrpcCvSettings(...)` and package-private `controlPlaneChannel(cvTrainingChannel, cvGrpcChannel)` (= training channel if present else falls back to inference channel) shared by `TrainingWiringConfiguration`/`VisualGeoWiringConfiguration` |
| `TrainingWiringConfiguration` | Training, Cv | `datasetUploadPort`, `trainingStores`, `replaySources`, `datasetService`, `labelingService` (takes `AssetDirectoryService`, not `AssetService`), `trainingPort`, `trainingJobService` all `@ConditionalOnProperty(vision.training.enabled=true)`, no fallback. `modelRegistryPort`/`modelRegistryService` are a **separate** switch since CV-SETTINGS-PLAN §5 (CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff decoupled the registry from training): `@ConditionalOnProperty(vision.cv.registry.enabled=true)`, whose own `application.yaml` default follows `vision.cv.enabled` via the `${vision.cv.enabled:false}` placeholder — a CV-only deployment gets the registry for free unless it explicitly opts out (`vision.cv.registry.enabled=false`); a training-only deployment does **not** get it for free any more. Channel-consuming beans route through `CvWiring.controlPlaneChannel(...)` |
| `CvProfileWiringConfiguration` | — | **Every bean unconditional**, no `@Conditional*` at all (CV-SETTINGS-PLAN §3.1/§5, CV-SETTINGS-CONTEXT.md's W2 → W5 handoff): `cvProfileCacheSettings` (from `VisionCvProperties.Profiles#cacheTtl()`, default 60s), `cvProfileCache` (write-through, lazy-TTL-reload), `cvProfileResolver` (asset→category→organization→platform fold, shared with `ApplicationServiceWiring#streamService` and `CvWiring#streamDetectionSupport`), `cvProfileService` (`DefaultCvProfileService`, behind `CvProfileController`) — profiles ship built-in (4 seeded rows, `V29__cv_profiles.sql`) regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`, the same "ships built-in" posture `TrackingWiring#cvTrackerRoster` takes |
| `DiscoveryWiringConfiguration` | Discovery, Mavlink | `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner`/`mavlinkHeartbeatScanner` — each COP `vision.discovery.enabled` default true. `discoveryService` and `mavlinkPort` **unconditional** (`DiscoveryController` needs the service regardless; `DefaultDiscoveryService` tolerates an empty port list). `mavlinkHeartbeatScanner` lives in adapter-mavlink, not adapter-discovery like its 3 siblings, because it borrows `mavlinkTelemetrySource`'s open socket and adapters can't depend on each other |
| `DiscoveryInboxWiringConfiguration` | Discovery | `discoveryInboxService(DiscoveryCandidateRepositoryPort, AssetService)` → `DefaultDiscoveryInboxService`, **unconditional** (`DiscoveryInboxController`, vision-api, needs it regardless of whether the sweep runs — an operator can still read/register/dismiss by hand with the runner off). `discoveryInboxRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.discovery.inbox.enabled=true` (matchIfMissing=true, the default — ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c deliberately overrides CLAUDE.md's usual opt-in-guardrail default, since shipping the sweep off by default would defeat the whole zero-config purpose) |
| `FeedTransmitterWiring` | Publish, Rtsp, Mjpeg, Mavlink, Rc, Onboarding | `rtspFeedTransmitter`, `mjpegFeedTransmitter` (`destroyMethod="close"` — owns a shared `HttpServer`), `mavlinkFeedTransmitter`, `feedTransmitterRegistry` — all unconditional |
| `PersistenceWiringConfiguration` | Persistence | **Every bean unconditional**, no `@Conditional*` anywhere — 23 one-line `Jpa*Repository(entityManagerFactory)` ports (WAREHOUSE-UX W3 added `maintenanceRepositoryPort`/`assetNoteRepositoryPort`; ZERO-CONFIG-ONBOARDING Z2c added `discoveryCandidateRepositoryPort`) + `persistenceEntityManagerFactory` (`destroyMethod="close"`, opens a real JDBC connection eagerly). No in-memory fallback exists for any repository port (Postgres is the only store) |
| `AuthWiringConfiguration` | — | `passwordHasherPort`, `authService`, `userService`, `groupService`, `scopeResolver`, `assignmentService` (`DefaultAssignmentService(assignmentRepositoryPort, assetService)` — takes `AssetService`, not `AssetRepositoryPort`), `activityService` — all unconditional. `devPrincipalResolver`/`noopSessionAuthenticator` COP `vision.auth.enabled=false` (matchIfMissing=true, the default); `securityContextPrincipalResolver`/`securitySessionAuthenticator` COP `vision.auth.enabled=true`. Repository ports (`UserRepositoryPort`/`GroupRepositoryPort`) come from `PersistenceWiringConfiguration`, orthogonal to `vision.auth.enabled` |
| `RateLimitWiring` | Api | Whole-class COP `vision.api.rate-limit.enabled=true` (default false): `rateLimitFilterRegistration` — `FilterRegistrationBean<RateLimitFilter>` on `/api/*` |
| `SystemStatusWiring` | — | Two mutually-exclusive beans per subsystem, each repeating the exact enabling expression its real resource already uses (never `@ConditionalOnBean` — order-sensitive, see Gotchas): `cvServiceStatus`/`cvServiceStatusDisabled`, `videoPublishStatus`/`videoPublishStatusDisabled` (COP `vision.publish.enabled`). `mavlinkLinkStatus` is **unconditional** (mavlink telemetry source always exists; reports `UNKNOWN` with no claimed vehicle, never `DISABLED`); **FLEET-RADIO R4** — the class now also declares `@EnableConfigurationProperties(VisionMavlinkProperties.class)` (matching `TelemetryWiring`'s/`DiscoveryWiringConfiguration`'s own identical declarations of the same class) and `mavlinkLinkStatus` takes a second parameter, `VisionMavlinkProperties`, building a `MavlinkSettings.LinkStatus` from its three D7 threshold fields to construct `MavlinkLinkStatusProvider` |
| `StreamLifecycleWiring` | Streams | `mediamtxReaderProbe` (COP `vision.publish.enabled` default true), `videoDemandPort` (unconditional — `HlsProxyController` needs it regardless of whether the idle policy is on), `idleStreamReaper` (`initMethod="start"`, `AutoCloseable`). Resolves `usageTracker` lazily inside a resolver lambda to avoid the same circular-reference hazard as `CvWiring#detectionDemandPort` |
| `TrackingWiring` | Tracking | `cvTrackerRoster()` (static constant). `streamStartTrackingSeed(...)` is a **package-private static method, not a bean** — called from `ApplicationServiceWiring` to fold into `StreamPipelineSettings`. No enable flag by design (`vision.cv.enabled` already gates CV wholesale; per-stream `TrackingMode.OFF` is the finer switch) |
| `OnboardingWiringConfiguration` | Onboarding | `noopVehicleConfigPort` (COP `probe.enabled=false`, matchIfMissing=true) vs. `mavlinkVehicleConfigurator` (COP `probe.enabled=true`, declared as `VehicleConfigPort`, never the concrete type). `vehicleProfileService`/`remediationService`/`readinessService`/`onboardingApiProperties`/`remediationOrchestrator` unconditional; `readinessService` now also takes `MaintenanceQuery` (WAREHOUSE-UX W5, `DefaultReadinessService`'s 4th ctor param — sourced from `ApplicationServiceWiring#maintenanceService`'s `DefaultMaintenanceService` bean, which implements both `MaintenanceService` and `MaintenanceQuery`). `passportCaptureObserver` COP `passport.enabled=true` (default false) |
| `FixedCameraGeoWiringConfiguration` | Geo | `fixedCameraGeoApiProperties`, `cameraPoseService`, `trackProjectionService` unconditional. `trackProjectionRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.geo.fixed-camera.enabled=true`. Its absence defaults `CvWiring#detectionDemandPort`'s camera-pose predicate to `assetId -> false`, not an error |
| `VisualGeoWiringConfiguration` | GeoVisual | `visualGeoApiProperties`, `referenceTileSourcePort` (`WaybackTileSource` vs `HttpTileSource` by `tiles.waybackMultiDate()`), `referenceRegionService`, `geolocationSessionService`, `trackCorrectionService` unconditional. `pulledGeolocationPort`/`referenceIndexPort` unconditional beans that self-branch on `enabled()` (real gRPC impl via `CvWiring.controlPlaneChannel` vs. `Noop*`) — both take **two `@Qualifier`-annotated `ObjectProvider<ManagedChannel>` params** (`cvTrainingChannel`, `cvGrpcChannel`), required once `cvGrpcChannel` is `@Primary` alongside a second channel bean (see Gotchas). `visualGeoRunner` (`initMethod="start"`/`destroyMethod="close"`) is the only truly gated bean, COP `vision.geo.visual.enabled=true` |
| `ControlProfileWiring` | Control | `controlProfileService`, `auxFunctionCatalog` (`AuxFunctionCatalog.defaults()` unless `properties.auxFunctions()` configured) — unconditional; the catalog is display-only, never a whitelist |
| `AfterActionWiringConfiguration` | — | `afterActionProperties`, `afterActionSources`, `afterActionAssembler` — unconditional, no flag. `maxPoints` is derived from `vision.application.replay.max-points-ceiling`, not its own key |
| `UsageWiringConfiguration` | Usage | `usageIdleCloseService(AssetUsageRepositoryPort, AssetLiveStatePort, UsageSessionService, VisionUsageProperties)` (warehouse's `DefaultUsageIdleCloseService`), `usageIdleCloseRunner` (`initMethod="start"`, `destroyMethod="close"`) — both **unconditional, no enable flag** (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1: a data-correctness fix, not an optional feature). Pure downstream leaf — composes three already-unconditional beans from `PersistenceWiringConfiguration`/`ApplicationServiceWiring`, no new bean-cycle risk |
| `ApplicationServiceWiring` | Cv, Live, Rc, Application, Publish, Simulation | The largest class — every `vision-application`/context `DefaultXService` bean. See "Application-service beans" below |

### Application-service beans (`ApplicationServiceWiring`)

- **Live server-push selectors (6, not 5):** `fleetLiveUpdatePort`/`telemetryLiveUpdatePort`/`detectionLiveUpdatePort`/`mapLiveUpdatePort`/`eventLiveUpdatePort`/`trackCorrectionLiveUpdatePort` — each takes `VisionLiveProperties` + `@Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry>`; returns the registry if `properties.enabled()` (default true) else `new NoopLiveUpdatePublisher()`. `trackCorrectionLiveUpdatePort` backs the `geo:<assetId>` topic (visual-geo), same flag.
- `eventPublisherPort` — `LoggingEventPublisher`, wrapped in `DetectionSessionCleanupEventPublisher` if CV enabled and `detectionPort` is a real `GrpcDetectionPort`, further wrapped in `LiveUpdateEventPublisher` if live enabled.
- `auditTrailPort`/`detectionEventRepositoryPort` — `Jpa*` (adapter-persistence), each wrapped in a `LiveUpdate*` decorator when live enabled.
- `manualControlService` — `DefaultManualControlService`'s **7-arg canonical constructor** (`Clock.systemUTC()`, a private `rcWatchdogScheduler()` single-thread daemon, `rcProperties.watchdogTimeoutMs()`, and `ControlProfileService::activeFor` as a method reference, not the whole service).
- `assetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` / `usageSessionService(AssetUsageRepositoryPort)` / `telemetryService(TelemetryRepositoryPort)` — thin per-repository ownership seams that `usageTracker` composes instead of holding repository ports directly (ARCHITECTURE-AUDIT-2026-08-26.md D1/R3). **`assetDirectoryService` wraps the repository ports directly rather than `AssetService`/`DeviceService` — this is the fix for the bean cycle described in Gotchas, not a shortcut.**
- `usageTracker` — composed from `assetDirectoryService`/`usageSessionService`/`telemetryService` + `List<TelemetrySourcePort>` + a `UsageTrackerSettings` record (telemetry live port, `geofenceMonitor::evaluate` — a method reference, so perception never depends on flight's context directly — persistence-batch settings, `UsagePhaseSettings.defaults()`, and an `ObjectProvider<UsagePhaseObserver>` defaulting to `NOOP`).
- `assetLiveStatePort(StreamService, UsageTracker, DetectionEventRepositoryPort)` → `StreamBackedAssetLiveState` (perception) implementing warehouse's `AssetLiveStatePort` — the one class allowed to compose all three; `assetService`/`deviceService`/`assetStatsService`/`fleetSummaryService` all take this port instead.
- `streamService` — builds `DefaultStreamService` via a `DefaultStreamServiceSettings` record folding in `streamPipelineSettings(...)` (adaptive-rate, tracking seed, CV-demand poll interval, `pipeline.videoStaleAfter()`), an `Optional<PullDetectionSettings>` (present iff `cv.pullEnabled()`), and an `Optional<DetectionDemandPort>`. Wrapped in `com.drones.vision.app.stream.LiveFrameFallbackStreamService` iff `vision.publish.source-proxy.enabled=true`.
- `mapAccessPolicy()`/`layerResolver(...)` — each **one shared singleton** (stateless policy; `layerResolver`'s find-or-create methods are `synchronized` and need single-instance guarding).
- `simulationService` — `DefaultSimulationService(assetService, assetStreamService, deviceService, feedTransmitterRegistry, mediamtx.rtspBase(), SimulationServiceSettings)`; `deviceService` (not `categoryRepositoryPort`) is the constructor's 3rd param, per ARCHITECTURE-AUDIT R4.
- `simulationResumeRunner` — gated purely on `VisionSimulationProperties#resumeOnBoot()` (persistence is unconditional, so there is no second half of this gate).
- **WAREHOUSE-UX W3 (unconditional, alongside `assetService`/`categoryService`):** `assetCustodyService(AssetRepositoryPort, MaintenanceRepositoryPort, AuditTrailPort)` → `DefaultAssetCustodyService`; `maintenanceService(MaintenanceRepositoryPort, AssetRepositoryPort, AuditTrailPort)` → declared `DefaultMaintenanceService` (the concrete type, not `MaintenanceService`, specifically so this one bean also satisfies a `MaintenanceQuery`-typed injection point — see `OnboardingWiringConfiguration#readinessService` above — the same "one instance, two seams" shape `usageSessionService`/`DefaultUsageSessionService` would use if `UsageSessionService` had a second interface); `inventoryExportService(AssetService)` → `com.drones.vision.api.support.InventoryExportService` (the hand-rolled CSV behind `GET /api/inventory/export`).
- **WAREHOUSE-UX W8 (unconditional, alongside `inventoryExportService`):** `assetRowFacts(VehicleProfileRepositoryPort, AssetUsageRepositoryPort)` → `com.drones.vision.api.support.AssetRowFacts` — bundles both cross-context ports `AssetController`'s new `firmware`/`totalFlightSeconds` join needs into `AssetController`'s fifth constructor parameter (see `station/vision-api/MODULE.md`'s Conventions). `vehicleProfileRepositoryPort` resolves to `PersistenceWiringConfiguration`'s unconditional bean; `assetUsageRepositoryPort` is the same bean `assetService` already consumes — no new port implementation, only a new query method on the existing one.

### Properties records (`config/properties/`, all `@ConfigurationProperties`)

| Record | Prefix | Primary consumer |
|---|---|---|
| `VisionApplicationProperties` | `vision.application` | `vision-application`'s pipeline/replay/simulation/fleet/stats settings |
| `VisionPersistenceProperties` | `vision.persistence` | `adapter-persistence`'s `PersistenceUnit`/pool/telemetry-batch settings |
| `VisionLiveProperties` | `vision.live` | selects `LiveUpdateRegistry` vs. `NoopLiveUpdatePublisher` (all 6 ports) |
| `VisionTrainingProperties` | `vision.training` | gates `TrainingWiringConfiguration`'s whole bean cluster |
| `VisionCvProperties` | `vision.cv` | `CvWiring`/`adapter-cv-grpc` channel + detection settings |
| `VisionDiscoveryProperties` | `vision.discovery` | `adapter-discovery` scan budgets; ZERO-CONFIG-ONBOARDING Z2c added two nested records to the canonical constructor's 4th/5th components — `Lobby(boolean enabled)` (default `true`, `DiscoveryInboxWiringConfiguration`'s `mavlinkTelemetrySource.holdLobby(...)` gate) and `Inbox(boolean enabled, int sweepSeconds, int scanTimeoutSeconds)` (defaults `true`/`30`/`5`, compact-constructor validated both ints `> 0`) — both default to `true`/on, a deliberate exception to the repo's usual opt-in-guardrail default (see `DiscoveryInboxWiringConfiguration` row above) |
| `VisionSimulationProperties` | `vision.simulation` | `adapter-simulation` video/telemetry settings + resume-on-boot |
| `VisionPublishProperties` | `vision.publish` | `PublishWiring`/`adapter-publish-hls` (mediamtx, encoder, resilience, cadence, replay, source-proxy) |
| `VisionRcProperties` | `vision.rc` | `adapter-mavlink`'s RC-override cadence/watchdog |
| `VisionRtspProperties` | `vision.rtsp` | `adapter-rtsp`'s `FfmpegSettings` |
| `VisionMjpegProperties` | `vision.mjpeg` | `adapter-mjpeg`'s `MjpegSettings` |
| `VisionV4l2Properties` | `vision.v4l2` | `V4l2VideoSource` directly (no adapter settings record) |
| `VisionMavlinkProperties` | `vision.mavlink` | `adapter-mavlink`'s `MavlinkSettings` (minus `Rc`); **FLEET-RADIO R4/D7** adds `dropRateWarnPercent`/`dropRateAlarmPercent`/`linkFailureGrace` (defaults 5.0/20.0/2s, compact-constructor validated: both percents 0..100, alarm>=warn, grace positive) — consumed by `TelemetryWiring#toMavlinkSettings` (into `MavlinkSettings.LinkStatus`) and directly by `SystemStatusWiring#mavlinkLinkStatus` |
| `VisionApiProperties` | `vision.api` | bridges to vision-api's plain mirror (same simple name, see Gotchas); snapshot/hlsProxy/live/paging/upload/rateLimit |
| `VisionOnboardingProperties` | `vision.onboarding` | probe/remediate/passport flags → `OnboardingWiringConfiguration` + `TelemetryWiring#toMavlinkSettings`. `probe.parameters` (list, default empty) **replaces** the adapter's firmware-verified probe list rather than adding to it; empty keeps it |
| `VisionControlProperties` | `vision.control` | `ControlProfileWiring`'s aux-function catalog |
| `VisionGeoProperties` | `vision.geo.fixed-camera` | `FixedCameraGeoWiringConfiguration` |
| `VisionGeoVisualProperties` | `vision.geo.visual` | `VisualGeoWiringConfiguration` (incl. `tiles.*`) |
| `VisionStreamsProperties` | `vision.streams` | `StreamLifecycleWiring`'s idle-stream reaper |
| `VisionTrackingProperties` | `vision.tracking` | `TrackingWiring` seed + per-stream read-model windows |
| `VisionUsageProperties` | `vision.usage` | `UsageWiringConfiguration`'s idle-usage-close sweep (`idleClose` default 10m, `sweepPeriod` default 60s) |

`vision.discovery.enabled` and `vision.api.rate-limit.enabled` are read directly via
`@ConditionalOnProperty` with no dedicated properties record.

**Naming collision, deliberate:** `com.drones.vision.app.config.properties.VisionApiProperties`
(Spring-bound, here) and `com.drones.vision.api.support.VisionApiProperties` (plain, vision-api,
which may not depend on `@ConfigurationProperties`) share a simple name in two packages;
`PublishWiring` — the one class needing both — always fully qualifies the vision-api one.

### devsupport fallbacks (`com.drones.vision.app.devsupport`)

Every repository-shaped port is unconditionally `Jpa*` (adapter-persistence) — Postgres is the only
store, so no `InMemory*` fallback exists for any of them. What remains here are genuinely-optional
feature no-ops:

| Class | Port | Selected when |
|---|---|---|
| `DevPrincipal` | — (constant holder: `USER_ID`/`GROUP_ID`/`OWNERSHIP`) | `vision.auth.enabled=false` |
| `LoggingEventPublisher` | `EventPublisherPort` | always the innermost layer (message-broker replacement is a later phase) |
| `NoopDetectionPort` | `DetectionPort` | `vision.cv.enabled=false` |
| `NoopStreamPublisher` | `StreamPublisherPort` | `vision.publish.enabled=false` |
| `NoopReplayFrameExtractor` | `ReplayFrameExtractionPort` | `vision.publish.enabled=false` |
| `NoopLiveUpdatePublisher` | all 6 live-update ports on one class | `vision.live.enabled=false` |
| `NoopReferenceIndexPort` | `ReferenceIndexPort` | `vision.geo.visual.enabled=false` |
| `NoopVehicleConfigPort` | `VehicleConfigPort` | `vision.onboarding.probe.enabled=false` (default) |

### ArchUnit rules

`ArchitectureTest` (hexagonal layering, `com.drones.vision` + test-scoped `com.drones.mavlink`):

| Rule | Enforces |
|---|---|
| `domainDependsOnlyOnDomainAndJava` | `..domain..`/`..kernel..`/`..platform..` → only those + `java..` |
| `applicationDependsOnlyOnApplicationDomainAndJava` | `..application..` → application/domain/kernel/platform/`java..`/`javax.imageio..` |
| `adaptersDoNotDependOnEachOther` | no `..adapter.(*)..` slice depends on a sibling slice |
| `onlyAppMayDependOnAdapterPackages` | only `..app..` (or adapter code itself) depends on `..adapter..` |
| `domainAndApplicationAreSpringAnnotationFree` | domain/application/kernel/platform never import `org.springframework..` |
| `restControllersLiveOnlyInApiControllerOrProxyPackage` | every `@RestController` in `api.controller` or `api.proxy` |
| `configurationPropertiesClassesLiveOnlyInAppConfigPropertiesPackage` | every `@ConfigurationProperties` in `app.config.properties` |
| `applicationHasNoClassesLooseAtItsRootPackage` | nothing sits directly in `com.drones.vision.application` |
| `onlyAppMayDependOnConfigPropertiesTypes` | only `..app..` depends on `..app.config.properties..` |
| `mavlinkCoreClassesAreActuallyOnTheTestClasspath` | sanity check guarding the next 4 rules against vacuous passes |
| `mavlinkCoreNeverDependsOnVision` | `com.drones.mavlink..` never imports `com.drones.vision..` |
| `mavlinkCoreIsSpringFree` | `com.drones.mavlink..` never imports `org.springframework..` |
| `mavlinkCoreTransportDoesNotReachUpTheStack` | `mavlink.transport..` doesn't depend on codec/session/service above it |
| `onlyCodecDependsOnTheMavlinkLibrary` | only `mavlink.codec..` imports `io.dronefleet.mavlink..` |

`ContextArchitectureTest` (bounded-context module graph — 8 contexts + universal `kernel`/`platform`):

| Rule | Enforces |
|---|---|
| `crossContextEdgesMatchTheDeclaredSetExactly` | observed cross-context edges == `DECLARED_EDGES` exactly (a burn-down list; fails on both a new edge and a stale allowance) |
| `theModuleGraphIsAcyclic` | no two contexts depend on each other; `DECLARED_CYCLES` must stay `Set.of()` |
| `noContextImportsAnotherContextsRepositoryPort` | bytecode scan: a context may not import another context's `*RepositoryPort` directly — must go through the owning context's application service, unless the edge is one of the 7 `REPOSITORY_PORT_EXEMPTIONS` (see Gotchas) |
| `kernelDependsOnNothingButItselfAndTheJdk` | `kernel` depends on nothing else |
| `platformDependsOnNothingButTheKernel` | `platform` depends only on kernel + itself |

`EndpointAuthorizationTest` (not ArchUnit, a reflection-based BFS over each `@RestController`'s call
graph): every handler must reach `CurrentUser.scope()`/`.viewer()` or a type whose name ends in
`Access`, or be `@OpenByDesign(reason)` (method- or class-level). `TEMPORARY_UNSCOPED` is a shrinking
ledger of known-unscoped handlers; a second assertion fails the build the moment an entry no longer
maps to a real handler, so it can't quietly outlive the gap it records.

## Conventions

- Wiring classes split by **concern/bean-type**, not by wave: a port-typed bean lives in the class
  matching its port's protocol/domain; an application-service bean goes in `ApplicationServiceWiring`
  regardless of which adapter it happens to depend on.
- Feature flags gate the **port or scheduled runner**, never the controller — a controller always
  resolves; what changes when a flag is off is a `Noop*` port or an absent runner bean.
- A flag that should remove a **whole optional bean cluster** puts `@ConditionalOnProperty`/
  `@ConditionalOnExpression` directly on each `@Bean` method with no fallback (`TrainingWiringConfiguration`
  style — beans simply don't exist when off). A port that must always answer *something* gets a
  `Noop*` devsupport fallback instead (`CvWiring`/`PublishWiring` style).
- Scheduled/background work is a self-managed daemon `ScheduledExecutorService`, or an `AutoCloseable`
  `@Bean(initMethod="start", destroyMethod="close")` runner — this module never uses `@Scheduled`/`@EnableScheduling`.
- A Spring-bound `@ConfigurationProperties` record consumed outside this module is mirrored into a
  plain, framework-free record in vision-api (the "bridge" pattern — `VisionApiProperties`,
  `VisionOnboardingProperties`→`OnboardingProperties`, `VisionGeoProperties`, `VisionGeoVisualProperties`),
  since vision-api may not depend on Spring's config-properties machinery.
- No new N-arg convenience constructors when adding a collaborator (CLAUDE.md rule 10) — extend an
  existing settings record instead; see `.claude/skills/java-clean-code/SKILL.md` §3.
- `application.yaml`, not `.properties`: one `vision.<owner>` block per Maven module or cross-module
  feature. A block whose keys are all commented has its module key commented too — an
  uncommented-but-empty block binds as a YAML **null**, which fails a record target at startup; a
  documented default must cost nothing at runtime.
- `ObjectProvider<T>` is the standard idiom both for "conditionally-present bean" and for breaking a
  circular bean dependency — see Gotchas for when it must be resolved lazily rather than eagerly.

## Gotchas

- **Bean cycle, by design workaround:** `AssetService → AssetLiveStatePort → StreamBackedAssetLiveState
  → UsageTracker → warehouse` would close if `usageTracker` (or its `AssetDirectoryService` collaborator)
  routed through `AssetService`/`DeviceService` instead of the raw `AssetRepositoryPort`/`DeviceRepositoryPort`.
  `ApplicationServiceWiring#assetDirectoryService` wraps the repository ports **directly** specifically
  to avoid this — "cleaning it up" by pointing it at `AssetService` reintroduces the cycle
  (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3).
- **`REPOSITORY_PORT_EXEMPTIONS` (`ContextArchitectureTest`) — 7 entries, each a recorded design
  decision, not a rubber stamp:** `DefaultLabelingService`→warehouse's `AssetUsageRepositoryPort`,
  `DefaultLabelingService`→perception's `DetectionRepositoryPort` (one-hop through `ReplaySources`);
  `DefaultReplayService`/`ReplaySources`→warehouse's `AssetUsageRepositoryPort`, →perception's
  `DetectionRepositoryPort`, and `DefaultReplayService`→flight's `TelemetryRepositoryPort` (vision-events'
  bulk, time-windowed historical reads — no owning context has a service shaped for them, and inventing
  one would produce a method with exactly one caller). The list is not exact-equality — shrinking it
  silently is fine — but adding an entry requires the same "genuinely bulk/historical" justification,
  not a way to silence a new violation.
- **`@Qualifier("liveUpdateRegistry")` on all 6 live-update selector beans is not decorative.** Remove
  it from any one and, once ≥2 of the six are resolved together, an unqualified `ObjectProvider<LiveUpdateRegistry>`
  lookup sees two candidates (the component-scanned bean + the already-resolved sibling) and throws
  `NoSuchBeanDefinitionException`.
- **`@Primary` does not help an `ObjectProvider` consumer.** `cvGrpcChannel` is `@Primary`, but once
  `cvTrainingChannel` also exists as a bean, every multi-channel consumer still needs explicit
  `@Qualifier("cvGrpcChannel")`/`@Qualifier("cvTrainingChannel")` — `@Primary` only resolves ambiguity
  for a plain `@Autowired` injection point. This was a real shipped break in `VisualGeoWiringConfiguration`,
  fixed by adding the qualifier pairs.
- **`ObjectProvider<T>` circular-dependency rule:** resolve it lazily (inside a lambda/predicate
  invoked after full context startup, never eagerly in the `@Bean` method body) whenever `T`'s own
  construction path loops back to the bean under construction. Two confirmed instances: `CvWiring#detectionDemandPort`'s
  `TrackProjectionRunner` (needs `AssetService`, whose path loops back through `detectionDemandPort`),
  and `ApplicationServiceWiring`/`StreamLifecycleWiring`'s `usageTracker` resolver.
- **Security default is condition-exclusivity, not a runtime tie-break.** `securedFilterChain` is
  `@ConditionalOnProperty(vision.auth.enabled, havingValue="true", matchIfMissing=true)`;
  `permitAllFilterChain` has no `matchIfMissing`, so it exists only when the property is explicitly
  `false`. Exactly one `SecurityFilterChain` bean is ever registered. CSRF is disabled in **both**
  chains deliberately (JSON-only API, same-origin, `SameSite=Lax` cookie — a documented deferred
  hardening step, not an oversight in the permit-all chain).
- **Spring Boot 4.1's destroy-method handling is strict when named, lenient when inferred.** An
  explicit `@Bean(destroyMethod="close")` throws `BeanDefinitionValidationException` at startup if the
  concrete resolved type lacks that method (e.g. a `Noop*` fallback with no `close()`); the default
  `"(inferred)"` mode silently no-ops instead. `detectionPort` uses an explicit **empty**
  `destroyMethod=""` because its channel's lifecycle belongs solely to `cvGrpcChannel`'s own
  `destroyMethod="shutdown"` — inferred cleanup here would tear the shared channel down under
  `GrpcModelRegistryPort`.
- **gRPC keepalive `Duration`s must convert with `.toMillis()`, not `.toSeconds()`** — a sub-second
  value truncates to `0` silently; `.usePlaintext()` must be a separate `if` statement before `.build()`,
  not inline in the fluent chain (can't express a conditional call mid-chain). `vision.cv.plaintext=false`
  only gets the JDK default trust chain — it does not validate a self-signed cv-service certificate.
- **`PublishWiring#streamPublisherPort` fails fast at wiring time**, not per-stream-start, if
  `vision.publish.source-proxy.enabled=true` while `vision.cv.frame-transport` is still `push`: a
  proxied source means this JVM never holds a frame, so push-mode detection would have nothing to
  send. Both flags default to the legal combination, so this never fires unconfigured.
- **`flyway-core` must stay a `compile` dependency here, never `test`.** Maven's nearest-definition
  rule lets a depth-1 test-scoped declaration beat the depth-2 transitive compile one from
  adapter-persistence, dropping Flyway from the repackaged jar — `java -jar` (how `Dockerfile` runs
  it) then dies at boot with `NoClassDefFoundError: org/flywaydb/core/Flyway`, while every test and IDE
  launch still passes.
- **`-pl`-scoped builds resolve every non-listed module from `~/.m2`, not from source.** A stale
  sibling adapter jar lets this module's whole suite pass green while a real cross-module wiring bug
  goes undetected — reinstall changed siblings first (`./mvnw -pl <adapters> install -DskipTests`).
  Always `mvn clean` too: a non-clean `target/` can serve stale pre-refactor classes and report
  "nothing to compile." **CLAUDE.md's own documented upstream-install command (`core/*`,
  `contexts/*`) does not cover `drone-link/**`** — a concurrent agent adding a public method to
  `adapter-mavlink` (e.g. `MavlinkTelemetrySource#holdLobby(int)`, ZERO-CONFIG-ONBOARDING Z2b/Z2c) can
  leave a stale, pre-that-method jar in `~/.m2` even after following CLAUDE.md's build recipe exactly,
  producing a `cannot find symbol` compile error against source that plainly declares the method
  (confirm with `stat` on the jar vs. the source file before assuming the code is wrong). Fix:
  `./mvnw -B -pl drone-link/mavlink-core,drone-link/mavlink install -DskipTests` before retrying.
- **Onboarding's two flags are independent and easy to misconfigure:** `vision.onboarding.passport.enabled=true`
  with `probe.enabled=false` (the default) makes every passport-capture attempt silently fail forever
  — `NoopVehicleConfigPort` has nothing to probe. The wiring logs one startup `WARNING` naming both
  flags for exactly this trap.
- **`CvChannelSupervisor` watches only the inference channel (`cvGrpcChannel`), never `cvTrainingChannel`.**
  Under a split cv-service deployment, the geolocation/training gate can be open while the training
  host is down, or closed by an unrelated inference outage. Accepted, permanent limitation — no
  second supervisor exists.
- `mavlinkFlightCommander`/`mavlinkManualControlSender`/`mavlinkHeartbeatScanner` all **borrow**
  `mavlinkTelemetrySource`'s already-open UDP socket rather than opening their own.
- A same-classpath-location `application.yaml` in `src/test/resources` fully **shadows** (not merges
  with) the real one via `ClassLoader#getResource` returning exactly one match — test-only overrides
  use `application.properties` instead, which loads alongside and wins per-key via Spring's normal
  `.properties`-over-`.yaml` precedence.
- `SystemStatusWiring`/`CvWiring#cvChannelSupervisor` deliberately repeat their gated resource's own
  `@ConditionalOnProperty`/`@ConditionalOnExpression` literally, rather than using
  `@ConditionalOnBean` — the latter is sensitive to `@Bean`-method declaration order within a
  `@Configuration` class and can pass in a narrow test slice while silently failing in full wiring.

## Status

- Real and default-on: video/telemetry source registries, mediamtx publish (`vision.publish.enabled=true`),
  discovery scanning, persistence (Postgres/Flyway, unconditional), auth (secured filter chain,
  `vision.auth.enabled=true`), tracking (`default-mode=ASSOCIATE`), idle-stream reaping, after-action
  evidence assembly (no flag), idle-usage-close sweep (no flag — `UsageIdleCloseRunner`, sweeps once
  at startup then every `vision.usage.sweep-period`, default 60s).
- Real and default-off: CV inference (`vision.cv.enabled`), CV training + split-channel routing
  (`vision.training.enabled`, `vision.cv.training.target`), API rate limiting
  (`vision.api.rate-limit.enabled`), mediamtx source-proxy (`vision.publish.source-proxy.enabled`),
  vehicle-onboarding probe/remediate/passport (`vision.onboarding.*.enabled`), fixed-camera geo
  (`vision.geo.fixed-camera.enabled`), visual geo (`vision.geo.visual.enabled`).
  Rationale for each default lives in the plan doc that introduced it
  (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md, docs/plans/active/DOMAIN-SEPARATION-W1.md,
  docs/plans/done/*-PLAN.md — linked per-bean above), not restated here.
- `GET /api/system/status` is real (4 `SubsystemStatusPort` beans: cv-service, mavlink-link,
  video-publish, live-updates).
- `EndpointAuthorizationTest`'s `TEMPORARY_UNSCOPED` ledger is a live, shrinking list — read the test
  source for its current size rather than trusting a number in this doc.

**WAREHOUSE-UX wave W8 done.** New unconditional `assetRowFacts` bean (see "Application-service beans"
above) — no flag, no new port implementation. `./mvnw -B -pl station/vision-app -DskipWeb test` —
**277 tests**, unchanged count (no new test file added to this module for W8; `ArchitectureTest`
(14), `ContextArchitectureTest` (5), and `EndpointAuthorizationTest` (2) all stayed green, confirming
`AssetRowFacts`/the new `assetRowFacts` bean/the widened `AssetController` constructor introduce no
ArchUnit violation).

**CV-SETTINGS wave W5 done (2026-08-30, uncommitted).** New unconditional `CvProfileWiringConfiguration`
(4 beans, see the wiring-map table above) behind `CvProfileController` (vision-api). `TrainingWiringConfiguration`
split its `modelRegistryPort`/`modelRegistryService` beans onto their own `vision.cv.registry.enabled`
switch, independent of `vision.training.enabled` — see that row above and `ModelRegistryController`'s
own javadoc. `VisionCvProperties` gained a `Profiles(Duration cacheTtl)` nested record (21st component
of the canonical constructor, default 60s) — every direct `new VisionCvProperties(...)` call site with
15+ positional args needed a trailing `null`/explicit value added; the two legacy convenience
constructors (4-arg, 15-arg) needed no change, their own compact-constructor fallback already covers it.

**Two real wiring-test defects found and fixed this wave** (both were pending items carried from an
earlier CV-SETTINGS segment, not new bugs introduced here):
1. `VisionCvPropertiesTest` had 5 direct `new VisionCvProperties(...)` calls using the canonical
   constructor's positional form that pre-dated the `Profiles` component — each now passes an
   explicit trailing `null`/value so the compact constructor's `profiles == null` fallback still runs.
2. `vision.cv.registry.enabled`'s new "follows `vision.cv.enabled`, not `vision.training.enabled`"
   default (see above) flipped two existing wiring-test assertions that pinned the *old* coupling:
   `CvEnabledWiringTest#cvOnlyConfigurationDoesNotWireTheModelRegistry` (renamed
   `cvOnlyConfigurationWiresTheModelRegistryByDefault`, assertion inverted — a CV-only deployment now
   gets the registry for free) and `TrainingEnabledWiringTest` (12 tests errored with `UnsatisfiedDependency`
   on `ModelRegistryPort` — its `@SpringBootTest` properties needed an explicit
   `vision.cv.registry.enabled=true` added, since `vision.training.enabled=true` alone no longer implies
   it). New `CvRegistryExplicitOptOutWiringTest` (1 test) proves the escape hatch
   (`vision.cv.registry.enabled=false` alongside `vision.cv.enabled=true`) still keeps the registry
   entirely absent, the pre-W5 default behavior, now reachable only by explicit opt-out.

**Verification pitfall, for the next agent**: a background-shelled `./mvnw ... test` (`run_in_background`
on the Bash tool, or a bare shell `&`) is killed the moment the issuing turn ends — it does **not**
survive to the next turn the way a normal long build does when run to completion synchronously. Two
build attempts in this wave were lost this way before switching to fully synchronous, foreground
`Bash` calls (`./mvnw -B -pl station/vision-api,station/vision-app -am -Dmaven.test.skip=true install`
first to refresh every upstream jar in one pass, then `./mvnw -B -pl station/vision-api test` and
`./mvnw -B -pl station/vision-app test` as two separate synchronous foreground commands — each may run
several minutes, budget the timeout accordingly). Running `-pl station/vision-api test` and
`-pl station/vision-app test` as two separate invocations **without** the `-am` install first also
independently produces misleading "cannot find symbol"/constructor-arity compile errors, because the
second invocation resolves the first's fresh output from a stale `~/.m2` jar rather than from
reactor-fresh classes — this is the documented `[[maven-build-verification]]` pitfall, not a code
defect; always `-am install -Dmaven.test.skip=true` across every touched module first when testing
modules as separate `-pl` invocations in the same session.

`./mvnw -B -pl station/vision-api,station/vision-app -am -Dmaven.test.skip=true install` (fresh jars
for the whole reactor, including `vision-web`'s own 3121 tests) then `./mvnw -B -pl station/vision-api
test` (**932**, all green) then `./mvnw -B -pl station/vision-app test` (**278**, all green — +1 over
the pre-W5 277-minus-the-two-broken-tests baseline: the new `CvRegistryExplicitOptOutWiringTest`) then
`./mvnw -B -pl storage/persistence test` (**260**, unchanged, read-only this wave; Docker ran for real —
`PostgresDockerIntegrationTest`'s 29 nested classes executed, not skipped). Default-config bar held
throughout: every pre-existing green suite stayed green, the two flipped assertions were pinning
behavior this wave deliberately changed (not accidentally broken), proven fixed rather than silenced.

**ZERO-CONFIG-ONBOARDING wave Z2c done.** New unconditional `DiscoveryInboxWiringConfiguration` (2
beans: `discoveryInboxService`, gated `discoveryInboxRunner` — see the wiring-map table above) +
`discovery.DiscoveryInboxRunner`, the module's second hand-rolled sweep runner built to the exact same
shape as `usage.UsageIdleCloseRunner` (own single-thread daemon `ScheduledExecutorService`, `AtomicBoolean`
start/close idempotency guards, `@Bean(initMethod="start", destroyMethod="close")`, `sweepSafely`
catching every `RuntimeException` so one bad sweep never kills the schedule — no `@Scheduled`, banned
repo-wide). Each sweep: hold the standing MAVLink lobby if `vision.discovery.lobby.enabled` (via the
already-unconditionally-wired `mavlinkTelemetrySource`, `TelemetryWiring`), scan
(`discoveryService.scan(new DiscoveryScanSpec(scanTimeoutSeconds, Set.of()))`), then report every hit
to `discoveryInboxService`. `PersistenceWiringConfiguration` gained `discoveryCandidateRepositoryPort`
(23rd unconditional repository bean). `VisionDiscoveryProperties` extended with `Lobby`/`Inbox` nested
records — see that row above; both new flags default `true`, a deliberate frozen-contract exception to
CLAUDE.md's usual opt-in-guardrail default, since an off-by-default sweep would defeat the entire
zero-config-onboarding point.

Found and fixed mid-wave: a **stale `~/.m2` `adapter-mavlink` jar** (built before a concurrent Z2b wave
added `MavlinkTelemetrySource#holdLobby(int)` to the module's source) produced a `cannot find symbol`
compile error even after following CLAUDE.md's documented upstream-install recipe exactly — that
recipe covers `core/*`/`contexts/*` only, not `drone-link/**`. Fixed by explicitly reinstalling
`drone-link/mavlink-core,drone-link/mavlink` first; flagged as a new Gotchas entry above so the next
agent doesn't waste time suspecting the newly-written code instead.

`./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation
install -DskipTests` then `./mvnw -B -pl drone-link/mavlink-core,drone-link/mavlink install -DskipTests`
(the fix above) then `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test
-DskipWeb` as one combined three-module command — `storage/persistence` **267** (+7), `station/vision-api`
**941** (+9), `station/vision-app` **293** (+15: `DiscoveryInboxRunnerTest` 7,
`VisionDiscoveryPropertiesTest` +4, `DiscoveryInboxWiringTest` 2, `DiscoveryInboxDisabledWiringTest` 2)
— all green on the first run after the jar fix, `ArchitectureTest`/`ContextArchitectureTest`/
`EndpointAuthorizationTest` all unaffected (no new ArchUnit violation), Docker ran for real (Postgres
Testcontainer migrated through `V31`, `DiscoveryCandidateRepositoryTests` executed). Default-config bar
held throughout — every pre-existing green suite stayed green.

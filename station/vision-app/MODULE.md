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
| `TelemetryWiring` | Simulation, Mavlink, Rc, Onboarding | `simulatedTelemetrySource`, `mavlinkTelemetrySource`, `mavlinkFlightCommander`, `mavlinkManualControlSender` — all unconditional. Static `toMavlinkSettings(...)` reused by `FeedTransmitterWiring`/`DiscoveryWiringConfiguration`/`OnboardingWiringConfiguration`. **MAVLINK-COMMANDS-PLAN P4** rewired `mavlinkFlightCommander` off the 2-arg `(MavlinkTelemetrySource, Duration)` back-compat constructor onto `MavlinkFlightCommander`'s canonical `(MavlinkTelemetrySource, MavlinkSettings)` one, reusing `toMavlinkSettings(...)` a fourth time (now takes `VisionRcProperties`/`VisionOnboardingProperties` too, matching `mavlinkTelemetrySource`'s own parameter list) |
| `PublishWiring` | Publish, Api, Cv, Media | `mediamtxStreamPublisher` (COP `vision.publish.enabled`, default true — own bean so `SystemStatusWiring` observes the *same* instance `streamPublisherPort` routes through), `streamPublisherPort` (unconditional `StreamPublisherPort`; `NoopStreamPublisher` if disabled, else a `PublisherRouter` wrapping the direct publisher + a `MediamtxProxyPublisher`, routed by `vision.publish.source-proxy.enabled`; **fails fast** if source-proxy is on while `vision.cv.frame-transport` is still `push` — see Gotchas), `mediamtxLiveFrameGrabber` (unconditional, cheap/lazy), `replayFrameExtractionPort` (unconditional; `NoopReplayFrameExtractor` if publish disabled), `hlsProxyUpstreamBase: URI`, `snapshotJpegEncoder`, `hlsProxySettings`, `liveSettings` (last 3 bridge Spring-bound `VisionApiProperties` → vision-api's plain mirror of the same simple name — see Gotchas). **ASSET-FLOWS-PLAN §2 S6**: every bean method above that builds a mediamtx URL now additionally takes `VisionMediaProperties mediaProperties`, converted once per call via the new private `toMediaCredentials(VisionMediaProperties)` helper into `adapter-publish-hls`'s `MediaCredentials` — `mediamtxLiveFrameGrabber` embeds the viewer credential in its RTSP read URL, `toPublishSettings`/`toProxySettings` thread it into `PublishSettings#auth`/`MediamtxProxySettings#media` (so `whepUrl`/`playbackUrl`/the RTSP push URL all carry credentials — see adapter-publish-hls/MODULE.md), and `toApiSupportProperties` appends `auth.viewerUsername()`/`auth.viewerPassword()` as `VisionApiProperties.HlsProxy`'s two new trailing components, which `HlsProxyController` (vision-api) sends as an outbound `Authorization: Basic` header (see that module's own MODULE.md Gotchas) |
| `CvWiring` | Cv | `cvGrpcChannel: ManagedChannel` `@Primary` (COE: `cv.enabled` OR `training.enabled` OR `frame-transport=pull` OR `geo.visual.enabled`; built via `CvChannels.forTargets`), `cvTrainingChannel` (COP `vision.cv.training.target` present — independent shutdown), `cvChannelSupervisor` (COE = cvGrpcChannel's expression AND `cv.reconnect.enabled` default true; `@Qualifier("cvGrpcChannel")`), `detectionPort` (unconditional bean, internal branch on `enabled`: `GrpcDetectionPort` w/ `@Qualifier("cvGrpcChannel")` else `NoopDetectionPort`; `destroyMethod=""`), `pulledDetectionPort` (COE `frame-transport=pull`; `@Qualifier("cvGrpcChannel")`), `cvModelRoster` (static constant), `detectionDemandPort` (COE default true, returns concrete `LiveAndPollDetectionDemand`), `streamDefaultConfig`, `streamDetectionSupport`. Static `toGrpcCvSettings(...)` and package-private `controlPlaneChannel(cvTrainingChannel, cvGrpcChannel)` (= training channel if present else falls back to inference channel) shared by `TrainingWiringConfiguration`/`VisualGeoWiringConfiguration` |
| `TrainingWiringConfiguration` | Training, Cv | `datasetUploadPort`, `trainingStores`, `replaySources`, `datasetService`, `labelingService` (takes `AssetDirectoryService`, not `AssetService`), `trainingPort`, `trainingJobService` all `@ConditionalOnProperty(vision.training.enabled=true)`, no fallback. `modelRegistryPort`/`modelRegistryService` are a **separate** switch since CV-SETTINGS-PLAN §5 (CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff decoupled the registry from training): `@ConditionalOnProperty(vision.cv.registry.enabled=true)`, whose own `application.yaml` default follows `vision.cv.enabled` via the `${vision.cv.enabled:false}` placeholder — a CV-only deployment gets the registry for free unless it explicitly opts out (`vision.cv.registry.enabled=false`); a training-only deployment does **not** get it for free any more. Channel-consuming beans route through `CvWiring.controlPlaneChannel(...)` |
| `CvProfileWiringConfiguration` | — | **Every bean unconditional**, no `@Conditional*` at all (CV-SETTINGS-PLAN §3.1/§5, CV-SETTINGS-CONTEXT.md's W2 → W5 handoff): `cvProfileCacheSettings` (from `VisionCvProperties.Profiles#cacheTtl()`, default 60s), `cvProfileCache` (write-through, lazy-TTL-reload), `cvProfileResolver` (asset→category→organization→platform fold, shared with `ApplicationServiceWiring#streamService` and `CvWiring#streamDetectionSupport`), `cvProfileService` (`DefaultCvProfileService`, behind `CvProfileController`) — profiles ship built-in (4 seeded rows, `V29__cv_profiles.sql`) regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`, the same "ships built-in" posture `TrackingWiring#cvTrackerRoster` takes |
| `DiscoveryWiringConfiguration` | Discovery, Mavlink | `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner`/`mavlinkHeartbeatScanner` — each COP `vision.discovery.enabled` default true. `mediamtxPathScanner` (ZERO-CONFIG-ONBOARDING Z3) is the fifth scanner, COP `name = {"enabled", "mediamtx.enabled"}` under the `vision.discovery` prefix — Spring ANDs the array together, so it needs **both** `vision.discovery.enabled` and `vision.discovery.mediamtx.enabled` (default true, its own independent off-switch since it is the one scanner that depends on another subsystem, mediamtx/`vision.publish.enabled`, being present at all). Built from `MediamtxScannerSettings(publishProperties.mediamtx().apiBase(), publishProperties.mediamtx().rtspBase(), properties.mediamtx().pathPrefix())` — takes `VisionPublishProperties` as a plain constructor param (registered by `PublishWiring`'s own `@EnableConfigurationProperties`, not this class's; autowires here as an ordinary bean regardless of `vision.publish.enabled` since only the publisher beans themselves are conditional on that flag) rather than inventing a parallel `vision.discovery.mediamtx.api-url`/`rtsp-base` pair, so the two consumers of "where mediamtx is" can never disagree. `discoveryService` and `mavlinkPort` **unconditional** (`DiscoveryController` needs the service regardless; `DefaultDiscoveryService` tolerates an empty port list). `mavlinkHeartbeatScanner` lives in adapter-mavlink, not adapter-discovery like its other 3 siblings, because it borrows `mavlinkTelemetrySource`'s open socket and adapters can't depend on each other |
| `DiscoveryInboxWiringConfiguration` | Discovery | `discoveryInboxService(DiscoveryCandidateRepositoryPort, AssetService)` → `DefaultDiscoveryInboxService`, **unconditional** (`DiscoveryInboxController`, vision-api, needs it regardless of whether the sweep runs — an operator can still read/register/dismiss by hand with the runner off). `discoveryInboxRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.discovery.inbox.enabled=true` (matchIfMissing=true, the default — ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c deliberately overrides CLAUDE.md's usual opt-in-guardrail default, since shipping the sweep off by default would defeat the whole zero-config purpose) |
| `FeedTransmitterWiring` | Publish, Rtsp, Mjpeg, Mavlink, Rc, Onboarding | `rtspFeedTransmitter`, `mjpegFeedTransmitter` (`destroyMethod="close"` — owns a shared `HttpServer`), `mavlinkFeedTransmitter`, `feedTransmitterRegistry` — all unconditional |
| `PersistenceWiringConfiguration` | Persistence | **Every bean unconditional**, no `@Conditional*` anywhere — 23 one-line `Jpa*Repository(entityManagerFactory)` ports (WAREHOUSE-UX W3 added `maintenanceRepositoryPort`/`assetNoteRepositoryPort`; ZERO-CONFIG-ONBOARDING Z2c added `discoveryCandidateRepositoryPort`) + `persistenceEntityManagerFactory` (`destroyMethod="close"`, opens a real JDBC connection eagerly). No in-memory fallback exists for any repository port (Postgres is the only store) |
| `AuthWiringConfiguration` | — | `passwordHasherPort`, `authService`, `userService`, `groupService`, `scopeResolver`, `assignmentService` (`DefaultAssignmentService(assignmentRepositoryPort, assetService)` — takes `AssetService`, not `AssetRepositoryPort`), `activityService` — all unconditional; `authService`/`userService`/`assignmentService` (AUTH-ROLES wave B3) take a third constructor arg, `AuditTrailPort`, threaded to the 3-arg `Default*` constructors D15 added. `devPrincipalResolver`/`noopSessionAuthenticator` COP `vision.auth.enabled=false` (matchIfMissing=true, the default); `securityContextPrincipalResolver`/`securitySessionAuthenticator` COP `vision.auth.enabled=true`. `securitySessionAuthenticator`'s factory method (wave B3) additionally declares two bare `@Value`-annotated `long` params (`vision.auth.session.idle-timeout-hours:12`, `vision.auth.session.kiosk-idle-timeout-days:365`) — `@Value` on the *constructor* would be silently ignored since the bean is built with `new SecuritySessionAuthenticator(...)` inside this factory method rather than via Spring's own reflective construction, so the annotation has to live on the factory method's own parameters instead; folding these into a real `VisionAuthProperties` record is deferred to wave B5. Repository ports (`UserRepositoryPort`/`GroupRepositoryPort`) come from `PersistenceWiringConfiguration`, orthogonal to `vision.auth.enabled` |
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
| `OpsWiringConfiguration` | Ops | `opsThresholds(VisionOpsProperties)` → `OpsThresholdsResponse` (vision-api DTO), **unconditional, no enable flag** — display config, not a feature. Same "plain config-backed DTO bean, no service layer" shape `TrackingWiring#cvTrackerRoster` established (ASSET-FLOWS-PLAN §2/BK3). FLY-CONTROL-UX-PLAN §2/BK1 widened the built response with `RcThresholdsResponse(properties.rc().neutralTolerancePercent())`, mirroring the `battery` mapping verbatim — no new bean, no constructor overload |
| `ApplicationServiceWiring` | Cv, Live, Rc, Application, Publish, Simulation | The largest class — every `vision-application`/context `DefaultXService` bean. See "Application-service beans" below |

### Application-service beans (`ApplicationServiceWiring`)

- **Live server-push selectors (6, not 5):** `fleetLiveUpdatePort`/`telemetryLiveUpdatePort`/`detectionLiveUpdatePort`/`mapLiveUpdatePort`/`eventLiveUpdatePort`/`trackCorrectionLiveUpdatePort` — each takes `VisionLiveProperties` + `@Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry>`; returns the registry if `properties.enabled()` (default true) else `new NoopLiveUpdatePublisher()`. `trackCorrectionLiveUpdatePort` backs the `geo:<assetId>` topic (visual-geo), same flag.
- `eventPublisherPort` — `LoggingEventPublisher`, wrapped in `DetectionSessionCleanupEventPublisher` if CV enabled and `detectionPort` is a real `GrpcDetectionPort`, further wrapped in `LiveUpdateEventPublisher` if live enabled.
- `auditTrailPort`/`detectionEventRepositoryPort` — `Jpa*` (adapter-persistence), each wrapped in a `LiveUpdate*` decorator when live enabled.
- `manualControlService` — `DefaultManualControlService`'s **7-arg canonical constructor** (`Clock.systemUTC()`, a private `rcWatchdogScheduler()` single-thread daemon, `rcProperties.watchdogTimeoutMs()`, and `ControlProfileService::activeFor` as a method reference, not the whole service).
- `flightCommandService(AssetService, FlightCommandPort, AuditTrailPort, ReadinessService)` — `DefaultFlightCommandService`'s 4-arg canonical constructor (ASSET-FLOWS-PLAN S1, wave BK1: `ReadinessService` is the new 4th param, same bean `manualControlService`/`OnboardingWiringConfiguration#readinessService` already consume — no new bean, just a new injection point).
- `assetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` / `usageSessionService(AssetUsageRepositoryPort)` / `telemetryService(TelemetryRepositoryPort)` — thin per-repository ownership seams that `usageTracker` composes instead of holding repository ports directly (ARCHITECTURE-AUDIT-2026-08-26.md D1/R3). **`assetDirectoryService` wraps the repository ports directly rather than `AssetService`/`DeviceService` — this is the fix for the bean cycle described in Gotchas, not a shortcut.**
- `usageTracker` — composed from `assetDirectoryService`/`usageSessionService`/`telemetryService` + `List<TelemetrySourcePort>` + a `UsageTrackerSettings` record (telemetry live port, a `BiConsumer<AssetId,Telemetry>` telemetry observer, persistence-batch settings, `UsagePhaseSettings.defaults()`, an `ObjectProvider<UsagePhaseObserver>` defaulting to `NOOP` — ASSET-FLOWS S1/BK1 — a required `MaintenanceQuery` param, the same `DefaultMaintenanceService` bean `OnboardingWiringConfiguration#readinessService`/`flightCommandService` already consume, and — ASSET-FLOWS S4/BK2b — a required `LinkLossNotifier` param, threaded through `UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier)` and as the settings record's now-last, 9th field). The observer is a composed lambda — `geofenceMonitor.evaluate(...)` then `batteryMonitor.evaluate(...)` against the same sample, both method calls rather than a chained `BiConsumer`, so perception never depends on flight's context directly (ASSET-FLOWS S4/BK2 composed `batteryMonitor` into this same seam rather than adding a second `UsageTrackerSettings` slot — CLAUDE.md rule 10); `linkLossNotifier` itself is passed straight through as a plain collaborator, not composed into that lambda, since `UsageTracker` calls it once from a `SupervisedPublisher` outage callback rather than once per sample.
- `batteryMonitor(EventPublisherPort, EventLiveUpdatePort, @Value("${vision.ops.battery.critical-percent:10}") double, @Value("${vision.ops.battery.warning-percent:25}") double)` → `vision-flight`'s `BatteryMonitor`, settings wrapped in a `BatteryAlertSettings`. `linkLossNotifier(EventPublisherPort, EventLiveUpdatePort)` → `vision-flight`'s `LinkLossNotifier`. **ASSET-FLOWS S4/BK2b wired this bean into `usageTracker` above** — perception's `UsageTracker#subscribeTelemetry` now calls `reportLinkLost` from its telemetry `SupervisedPublisher`'s outage callback (see `contexts/vision-perception/MODULE.md`). Both beans read the D6 threshold properties directly via `@Value` with code defaults (`vision.ops.battery.*` is not yet in `application.yaml` — a follow-up wave's job) rather than a dedicated `@ConfigurationProperties` record, since `BatteryAlertSettings` itself is already the settings record and a second properties type would just duplicate its two fields.
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
| `VisionDiscoveryProperties` | `vision.discovery` | `adapter-discovery` scan budgets; ZERO-CONFIG-ONBOARDING Z2c added two nested records to the canonical constructor's 4th/5th components — `Lobby(boolean enabled)` (default `true`, `DiscoveryInboxWiringConfiguration`'s `mavlinkTelemetrySource.holdLobby(...)` gate) and `Inbox(boolean enabled, int sweepSeconds, int scanTimeoutSeconds)` (defaults `true`/`30`/`5`, compact-constructor validated both ints `> 0`) — both default to `true`/on, a deliberate exception to the repo's usual opt-in-guardrail default (see `DiscoveryInboxWiringConfiguration` row above). Z3 added a 6th component, `Mediamtx(boolean enabled, String pathPrefix)` (defaults `true`/`"ingest/"`, compact-constructor rejects a blank `pathPrefix`) — consumed by `DiscoveryWiringConfiguration#mediamtxPathScanner`'s COP and settings-record construction (see that row above); `apiBase`/`rtspBase` are deliberately **not** on this record — they come from `VisionPublishProperties.Mediamtx` instead, so this record only owns the one property that's genuinely this module's own concern |
| `VisionSimulationProperties` | `vision.simulation` | `adapter-simulation` video/telemetry settings + resume-on-boot |
| `VisionPublishProperties` | `vision.publish` | `PublishWiring`/`adapter-publish-hls` (mediamtx, encoder, resilience, cadence, replay, source-proxy) |
| `VisionMediaProperties` | `vision.media` | ASSET-FLOWS-PLAN §2 S6 mediamtx read/publish credentials. `record VisionMediaProperties(Auth auth)` — a `null` `auth` (whole block absent) normalizes to `Auth`'s own all-`@DefaultValue` construction in the compact ctor, matching `VisionOpsProperties.Battery`'s precedent (Spring relaxed binding does not apply a nested record's `@DefaultValue`s when the whole block is missing). `record Auth(String viewerUsername, String viewerPassword, String publisherUsername, String publisherPassword)`, `@DefaultValue`s `vision-viewer`/`change-me`/`vision-publisher`/`change-me` — **deliberately non-blank**, not the repo's usual "empty disables the feature" idiom, because mediamtx's own `authInternalUsers` (`./mediamtx.yml`) ships those exact same accounts already configured with the same `change-me` password: a blank Java-side default would silently stop authenticating against a mediamtx that already requires auth, which is a worse failure than an easily-`grep`-able placeholder credential. Operators MUST override both this block's `viewer-password`/`publisher-password` AND `mediamtx.yml`'s matching `pass:` fields (plus `docker-compose.yml`'s `VISION_MEDIA_AUTH_*` env vars) together — mediamtx's env-var loader has no override mechanism for `authInternalUsers` (a list-of-struct field), so the mounted `mediamtx.yml` file and this record's binding are two independent sources of the same secret. Consumed only by `PublishWiring` (see that row's own entry and Gotchas). |
| `VisionRcProperties` | `vision.rc` | `adapter-mavlink`'s RC-override cadence/watchdog. **FLY-CONTROL-UX H1**: `vision.rc.engage-slow-threshold-ms` (default 2000) lives in the same `vision.rc.*` namespace but deliberately has no field on this record — it is read independently by `vision-api`'s `ManualControlWebSocketHandler` via its own `@Value` (vision-api may not depend on vision-app), the same split this record's own javadoc already documents for `watchdog-timeout-ms` |
| `VisionRtspProperties` | `vision.rtsp` | `adapter-rtsp`'s `FfmpegSettings` |
| `VisionMjpegProperties` | `vision.mjpeg` | `adapter-mjpeg`'s `MjpegSettings` |
| `VisionV4l2Properties` | `vision.v4l2` | `V4l2VideoSource` directly (no adapter settings record) |
| `VisionMavlinkProperties` | `vision.mavlink` | `adapter-mavlink`'s `MavlinkSettings` (minus `Rc`); **FLEET-RADIO R4/D7** adds `dropRateWarnPercent`/`dropRateAlarmPercent`/`linkFailureGrace` (defaults 5.0/20.0/2s, compact-constructor validated: both percents 0..100, alarm>=warn, grace positive) — consumed by `TelemetryWiring#toMavlinkSettings` (into `MavlinkSettings.LinkStatus`) and directly by `SystemStatusWiring#mavlinkLinkStatus`. **MAVLINK-COMMANDS-PLAN P4** re-scoped `ackTimeout`'s `@DefaultValue` from `2s` to `700ms` (now byte-identical to `MavlinkSettings.DEFAULT_ACK_TIMEOUT_MILLIS`, the per-*attempt* wait) and added `commandRetries` (11th canonical-constructor component, right after `ackTimeout`; `@DefaultValue` `2`, compact-constructor validated `>= 0`, byte-identical to `MavlinkSettings.DEFAULT_COMMAND_RETRIES`) — both threaded through `TelemetryWiring#toMavlinkSettings` via `MavlinkSettings.withCommandRetries(...)`, closing the P1-documented production gap (see Gotchas) |
| `VisionApiProperties` | `vision.api` | bridges to vision-api's plain mirror (same simple name, see Gotchas); snapshot/hlsProxy/live/paging/upload/rateLimit |
| `VisionOnboardingProperties` | `vision.onboarding` | probe/remediate/passport flags → `OnboardingWiringConfiguration` + `TelemetryWiring#toMavlinkSettings`. `probe.parameters` (list, default empty) **replaces** the adapter's firmware-verified probe list rather than adding to it; empty keeps it |
| `VisionControlProperties` | `vision.control` | `ControlProfileWiring`'s aux-function catalog |
| `VisionGeoProperties` | `vision.geo.fixed-camera` | `FixedCameraGeoWiringConfiguration` |
| `VisionGeoVisualProperties` | `vision.geo.visual` | `VisualGeoWiringConfiguration` (incl. `tiles.*`) |
| `VisionStreamsProperties` | `vision.streams` | `StreamLifecycleWiring`'s idle-stream reaper |
| `VisionTrackingProperties` | `vision.tracking` | `TrackingWiring` seed + per-stream read-model windows |
| `VisionUsageProperties` | `vision.usage` | `UsageWiringConfiguration`'s idle-usage-close sweep (`idleClose` default 10m, `sweepPeriod` default 60s) |
| `VisionOpsProperties` | `vision.ops` | `OpsWiringConfiguration`'s `opsThresholds` bean, behind `GET /api/ops/thresholds` (vision-api). Nested `Battery(int warningPercent, int criticalPercent)`, defaults 25/10, compact-constructor validated (`critical < warning`, both `[0,100]`); absent `battery` block falls back to `Battery.defaults()` since Spring relaxed binding does not apply a nested record's own `@DefaultValue`s when the whole block is missing (`VisionMavlinkProperties`'s `Scan`/`Transmit` precedent). `ApplicationServiceWiring#batteryMonitor` (BK2, this same cycle) independently reads the identical `vision.ops.battery.critical-percent`/`warning-percent` keys via raw `@Value`, by deliberate design (see that bean's own javadoc) rather than by accident — it carries the same 10/25 defaults inline so it behaves correctly whether or not this record/its `application.yaml` block exists yet, and now that both do, an operator override of the yaml block reaches **both** consumers identically since they bind the same property keys, which is the actual "one configured severity source" ASSET-FLOWS-PLAN §2 asks for. The two Java binding mechanisms (this `@ConfigurationProperties` record vs. `batteryMonitor`'s two `@Value`s) staying separate rather than both consuming this one record is a minor follow-up cleanup, not a config-drift risk. FLY-CONTROL-UX-PLAN §2/BK1 added a second nested record, `Rc(int neutralTolerancePercent)`, default **5**, compact-constructor validated `[1,25]`, absent `rc` block falling back to `Rc.defaults()` under the exact same whole-block-absent rule as `Battery` — the web cockpit's neutral-stick arm gate's tolerance, read off `GET /api/ops/thresholds`'s new `rc` field. |

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
- **`vision.media.auth.*`'s defaults (`vision-viewer`/`change-me`/`vision-publisher`/`change-me`) are
  non-blank on purpose and change observable behaviour out of the box** (ASSET-FLOWS-PLAN §2 S6): a
  fresh checkout's default WHEP URL now carries `?user=vision-viewer&pass=change-me`, and `./mediamtx.yml`
  ships the matching `authInternalUsers` accounts already gating read on every path and publish on
  every path except `ingest/` — this is the single biggest source of "my bookmarked mediamtx URL from
  before this change now 401s" reports, and is the accepted, intended cost of closing the defect (see
  that module's own MODULE.md and header comment). Overriding the password half of this block without
  also editing `./mediamtx.yml`'s matching `pass:` fields (mediamtx's env-var loader cannot set
  `authInternalUsers`, a list-of-struct field) breaks auth in the *opposite* direction — the Java side
  sends a credential mediamtx no longer recognizes.
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

**ZERO-CONFIG-ONBOARDING wave Z3 done.** New fifth `DeviceDiscoveryPort` bean, `mediamtxPathScanner`
(`DiscoveryWiringConfiguration`), wired behind a two-flag `@ConditionalOnProperty` array (`vision.
discovery.enabled` AND `vision.discovery.mediamtx.enabled`, both default `true`) — see that row above
for the full design. `VisionDiscoveryProperties` gained a 6th canonical-constructor component,
`Mediamtx(boolean enabled, String pathPrefix)` (default `true`/`"ingest/"`); the scanner's
`apiBase`/`rtspBase` are deliberately sourced from the *existing* `VisionPublishProperties.Mediamtx`
record instead — confirmed by reading `docker-compose.yml` that vision-app's own container already
sets `VISION_PUBLISH_MEDIAMTX_API_BASE=http://mediamtx:9997`/`VISION_PUBLISH_MEDIAMTX_RTSP_BASE=
rtsp://mediamtx:8554` for exactly this reachability need (`adapter-publish-hls`'s `MediamtxControlApi`
already uses the same properties to reach the same mediamtx instance), so **no docker-compose.yml
change was needed** — reusing the property solved the docker-compose-vs-localhost distinction for
free. `mediamtx.yml` gained a documentation-only comment block (no functional/effective config
change) explaining the `ingest/` push convention the scanner reads back out. `DiscoveryInboxRunner`
needed **no change** — it already fans a sweep out across every registered `DeviceDiscoveryPort` via
`discoveryService.scan(...)`, so the new scanner participates automatically once registered as a bean.
No `station/vision-web` or `contexts/**` files touched (out of this wave's file scope; the found-
device inbox UI is a separate concurrent wave).

`./mvnw -B -pl device-discovery/onvif-mdns-v4l2 test` (**58**, +18 over the pre-Z3 40: `mediamtx`
package's `MediamtxPathListParserTest` 9 + `MediamtxPathScannerTest` 9, both new) then `./mvnw -B -pl
station/vision-app -am test -DskipWeb` (**298**, +5 over the pre-Z3 293: `VisionDiscoveryPropertiesTest`
+3 — `mediamtxDefaultsWhenAbsent`, `explicitMediamtxIsCarriedThrough`, `blankMediamtxPathPrefixIsRejected`
— and new `DiscoveryMediamtxWiringTest` 2 — `noMediamtxScannerBeanWhenDisabled`,
`theOtherFourScannersAreStillRegistered`) — all green on the first full run, no stale-jar issue this
time (this wave never touched a module outside `device-discovery`/`station/vision-app`, so no
upstream `-am` dependency needed reinstalling). `DiscoveryWiringTest`'s default-config assertion
widened from four methods to five (`onvif`/`mdns`/`v4l2`/`mavlink`/`mediamtx`); `DiscoveryInboxRunnerTest`
and `VisionDiscoveryPropertiesTest`'s pre-existing 5-arg canonical-constructor call sites both needed a
trailing `null` for the new 6th `Mediamtx` component. `ArchitectureTest`/`ContextArchitectureTest`
unaffected — `MediamtxPathScanner` imports nothing from another adapter (only `vision-warehouse`'s
`DeviceDiscoveryPort`/`DiscoveredDevice`, matching every other scanner in the module).

**MAVLINK-COMMANDS wave P3 done — verified already wired, no wiring change.** The 2026-08-26
architecture audit flagged that R3's codebase inventory could not confirm every production
`MavlinkSession` actually registers a `mavlink-core` `onLinkFailure` listener (vs. the hook merely
existing). Traced to ground: every production `MavlinkSession` is built in exactly one place —
`MavlinkGateway`'s package-private `(MavlinkLink, MavlinkSettings)` constructor
(`drone-link/mavlink`'s `MavlinkGateway.java:168-176`), which the production `(String, int,
MavlinkSettings)` constructor unconditionally delegates to (`MavlinkGateway.java:156-158`) — `grep
-rn "new MavlinkSession("` across the repo shows no other production call site, only mavlink-core's
own unit tests. That constructor registers `session.onLinkFailure((linkId, cause) ->
handleLinkFailure(cause))` at line 176, before any device registration/claim policy/subscription
exists. `MavlinkGateway` itself has exactly two production callers — `MavlinkTelemetrySource.java:322`
(the RX hub every device shares) and `MavlinkVehicleConfigurator.java:494` (the onboarding probe's
self-bound gateway) — both go through that one constructor. `handleLinkFailure`
(`MavlinkGateway.java:347-351`) logs a `WARNING` naming the failed link and closes every registered
device's `SubmissionPublisher` exceptionally, which is exactly the `onError` `UsageTracker`
(vision-perception) `SupervisedPublisher`-wraps into the cockpit's existing stale-not-live telemetry
rendering (OPERATOR-UX-3) — no parallel status concept needed. `MavlinkHeartbeatScanner`'s self-bind
discovery path never builds a `MavlinkSession` at all (a bounded scan over a bare
`UdpListenLink`+`FrameReader`, its own synchronous bind-failure handling), so it was never a
candidate for this failure class. vision-app itself has no seam to add a listener to —
`MavlinkGateway`/`MavlinkSession` construction is fully encapsulated inside adapter-mavlink's
package-private `MavlinkGateway`; vision-app only ever sees the public `MavlinkTelemetrySource`
facade. **No wiring change made** — the "already wired, write the missing test, stop" branch.

New `config/wiring/MavlinkLinkStatusWiringTest` (1 test, no Spring context, no Docker) pins the piece
that genuinely is this module's own responsibility: that `SystemStatusWiring#mavlinkLinkStatus`
reads its health off the *same* `MavlinkTelemetrySource` instance the rest of the app's MAVLink
wiring shares, not a stale/disconnected copy. Builds the real production object graph
(`TelemetryWiring#toMavlinkSettings` → a real socket-bound `MavlinkTelemetrySource` →
`SystemStatusWiring#mavlinkLinkStatus`), sends one real MAVLink heartbeat over real loopback UDP via
the existing `MavlinkFeedTransmitter` test double, and proves the status port's answer is live:
`Health.UNKNOWN` before anything is heard, non-`UNKNOWN` once a real vehicle is claimed, with
`source.claimedVehicleHealth()` (the exact `Supplier` `mavlinkLinkStatus` is built from) showing the
same device. A genuine-`IOException` reproduction of `onLinkFailure` itself was deliberately not
attempted from this module: the only injectable-failing-link seam (`MavlinkGateway(MavlinkLink,
MavlinkSettings)`) is package-private inside adapter-mavlink and already exercised end-to-end there
(`MavlinkGatewayLinkFailureTest`) and at the `mavlink-core` layer
(`MavlinkSessionLinkFailureTest`) — reproducing it from vision-app would need either reflection into
private socket internals (a pattern FLEET-RADIO R4 explicitly rejected as fragile/invasive when
building this exact seam) or duplicate coverage of a mechanism already proven at the layer that owns
it.

`./mvnw -B -pl station/vision-app -am test -DskipWeb` — **299** (+1 over the pre-P3 298), all green;
`ArchitectureTest`/`ContextArchitectureTest`/`EndpointAuthorizationTest` unaffected (no new bean, no
new ArchUnit surface). Two collisions hit during this wave's gate runs, both from other agents'
concurrent in-progress work on this shared branch, both cleared on retry: `FlightModesTest` failed
once against a mid-edit `drone-link/mavlink/FlightModes.java` (P1's file scope), and `npm run
test:ci` failed once against mid-edit `station/vision-web/src/app/core/rc/*` files (W1's file scope,
`-DskipWeb` used for every run after that to isolate this module's own verification from the
concurrent frontend wave). A `MediamtxDockerIntegrationTest` (`adapter-rtsp`, untouched by this or
any MAVLINK-COMMANDS wave) failure also cleared on retry — a timing flake under concurrent
sandbox load, not a regression. Docker ran for real throughout (Postgres Testcontainer migrated
through `V31`).

**MAVLINK-COMMANDS-PLAN.md wave P4 done** — closes the production gap P1 flagged in
`drone-link/mavlink/MODULE.md`'s Gotchas: `TelemetryWiring#mavlinkFlightCommander` still called
`MavlinkFlightCommander`'s 2-arg `(MavlinkTelemetrySource, Duration)` back-compat constructor with
`properties.ackTimeout()`, and `VisionMavlinkProperties.ackTimeout()` still defaulted to the old
single-whole-command 2s literal with no `commandRetries` field at all — so production got the new
bounded-retry behaviour (3 attempts) layered onto the *old*, larger per-attempt timeout: worst case
~6s (3 × 2s) for a fully-silent vehicle instead of the ~2.1s D2a intended (3 × 700ms).

Two changes, both in `config/properties/VisionMavlinkProperties.java`:
1. `ackTimeout`'s `@DefaultValue` changed from `"2s"` to `"700ms"` — now byte-identical to
   `MavlinkSettings.DEFAULT_ACK_TIMEOUT_MILLIS`.
2. New `commandRetries` component (11th on the canonical constructor, inserted right after
   `ackTimeout` — matching `MavlinkSettings`'s own field order), `@DefaultValue` `"2"`
   (`DEFAULT_COMMAND_RETRIES`, byte-identical to `MavlinkSettings.DEFAULT_COMMAND_RETRIES`),
   compact-constructor validated `>= 0` (mirrors `MavlinkSettings`'s own check, same message shape).
   Every existing positional `new VisionMavlinkProperties(...)` call site (3, all in this module's own
   tests — `VisionMavlinkPropertiesTest`, `TelemetryWiringOnboardingTest`,
   `MavlinkLinkStatusWiringTest`) needed the new arg inserted; none live outside `station/vision-app`.

One change in `config/wiring/TelemetryWiring.java`: `toMavlinkSettings(...)` now chains
`.withCommandRetries(properties.commandRetries())` onto the `MavlinkSettings` it builds (right after
the constructor call, before `.withOnboarding(...)`/`.withLinkStatus(...)`) — the 8-arg back-compat
`MavlinkSettings` constructor it still calls only *defaults* `commandRetries` to `MavlinkSettings`'s
own constant; without this `.withCommandRetries(...)` call, `vision.mavlink.command-retries` would
still be a documentation-only key for every consumer of `toMavlinkSettings`, not just the commander.
This one-line addition means all four `toMavlinkSettings` consumers (`mavlinkTelemetrySource`,
`FeedTransmitterWiring#mavlinkFeedTransmitter`, `OnboardingWiringConfiguration`, and now
`mavlinkFlightCommander`) honor an operator-configured retry budget, not just the commander this wave
targeted. `mavlinkFlightCommander` itself was rewired from `new MavlinkFlightCommander(mavlinkTelemetrySource,
properties.ackTimeout())` onto `new MavlinkFlightCommander(mavlinkTelemetrySource,
toMavlinkSettings(mavlinkProperties, rcProperties, onboardingProperties))` — the canonical
`(MavlinkTelemetrySource, MavlinkSettings)` constructor, gaining `VisionRcProperties`/
`VisionOnboardingProperties` as two new bean-method parameters (Spring resolves both from beans
`TelemetryWiring`/`OnboardingWiringConfiguration` already register; no new `@EnableConfigurationProperties`
needed since `TelemetryWiring` already declares all three). Per CLAUDE.md rule 10/java-clean-code §3,
this is "update the call site," not a new overload — `MavlinkFlightCommander`'s own constructors were
untouched (out of scope: `drone-link/mavlink` is a different wave's file scope on this branch).

No `application.yaml` change — P1's `mavlink:` block is already fully commented out (module key
included, per this module's own "block whose keys are all commented has its module key commented too"
convention) and documents `ack-timeout: 700ms`/`command-retries: 2` as the intended values already;
its "KNOWN GAP" comment (that `command-retries` was documentation-only and `ack-timeout` still ran at
2s in production) is now stale but was left untouched — out of this wave's brief, which permitted
touching that file only for a key-*name* change, and none was needed (`command-retries`/`ack-timeout`
already relaxed-bind onto `commandRetries`/`ackTimeout` the same way `drop-rate-warn-percent` already
binds onto `dropRateWarnPercent`). Flagged here for whichever wave next touches that comment block.

New `config/wiring/TelemetryWiringCommandRetryWiringTest` (3 tests) pins the fix at the one seam this
module owns, since `MavlinkFlightCommander` exposes no accessor for its private `ackTimeout`/
`commandRetries` fields (and `drone-link/mavlink` was another agent's concurrent file scope, so no
reflection/getter could be added there this wave): `defaultPropertiesYieldTheD2aBudget` binds
`VisionMavlinkProperties`/`VisionRcProperties`/`VisionOnboardingProperties` from an **empty** source
(the `Binder`/`MapConfigurationPropertySource` idiom `PersistenceWiringConfigurationTest` established)
and asserts `toMavlinkSettings(...)`'s result carries `ackTimeout() == 700ms`/`commandRetries() == 2`;
`mavlinkFlightCommanderBeanBuildsOnTheSameSettingsAsMavlinkTelemetrySource` calls the actual
`TelemetryWiring#mavlinkFlightCommander` bean method (against a bare `new MavlinkTelemetrySource()` —
safe, opens no socket) and asserts it builds without throwing; `anOperatorSuppliedCommandRetriesReachesTheSettingsObject`
proves `vision.mavlink.command-retries: 0` (today's byte-for-byte single-shot behaviour) reaches the
built `MavlinkSettings`, not just the properties record. `VisionMavlinkPropertiesTest` gained 3 tests:
`negativeCommandRetriesIsRejected`/`zeroCommandRetriesIsAccepted` (compact-constructor validation) and
`ackTimeoutAndCommandRetriesDefaultsMatchMavlinkSettings` (same empty-`Binder` idiom, pinning the
`@DefaultValue` annotations themselves rather than a hand-written `new` call's positional args).

`./mvnw -B -pl station/vision-app -am test -DskipWeb` — **305** (+6 over the pre-P4 299: 3 in
`VisionMavlinkPropertiesTest`, 3 in the new `TelemetryWiringCommandRetryWiringTest`), all green on the
first run, no collisions this time (`drone-link/mavlink` — `adapter-mavlink` in the reactor summary —
built clean at 02:07 min, unaffected by any concurrent edit at gate time). `ArchitectureTest`/
`ContextArchitectureTest`/`EndpointAuthorizationTest` unaffected (no new bean, no new ArchUnit
surface — `mavlinkFlightCommander` gained two constructor parameters, not a new bean or a new
`@Configuration` class). Docker ran for real (Postgres Testcontainer migrated through `V31`).

**ASSET-FLOWS wave BK3 (D6/S3 backend) done.** New `VisionOpsProperties` (`config/properties/`, prefix
`vision.ops`, nested `Battery(int warningPercent, int criticalPercent)` — defaults 25/10,
compact-constructor validated `critical < warning` and both in `[0,100]`; absent `battery` block falls
back to `Battery.defaults()` rather than the nested record's own per-field `@DefaultValue`s, the same
whole-block-absent behavior `VisionMavlinkProperties`'s `Scan`/`Transmit` already documents) + new
unconditional `OpsWiringConfiguration` (1 bean, see the wiring-map table above) behind vision-api's new
`GET /api/ops/thresholds` — see the properties-records table row above for the full writeup.
`ApplicationServiceWiring#batteryMonitor` (BK2, this same cycle) already reads the identical two
`vision.ops.battery.*` keys via raw `@Value`, by deliberate design confirmed in that bean's own javadoc —
it was built ahead of this wave to converge on the same property keys/defaults once this record and its
`application.yaml` documentation landed, which they now have; an operator override of the yaml block
reaches both consumers identically. The two Java binding mechanisms staying separate (a
`@ConfigurationProperties` record here vs. two `@Value`s there) rather than both consuming this one
record is a minor follow-up cleanup, not a config-drift risk — out of this wave's scope since
`ApplicationServiceWiring` was BK1/BK2's concurrently-running file this cycle. `application.yaml` gained
one new fully-commented documentation block under `vision.ops.battery.*` (module key itself commented,
per this module's own "block whose keys are all commented has its module key commented too" convention)
— no key actually turned on, so every default-config deployment is byte-for-byte unchanged; this wave's
sole edit to that file, respecting the plan's "BK3 owns the `vision.ops.*` block, touches nothing else in
it" scope line.

Two new test files: `VisionOpsPropertiesTest` (10 cases — explicit values carried through, an absent
block defaulting to 25/10, five compact-constructor rejection cases plus one acceptance case, and two
`Binder`/`MapConfigurationPropertySource` cases pinning the actual `@DefaultValue`-driven bind against an
empty source, mirroring `VisionMavlinkPropertiesTest`'s own no-Spring-context idiom) and
`OpsWiringConfigurationTest` (2 cases — explicit properties map verbatim onto the response; `null`
properties yield 25/10 — mirrors `TrackingWiringTest`'s no-context style for a plain properties-to-bean
mapping method).

Build was blocked for a stretch this wave by concurrent, in-progress edits to
`contexts/vision-perception/UsageTracker.java`/`UsageTrackerSettings.java`/`UsageTrackerTest.java` (BK1's
own wave, adding a `MaintenanceQuery` collaborator — see "Application-service beans" above,
`usageTracker`'s new required param) landing on this same shared branch mid-build; per CLAUDE.md's "never
run reactor-wide builds while another agent's task holds modules red," this wave did not edit those files
and instead polled (`./mvnw -pl contexts/vision-perception -am test-compile` on a ~20-25s cadence) until
BK1's edit stabilized, then reran the full gate — no code correctness issue in this wave's own changes at
any point; every failure traced to files outside BK3's declared scope. A `clean` was also needed once
mid-wave (`./mvnw -B -pl station/vision-app -am clean test -DskipWeb`) per this file's own documented
"non-clean `target/` can serve stale pre-refactor classes" gotcha — `contexts/vision-flight`'s
`target/classes` was briefly stale relative to its current source.

`./mvnw -B -pl station/vision-app -am clean test -DskipWeb` (full reactor, 26 modules, ~9:45 min) —
**317** tests, 0 failures (+12 over the pre-BK3 305 baseline documented above — exactly this wave's two
new test classes, `VisionOpsPropertiesTest` 10 + `OpsWiringConfigurationTest` 2 — meaning no other
concurrently-landed wave in this cycle changed vision-app's own net test count as of this gate).
`ArchitectureTest`/`ContextArchitectureTest`/`EndpointAuthorizationTest` all green, unaffected (no new
`@RestController`, and `OpsWiringConfiguration`/`VisionOpsProperties` both sit exactly where ArchUnit
expects a `@Configuration`/`@ConfigurationProperties` class to live). Also independently green inside the
same reactor run: `station/vision-api` **944** (2 of which are this wave's own `OpsThresholdsControllerTest`
— see that module's own MODULE.md entry), `adapter-persistence` **1:12 min** wall time (Testcontainers
Postgres actually spun up, not skipped), `RtspSimulationDockerE2ETest` (real docker, 8.878s). Docker ran
for real throughout — not skipped. Nothing deferred on this side beyond the flagged duplication above.

**FLY-CONTROL-UX-PLAN wave BK1 done.** `VisionOpsProperties` gained a second nested record, `Rc(int
neutralTolerancePercent)` — default **5**, compact-constructor validated `[1,25]` (outside throws
`IllegalArgumentException`), absent `rc` block falling back to `Rc.defaults()` under the exact same
whole-block-absent rule the `battery` field already documents (Spring relaxed binding does not apply a
nested record's own `@DefaultValue`s when the block is entirely missing). `OpsWiringConfiguration#opsThresholds`
now also builds `RcThresholdsResponse(rc.neutralTolerancePercent())` into the response — a widened
constructor call at the one call site, per CLAUDE.md rule 10 (no overload, no `null`-means-off param).
`application.yaml` gained a `#   rc:` / `#     neutral-tolerance-percent: 5` pair nested under the
existing commented-out `# ops:` block (module key itself already commented per this module's own
convention), plus a one-line WHY: arm is enabled in the web cockpit only when live sticks read neutral
within this tolerance — no key turned on, every default-config deployment byte-for-byte unchanged.
`RcThresholdsResponse`/`OpsThresholdsResponse` widening itself is vision-api's own change (see that
module's MODULE.md) — this module only supplies the new field's value.

Extended (not new-file) tests: `VisionOpsPropertiesTest` gained 7 cases (explicit value carried through,
absent-block default, four compact-constructor bounds cases `[1,25]`, one `Binder`-against-empty-source
case pinning `application.yaml`'s documented default of 5, one `Binder`-with-explicit-key case proving
`battery` stays at its own defaults when only `rc` is configured — same "whole-block-absent vs.
one-key-present" distinction the existing `battery` tests already draw). `OpsWiringConfigurationTest`
gained 2 cases (explicit `Rc` maps verbatim onto the response; `null` `Rc` yields the 5% default).

`./mvnw -B -pl station/vision-app -am test -DskipWeb` — **326** tests, 0 failures, 0 errors (+9 over this
file's own previously-documented 317 baseline — exactly this wave's 7 + 2 new cases, no other net change
observed at this gate). Docker ran for real (Postgres Testcontainer migrated through `V32`; the
`asset_usages does not exist` warning logged by a background idle-close sweep during the brief pre-Flyway
window is the pre-existing async race already known from prior waves, not a new failure — surfaced as a
caught-and-logged warning, not a test failure). Also green in the same session: `station/vision-api`
**951** (0 new test methods this wave — see that module's own MODULE.md entry for the widened-assertion
detail). Nothing deferred.

**AUTH-ROLES-PLAN wave B0a done** (docs/plans/active/AUTH-ROLES-PLAN.md, D1) — config honesty only, no
behavior change and the value stays `false`. `application.yaml`'s `vision.auth.enabled` comment block no
longer claims `true` is "the default, set explicitly" while the line beneath it sets `false` — the
self-contradiction D1 named, introduced by an unrelated commit (`23d13895`) undoing R7's flip. The
rewritten comment says plainly what the shipped file actually boots into (permit-all, dev principal,
`authEnabled=false`), names `SecurityConfig`'s own compiled default (`matchIfMissing = true`, i.e. true
is the *code's* default, not this *file's*), and points at why flipping it alone is unsafe (D3 — no
bootstrap path exists yet; that is AUTH-ROLES-PLAN wave B3/B0b, not this one). `SecurityConfig`'s class
javadoc gained a parallel "Honesty note" so a reader of only the Java doc doesn't come away believing a
fresh clone is secured by default. `permitAllFilterChain` now logs one boot `WARN` on every activation,
naming the setting (`vision.auth.enabled=false`) and pointing at the web UI's own red banner — visible in
the test log above (`c.d.vision.app.config.SecurityConfig : vision.auth.enabled=false -- this station is
UNSECURED...`), confirming it fires under the same `application.yaml` every other test in this module
already runs against. No new dependency, no new bean, no `@ConfigurationProperties` record yet (D2's
three-defaults problem is wave B3's `VisionAuthProperties`, out of this wave's scope).

`./mvnw -B -pl station/vision-app test -DskipWeb` (no `-am` — this wave touches only files inside this
module, no upstream dependency changed) — **326/326** green, unchanged from the pre-wave baseline above
(no new test file; a log statement and two comment blocks have nothing to unit-test beyond "the app still
boots," which every existing `@SpringBootTest` already proves each run). `ArchitectureTest`/
`ContextArchitectureTest`/`EndpointAuthorizationTest` unaffected (no new bean, no new `@RestController`,
no new ArchUnit-relevant type).

**AUTH-ROLES-PLAN wave B3 done.** `DevPrincipalResolver`/`SecurityContextPrincipalResolver` gained
`role()`/`authority()` (the two methods `PrincipalResolver`, vision-api, widened to — B4's per-asset
`AssetAuthority` and the wire's `MeResponse.capabilities[]`/`scopeKind` both read through these).
`DevPrincipalResolver#role()` is a fixed `Role.ADMIN`/`authority()` a fixed `Authority.full()` (the
auth-off dev principal has always resolved to unbounded everything); `SecurityContextPrincipalResolver`
reuses its own existing `topRoleOf(User)` for `role()` and pairs it with `RoleAuthority.capabilitiesOf(...)`
for `authority()` — no new collaborator, no widened constructor. `SecuritySessionAuthenticator#login`
gained a `boolean kiosk` parameter (`NoopSessionAuthenticator`'s mirrors it, unused) and now calls
`request.getSession(true).setMaxInactiveInterval(...)` with one of two configured durations — see the
`AuthWiringConfiguration` row above for the two new `@Value` params this threads through, and the
deferred-to-B5 note on why they are bare primitives rather than a properties record yet.
`SecurityConfig`'s permit-all matcher list gained `/api/auth/bootstrap`.

Three pre-existing test files needed mechanical fixes for the widened contexts/vision-identity
application-service signatures B3 also touches (`UserService#create`/`AssignmentService#assign` both
gained parameters — see `contexts/vision-identity/MODULE.md`): `DevAccountSeeder` (the `*AuthEnabledTest`
suites' `AuthSeedRunner` stand-in) now threads a fresh `UserId` as the seeding actor; `ScopedAssetReadAuthEnabledTest`
now passes `AssignmentRole.PILOT` + an actor to its one direct `assignmentService.assign(...)` call;
`PrincipalResolverTest`'s hand-rolled `ScopeResolver` test doubles (a lambda and an anonymous
`RecordingScopeResolver`) gained `authorityFor(User)` overrides now that `ScopeResolver` carries two
abstract methods, and three inline `new User(...)` constructions gained the `mustChangePassword`
boolean B2 added to the domain record. None of these are behavior changes — every fix keeps the test's
original assertion intent.

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` —
**326/326** green, unchanged count (every edit in this module this wave was either a new method on an
existing class or a call-site fix in an existing test; no new test file, no new test method). Also
green in the same run: `storage/persistence` **276** (274 → 276, +2) and `station/vision-api` **961**
(954 → 961, +7) — see those modules' own MODULE.md entries. Docker ran for real (Testcontainers
`postgres:16`, Flyway migrated through `V33`). `vision.auth.enabled` stays `false` by default
(unchanged this wave — B0b flips it); `AuthDisabledSecurityTest`/`ManualControlSecurityDisabledTest`
(the default-config auth-off suites) both still green, proving the opt-in guardrail. Waves B4/B5/B6/B0b
open — see `docs/plans/active/AUTH-ROLES-PLAN.md`.

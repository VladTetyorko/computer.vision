# vision-app

Spring Boot assembly: the only module that knows about every adapter, wires domain ports to
concrete implementations, and holds runtime configuration + the ArchUnit/context-boundary rules.

**Depends on:** vision-kernel, vision-platform, all 8 context modules (vision-warehouse,
vision-identity, vision-flight, vision-perception, vision-map, vision-events, vision-learning,
vision-simulation), adapter-simulation, adapter-rtsp, adapter-mjpeg, adapter-mavlink (+ mavlink-core,
test scope), adapter-v4l2, adapter-publish-hls, adapter-cv-grpc, adapter-tiles, adapter-discovery,
adapter-persistence, adapter-carrier-udp, adapter-carrier-serial (LINK-PAIRING-PLAN.md §3.2, wave L1
— component-scanned, wired by `CarrierWiring`), vision-api, vision-web (static-only jar),
spring-boot-starter, spring-boot-starter-security, spring-boot-starter-actuator · test:
spring-boot-starter-test, archunit-junit5, testcontainers-postgresql
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
  events/                DetectionSessionCleanupEventPublisher, PersistingEventPublisher,
                         LiveUpdateEventPublisher, LiveUpdateAuditTrail,
                         LiveUpdateDetectionEventRepository, LiveUpdateDiscoveryInboxService
                         (decorator chains)
  onboarding/            PassportCaptureObserver (UsagePhaseObserver impl)
  cv/                    DetectionPolicyCache (self-scheduled AssetId snapshot, ALWAYS-ON-FLOW wave D1)
  geo/                   TrackProjectionRunner, VisualGeoRunner (poller ApplicationRunners)
  usage/                 UsageIdleCloseRunner, TelemetryPinRunner (self-scheduled sweeps, same shape as geo/'s runners)
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
| `VideoSourceWiring` | Simulation, Rtsp, Mjpeg, V4l2 | `simulatedVideoSource` (LINK-PAIRING-PLAN.md §4 row L2: now COP `vision.simulation.enabled=true`, the whole Playground's master switch — was unconditional) plus `ffmpegVideoSource`/`mjpegVideoSource`/`v4l2VideoSource` (still unconditional `VideoSourcePort`), `videoSourceRegistry`. Exposes static `toFfmpegSettings`/`toMjpegSettings` mappers reused by `FeedTransmitterWiring` |
| `TelemetryWiring` | Simulation, Mavlink, Rc, Onboarding | `simulatedTelemetrySource` (LINK-PAIRING-PLAN.md §4 row L2: now COP `vision.simulation.enabled=true`, same gate as `VideoSourceWiring#simulatedVideoSource` — was unconditional), `mavlinkTelemetrySource`, `mavlinkFlightCommander`, `mavlinkManualControlSender` — the latter three still unconditional. Static `toMavlinkSettings(...)` reused by `FeedTransmitterWiring`/`DiscoveryWiringConfiguration`/`OnboardingWiringConfiguration`. **MAVLINK-COMMANDS-PLAN P4** rewired `mavlinkFlightCommander` off the 2-arg `(MavlinkTelemetrySource, Duration)` back-compat constructor onto `MavlinkFlightCommander`'s canonical `(MavlinkTelemetrySource, MavlinkSettings)` one, reusing `toMavlinkSettings(...)` a fourth time (now takes `VisionRcProperties`/`VisionOnboardingProperties` too, matching `mavlinkTelemetrySource`'s own parameter list). **LINK-PAIRING-PLAN.md §3.4/§4 row L3** widened `toMavlinkSettings(...)` to a 4th parameter, `VisionLinksProperties` (soft-timeout/hard-timeout/dwell-window, folded into `MavlinkSettings`'s election thresholds) — every call site above updated, no overload added (CLAUDE.md rule 10). Also added `mavlinkVehicleLinkPort(MavlinkTelemetrySource, AssetService)` → `drone-link/mavlink`'s `MavlinkVehicleLinkPort`, unconditional, declared by its concrete type (not `VehicleLinkPort`) so it also satisfies `CarrierDirectoryPort`-typed injection elsewhere — see "Application-service beans" below |
| `CarrierWiring` | — | **LINK-PAIRING-PLAN.md §3.2/§7, sibling to `TelemetryWiring`.** `lobbyLinkRegistry(MavlinkTelemetrySource, int mavlinkPort)` → `LinkRegistry`, unconditional — just `mavlinkTelemetrySource.linkRegistry(mavlinkPort)`, reusing `DiscoveryWiringConfiguration#mavlinkPort` rather than a third read of `vision.discovery.mavlink-port`. Constructs no gateway itself. `drone-link/carrier-udp`'s `UdpCarrierConfiguration` and `drone-link/carrier-serial`'s `SerialCarrierConfiguration` are themselves `@Configuration` classes under `com.drones.vision.adapter.carrierudp`/`carrierserial` — Spring Boot component-scans both automatically once vision-app depends on those modules (see `pom.xml`), no `@Import` needed here; this class supplies only the one collaborator neither can construct itself, the concrete `LinkRegistry` |
| `PublishWiring` | Publish, Api, Cv, Media | `mediamtxStreamPublisher` (COP `vision.publish.enabled`, default true — own bean so `SystemStatusWiring` observes the *same* instance `streamPublisherPort` routes through), `streamPublisherPort` (unconditional `StreamPublisherPort`; `NoopStreamPublisher` if disabled, else a `PublisherRouter` wrapping the direct publisher + a `MediamtxProxyPublisher`, routed by `vision.publish.source-proxy.enabled`; **fails fast** if source-proxy is on while `vision.cv.frame-transport` is still `push` — see Gotchas), `mediamtxLiveFrameGrabber` (unconditional, cheap/lazy), `replayFrameExtractionPort` (unconditional; `NoopReplayFrameExtractor` if publish disabled), `hlsProxyUpstreamBase: URI`, `snapshotJpegEncoder`, `hlsProxySettings`, `liveSettings` (last 3 bridge Spring-bound `VisionApiProperties` → vision-api's plain mirror of the same simple name — see Gotchas). **ASSET-FLOWS-PLAN §2 S6**: every bean method above that builds a mediamtx URL now additionally takes `VisionMediaProperties mediaProperties`, converted once per call via the new private `toMediaCredentials(VisionMediaProperties)` helper into `adapter-publish-hls`'s `MediaCredentials` — `mediamtxLiveFrameGrabber` embeds the viewer credential in its RTSP read URL, `toPublishSettings`/`toProxySettings` thread it into `PublishSettings#auth`/`MediamtxProxySettings#media` (so `whepUrl`/`playbackUrl`/the RTSP push URL all carry credentials — see adapter-publish-hls/MODULE.md), and `toApiSupportProperties` appends `auth.viewerUsername()`/`auth.viewerPassword()` as `VisionApiProperties.HlsProxy`'s two new trailing components, which `HlsProxyController` (vision-api) sends as an outbound `Authorization: Basic` header (see that module's own MODULE.md Gotchas) |
| `CvWiring` | Cv | `cvGrpcChannel: ManagedChannel` `@Primary` (COE: `cv.enabled` OR `training.enabled` OR `frame-transport=pull` OR `geo.visual.enabled`; built via `CvChannels.forTargets`), `cvTrainingChannel` (COP `vision.cv.training.target` present — independent shutdown), `cvChannelSupervisor` (COE = cvGrpcChannel's expression AND `cv.reconnect.enabled` default true; `@Qualifier("cvGrpcChannel")`), `detectionPort` (unconditional bean, internal branch on `enabled`: `GrpcDetectionPort` w/ `@Qualifier("cvGrpcChannel")` else `NoopDetectionPort`; `destroyMethod=""`), `pulledDetectionPort` (COE `frame-transport=pull`; `@Qualifier("cvGrpcChannel")`), `cvModelRoster` (static constant), `detectionDemandPort` (COE default true, returns concrete `LiveAndPollDetectionDemand`), `streamDefaultConfig`, `streamDetectionSupport`. **ALWAYS-ON-FLOW-PLAN.md wave D1** added `detectionPolicyCache` (`initMethod="start"`/`destroyMethod="close"`, COE = `cvGrpcChannel`'s own "CV switched on at all" expression minus the reconnect AND-clause, mirroring `cvChannelSupervisor`'s precedent for self-scheduled background beans — absent, not merely inert, when CV is entirely off) and `detectionPolicyPort` (unconditional, a lambda over `ObjectProvider<DetectionPolicyCache>` whose `.getIfAvailable()` is deferred inside the lambda body exactly like `hasCameraPose`'s own `TrackProjectionRunner` consumption, avoiding a circular-dependency hazard; reads every asset as `DetectionPolicy.ON_VIEW`/`false` when the cache is absent — fail-closed, unlike `detectionDemandPort`'s fail-open). **CV-ORCHESTRATION wave W2.5** added `cvInspectClient: GrpcCvInspectClient` (`@Qualifier("cvGrpcChannel")`, same COE as `detectionPort` — feeds `SystemStatusWiring#cvServiceStatus`'s capacity enrichment, see below) and `traceDemandPort: LiveAndPollTraceDemand implements TraceDemandPort` (same shape/COE as `detectionDemandPort`, a second SSE/poll-OR demand port — `#watchingTrace(AssetId)` OR a recent `GET .../cv/trace` poll timestamp); `streamDetectionSupport` widened to consume `ObjectProvider<LiveAndPollTraceDemand>` as its 6th constructor argument (`.getIfAvailable()`, `null` when the demand gate isn't wired — same optionality as `demand`). Static `toGrpcCvSettings(...)` and package-private `controlPlaneChannel(cvTrainingChannel, cvGrpcChannel)` (= training channel if present else falls back to inference channel) shared by `TrainingWiringConfiguration`/`VisualGeoWiringConfiguration` |
| `TrainingWiringConfiguration` | Training, Cv | `datasetUploadPort`, `trainingStores`, `replaySources`, `datasetService`, `labelingService` (takes `AssetDirectoryService`, not `AssetService`), `trainingPort`, `trainingJobService` all `@ConditionalOnProperty(vision.training.enabled=true)`, no fallback. `modelRegistryPort`/`modelRegistryService` are a **separate** switch since CV-SETTINGS-PLAN §5 (CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff decoupled the registry from training): `@ConditionalOnProperty(vision.cv.registry.enabled=true)`, whose own `application.yaml` default follows `vision.cv.enabled` via the `${vision.cv.enabled:false}` placeholder — a CV-only deployment gets the registry for free unless it explicitly opts out (`vision.cv.registry.enabled=false`); a training-only deployment does **not** get it for free any more. Channel-consuming beans route through `CvWiring.controlPlaneChannel(...)` |
| `CvProfileWiringConfiguration` | — | **Every bean unconditional**, no `@Conditional*` at all (CV-SETTINGS-PLAN §3.1/§5, CV-SETTINGS-CONTEXT.md's W2 → W5 handoff): `cvProfileCacheSettings` (from `VisionCvProperties.Profiles#cacheTtl()`, default 60s), `cvProfileCache` (write-through, lazy-TTL-reload), `cvProfileResolver` (asset→category→organization→platform fold, shared with `ApplicationServiceWiring#streamService` and `CvWiring#streamDetectionSupport`), `cvProfileService` (`DefaultCvProfileService`, behind `CvProfileController`) — profiles ship built-in (4 seeded rows, `V29__cv_profiles.sql`) regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`, the same "ships built-in" posture `TrackingWiring#cvTrackerRoster` takes |
| `DiscoveryWiringConfiguration` | Discovery, Mavlink | `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner`/`mavlinkHeartbeatScanner` — each COP `vision.discovery.enabled` default true. `mediamtxPathScanner` (ZERO-CONFIG-ONBOARDING Z3) is the fifth scanner, COP `name = {"enabled", "mediamtx.enabled"}` under the `vision.discovery` prefix — Spring ANDs the array together, so it needs **both** `vision.discovery.enabled` and `vision.discovery.mediamtx.enabled` (default true, its own independent off-switch since it is the one scanner that depends on another subsystem, mediamtx/`vision.publish.enabled`, being present at all). Built from `MediamtxScannerSettings(publishProperties.mediamtx().apiBase(), publishProperties.mediamtx().rtspBase(), properties.mediamtx().pathPrefix())` — takes `VisionPublishProperties` as a plain constructor param (registered by `PublishWiring`'s own `@EnableConfigurationProperties`, not this class's; autowires here as an ordinary bean regardless of `vision.publish.enabled` since only the publisher beans themselves are conditional on that flag) rather than inventing a parallel `vision.discovery.mediamtx.api-url`/`rtsp-base` pair, so the two consumers of "where mediamtx is" can never disagree. `discoveryService` and `mavlinkPort` **unconditional** (`DiscoveryController` needs the service regardless; `DefaultDiscoveryService` tolerates an empty port list). `mavlinkHeartbeatScanner` lives in adapter-mavlink, not adapter-discovery like its other 3 siblings, because it borrows `mavlinkTelemetrySource`'s open socket and adapters can't depend on each other. **SOURCE-ONBOARDING-2 wave C** added three more beans, all consumed by `vision-api`'s `SystemNetworkController`/`DiscoveryStatusController`: `videoPushPort`/`videoPushPathPrefix` (both `@Bean` methods COP `vision.publish.enabled=true`, matchIfMissing=true — genuinely absent, not null-valued, when mediamtx publish is off, since a required `@Autowired` constructor parameter elsewhere cannot accept a null-valued bean; see `vision-api`'s MODULE.md Conventions for why the consumer takes these through `ObjectProvider<T>`) and `discoveryStatusFacts` (unconditional `Supplier<DiscoveryStatusFacts>`, taking `MavlinkTelemetrySource`/`VisionDiscoveryProperties`/`VisionPublishProperties` plus two `ObjectProvider`s — `DiscoveryInboxRunner` and `MediamtxPathScanner` — since either may be conditionally absent; an absent runner reports `lastSweepAt` as `null`, an absent scanner reports `readyPaths` as empty, both honestly rather than fabricated). **LINK-PAIRING-PLAN.md §4 row L2** added a fourth new bean here, `simulationEnabled(VisionSimulationProperties): boolean` — unconditional, plain `boolean` (mirrors `mavlinkPort`'s own "always present, never `ObjectProvider`" shape, not the COP-then-`ObjectProvider` pattern this class's other three cross-module beans use, since this one is never conditionally absent) — the sole consumer is `vision-api`'s `SystemNetworkController`, whose response field of the same name the web reads to decide whether to offer the Playground UI at all; `VisionSimulationProperties` itself is already registered by `VideoSourceWiring`/`TelemetryWiring`'s own `@EnableConfigurationProperties`, so no further registration is needed here |
| `DiscoveryInboxWiringConfiguration` | Discovery | `discoveryInboxService(DiscoveryCandidateRepositoryPort, AssetService, DeviceService, VisionLiveProperties, VisionDiscoveryProperties, ObjectProvider<LiveUpdateRegistry>)` → `DefaultDiscoveryInboxService`, **unconditional** (`DiscoveryInboxController`, vision-api, needs it regardless of whether the sweep runs — an operator can still read/register/dismiss by hand with the runner off); **SOURCE-ONBOARDING-2 wave C** widened this bean method to take `DeviceService` (fixing a pre-existing 2-arg→3-arg constructor-arity bug — `DefaultDiscoveryInboxService`'s canonical constructor already needed it, the wiring just hadn't been updated) and to wrap the delegate in `com.drones.vision.app.events.LiveUpdateDiscoveryInboxService` when both `VisionLiveProperties#enabled()` and `VisionDiscoveryProperties.Live#enabled()` are true (both default true — see "Application-service beans" below and that decorator's own javadoc for the deliberate concrete-`LiveUpdateRegistry` dependency). `discoveryInboxRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.discovery.inbox.enabled=true` (matchIfMissing=true, the default — ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c deliberately overrides CLAUDE.md's usual opt-in-guardrail default, since shipping the sweep off by default would defeat the whole zero-config purpose); its `lastSweepAt()` accessor (SOURCE-ONBOARDING-2 wave C) reports the instant the runner last **completed** a sweep (not merely attempted one), `null` before the first completion — feeds `discoveryStatusFacts` above |
| `FeedTransmitterWiring` | Publish, Rtsp, Mjpeg, Mavlink, Rc, Onboarding | `rtspFeedTransmitter`, `mjpegFeedTransmitter` (`destroyMethod="close"` — owns a shared `HttpServer`), `mavlinkFeedTransmitter`, `feedTransmitterRegistry` — all unconditional |
| `PersistenceWiringConfiguration` | Persistence | **Every bean unconditional**, no `@Conditional*` anywhere — 24 one-line `Jpa*Repository(entityManagerFactory)` ports (WAREHOUSE-UX W3 added `maintenanceRepositoryPort`/`assetNoteRepositoryPort`; ZERO-CONFIG-ONBOARDING Z2c added `discoveryCandidateRepositoryPort`; LINK-PAIRING-PLAN.md §4 row L2 added `pairingRepositoryPort`) + `persistenceEntityManagerFactory` (`destroyMethod="close"`, opens a real JDBC connection eagerly). No in-memory fallback exists for any repository port (Postgres is the only store). **AUTH-ROLES wave B5** split the pooled connection out into its own bean, `visionDataSource` (`destroyMethod=""` — `persistenceEntityManagerFactory` closes the same pool transitively via `ClosingDatasourceConnectionProvider`, and Spring destroys the dependent bean first, so a second close on this one is avoided) built via `PersistenceUnit.buildDataSource` (now public); `persistenceEntityManagerFactory` takes that `DataSource` plus `properties.seedDevUsers()` and binds via the new `PersistenceUnit.start(DataSource, boolean)` overload rather than building its own pool. A third new bean, `visionSessionTransactionManager` (`JdbcTransactionManager`, bound to the same `visionDataSource`), exists solely so Spring Session JDBC wraps its own `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` reads/writes in a real transaction rather than falling back to Spring Session's own no-op `ResourcelessTransactionManager` — no `Jpa*Repository` uses it, each still manages its own transaction natively via `EntityManager`/`EntityTransaction`. This is the first `DataSource`/`PlatformTransactionManager` bean this app has ever exposed to Spring — needed because Spring Session JDBC's autoconfiguration binds to one, and this app's persistence layer otherwise never goes through Spring's own `DataSourceAutoConfiguration`. |
| `AuthWiringConfiguration` | — | `passwordHasherPort`, `authService`, `userService`, `groupService`, `scopeResolver`, `assignmentService` (`DefaultAssignmentService(assignmentRepositoryPort, assetService)` — takes `AssetService`, not `AssetRepositoryPort`), `activityService` — all unconditional; `authService`/`userService`/`assignmentService` (AUTH-ROLES wave B3) take a third constructor arg, `AuditTrailPort`, threaded to the 3-arg `Default*` constructors D15 added. `devPrincipalResolver`/`noopSessionAuthenticator` COP `vision.auth.enabled=false` (matchIfMissing=true, the default); `securityContextPrincipalResolver`/`securitySessionAuthenticator` COP `vision.auth.enabled=true`. `securitySessionAuthenticator`'s factory method now takes `VisionAuthProperties` as an ordinary `@Bean`-method argument instead of the two bare `@Value`-annotated `long` params wave B3 used (`@Value` on the *constructor* would have been silently ignored since the bean is built with `new SecuritySessionAuthenticator(...)` inside this factory method rather than via Spring's own reflective construction — AUTH-ROLES wave B5 closed that gap by giving the session-config surface its own `@ConfigurationProperties` record instead, registered here via `@EnableConfigurationProperties(VisionAuthProperties.class)`). Repository ports (`UserRepositoryPort`/`GroupRepositoryPort`) come from `PersistenceWiringConfiguration`, orthogonal to `vision.auth.enabled` |
| `RateLimitWiring` | Api | Whole-class COP `vision.api.rate-limit.enabled=true` (default false): `rateLimitFilterRegistration` — `FilterRegistrationBean<RateLimitFilter>` on `/api/*` |
| `SystemStatusWiring` | — | Two mutually-exclusive beans per subsystem, each repeating the exact enabling expression its real resource already uses (never `@ConditionalOnBean` — order-sensitive, see Gotchas): `cvServiceStatus`/`cvServiceStatusDisabled`, `videoPublishStatus`/`videoPublishStatusDisabled` (COP `vision.publish.enabled`). `mavlinkLinkStatus` is **unconditional** (mavlink telemetry source always exists; reports `UNKNOWN` with no claimed vehicle, never `DISABLED`); **FLEET-RADIO R4** — the class now also declares `@EnableConfigurationProperties(VisionMavlinkProperties.class)` (matching `TelemetryWiring`'s/`DiscoveryWiringConfiguration`'s own identical declarations of the same class) and `mavlinkLinkStatus` takes a second parameter, `VisionMavlinkProperties`, building a `MavlinkSettings.LinkStatus` from its three D7 threshold fields to construct `MavlinkLinkStatusProvider`. **CV-ORCHESTRATION wave W2.5** — `cvServiceStatus` widened to a second parameter, `ObjectProvider<GrpcCvInspectClient> cvInspectClient` (`CvWiring#cvInspectClient`'s provider), matching `CvStatusProvider`'s own constructor growing from 1-arg to 2-arg (`cv/grpc/MODULE.md`) — `cvServiceStatusDisabled` is unaffected (CV off entirely never constructs a `CvStatusProvider` at all) |
| `StreamLifecycleWiring` | Streams | `mediamtxReaderProbe` (COP `vision.publish.enabled` default true), `videoDemandPort` (unconditional — `HlsProxyController` needs it regardless of whether the idle policy is on), `idleStreamReaper` (`initMethod="start"`, `AutoCloseable`). Resolves `usageTracker` lazily inside a resolver lambda to avoid the same circular-reference hazard as `CvWiring#detectionDemandPort` |
| `TrackingWiring` | Tracking | `cvTrackerRoster()` (static constant). `streamStartTrackingSeed(...)` is a **package-private static method, not a bean** — called from `ApplicationServiceWiring` to fold into `StreamPipelineSettings`. No enable flag by design (`vision.cv.enabled` already gates CV wholesale; per-stream `TrackingMode.OFF` is the finer switch) |
| `OnboardingWiringConfiguration` | Onboarding | `noopVehicleConfigPort` (COP `probe.enabled=false`, matchIfMissing=true) vs. `mavlinkVehicleConfigurator` (COP `probe.enabled=true`, declared as `VehicleConfigPort`, never the concrete type). `vehicleProfileService`/`remediationService`/`readinessService`/`onboardingApiProperties`/`remediationOrchestrator` unconditional; `readinessService` now also takes `MaintenanceQuery` (WAREHOUSE-UX W5, `DefaultReadinessService`'s 4th ctor param — sourced from `ApplicationServiceWiring#maintenanceService`'s `DefaultMaintenanceService` bean, which implements both `MaintenanceService` and `MaintenanceQuery`). `passportCaptureObserver` COP `passport.enabled=true` (default false) |
| `FixedCameraGeoWiringConfiguration` | Geo | `fixedCameraGeoApiProperties`, `cameraPoseService`, `trackProjectionService` unconditional. `trackProjectionRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.geo.fixed-camera.enabled=true`. Its absence defaults `CvWiring#detectionDemandPort`'s camera-pose predicate to `assetId -> false`, not an error |
| `VisualGeoWiringConfiguration` | GeoVisual | `visualGeoApiProperties`, `referenceTileSourcePort` (`WaybackTileSource` vs `HttpTileSource` by `tiles.waybackMultiDate()`), `referenceRegionService`, `geolocationSessionService`, `trackCorrectionService` unconditional. `pulledGeolocationPort`/`referenceIndexPort` unconditional beans that self-branch on `enabled()` (real gRPC impl via `CvWiring.controlPlaneChannel` vs. `Noop*`) — both take **two `@Qualifier`-annotated `ObjectProvider<ManagedChannel>` params** (`cvTrainingChannel`, `cvGrpcChannel`), required once `cvGrpcChannel` is `@Primary` alongside a second channel bean (see Gotchas). `visualGeoRunner` (`initMethod="start"`/`destroyMethod="close"`) is the only truly gated bean, COP `vision.geo.visual.enabled=true` |
| `ControlProfileWiring` | Control | `controlProfileService`, `auxFunctionCatalog` (`AuxFunctionCatalog.defaults()` unless `properties.auxFunctions()` configured) — unconditional; the catalog is display-only, never a whitelist |
| `AfterActionWiringConfiguration` | — | `afterActionProperties`, `afterActionSources`, `afterActionAssembler` — unconditional, no flag. `maxPoints` is derived from `vision.application.replay.max-points-ceiling`, not its own key |
| `UsageWiringConfiguration` | Usage | `usageIdleCloseService(AssetUsageRepositoryPort, AssetLiveStatePort, UsageSessionService, VisionUsageProperties)` (warehouse's `DefaultUsageIdleCloseService`), `usageIdleCloseRunner` (`initMethod="start"`, `destroyMethod="close"`) — both **unconditional, no enable flag** (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1: a data-correctness fix, not an optional feature). Pure downstream leaf — composes three already-unconditional beans from `PersistenceWiringConfiguration`/`ApplicationServiceWiring`, no new bean-cycle risk. **ALWAYS-ON-FLOW-PLAN wave A1** added a second runner here, `telemetryPinRunner(AssetService, UsageTracker, VisionTelemetryProperties)` (`initMethod="start"`, `destroyMethod="close"`), and a second `@EnableConfigurationProperties` entry (`VisionTelemetryProperties`). Also **unconditional as a bean** — the enable flag lives inside `TelemetryPinRunner#start()`, not on the `@Bean`, following `IdleStreamReaper`'s idiom rather than `@ConditionalOnProperty`: "always-on telemetry is off" then reads as a state of a present object (`pinnedCount()==0`, `lastSweepAt()==null`) rather than an absent one, which is what makes it diagnosable from `GET`-able status rather than only from a bean listing |
| `OpsWiringConfiguration` | Ops | `opsThresholds(VisionOpsProperties)` → `OpsThresholdsResponse` (vision-api DTO), **unconditional, no enable flag** — display config, not a feature. Same "plain config-backed DTO bean, no service layer" shape `TrackingWiring#cvTrackerRoster` established (ASSET-FLOWS-PLAN §2/BK3). FLY-CONTROL-UX-PLAN §2/BK1 widened the built response with `RcThresholdsResponse(properties.rc().neutralTolerancePercent())`, mirroring the `battery` mapping verbatim — no new bean, no constructor overload |
| `SeatWiringConfiguration` | Crew | `seatService(AuditTrailPort, VisionCrewProperties)` → `DefaultSeatService` (contexts/vision-flight), **unconditional** — cheap in-heap registry, inert until something calls `take`/`preempt`/`forceRelease`. `seatAccessSettings(VisionCrewProperties)` → `SeatAccessSettings`, the framework-free bridge-properties mirror `com.drones.vision.api.security.SeatAccess` consumes (vision-api may not depend on `@ConfigurationProperties`), same shape `OpsWiringConfiguration#opsThresholds` already establishes |
| `ApplicationServiceWiring` | Cv, Live, Rc, Application, Publish, Simulation, EventHistory, Pairing | The largest class — every `vision-application`/context `DefaultXService` bean. See "Application-service beans" below |

### Application-service beans (`ApplicationServiceWiring`)

- **Live server-push selectors (8, not 6):** `fleetLiveUpdatePort`/`telemetryLiveUpdatePort`/`detectionLiveUpdatePort`/`mapLiveUpdatePort`/`eventLiveUpdatePort`/`trackCorrectionLiveUpdatePort`/`geofenceLiveUpdatePort`/`linkStateLiveUpdatePort` — each takes `VisionLiveProperties` + `@Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry>`; returns the registry if `properties.enabled()` (default true) else `new NoopLiveUpdatePublisher()`. `trackCorrectionLiveUpdatePort` backs the `geo:<assetId>` topic (visual-geo), same flag. `geofenceLiveUpdatePort` (LIVE-POLL-RETIREMENT-PLAN.md §3 D2, wave L3) is byte-identical in shape to the six preceding it — added because `contexts/vision-flight`'s `GeofenceLiveUpdatePort` backs the new `zones` SSE topic (see `station/vision-api/MODULE.md`'s "Live updates" section); `geofenceService`'s bean method now also takes it, passed as the 3rd constructor arg to `DefaultGeofenceService`. `linkStateLiveUpdatePort` (LINK-PAIRING-PLAN.md §3.4/§4 row L3) is the eighth, same shape again — backs the per-asset `links:<assetId>` topic; the new `linkStateService(VehicleLinkPort, LinkStateLiveUpdatePort, EventPublisherPort)` bean threads it straight into `contexts/vision-flight`'s `DefaultLinkStateService` as its 2nd constructor arg, behind `station/vision-api`'s `AssetLinksController`.
- **The `system` SSE topic (LIVE-POLL-RETIREMENT-PLAN.md §3 D3, wave L4) needs no selector bean at all.** `vision-api`'s `live/SystemStatusSampler` is a plain `@Component` (self-gated by the same `@ConditionalOnProperty(vision.live.enabled)` `LiveUpdateRegistry`/`LiveController` already use), picked up by ordinary component scan — there is no per-context port for it to select between, and no `Noop*` companion is needed either: nothing else depends on it, so a disabled deployment simply has zero instances of the bean, not an inert stand-in. `LiveWiringTest`/`LiveDisabledWiringTest` (this module) assert its presence/absence directly via `ApplicationContext#getBeansOfType(SystemStatusSampler.class)`, the same idiom already used for `LiveController`/`LiveUpdateRegistry` themselves.
- **`streamStateObserver` (SOURCE-ONBOARDING-2 wave C) is a seventh selector alongside those six, but not a seventh `*LiveUpdatePort`** — `LiveUpdateRegistry` implements no such port for this. Gated on **both** `VisionLiveProperties#enabled()` and the new nested `VisionLiveProperties.StreamStatePush#enabled()` (both default true); with either off it's `StreamStateObserver.NOOP`, otherwise a lambda calling `LiveUpdateRegistry#publishDevicesSnapshot()` on every computed `StreamState` transition — the same `devices` snapshot `fleetLiveUpdatePort`'s sibling `publishFleetChanged` already coalesces into on asset/device/stream lifecycle changes, here additionally fired directly on a stream-state transition. Threaded into `streamService`'s `DefaultStreamServiceSettings` as its (now 7th) `StreamStateObserver` field.
- `eventPublisherPort` — `LoggingEventPublisher`, wrapped in `DetectionSessionCleanupEventPublisher` if CV enabled and `detectionPort` is a real `GrpcDetectionPort`, then (ALWAYS-ON-FLOW-PLAN wave B3) wrapped in `events.PersistingEventPublisher` if `vision.events.history.enabled`, finally wrapped in `LiveUpdateEventPublisher` if live enabled. Durability wraps before the live announcement — a caller-visible ordering choice only in that `PersistingEventPublisher`'s async durable write and `LiveUpdateEventPublisher`'s SSE fan-out race independently either way, but placing the pure decorator (no I/O on the calling thread) closer to the raw publisher keeps `LiveUpdateEventPublisher` — which SSE-fans-out synchronously — as the outermost, most failure-visible layer.
- `eventHistoryPort(EntityManagerFactory, VisionEventHistoryProperties)` (wave B3) → `JpaEventHistory`, **unconditional** (adapter-persistence is the only store, same posture as `auditTrailPort`/`detectionEventRepositoryPort` below) — resolved regardless of `vision.events.history.enabled`; only the *decorator* that writes to it is gated, following the "flag gates the write path, the port itself always exists" convention `NoopLiveUpdatePublisher`'s sibling ports do not need here since there is no in-memory fallback for this port at all (see `storage/persistence/MODULE.md`).
- `auditTrailPort`/`detectionEventRepositoryPort` — `Jpa*` (adapter-persistence), each wrapped in a `LiveUpdate*` decorator when live enabled.
- `defaultManualControlService` — `DefaultManualControlService`'s **7-arg canonical constructor** (`Clock.systemUTC()`, a private `rcWatchdogScheduler()` single-thread daemon, `rcProperties.watchdogTimeoutMs()`, and `ControlProfileService::activeFor` as a method reference, not the whole service). **CREW-CONTROL wave W2** split the bean `ManualControlWebSocketHandler` actually injects into a second bean, `manualControlService(DefaultManualControlService, SeatService, VisionCrewProperties)`: with `vision.crew.enabled=false` (default) it returns `defaultManualControlService` unchanged; when true it wraps `engage` in a lambda that additionally registers a `SeatService#onPreempted(assetId, FLIGHT, session::release)` listener — the RC-release hook that kills a displaced pilot's sticks within one watchdog period when a manager forces the flight seat away (§3.2 rule 4) — rather than adding a sixth parameter to `defaultManualControlService` itself (java-clean-code §3). The listener registration itself is gated on the same flag so a disabled deployment never accumulates an unused listener in `SeatService`'s per-asset list (§3.8, the default-config guardrail).
- `flightCommandService(AssetService, FlightCommandPort, AuditTrailPort, ReadinessService)` — `DefaultFlightCommandService`'s 4-arg canonical constructor (ASSET-FLOWS-PLAN S1, wave BK1: `ReadinessService` is the new 4th param, same bean `manualControlService`/`OnboardingWiringConfiguration#readinessService` already consume — no new bean, just a new injection point).
- `assetDirectoryService(AssetRepositoryPort, DeviceRepositoryPort)` / `usageSessionService(AssetUsageRepositoryPort)` / `telemetryService(TelemetryRepositoryPort)` — thin per-repository ownership seams that `usageTracker` composes instead of holding repository ports directly (ARCHITECTURE-AUDIT-2026-08-26.md D1/R3). **`assetDirectoryService` wraps the repository ports directly rather than `AssetService`/`DeviceService` — this is the fix for the bean cycle described in Gotchas, not a shortcut.**
- `usageTracker` — composed from `assetDirectoryService`/`usageSessionService`/`telemetryService` + `List<TelemetrySourcePort>` + a `UsageTrackerSettings` record (telemetry live port, a `BiConsumer<AssetId,Telemetry>` telemetry observer, persistence-batch settings, `UsagePhaseSettings.defaults()`, an `ObjectProvider<UsagePhaseObserver>` defaulting to `NOOP` — ASSET-FLOWS S1/BK1 — a required `MaintenanceQuery` param, the same `DefaultMaintenanceService` bean `OnboardingWiringConfiguration#readinessService`/`flightCommandService` already consume, and — ASSET-FLOWS S4/BK2b — a required `LinkLossNotifier` param, threaded through `UsageTrackerSettings.defaults(maintenanceQuery, linkLossNotifier)` and as the settings record's now-last, 9th field). The observer is a composed lambda — `geofenceMonitor.evaluate(...)` then `batteryMonitor.evaluate(...)` against the same sample, both method calls rather than a chained `BiConsumer`, so perception never depends on flight's context directly (ASSET-FLOWS S4/BK2 composed `batteryMonitor` into this same seam rather than adding a second `UsageTrackerSettings` slot — CLAUDE.md rule 10); `linkLossNotifier` itself is passed straight through as a plain collaborator, not composed into that lambda, since `UsageTracker` calls it once from a `SupervisedPublisher` outage callback rather than once per sample.
- `batteryMonitor(EventPublisherPort, EventLiveUpdatePort, @Value("${vision.ops.battery.critical-percent:10}") double, @Value("${vision.ops.battery.warning-percent:25}") double)` → `vision-flight`'s `BatteryMonitor`, settings wrapped in a `BatteryAlertSettings`. `linkLossNotifier(EventPublisherPort, EventLiveUpdatePort)` → `vision-flight`'s `LinkLossNotifier`. **ASSET-FLOWS S4/BK2b wired this bean into `usageTracker` above** — perception's `UsageTracker#subscribeTelemetry` now calls `reportLinkLost` from its telemetry `SupervisedPublisher`'s outage callback (see `contexts/vision-perception/MODULE.md`). Both beans read the D6 threshold properties directly via `@Value` with code defaults (`vision.ops.battery.*` is not yet in `application.yaml` — a follow-up wave's job) rather than a dedicated `@ConfigurationProperties` record, since `BatteryAlertSettings` itself is already the settings record and a second properties type would just duplicate its two fields.
- `assetLiveStatePort(StreamService, UsageTracker, DetectionEventRepositoryPort)` → `StreamBackedAssetLiveState` (perception) implementing warehouse's `AssetLiveStatePort` — the one class allowed to compose all three; `assetService`/`deviceService`/`assetStatsService`/`fleetSummaryService` all take this port instead.
- `streamService` — builds `DefaultStreamService` via a `DefaultStreamServiceSettings` record folding in `streamPipelineSettings(...)` (adaptive-rate, tracking seed, CV-demand poll interval, `pipeline.videoStaleAfter()`), an `Optional<PullDetectionSettings>` (present iff `cv.pullEnabled()`), an `Optional<DetectionDemandPort>`, and (SOURCE-ONBOARDING-2 wave C, the settings record's 7th field) the `streamStateObserver` bean above — no new constructor overload, per CLAUDE.md rule 10. Wrapped in `com.drones.vision.app.stream.LiveFrameFallbackStreamService` iff `vision.publish.source-proxy.enabled=true`.
- `mapAccessPolicy()`/`layerResolver(...)` — each **one shared singleton** (stateless policy; `layerResolver`'s find-or-create methods are `synchronized` and need single-instance guarding).
- `simulationService` — `DefaultSimulationService(assetService, assetStreamService, deviceService, feedTransmitterRegistry, mediamtx.rtspBase(), SimulationServiceSettings)`; `deviceService` (not `categoryRepositoryPort`) is the constructor's 3rd param, per ARCHITECTURE-AUDIT R4.
- `simulationResumeRunner` — gated purely on `VisionSimulationProperties#resumeOnBoot()` (persistence is unconditional, so there is no second half of this gate).
- **WAREHOUSE-UX W3 (unconditional, alongside `assetService`/`categoryService`):** `assetCustodyService(AssetRepositoryPort, MaintenanceRepositoryPort, AuditTrailPort)` → `DefaultAssetCustodyService`; `maintenanceService(MaintenanceRepositoryPort, AssetRepositoryPort, AuditTrailPort)` → declared `DefaultMaintenanceService` (the concrete type, not `MaintenanceService`, specifically so this one bean also satisfies a `MaintenanceQuery`-typed injection point — see `OnboardingWiringConfiguration#readinessService` above — the same "one instance, two seams" shape `usageSessionService`/`DefaultUsageSessionService` would use if `UsageSessionService` had a second interface); `inventoryExportService(AssetService)` → `com.drones.vision.api.support.InventoryExportService` (the hand-rolled CSV behind `GET /api/inventory/export`).
- **WAREHOUSE-UX W8 (unconditional, alongside `inventoryExportService`):** `assetRowFacts(VehicleProfileRepositoryPort, AssetUsageRepositoryPort)` → `com.drones.vision.api.support.AssetRowFacts` — bundles both cross-context ports `AssetController`'s new `firmware`/`totalFlightSeconds` join needs into `AssetController`'s fifth constructor parameter (see `station/vision-api/MODULE.md`'s Conventions). `vehicleProfileRepositoryPort` resolves to `PersistenceWiringConfiguration`'s unconditional bean; `assetUsageRepositoryPort` is the same bean `assetService` already consumes — no new port implementation, only a new query method on the existing one.
- **LINK-PAIRING-PLAN.md §3.3/§4 row L2 (unconditional):** `pairingService(PairingRepositoryPort, DeviceService, AuditTrailPort, VisionPairingProperties)` → `DefaultPairingService`, behind `vision-api`'s `PairingController` and `DiscoveryInboxController`'s adopt-is-one-motion call. `pairingRepositoryPort` resolves to `PersistenceWiringConfiguration`'s new bean; `VisionPairingProperties#toSettings()` crosses the module boundary as a plain `PairingSettings` record, not a raw `sysid-range` string — the same "parse once at the config boundary" convention every other `Vision*Properties`→domain-settings translation in this class follows.
- **LINK-PAIRING-PLAN.md §3.4/§4 row L3 (unconditional):** `linkStateService(VehicleLinkPort, LinkStateLiveUpdatePort, EventPublisherPort)` → `contexts/vision-flight`'s `DefaultLinkStateService`, behind `vision-api`'s `AssetLinksController`. The `VehicleLinkPort` argument resolves to `TelemetryWiring#mavlinkVehicleLinkPort` — a single `drone-link/mavlink` bean (`MavlinkVehicleLinkPort(MavlinkTelemetrySource, AssetService)`) declared by its concrete type there so it also satisfies `CarrierDirectoryPort`-typed injection (`vision-api`'s `CarriersController`) with no second bean method, the same "one bean, several ports by Spring type-matching" idiom `mavlinkFlightCommander`/`mavlinkManualControlSender` already establish for `FlightCommandPort`/`ManualControlPort`. `EventPublisherPort` is the same decorated instance every other application service in this class shares (see `eventPublisherPort` above) — `DefaultLinkStateService` raises `EventType.LINK_FAILOVER` on it, not a dedicated notifier.

### Properties records (`config/properties/`, all `@ConfigurationProperties`)

| Record | Prefix | Primary consumer |
|---|---|---|
| `VisionApplicationProperties` | `vision.application` | `vision-application`'s pipeline/replay/simulation/fleet/stats settings |
| `VisionPersistenceProperties` | `vision.persistence` | `adapter-persistence`'s `PersistenceUnit`/pool/telemetry-batch settings |
| `VisionLiveProperties` | `vision.live` | selects `LiveUpdateRegistry` vs. `NoopLiveUpdatePublisher` (all 6 ports). **SOURCE-ONBOARDING-2 wave C** widened this from a single-field to a 2-component canonical constructor (`enabled`, `streamStatePush`), gaining a nested `StreamStatePush(boolean enabled)` record (default true) that gates the new `streamStateObserver` selector above; no back-compat 1-arg convenience constructor was added since a repo-wide grep found zero existing `new VisionLiveProperties(...)` call sites to preserve (CLAUDE.md rule 10) |
| `VisionTrainingProperties` | `vision.training` | gates `TrainingWiringConfiguration`'s whole bean cluster |
| `VisionCvProperties` | `vision.cv` | `CvWiring`/`adapter-cv-grpc` channel + detection settings. **ALWAYS-ON-FLOW-PLAN.md wave D1** added a `Policy policy` component (right after `profiles`, 22nd of the canonical constructor — every pre-existing `new VisionCvProperties(...)` call site with the old 21-arg shape needed a trailing `null` appended, `VisionCvPropertiesTest`'s 5 sites among them), with a null-fallback to `new Policy(Policy.DEFAULT_REFRESH_INTERVAL)` in the compact ctor. `record Policy(Duration refreshInterval)` (`@DefaultValue("15s")`) — `CvWiring#detectionPolicyCache`'s own re-list cadence; the *record* is always present regardless of deployment (no feature flag gates the config shape, and binding the property costs nothing even unused), but the *bean* that consumes it is conditional — see the `CvWiring` row below. No `docker-compose.yml` env var added: like its sibling per-tick tunables (`Profiles.cacheTtl`, `detectionDemandGrace`/`detectionDemandPollInterval`), the 15s default is deployment-topology-independent, so Spring's own relaxed env-var binding (`VISION_CV_POLICY_REFRESH_INTERVAL`) is sufficient without a compose-file line, consistent with the precedent that none of those siblings have one either |
| `VisionDiscoveryProperties` | `vision.discovery` | `adapter-discovery` scan budgets; ZERO-CONFIG-ONBOARDING Z2c added two nested records to the canonical constructor's 4th/5th components — `Lobby(boolean enabled)` (default `true`, `DiscoveryInboxWiringConfiguration`'s `mavlinkTelemetrySource.holdLobby(...)` gate) and `Inbox(boolean enabled, int sweepSeconds, int scanTimeoutSeconds)` (defaults `true`/`30`/`5`, compact-constructor validated both ints `> 0`) — both default to `true`/on, a deliberate exception to the repo's usual opt-in-guardrail default (see `DiscoveryInboxWiringConfiguration` row above). Z3 added a 6th component, `Mediamtx(boolean enabled, String pathPrefix)` (defaults `true`/`"ingest/"`, compact-constructor rejects a blank `pathPrefix`) — consumed by `DiscoveryWiringConfiguration#mediamtxPathScanner`'s COP and settings-record construction (see that row above); `apiBase`/`rtspBase` are deliberately **not** on this record — they come from `VisionPublishProperties.Mediamtx` instead, so this record only owns the one property that's genuinely this module's own concern. C4 added a 7th component, `Live(boolean enabled)` (default `true`) — gates whether `DiscoveryInboxWiringConfiguration#discoveryInboxService` decorates its delegate with `LiveUpdateDiscoveryInboxService` (see `vision-api`'s MODULE.md Live-updates section for the `discovery` SSE topic this feeds); ships on by default (SOURCE-ONBOARDING-2-PLAN.md §3.2 C4, D8) |
| `VisionSimulationProperties` | `vision.simulation` | `adapter-simulation` video/telemetry settings + resume-on-boot; **LINK-PAIRING-PLAN.md §4 row L2** added a 4th canonical-constructor component, `boolean enabled` (defaults `false`, the opt-in guardrail — the Playground's own master switch, independent of `resumeOnBoot`), so the existing 1-arg convenience constructor (`resumeOnBoot`-only call sites) now defaults `enabled=false` too rather than gaining a second overload |
| `VisionPairingProperties` | `vision.pairing` | **LINK-PAIRING-PLAN.md §7 ruling 2, wave L2.** `record VisionPairingProperties(String sysidRange)` — one `"min-max"` string (e.g. `"10-250"`), not two properties, matching the plan's own frozen spelling; default `"10-250"`, compact-constructor validated against `\d{1,3}-\d{1,3}`. `toSettings()` parses it into the `PairingSettings` record `PairingService#pair` actually uses — the config-boundary crossing, so `ApplicationServiceWiring#pairingService` never threads a raw string into the domain |
| `VisionLinksProperties` | `vision.links` | **LINK-PAIRING-PLAN.md §3.4/§4 row L3.** `record VisionLinksProperties(Duration softTimeout, Duration hardTimeout, Duration dwellWindow)` — defaults `3s`/`10s`/`5s`, byte-identical to `com.drones.vision.adapter.mavlink.election.LinkElectionSettings.defaults()`. `softTimeout` (must be positive) is how long since a link last delivered a frame before it stops being eligible to hold/win ACTIVE for its sysid; `hardTimeout` (must be `>= softTimeout`) is how long before it is dropped from the group entirely — a stale link is still kept, as last-known ACTIVE, until this bound (CLAUDE.md rule 7, "a stale link beats no link"); `dwellWindow` (must be `>= 0`) is how long a recovered higher-priority link must stay continuously best-eligible before it displaces a healthy ACTIVE link — the anti-flap guardrail. Consumed only by `TelemetryWiring#toMavlinkSettings`'s new 4th parameter (mapped onto `LinkElectionSettings`, which `drone-link/mavlink`'s `com.drones.vision.adapter.mavlink.election` package actually runs against — see that module's own MODULE.md) |
| `VisionPublishProperties` | `vision.publish` | `PublishWiring`/`adapter-publish-hls` (mediamtx, encoder, resilience, cadence, replay, source-proxy) |
| `VisionMediaProperties` | `vision.media` | ASSET-FLOWS-PLAN §2 S6 mediamtx read/publish credentials. `record VisionMediaProperties(Auth auth)` — a `null` `auth` (whole block absent) normalizes to `Auth`'s own all-`@DefaultValue` construction in the compact ctor, matching `VisionOpsProperties.Battery`'s precedent (Spring relaxed binding does not apply a nested record's `@DefaultValue`s when the whole block is missing). `record Auth(String viewerUsername, String viewerPassword, String publisherUsername, String publisherPassword)`, `@DefaultValue`s `vision-viewer`/`change-me`/`vision-publisher`/`change-me` — **deliberately non-blank**, not the repo's usual "empty disables the feature" idiom, because mediamtx's own `authInternalUsers` (`./mediamtx.yml`) ships those exact same accounts already configured with the same `change-me` password: a blank Java-side default would silently stop authenticating against a mediamtx that already requires auth, which is a worse failure than an easily-`grep`-able placeholder credential. Operators MUST override both this block's `viewer-password`/`publisher-password` AND `mediamtx.yml`'s matching `pass:` fields (plus `docker-compose.yml`'s `VISION_MEDIA_AUTH_*` env vars) together — mediamtx's env-var loader has no override mechanism for `authInternalUsers` (a list-of-struct field), so the mounted `mediamtx.yml` file and this record's binding are two independent sources of the same secret. Consumed only by `PublishWiring` (see that row's own entry and Gotchas). |
| `VisionRcProperties` | `vision.rc` | `adapter-mavlink`'s RC-override cadence/watchdog. **FLY-CONTROL-UX H1**: `vision.rc.engage-slow-threshold-ms` (default 2000) lives in the same `vision.rc.*` namespace but deliberately has no field on this record — it is read independently by `vision-api`'s `ManualControlWebSocketHandler` via its own `@Value` (vision-api may not depend on vision-app), the same split this record's own javadoc already documents for `watchdog-timeout-ms` |
| `VisionRtspProperties` | `vision.rtsp` | `adapter-rtsp`'s `FfmpegSettings` |
| `VisionMjpegProperties` | `vision.mjpeg` | `adapter-mjpeg`'s `MjpegSettings` |
| `VisionV4l2Properties` | `vision.v4l2` | `V4l2VideoSource` directly (no adapter settings record) |
| `VisionMavlinkProperties` | `vision.mavlink` | `adapter-mavlink`'s `MavlinkSettings` (minus `Rc`); **FLEET-RADIO R4/D7** adds `dropRateWarnPercent`/`dropRateAlarmPercent`/`linkFailureGrace` (defaults 5.0/20.0/2s, compact-constructor validated: both percents 0..100, alarm>=warn, grace positive) — consumed by `TelemetryWiring#toMavlinkSettings` (into `MavlinkSettings.LinkStatus`) and directly by `SystemStatusWiring#mavlinkLinkStatus`. **MAVLINK-COMMANDS-PLAN P4** re-scoped `ackTimeout`'s `@DefaultValue` from `2s` to `700ms` (now byte-identical to `MavlinkSettings.DEFAULT_ACK_TIMEOUT_MILLIS`, the per-*attempt* wait) and added `commandRetries` (11th canonical-constructor component, right after `ackTimeout`; `@DefaultValue` `2`, compact-constructor validated `>= 0`, byte-identical to `MavlinkSettings.DEFAULT_COMMAND_RETRIES`) — both threaded through `TelemetryWiring#toMavlinkSettings` via `MavlinkSettings.withCommandRetries(...)`, closing the P1-documented production gap (see Gotchas) |
| `VisionApiProperties` | `vision.api` | bridges to vision-api's plain mirror (same simple name, see Gotchas); snapshot/hlsProxy/live/paging/upload/rateLimit. **LIVE-POLL-RETIREMENT-PLAN.md §3 D3, wave L4**: the nested `Live` record gained a 9th component, `Duration systemSample` (`@DefaultValue("5s")`, compact-ctor validated positive) — `PublishWiring#toApiSupportProperties`/`#liveSettings` both bridge it through unchanged shape (just a 9th positional arg); consumed by `vision-api`'s `SystemStatusSampler` (its own `@Autowired` constructor takes the plain-mirror `VisionApiProperties.Live` directly, same bean `liveSettings()` already builds for `LiveController`) |
| `VisionOnboardingProperties` | `vision.onboarding` | probe/remediate/passport flags → `OnboardingWiringConfiguration` + `TelemetryWiring#toMavlinkSettings`. `probe.parameters` (list, default empty) **replaces** the adapter's firmware-verified probe list rather than adding to it; empty keeps it |
| `VisionControlProperties` | `vision.control` | `ControlProfileWiring`'s aux-function catalog |
| `VisionGeoProperties` | `vision.geo.fixed-camera` | `FixedCameraGeoWiringConfiguration` |
| `VisionGeoVisualProperties` | `vision.geo.visual` | `VisualGeoWiringConfiguration` (incl. `tiles.*`) |
| `VisionStreamsProperties` | `vision.streams` | `StreamLifecycleWiring`'s idle-stream reaper |
| `VisionTrackingProperties` | `vision.tracking` | `TrackingWiring` seed + per-stream read-model windows |
| `VisionUsageProperties` | `vision.usage` | `UsageWiringConfiguration`'s idle-usage-close sweep (`idleClose` default 10m, `sweepPeriod` default 60s) |
| `VisionTelemetryProperties` | `vision.telemetry` | **ALWAYS-ON-FLOW-PLAN wave A1.** `record VisionTelemetryProperties(AlwaysOn alwaysOn)`, one nested `AlwaysOn(boolean enabled, Duration sweepInterval)` — `enabled` defaults **`false`** (ships invisible; on in `docker-compose.yml`), `sweepInterval` defaults `30s`, compact-constructor validated positive. Absent `always-on` block falls back to `new AlwaysOn(false, null)` under the same whole-block-absent rule as `VisionOpsProperties.Battery`. Consumed only by `UsageWiringConfiguration#telemetryPinRunner`. **Its own root, deliberately not under `vision.streams.*`**: those keys decide whether a video *stream* should exist, this one decides whether a *link* should be claimed — conflating the two is the defect wave A closes, so co-locating the keys would re-suggest exactly the wrong mental model |
| `VisionOpsProperties` | `vision.ops` | `OpsWiringConfiguration`'s `opsThresholds` bean, behind `GET /api/ops/thresholds` (vision-api). Nested `Battery(int warningPercent, int criticalPercent)`, defaults 25/10, compact-constructor validated (`critical < warning`, both `[0,100]`); absent `battery` block falls back to `Battery.defaults()` since Spring relaxed binding does not apply a nested record's own `@DefaultValue`s when the whole block is missing (`VisionMavlinkProperties`'s `Scan`/`Transmit` precedent). `ApplicationServiceWiring#batteryMonitor` (BK2, this same cycle) independently reads the identical `vision.ops.battery.critical-percent`/`warning-percent` keys via raw `@Value`, by deliberate design (see that bean's own javadoc) rather than by accident — it carries the same 10/25 defaults inline so it behaves correctly whether or not this record/its `application.yaml` block exists yet, and now that both do, an operator override of the yaml block reaches **both** consumers identically since they bind the same property keys, which is the actual "one configured severity source" ASSET-FLOWS-PLAN §2 asks for. The two Java binding mechanisms (this `@ConfigurationProperties` record vs. `batteryMonitor`'s two `@Value`s) staying separate rather than both consuming this one record is a minor follow-up cleanup, not a config-drift risk. FLY-CONTROL-UX-PLAN §2/BK1 added a second nested record, `Rc(int neutralTolerancePercent)`, default **5**, compact-constructor validated `[1,25]`, absent `rc` block falling back to `Rc.defaults()` under the exact same whole-block-absent rule as `Battery` — the web cockpit's neutral-stick arm gate's tolerance, read off `GET /api/ops/thresholds`'s new `rc` field. |
| `VisionAuthProperties` | `vision.auth` | **AUTH-ROLES-PLAN §3.6, wave B5.** `record VisionAuthProperties(Session session)`, one nested record `Session(Duration idleTimeout, Duration kioskIdleTimeout, boolean cookieSecure)` — defaults `12h`/`365d`/`false`, compact-constructor validated (both durations positive). Consumed by `AuthWiringConfiguration#securitySessionAuthenticator` → `SecuritySessionAuthenticator`'s constructor (idle/kiosk timeouts, replacing two bare `@Value`s) and by `application.yaml`'s `server.servlet.session.cookie.secure` (placeholder interpolation onto `vision.auth.session.cookie-secure`, not Java). **Scope note**: this record covers only the `session.*` block, deliberately narrower than §3.6/D2's full target shape (`enabled`+`session.*`+`password.*` in one record) — `vision.auth.enabled` (`SecurityConfig`'s/`AuthWiringConfiguration`'s own `@ConditionalOnProperty`s) and `password.*` (`AuthController`/`BootstrapController`/`AuthPasswordController`/`PasswordPolicy`'s scattered `@Value`s) are untouched, deferred to a later wave — see the record's own javadoc. |
| `VisionCrewProperties` | `vision.crew` | **CREW-CONTROL-PLAN.md §3.6, wave W2.** `record VisionCrewProperties(boolean enabled, long seatTtlMs)` — `enabled` defaults `false` (the opt-in guardrail, §3.8: off, `SeatAccess` is a pass-through and every default-config test suite observes no change at all), `seatTtlMs` defaults `15000`, compact-constructor validated positive. Consumed by `SeatWiringConfiguration#seatService`/`#seatAccessSettings` and (for the gate flag only) `ApplicationServiceWiring#manualControlService`'s RC-release-hook registration. |
| `VisionEventHistoryProperties` | `vision.events.history` | **ALWAYS-ON-FLOW-PLAN.md wave B3.** `record VisionEventHistoryProperties(boolean enabled, Retention retention)`, one nested `Retention(int maxRows)` — `enabled` defaults **`false`** (the opt-in guardrail; on in `docker-compose.yml`), `maxRows` defaults `20000`, compact-constructor validated positive; absent `retention` block falls back to `Retention.defaults()` under the same whole-block-absent rule as `VisionOpsProperties.Battery`/`VisionTelemetryProperties.AlwaysOn`. Consumed by `ApplicationServiceWiring#eventHistoryPort` (the cap, always) and `#eventPublisherPort` (the enable flag, gates the `PersistingEventPublisher` decorator only — the port itself is unconditional, see above). |

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
| `NoopLiveUpdatePublisher` | all 7 live-update ports on one class (`GeofenceLiveUpdatePort` added LIVE-POLL-RETIREMENT wave L3) | `vision.live.enabled=false` |
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
| `configurationPropertiesClassesLiveOnlyInAppConfigPropertiesPackage` | every `@ConfigurationProperties` in `app.config.properties`, except `adapter.carrierudp..`/`adapter.carrierserial..` (LINK-PAIRING-PLAN.md §3.2, wave L1 — those two self-contained adapters must not depend on vision-app) |
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
- **`@Qualifier("liveUpdateRegistry")` on all 7 live-update selector beans is not decorative.** Remove
  it from any one and, once ≥2 of the seven are resolved together, an unqualified `ObjectProvider<LiveUpdateRegistry>`
  lookup sees two candidates (the component-scanned bean + the already-resolved sibling) and throws
  `NoSuchBeanDefinitionException`. `vision-api`'s own `LiveUpdateStatusProvider`/`SystemStatusSampler`
  need the identical qualifier for the identical reason — see that module's own MODULE.md Gotchas.
- **`@Primary` does not help an `ObjectProvider` consumer.** `cvGrpcChannel` is `@Primary`, but once
  `cvTrainingChannel` also exists as a bean, every multi-channel consumer still needs explicit
  `@Qualifier("cvGrpcChannel")`/`@Qualifier("cvTrainingChannel")` — `@Primary` only resolves ambiguity
  for a plain `@Autowired` injection point. This was a real shipped break in `VisualGeoWiringConfiguration`,
  fixed by adding the qualifier pairs.
- **`ObjectProvider<T>` circular-dependency rule:** resolve it lazily (inside a lambda/predicate
  invoked after full context startup, never eagerly in the `@Bean` method body) whenever `T`'s own
  construction path loops back to the bean under construction. Three confirmed instances: `CvWiring#detectionDemandPort`'s
  `TrackProjectionRunner` (needs `AssetService`, whose path loops back through `detectionDemandPort`),
  `ApplicationServiceWiring`/`StreamLifecycleWiring`'s `usageTracker` resolver, and (ALWAYS-ON-FLOW
  wave D1) `CvWiring#detectionPolicyPort`'s `ObjectProvider<DetectionPolicyCache>` — the same shape
  as the `TrackProjectionRunner` case, `.getIfAvailable()` deferred inside the returned
  `DetectionPolicyPort` lambda's body, not called eagerly while the bean method itself runs.
- **Security default is condition-exclusivity, not a runtime tie-break.** `securedFilterChain` is
  `@ConditionalOnProperty(vision.auth.enabled, havingValue="true", matchIfMissing=true)`;
  `permitAllFilterChain` has no `matchIfMissing`, so it exists only when the property is explicitly
  `false`. Exactly one `SecurityFilterChain` bean is ever registered. CSRF is disabled in **both**
  chains deliberately (JSON-only API, same-origin, `SameSite=Lax` cookie — a documented deferred
  hardening step, not an oversight in the permit-all chain). Since AUTH-ROLES-PLAN wave B0b,
  `application.yaml`'s own explicit `vision.auth.enabled` value is `true` — this file's compiled
  default and every deployment's actual behavior finally agree; `false` is now something an operator
  must opt back into explicitly for a local/demo run, not the out-of-the-box posture.
- **Lost the only admin's password? Re-enabling `seed-dev-users` does NOT help.**
  `V90001__dev_accounts.sql`'s per-account guard is `WHERE NOT EXISTS (... id = ... OR username = ...)`
  — once a username like `admin` already exists (true of any deployment past its very first boot),
  that migration's `INSERT` is a permanent no-op for that row; it never overwrites an existing
  `password_hash`, seed-dev-users on or off. There is no self-service reset endpoint (out of scope,
  AUTH-ROLES-PLAN.md's Non-goals) and no admin-impersonation escape hatch once
  `vision.auth.enabled=true`. The only recovery path is a direct database edit:
  `UPDATE users SET password_hash = '<bcrypt-hash>' WHERE username = '<locked-out-admin>';` via `psql`
  against the running Postgres instance, where `<bcrypt-hash>` is generated out-of-band with the app's
  own hasher (e.g. `new BCryptPasswordEncoder().encode("new-password")` in a scratch test/REPL — the
  same "real BCrypt output, not invented" posture V90001's own header documents for its three seeded
  hashes). Restart is not required; the next login reads the updated row.
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
  **The same class of staleness reaches every driven adapter, not just drone-link** (SOURCE-ONBOARDING-2-PLAN.md wave C hit it against a concurrent agent's `vision-identity` rebuild plus a second, independent stale `adapter-mavlink` jar predating `MavlinkIntakeStatus`/`intakeStatus(int)` in the very same session): when in doubt, reinstall the whole adapter set this module depends on from worktree source before trusting a red build — `./mvnw -B -pl simulation-sources/sim,video-input/rtsp,video-input/mjpeg,drone-link/mavlink,video-input/v4l2,video-output/publish-hls,cv/grpc,cv/tiles,device-discovery/onvif-mdns-v4l2,storage/persistence -am install -DskipTests` (`-DskipTests` sidesteps the flaky, docker-gated `adapter-rtsp` `MediamtxDockerIntegrationTest` rather than diagnosing a module none of this wave's edits touched).
- **A `@Bean` method returning `null` satisfies only `Optional`/`ObjectProvider`/`@Nullable` injection points, never a plain required constructor parameter.** Spring wraps a null return in an internal `NullBean` marker; a consumer with a plain typed parameter still sees "no qualifying bean" and throws `UnsatisfiedDependencyException`/`NoSuchBeanDefinitionException` at context startup, not at the `getBean` call that actually returns null. `DiscoveryWiringConfiguration`'s `videoPushPort`/`videoPushPathPrefix` shipped exactly this bug when mediamtx publish was disabled (`vision.publish.enabled=false`): both bean methods stayed unconditionally registered and returned `null` internally instead of being conditionally absent. Fixed by making both genuinely conditional (`@ConditionalOnProperty(prefix = "vision.publish", name = "enabled", havingValue = "true", matchIfMissing = true)`, no internal null-check) and having the one cross-module consumer (`vision-api`'s `SystemNetworkController`) take them as `ObjectProvider<Integer>`/`ObjectProvider<String>` and call `.getIfAvailable()` — never a plain nullable-by-convention type crossing the module boundary (see that module's own MODULE.md Conventions section for the consumer-side half of this pattern).
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
- **`vision.simulation.enabled` defaults to `false` in production but `true` in this module's test
  suite** (LINK-PAIRING-PLAN.md §4 row L2, same "flip the test-wide default, override back per-test"
  shape `vision.auth.enabled` already established): `src/test/resources/application.properties`
  pins it to `true` so every pre-existing full-context test — `DeviceProbeSmokeTest`,
  `CvDetectionE2ETest`/`CvDetectionEndpointE2ETest`/`CvDetectionResilienceSmokeTest`,
  `SimStreamSmokeTest`, `AssetParameterFlagGatingTest`, `TrackingAssociateE2ETest`, `AssetWiringTest`,
  `PublishWiringTest` — keeps seeing the Playground's `SimulationController`/`sim` `VideoSourcePort`
  beans it relied on before the gate existed, without each naming the property itself.
  `SimulationDisabledWiringTest` is the one test whose entire point is the true, genuinely-off
  production default — it overrides the flag back to `false` in its own `@SpringBootTest(properties
  = ...)`, which always wins over the module-wide file.
- `SystemStatusWiring`/`CvWiring#cvChannelSupervisor` deliberately repeat their gated resource's own
  `@ConditionalOnProperty`/`@ConditionalOnExpression` literally, rather than using
  `@ConditionalOnBean` — the latter is sensitive to `@Bean`-method declaration order within a
  `@Configuration` class and can pass in a narrow test slice while silently failing in full wiring.
- **`TrackingAssociateE2ETest#aStreamStartedOnTheDefaultsTracksAndTheTracksEndpointReportsStableIdsAcrossFrames`
  is a read-model *timing* canary, not a flaky test — and it caught a real wave-D regression
  (ALWAYS-ON-FLOW-PLAN.md D1/D2, 2026-09-06).** It asserts `>=3` detector passes off
  `$.stats.detectorPasses`, which `StreamPipeline#onDetectionResult` feeds via
  `trackingStats.accept`. D2's first cut reordered that method to run the durable plane
  (`detectionRepositoryPort#save`, synchronous database I/O) **before** the live read models. That
  made every read model a polling client observes lag by one database round trip, and the test read
  `saw 2` on every full-suite run while passing in isolation — the classic signature of a load-
  dependent flake, which is exactly how it was first misdiagnosed.
  **It was not a flake.** A baseline run of the same suite at the immediately preceding commit, under
  the same load on the same machine, was 350/350 green; the failure appeared only with D2 applied and
  vanished the moment `onDetectionResult` was restored to live-plane-first. Two ablations had already
  "ruled out" `detectionPolicyCache` and `DefaultStreamService`'s scheduler-arming condition — both
  genuinely innocent, which is precisely why ablating them changed nothing while the real cause sat
  untouched in a third place. **The lesson worth keeping: an ablation that clears its target has
  narrowed nothing unless the candidate set was complete, and "passes alone, fails under load" is
  equally consistent with a real ordering regression.** If this assertion drops below 3 again, suspect
  something moved in `onDetectionResult` before reaching for `@Disabled`.

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
  (`vision.geo.fixed-camera.enabled`), visual geo (`vision.geo.visual.enabled`), the two-seat
  crew-control gate (`vision.crew.enabled` — `seatService` itself is wired unconditionally, but
  `SeatAccess`'s pass-through and the RC-release-hook registration both stay inert until this flips).
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

**AUTH-ROLES-PLAN wave B4 done.** Per-asset command authority — `AssetAuthority`/
`CapabilityAssetAuthority`, both new types in `vision-api`'s `security` package (see that module's own
MODULE.md for the interface/implementation split and its two-entry-point design). **This module
required no wiring code at all**: `CapabilityAssetAuthority` is `@Component`-annotated and
constructor-injects only pre-existing beans (`CurrentUser`, `AssetService`, `AssignmentRepositoryPort`)
that were already in the context, so component scanning resolves it with zero `vision-app` involvement
— the same is true of `ManualControlWebSocketHandler`'s widened (now 4-arg) constructor and
`AssetSessionController`'s widened (now 4-arg) constructor: neither is built by an explicit `@Bean`
factory method anywhere in this module, so both simply pick up their new collaborator
(`CapabilityAssetAuthority`, `AuditTrailPort` respectively) through the same autowiring that already
worked. `SecurityConfig`'s secured-chain matcher list gained `/hls/**` alongside the pre-existing
`/api/**`/`/ws/**` (D10) — the only production-code line this module actually touched this wave; its
class javadoc gained a matching `/hls/**` section explaining the two-part fix (the other half,
`StreamAccess#requireVisibleForHlsProxy` failing closed instead of no-opping, lives in `vision-api`).

`EndpointAuthorizationTest` (ArchUnit-adjacent BFS over `@RestController` call graphs, see "Gotchas")
needed two small widenings so it recognizes the new authority axis: `AUTHORITY_METHODS` gained
`"authority"` (alongside the pre-existing `"scope"`/`"viewer"`), and `reachesAuthorityCheck`'s
owner-name test widened from `owner.endsWith("Access")` to `owner.endsWith("Access") ||
owner.endsWith("Authority")` — without this, any future `@RestController` handler reaching only
`CurrentUser#authority()` or `AssetAuthority`/`CapabilityAssetAuthority` (never `StreamAccess` or
`CurrentUser#scope()`) would have false-positived as unscoped. No handler in this wave actually needed
the widening to pass (B4 added no new `@RestController` handler and touched no existing one's
authority path), so this is forward cover for B6's migration, not a fix for an observed failure.

New test coverage, all in `vision-api`: `CapabilityAssetAuthorityTest` (new file, 12 cases) — the
CREW/PILOT/VIEWER/MANAGER matrix against both `mayFly(AssetId)` (ambient `CurrentUser`) and
`mayFly(Authority, UserId, AssetId)` (the explicit-actor overload `ManualControlWebSocketHandler`
uses), plus `mayOperateCamera`/`mayForceSeat`. `ManualControlWebSocketHandlerTest` gained 2 cases
(mayFly-denied → `OUT_OF_SCOPE`; mayFly asked exactly once at engage, never re-checked on a later
`channels` frame even after the test flips the fake's answer mid-session — the mid-flight rule, §3.7
clause 1, proven directly). `AssetSessionControllerTest` gained 2 cases (an audit entry is recorded
naming the caller on a real disengage; none is recorded when nothing was engaged).
`FlightCommandControllerTest` gained 2 cases (`arm` denied 403 without ever touching
`FlightCommandService` when `AssetAuthority#mayFly` denies; all six command handlers alike are gated,
proven once per family). In `vision-app`:
`AuthEnabledFlowTest` gained 2 cases — an unauthenticated `/hls/**` request is `401` (the filter-chain
half of D10, provable only here since `HlsProxyControllerTest` is a `standaloneSetup` test with no
security filter at all), and an authenticated caller naming a syntactically-valid but not-currently-running
`streamId` gets `404` before any upstream contact is attempted (the `StreamAccess` half).

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` —
vision-api **979** (961 → 979, +18: 12 new `CapabilityAssetAuthorityTest` cases + 2
`ManualControlWebSocketHandlerTest` + 2 `AssetSessionControllerTest` + 2 `FlightCommandControllerTest`),
vision-app (this module) **328** (326 → 328, +2: `AuthEnabledFlowTest`'s two new cases above),
`storage/persistence` unaffected at **276** (no file touched this wave). Docker ran for real
(Testcontainers `postgres:16`, Flyway migrated through `V33` — unchanged this wave, no new migration).
`vision.auth.enabled` stays `false` by default, unchanged this wave — the default-config auth-off
suites (`AuthDisabledSecurityTest`/`ManualControlSecurityDisabledTest`) stayed green throughout,
proving the opt-in guardrail. Deferred: `contexts/vision-flight` and `contexts/vision-identity` were
left untouched even though AUTH-ROLES-PLAN.md's literal B4 file list names them — no call site in
either module needed to change for this wave's actual scope (the `AssetAuthority` interface and its
one implementation live entirely in `vision-api`; `AssignmentRepositoryPort` was already reachable
from there, and `FlightCommandController`'s new edge gate sits strictly in front of
`DefaultFlightCommandService`'s own pre-existing scope check, not inside it). Waves B5/B6/B0b open.

**AUTH-ROLES-PLAN wave B5 done.** Sessions now survive a restart: `spring-session-jdbc` (new `pom.xml`
dependency) backs the session store with the same Postgres this app already runs against
(`spring.session.store-type=jdbc`, `application.yaml`), replacing Tomcat's in-memory session map —
`spring.session.jdbc.initialize-schema` stays `never` since Flyway owns the schema (new
`storage/persistence` migration `V34__spring_session.sql`, below) and `spring.session.jdbc.cleanup-cron`
is explicitly set to `"-"` (disabling Spring Session's own built-in `@Scheduled` cleanup cron) since
this codebase deliberately never calls `@EnableScheduling` anywhere — an honest "no automatic cleanup"
rather than a silently-inert default cron, flagged as unbounded storage growth (a hygiene concern, not
correctness: `JdbcIndexedSessionRepository.findById` already checks `expiryTime` before returning a
session as live).

New `config/properties/VisionAuthProperties` (prefix `vision.auth`, one nested `Session(Duration
idleTimeout, Duration kioskIdleTimeout, boolean cookieSecure)` record — `@DefaultValue`s `12h`/`365d`/
`false`, compact-constructor-validated positive-duration) replaces the two bare `@Value` longs
`SecuritySessionAuthenticator` used since B3 — see that class's own javadoc for why kiosk logins (top
role exactly VIEWER, see `AuthController`) get the long-lived window. **Deliberately narrower than
D2's full three-part target shape** (`enabled`+`session.*`+`password.*`): `enabled` and `password.*`
stay on their existing scattered `@Value`s/call sites this wave, deferred to a later wave —
`AuthWiringConfiguration` gained `@EnableConfigurationProperties(VisionAuthProperties.class)`.

**Session-id rotation (fixation fix).** This app's login flow calls `AuthService` directly rather than
through Spring Security's own `UsernamePasswordAuthenticationFilter`, so the `sessionManagement()` DSL's
automatic `ChangeSessionIdAuthenticationStrategy` never fires here (confirmed: `SecurityConfig` declares
no `sessionManagement()` DSL at all) — fixation protection had to be done by hand.
`SecuritySessionAuthenticator#login` now calls `request.getSession(true)` then `request.changeSessionId()`
*before* building/writing the `SecurityContext`, closing a real gap: a pre-login session id (e.g. one an
attacker planted and lured a victim into using) could previously be reused, now authenticated, after
login. Spring Session JDBC's own request wrapper implements `changeSessionId()` correctly against the
JDBC-backed store (verified: not a no-op). Cookie policy is plain Spring Boot auto-binding, no code —
new `server.servlet.session.cookie.{http-only: true, same-site: strict, secure:
${vision.auth.session.cookie-secure:false}}` block in `application.yaml`; `SecurityConfig` gained a
documentation-only javadoc section pointing at where rotation/cookie-policy/session-store each actually
live (no functional code changed in that file).

**Shared `DataSource` seam (storage/persistence).** Spring Session JDBC's autoconfiguration needs a
`DataSource`/`PlatformTransactionManager` bean to bind to, but `PersistenceUnit.start()` has always built
its own Hikari pool manually via Hibernate's native bootstrap API, never through Spring's
`DataSourceAutoConfiguration`. Resolved by exposing that pool as a reusable seam rather than building a
second, independent pool: `PersistenceUnit.buildDataSource(...)` (was private) is now public, and a new
`PersistenceUnit.start(DataSource, boolean seedDevUsers)` overload lets the caller hand in an
already-built pool instead of building one internally. `PersistenceWiringConfiguration` now builds the
pool once as its own `visionDataSource` bean (`@Bean(destroyMethod = "")` — no double-close, since
`persistenceEntityManagerFactory`'s own `destroyMethod="close"` already closes the same pool transitively
via `ClosingDatasourceConnectionProvider`, and Spring destroys a dependent bean before the bean it depends
on) and shares that one instance three ways: Hibernate/Flyway (via the new `start(DataSource, boolean)`
overload), Spring Session JDBC (Boot's own autoconfiguration binds directly to the exposed `DataSource`
bean — no explicit wiring needed), and a new `visionSessionTransactionManager` bean
(`org.springframework.jdbc.support.JdbcTransactionManager`, the sole `PlatformTransactionManager` in this
app, existing solely for Spring Session JDBC's own internal reads/writes).

`storage/persistence` gained `V34__spring_session.sql` — a byte-for-byte copy of Spring Session JDBC
4.1.0's own official Postgres schema (`SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES`, extracted from the
jar, not hand-transcribed, per this repo's Flyway-migrations-are-frozen convention). Both new tables
(Postgres folds the unquoted identifiers to lowercase `spring_session`/`spring_session_attributes`) were
added to `PostgresDockerIntegrationTest`'s `EXCLUDED_TABLES` set (this repo's own infrastructure, same
classification as `flyway_schema_history` — not domain data) — caught proactively before the live-schema
`DbAuditLogCoverageTests.everyPublicBaseTableIsEitherAuditedOrExplicitlyExcluded()` test would have failed
against the new tables.

`docker-compose.yml` gained `VISION_AUTH_SESSION_COOKIE_SECURE` (defaults `false`, same posture as every
other `VISION_*` env passthrough) — sessions live in the existing Postgres volume, no new store/volume
needed; `.env.example` documents the override (commented out) for a deployment that terminates TLS in
front of this station.

**Test-suite impact confirmed near-zero by design**: every existing `@SpringBootTest`/`MockHttpSession`
test in this module drives its `MockMvc` via `webAppContextSetup(...).addFilters(<one security filter
bean>)`, which never routes through Spring Session's own `SessionRepositoryFilter` — so none of the
~50 pre-existing session-touching tests are affected by the JDBC-backed store; they keep using MockMvc's
own in-memory `MockHttpSession` exactly as before.

New `security/SecuritySessionAuthenticatorTest` (5 cases, `vision-app`) exercises the real
`SecuritySessionAuthenticator` against a real `HttpSessionSecurityContextRepository` and Spring's mock
servlet request/response (no Spring context, no Docker): `loginRotatesTheSessionId`,
`loginPersistsTheAuthenticatedPrincipalIntoTheNewSessionId`, `normalLoginGetsTheIdleTimeoutFromProperties`,
`kioskLoginGetsTheKioskIdleTimeoutFromProperties`, `failedLoginReturnsEmptyAndNeverTouchesTheSession`.

`./mvnw -B -pl storage/persistence,station/vision-app -am test -DskipWeb` — vision-app (this module)
**333** (328 → 333, +5: exactly `SecuritySessionAuthenticatorTest`'s new cases, no other net change),
`storage/persistence` unaffected at **276** (no new test method there this wave — the migration ledger and
`EXCLUDED_TABLES` fix are exercised by pre-existing `DbAuditLogCoverageTests` methods, both confirmed
still green against the live schema, including `everyPublicBaseTableIsEitherAuditedOrExplicitlyExcluded`
and `everyAuditedTableCarriesExactlyTheAuditTriggerAndNoExcludedTableDoes`). `BUILD SUCCESS` on both
scoped runs. Docker ran for real throughout (Testcontainers `postgres:16`, Flyway migrated through `V34`,
230 nested-class test methods executed inside `PostgresDockerIntegrationTest`). `vision.auth.enabled`
stays `false` by default, unchanged this wave — `AuthDisabledSecurityTest`/`ManualControlSecurityDisabledTest`
(the default-config auth-off suites) stayed green throughout, proving the opt-in guardrail held.
`ArchitectureTest`/`ContextArchitectureTest`/`EndpointAuthorizationTest` unaffected (no new
`@RestController`, no new ArchUnit-relevant type). Deferred: `enabled`/`password.*` on
`VisionAuthProperties` (D2's full shape, left on their existing scattered `@Value`s/call sites); the SPA
logout-teardown half of §3.6 (web-side, out of this backend wave). Waves B6/B0b open.

**AUTH-ROLES-PLAN wave B6 done.** The `VisibilityScope`→`Authority` migration this wave's plan text
scoped across `contexts/{warehouse,flight,perception,learning,map,identity}` and every `vision-api`
controller/support class also reached three genuinely un-migrated call sites in this module (a
pre-existing gap, not new breakage — see the stale-jar note below for why the earlier build that should
have caught them didn't):

- **`onboarding/PassportCaptureObserver`** (production) — `capture(...)` called
  `vehicleProfileService.captureSnapshot(assetId, usageId, phase, window, PlatformActor.USER_ID,
  VisibilityScope.unbounded())`; now passes `Authority.full()`, matching `VehicleProfileService`'s
  own widened signature (`contexts/vision-flight`, this wave's own migration — see that module's
  MODULE.md).
- **`onboarding/PassportCaptureObserverTest`** — two `verify(...).captureSnapshot(...)` calls updated
  `eq(VisibilityScope.unbounded())` → `eq(Authority.full())` to match.
- **`ScopedAssetReadAuthEnabledTest`** — `setUp()`'s `groupService.create(new GroupSpec(...),
  VisibilityScope.unbounded())` and the pilot-scoped test's `assignmentService.assign(...,
  VisibilityScope.unbounded())` both now pass `Authority.full()`, matching `GroupService#create`/
  `AssignmentService#assign`'s widened signatures (`contexts/vision-identity`, own B6 migration).
- **`DevAccountSeeder`** (test-only devsupport fixture) — `seedIfAbsent` built one `VisibilityScope
  system = VisibilityScope.unbounded()` used for every gated call; now builds `Authority authority =
  Authority.full()` alongside it and passes `authority` to `groupService.create`/all three
  `userService.create` calls, while the non-gated `userService.list(system)`/`groupService.list(system)`
  reads keep the plain scope.

**Lesson worth keeping**: these four were caught only after two layers of stale-jar masking peeled
back in sequence — first `~/.m2`'s installed `adapter-persistence`/`vision-identity`/`vision-flight`
jars were stale relative to a freshly-changed upstream module (a `NoSuchMethodError` on
`PersistenceUnit.buildDataSource` visibility, unrelated to this wave, fixed by a full
`install -DskipTests` of every foundation module); *then*, with fresh jars installed, this module's own
previously-compiled test-classes were themselves stale until a `test-compile` forced a real rebuild —
only then did the four genuine gaps above surface as `NoSuchMethodError`s instead of silently compiling
green against pre-migration method signatures. A scoped single-module build can mask exactly this shape
of gap; the plan's own full multi-module `-am test` green line is what actually proves nothing was
missed — and even that caught one more gap in a sibling module (`vision-api`'s `security.StreamAccess`,
see that module's MODULE.md) on its first honest run.

`./mvnw -B -pl station/vision-app test -DskipWeb` — **333/333** green, 0 failures/errors — unchanged
from B5's own ending count (this wave repaired four existing call sites/tests, added none). Plan's full
green line (`core/vision-platform,contexts/vision-warehouse,contexts/vision-flight,
contexts/vision-perception,contexts/vision-learning,contexts/vision-map,contexts/vision-identity,
station/vision-api,station/vision-app -am test`) — **BUILD SUCCESS**, this module's 333/333 among every
other listed module's own green count (see `core/vision-platform/MODULE.md`'s B6 entry for the full
per-module tally). That build had to be run **in the foreground** to actually complete: a first attempt
was launched with `run_in_background` and reported `completed` well after the fact, but the agent turn
that started it had already ended — a backgrounded Maven run does not survive the turn that launched
it, so its log was abandoned mid-flight and only the foreground rerun (generous timeout, no
backgrounding) produced the real, trustworthy result. Docker ran for real throughout (Testcontainers
`postgres:16`, Flyway through `V34`, unchanged this wave — no new migration). `vision.auth.enabled`
stays `false` by default, unchanged — the default-config bar held throughout. Wave B0b (flip
`vision.auth.enabled`'s default) is the only item this plan still has open.

**AUTH-ROLES-PLAN wave B0b done — plan closed** (docs/plans/active/AUTH-ROLES-PLAN.md §5) — flips
`vision.auth.enabled`'s value in `application.yaml` from `false` to `true`. This closes the last gap
D1/D2 documented: `SecurityConfig`'s/`AuthWiringConfiguration`'s own compiled default was already
`true` (`matchIfMissing=true`), but this file overrode it to `false` explicitly, so every real
deployment ran auth-off regardless of the compiled default. Now both agree. `vision.persistence.
seed-dev-users` is unchanged (`false`) — a fresh clone boots with an empty `users` table and access
control on, which is exactly `BootstrapController`'s one-way latch's reason to exist (wave B3, already
shipped): `GET /api/auth/bootstrap` reports `required: true` whenever `vision.auth.enabled` is on and
no enabled user holds an `ADMIN` membership (`DefaultAuthService#adminExists()`), and `POST` creates
that first administrator over the same open endpoint — traced through both classes' current source
this wave to confirm the flip changes no code path, only which one is reachable at zero users.
`resolveRootGroup()` reuses the group V13 already seeded parentless (rather than minting a second
root), so the bootstrapped admin lands in the same fixed root every dev-mode asset is already owned
under.

D16 regression test (the one code change this wave makes, `storage/persistence`):
`UpgradePathMigrationTest#freshInstallManagerOfRootSeesAnAssetOwnedByTheDevPrincipalGroup`, a new
sibling to the pre-existing `upgradeRestoresManagerVisibilityOfDevPrincipalOwnedAssets`. Together the
two close the auth-off→auth-on visibility cliff for both shapes a real deployment can be in: a
brand-new install (V13's fixed root group directly, V90001's seeded manager already a member of it —
no legacy state to fake) and an upgrade from a database that pre-dates the fixed root (V16 adopts the
fixed group under the old random root). Both drive the real `DefaultScopeResolver` against a real
Postgres, not a hand-simulated approximation of its subtree walk.

`docker-compose.yml`'s `VISION_AUTH_ENABLED: "true"` comment rewritten to say it is now redundant with
the compiled default (kept set explicitly anyway — documents intent, survives if the compiled default
is ever revisited); the variable itself, and every other line, is untouched.

**Lost-admin-password recovery** (this wave's required Non-goals documentation): see the Gotchas entry
above.

**Explicitly deferred, unchanged by this wave** (D2): the three-way default disagreement across
`SecurityConfig`/`AuthWiringConfiguration`/`AuthController`'s scattered `@Value`/`@ConditionalOnProperty`
defaults, and building the full `VisionAuthProperties` shape (`enabled`+`session.*`+`password.*` in one
record — `VisionAuthProperties` today covers only `session.*`, wave B5). Flipping `application.yaml`'s
one explicit value was sufficient because that value — not any compiled default — is what every real
deployment actually reads; unifying the three is a separate, not-yet-scheduled cleanup.

Test counts: `./mvnw -B -pl storage/persistence test` — **277/277** green (was 276/276; +1 new test —
`UpgradePathMigrationTest` alone: 5/5, was 4/4). `./mvnw -B -pl station/vision-app test -DskipWeb` —
**333/333** green, unchanged from B6's own ending count — proving the plan's own "Zero other test
impact" claim: `src/test/resources/application.properties` already pinned `vision.auth.enabled=false`
for every `@SpringBootTest` in this module before this wave landed (added ahead of time, anticipating
exactly this flip), so the compiled-default change altered no test's effective configuration.
`vision-api` has no `@SpringBootTest` context of its own bound to this property (every
`vision.auth.enabled` reference there is javadoc/comment prose, confirmed by inspection this wave) and
was not rebuilt. Docker ran for real throughout (Testcontainers `postgres:16`, Flyway through `V34`, no
new migration this wave — the D16 test needed no schema change). Both builds ran in the foreground with
an explicit generous timeout, never backgrounded.

AUTH-ROLES-PLAN.md is now fully closed — B0b was its last open wave.

**B5-fix (2026-09-05): live verification found wave B5 broken in production, two stacked defects.**
"Fully closed" above spoke too soon — B0b's own green suite could not have caught either defect, since
neither one is observable through this module's test doubles; both only surface against a real socket
and a real Postgres connection with the JDBC session store genuinely active.

*Defect 1 — the session store was inert.* `pom.xml` depended on the bare
`org.springframework.session:spring-session-jdbc`, not Boot's own `org.springframework.boot:
spring-boot-session-jdbc`. Spring Boot 4 moved session auto-configuration out of
`spring-boot-autoconfigure` into per-technology modules (the same restructuring that moved
`TestRestTemplate` out of `spring-boot-test`, below) — the bare library sits on the classpath with no
`SessionRepositoryFilter` ever registered, so Tomcat silently kept serving in-memory `JSESSIONID`
cookies the whole time. `spring_session` stayed at 0 rows forever; `spring.session.store-type`
has not existed since Boot 3 and was dead config, now removed from `application.yaml` along with the
stale "store-type=jdbc backs..." comment line that referenced it. Fixed by depending on
`spring-boot-session-jdbc` instead — no other change needed, Spring Session's own JDBC schema/table
names are unaffected.

*Defect 2 — the principal graph wasn't `Serializable`.* With the JDBC store genuinely active,
`POST /api/auth/login` started 500ing: `SerializationFailedException` /
`NotSerializableException: com.drones.vision.kernel.Ownership`. Spring Session JDBC java-serializes
the whole `SecurityContext` into `spring_session_attributes`, and `VisionUserDetails` (this module's
`UserDetails` principal) holds a plain `User` plus a derived `Ownership` — neither was `Serializable`.
Fixed at the source, not here: `core/vision-kernel`'s `UserId`/`GroupId`/`Ownership` and
`contexts/vision-identity`'s `User`/`Membership` all gained `implements java.io.Serializable` (see
those modules' own MODULE.md entries for the root-cause writeup) — nothing in this module's own
source changed for Defect 2, only its test suite gained the regression coverage below.
`SecurityContextPrincipalResolver` never held `VisibilityScope`/`Authority` in the session at all
(both are recomputed per-request from `ScopeResolver`, already the "slim principal, re-resolve per
request" shape CLAUDE.md rule 9 favors) — so the fix's blast radius is exactly `User`+`Ownership`'s
own reachable graph, not the wider principal-adjacent type set the live-verification report
considered making Serializable.

*Why `AuthEnabledFlowTest` never caught either defect.* That test builds its `MockMvc` by hand —
`MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(springSecurityFilterChain)` —
which registers exactly one filter bean, the named Spring Security chain. Boot's own
`SessionRepositoryFilter` (a `FilterRegistrationBean` against the real `ServletContext`, which only
`@AutoConfigureMockMvc` or a real embedded server picks up) is never one of the filters handed to
`addFilters(...)`. `request.getSession(...)` in that test therefore resolves to a plain
`MockHttpSession` the servlet-mock layer invents on the spot — never a JDBC-backed `Session` — so
`HttpSessionSecurityContextRepository#saveContext` just calls `setAttribute` on that mock object and
Java serialization is never invoked at all. This is not a lazy-flush timing gap; MockMvc's manual
filter list structurally cannot reach `SessionRepositoryFilter`, so no additional assertion inside
`AuthEnabledFlowTest` itself could have caught this.

**New regression test, `SessionPersistenceIntegrationTest`** — the one this module needed and did not
have: `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {"vision.auth.enabled=true", ...})`,
a real embedded server, a real login over HTTP, asserting (1) the login response is `200` (a `500`
here fails for the same reason production did), (2) the `Set-Cookie` response carries a `SESSION`
cookie and no `JSESSIONID` (proving `SessionRepositoryFilter` actually engaged, not Tomcat's own
container-level session), and (3) `select count(*) from spring_session` is `> 0` after login (a row
genuinely landed). Deliberately its own `@SpringBootTest` context (`RANDOM_PORT`, unlike every other
class's default `MOCK`) so it is never folded into the cached context `AuthEnabledFlowTest` and its
siblings share via `PostgresContextCustomizerFactory`/`PostgresResetTestExecutionListener` — this is
the one test in the module that actually needs a live socket.

**`TestRestTemplate` does not exist in Boot 4** (a second instance of the same "moved out" pattern as
Defect 1): `spring-boot-test-4.1.0.jar` has no `org.springframework.boot.test.web.client` package at
all — confirmed by inspecting the jar directly, present as recently as `spring-boot-test-3.5.3.jar`
and gone by `4.0.0`. Its Boot-4/Spring-Framework-7 replacement is `org.springframework.test.web.
servlet.client.RestTestClient` (`spring-test`, the same artifact `MockMvc` already lives in — no
`pom.xml` change needed): `RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()`
for a real live-server round trip (there is also `.bindTo(MockMvc)` for an in-process variant, unused
here since a live socket is the whole point). `@LocalServerPort` itself is unaffected by the
restructuring — still `org.springframework.boot.test.web.server.LocalServerPort`. Its fluent API
mirrors `WebTestClient`'s shape (`.post().uri(...).contentType(...).body(...).exchange()`, returning a
`ResponseSpec`); `.exchange().returnResult()` hands back a plain `ExchangeResult` with `getStatus()`/
`getResponseCookies()` for a JUnit-assertion style, used here instead of the fluent
`expectStatus()...` chain to keep the test's assertions in the same idiom as the rest of this module's
suite.

Test counts: `./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,
contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,
contexts/vision-events,contexts/vision-learning,contexts/vision-simulation install -DskipTests` —
**BUILD SUCCESS**, all ten (adding `Serializable` to five types elsewhere is source-compatible, no
downstream module needed a code change). `./mvnw -B -pl core/vision-kernel,contexts/vision-identity
test` — **153/153** green, unchanged (neither module's own suite serializes these types). `./mvnw -B
-pl storage/persistence,station/vision-api,station/vision-app install -DskipWeb` —
**BUILD SUCCESS**: `adapter-persistence` **277/277** (unchanged from B0b), `vision-api` **985/985**
(untouched by either defect — every `vision.auth.enabled` reference there is javadoc/comment prose,
no `@SpringBootTest` binding), `vision-app` **334/334** (**333/333** unchanged +1 new —
`SessionPersistenceIntegrationTest`). Docker ran for real throughout (Testcontainers `postgres:16`).
Every build ran in the foreground with an explicit generous timeout, never backgrounded.
**SOURCE-ONBOARDING-2-PLAN.md §3.2 wave C done (2026-09-05, uncommitted at time of writing).** Six wire
contracts (C1-C6) landed against `vision-api`'s `DiscoveryInboxController`/`SystemNetworkController`/new
`DiscoveryStatusController` (see that module's own MODULE.md for the endpoint/DTO/SSE shapes themselves) —
this module's share is the wiring and property surface those controllers cross into:

- `LiveUpdateDiscoveryInboxService` (new, `events/` package) decorates `DiscoveryInboxService` to publish
  a `discovery` SSE delta on every `attach`/`dismiss`/`restore`. **Deliberate deviation from this module's
  own established decorator pattern**: every other `LiveUpdate*` decorator (`LiveUpdateAuditTrail`,
  `LiveUpdateEventPublisher`, `LiveUpdateDetectionEventRepository`) depends on a context-owned port that
  vision-warehouse itself declares; this one depends directly on the concrete `com.drones.vision.api.live.LiveUpdateRegistry`
  class instead of a new `DiscoveryLiveUpdatePort` vision-warehouse would have to own. Justified on file-scope
  grounds (adding a port to vision-warehouse was out of this wave's disjoint file scope) and recorded in the
  class's own javadoc — a candidate for follow-up cleanup, not a design endorsement.
- `DiscoveryInboxWiringConfiguration#discoveryInboxService` now wraps its delegate in the above decorator,
  gated by `VisionDiscoveryProperties.Live#enabled()` (see that properties-table row below); its constructor
  widened to 6 params fixing a pre-existing `DeviceService` ctor-arity bug uncovered while wiring this
  through. `DiscoveryInboxRunner` gained a `lastSweepAt()` accessor (last **completed**, not attempted,
  sweep) feeding C2's `GET /api/discovery/status`.
- `DiscoveryWiringConfiguration` gained three beans for `SystemNetworkController`/`DiscoveryStatusController`
  (C3/C2): `videoPushPort`/`videoPushPathPrefix` (both `@ConditionalOnProperty(vision.publish.enabled,
  matchIfMissing=true)` — genuinely absent, not null-valued, when mediamtx publish is off) and
  `discoveryStatusFacts` (unconditional `Supplier<DiscoveryStatusFacts>`, taking two `ObjectProvider`s for
  `DiscoveryInboxRunner`/`MediamtxPathScanner` since either may be conditionally absent). See the Gotchas
  entry below for the null-bean bug this uncovered and its fix.
- `ApplicationServiceWiring` gained a 7th `streamService` selector, `streamStateObserver` (C6): `NOOP` unless
  both `VisionLiveProperties#enabled()` and its new nested `streamStatePush().enabled()` are `true`, otherwise
  a lambda calling `liveUpdateRegistry.publishDevicesSnapshot()` on every computed `StreamState` transition.
  Threaded into `DefaultStreamServiceSettings`'s 7th field — no new constructor overload, per CLAUDE.md rule 10.
- `VisionLiveProperties` widened from a single `boolean enabled` to `(enabled, streamStatePush)` with a new
  nested `StreamStatePush(boolean enabled)` record, default `true`. No back-compat 1-arg convenience
  constructor added — confirmed via grep that zero existing call sites needed one.
- `VisionDiscoveryProperties` gained a 7th canonical-constructor component, `Live(boolean enabled)`, default
  `true` — gates the `LiveUpdateDiscoveryInboxService` wrap above. Ships on by default (D8: this wave's whole
  point is discovery events being live, so shipping the flag off would defeat the plan it implements) — the
  same accepted exception to the repo's usual opt-in-guardrail default that `Lobby`/`Inbox`/`Mediamtx` already
  established for this record.

Two pre-existing test-compile breaks were found and fixed while getting this wave green, both call sites
that had not been updated for an earlier wave's own change, not regressions introduced here:
`VisionDiscoveryPropertiesTest`'s two direct 6-arg `VisionDiscoveryProperties` constructor calls (widened to
7 args, trailing `null` for the new `live` component — no new test cases, this record's own default/validation
behaviour for `Live` needed none beyond what the compact constructor already covers by symmetry with its five
siblings); `DiscoveryInboxRunnerTest#reportsEveryDiscoveredDeviceFromTheScan`'s mock stub returning a bare
`DiscoveryCandidate` from `discoveryInboxService.report(...)`, whose return type an earlier wave (B1) had
already widened to `ReportOutcome` — wrapped in `new ReportOutcome(candidate, true)`.

Build required two upstream reinstalls beyond CLAUDE.md's own documented recipe, both from a concurrently-running
sibling agent's rebuild of the shared `~/.m2` from a *different* checkout, not from any edit in this wave (see
the two new Gotchas entries above for the general lesson): first `./mvnw -B -pl core/vision-kernel,core/vision-platform,contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation install -DskipTests`
(a concurrent agent's rebuild of vision-identity/vision-platform from the main checkout had overwritten shared
SNAPSHOT jars with an incompatible `VisibilityScope`→`Authority` API), then
`./mvnw -B -pl simulation-sources/sim,video-input/rtsp,video-input/mjpeg,drone-link/mavlink,video-input/v4l2,video-output/publish-hls,cv/grpc,cv/tiles,device-discovery/onvif-mdns-v4l2,storage/persistence -am install -DskipTests`
(a stale `adapter-mavlink` jar predated this worktree's own already-committed `MavlinkIntakeStatus` class).
Both BUILD SUCCESS, reinstalling from this worktree's own source.

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test -DskipWeb` — green throughout:
`storage/persistence` **274** (unchanged), `station/vision-api` **965** (+11 over the 954 baseline —
`DiscoveryInboxControllerTest`'s new `attach`/`restore` cases, `SystemNetworkControllerTest`'s widened
assertions, `DiscoveryStatusControllerTest`, `LocalNetworkAddressesTest`'s `kind` cases — see that module's
own MODULE.md for the exact breakdown), `station/vision-app` **326** (unchanged — this wave's only two
vision-app test-file touches were the call-site fixes above, zero new test methods). A benign stack trace
from a background `DiscoveryInboxRunner` sweep hitting a torn-down Postgres Testcontainer during test teardown
(`relation "discovery_candidates" does not exist`) appeared in the log — confirmed inside `sweepSafely()`'s
already-caught `RuntimeException` clause, the same known-benign async-teardown race prior waves have already
logged, not a test failure (aggregate summary: "Tests run: 326, Failures: 0, Errors: 0"). Docker ran for real
throughout (Postgres Testcontainer backing `storage/persistence`'s 274 tests). Nothing deferred on this
module's side.

**CREW-CONTROL-PLAN.md wave W2 done (2026-09-05, uncommitted at time of writing, branch
`feat/crew-control`).** New `SeatWiringConfiguration` (2 beans, see the wiring-map table above) —
unconditional `seatService`, and `seatAccessSettings` bridging `VisionCrewProperties` into vision-api's
plain `SeatAccessSettings` mirror. `ApplicationServiceWiring#manualControlService` split off
`defaultManualControlService` (see "Application-service beans" above) to register the flag-gated
RC-release hook without adding a sixth parameter to the canonical 7-arg constructor. New
`VisionCrewProperties` (`vision.crew.*`, see the properties table above), already present in
`application.yaml` with `enabled: false` (default, unchanged from before this wave — the opt-in
guardrail). No ArchUnit change needed: `SeatController` lives in `api.controller` like every other
`@RestController` (`restControllersLiveOnlyInApiControllerOrProxyPackage` already covers it), and
`EndpointAuthorizationTest`'s BFS mechanism already recognizes any collaborator whose name ends
`Access` — `SeatAccess` needed no new `TEMPORARY_UNSCOPED` entry, confirmed by inspection of that
test's own mechanism before starting.

**Deviation, unrelated to CREW-CONTROL, found and fixed to unblock this wave's build:** a genuine
**pre-existing** compile defect in `stream/LiveFrameFallbackStreamService` — missing the
`StreamService#followStatus` override. `git blame` confirmed this decorator was never updated when an
already-merged, unrelated commit (`29536635 feat(track-follow W2): FollowTracker state machine +
StreamService#followStatus`) widened the interface; neither this class nor
`contexts/vision-perception`'s `StreamService` had any uncommitted change on this branch before this
fix. Fixed minimally — a one-line pure delegation to `delegate.followStatus(streamId)`, matching every
other method on this class (see that class's own javadoc, "every other method delegates unchanged").

`./mvnw -B -pl contexts/vision-flight,station/vision-api,station/vision-app test -DskipWeb` —
`station/vision-app` **334** (unchanged from the prior entry's 326 baseline plus intervening waves —
no test file in this module's own scope was touched this wave; the only vision-app source change was
the `LiveFrameFallbackStreamService` one-method fix and `ApplicationServiceWiring`/
`SeatWiringConfiguration` wiring, neither of which added or removed a test method). See
`station/vision-api/MODULE.md`'s own CREW-CONTROL W2 entry for that module's exact +37 test-count
breakdown and the guard-insertion-point list (5 REST controllers + the WS handler); `contexts/vision-flight`
stayed at its pre-existing count (447, W2's file scope excludes that module — W1 already shipped the
domain model it consumes). All green, 0 failures/errors. Docker ran for real (Testcontainers
`postgres:16`, Flyway migrated through `V34`). Default-config guardrail held throughout:
`vision.crew.enabled` defaults `false`, `seatService` is wired but inert, and every pre-existing test
across all three modules stayed green unmodified under that default. Nothing deferred from this
module's own scope; W3 (crew UI, vision-web) is a separate, concurrently-running agent's file scope.

### 2026-09-06, E2E-FLOW-AUDIT U1 — eight shipped-but-dark features switched on in the deployed config

The audit (`docs/plans/active/E2E-FLOW-AUDIT-2026-09-05.md`, proposal U1) found eight features that
are built, tested and merged but unreachable on any real server: their compiled defaults in
`application.yaml` are `false`, and nothing in `docker-compose.yml` overrode them. `docker-compose.yml`
now sets all eight — `VISION_CREW_ENABLED`, `VISION_ONBOARDING_PROBE_ENABLED`,
`VISION_ONBOARDING_PASSPORT_ENABLED`, `VISION_GEO_FIXED_CAMERA_ENABLED`, `VISION_TRAINING_ENABLED`,
plus the three the owner took as explicit judgment calls: `VISION_API_RATE_LIMIT_ENABLED`,
`VISION_GEO_VISUAL_ENABLED`, `VISION_ONBOARDING_REMEDIATE_MESSAGE_INTERVAL_ENABLED`.

**No compiled default changed, and no test was touched.** That split is the point: `application.yaml`
describes what a fresh build does (~26 `@SpringBootTest` classes and every `mvn spring-boot:run`
depend on those `false`s), while `docker-compose.yml` describes what the *run* does — CLAUDE.md's
"Deployment maintenance" rule. Flipping the compiled defaults instead would have re-armed a large
test surface for no deployment benefit.

Two preconditions were checked against the same file rather than assumed, since both are stated in
`application.yaml`'s own comments: `crew` and `api.rate-limit` each require `vision.auth.enabled=true`
(rate limiting keys buckets on the acting principal, so with auth off the whole deployment shares one
bucket) — satisfied by the pre-existing `VISION_AUTH_ENABLED: "true"`; and `onboarding.passport` is
inert without `onboarding.probe`, so the two flip together and the app's own
"never silently inert" startup WARNING stays quiet.

**Env-var spelling gotcha.** Three of these keys are hyphenated (`vision.geo.fixed-camera.enabled`,
`vision.api.rate-limit.enabled`, `vision.onboarding.remediate.message-interval.enabled`). Spring maps
a hyphenated property to *two* env-var candidates — `Form.UNIFORM` (dashes removed:
`VISION_API_RATELIMIT_ENABLED`) and the legacy name (dashes → underscores:
`VISION_API_RATE_LIMIT_ENABLED`). Both bind; this file uses the second, the convention the
pre-existing `VISION_PERSISTENCE_SEED_DEV_USERS` → `vision.persistence.seed-dev-users` mapping
already established. Guessing here is how a flag ships green but inert, so the precedent was verified
before writing rather than after.

`vision.geo.visual.enabled` is the one flag with a real steady-state cost — it opens a gRPC geo
session per flying asset with a resolvable stream and pulls keyframes from mediamtx — so cv-service
load is worth watching after the first redeploy with this config.

---

## ALWAYS-ON-FLOW wave A — `TelemetryPinRunner` (2026-09-06)

`docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` wave A1's scheduling half. The owner's report was that
*stopping a stream stops the telemetry*; verified exactly (`ALWAYS-ON-FLOW-CONTEXT.md` findings 1–2),
and worse than reported — `IdleStreamReaper` makes the teardown automatic after 10 minutes with no
viewer, with defaults live in production because `vision.streams.*` is overridden nowhere.

`usage.TelemetryPinRunner` is this module's **third** hand-rolled sweep runner, built to the same
shape as `usage.UsageIdleCloseRunner` and `discovery.DiscoveryInboxRunner` (own single-thread daemon
`ScheduledExecutorService`, `AtomicBoolean` start/close latches, `lastSweepAt()` stamped only on a
sweep that completed, every sweep body wrapped so one throw cannot cancel `scheduleAtFixedRate`).
This codebase still uses no `@Scheduled`/`@EnableScheduling` anywhere.

**What one sweep does:** lists `AssetService#assets()`, calls `UsageTracker#pinTelemetry` for every
`isActive()` asset, and `#unpinTelemetry` for everything it had pinned that no longer qualifies.

**Why a reconciler and not an event listener.** The desired set changes for reasons this class cannot
observe — an asset registered, deactivated, deleted, or a telemetry-capable device attached. A
periodic diff converges on all of them through one code path and, unlike a subscription, self-heals
after a missed event, a restart, or a source that failed to open on an earlier tick. Deletion in
particular is observed as an *absence* from the listing, never as an event. `#pinTelemetry` is
idempotent precisely so this can run every sweep; re-pinning is also how a newly attached device is
picked up.

**The distinction that makes this safe: pinning opens a LINK, never a FLIGHT.** No `AssetUsage` is
opened, so a pinned-but-unengaged aircraft writes no per-usage telemetry rows — its samples update
`latestTelemetry`, publish to whoever is watching, and are evaluated for geofence breaches, and
nothing else. Opening a session stays explicit (`POST /api/assets/{id}/session`) or stream-triggered,
unchanged. Unpinning is likewise conservative — it tears nothing down that an active device or an
`OPERATOR`-origin usage still needs, so an asset deactivated mid-flight keeps its telemetry until the
flight ends.

**Cost.** One telemetry subscription per `TELEMETRY`-capable device of an in-service asset. The UDP
socket layer is shared and already always-on and reference-counted (`DiscoveryInboxRunner` holds the
`:14550` lobby at boot; `MavlinkGateway#unregister` refuses to close a held socket), so this adds
*claims*, not sockets — which is why wave A is M-effort rather than a rewrite. One asset listing per
tick, plus one asset lookup per in-service asset: `UsageTracker#pinTelemetry` re-reads the asset so a
device attached since the last sweep is picked up, so a sweep is database work, not purely in-memory
— N/30 lookups per second at the default cadence, negligible here but worth knowing before shortening
`sweep-interval`.

`./mvnw -B -pl station/vision-app -am test -DskipWeb` — `TelemetryPinRunnerTest` 6 tests, all green
(one sweep pins every in-service asset; repeated sweeps re-pin idempotently and never unpin; an asset
that leaves the listing is unpinned; a deactivated asset is unpinned; a throwing sweep is swallowed
and stamps no timestamp; `start()` is a no-op while disabled). Each test drives one deterministic
`sweepSafely()` rather than racing the scheduler — this runner's contract is entirely about *which*
assets a pass pins, so the background timing `UsageIdleCloseRunnerTest` exercises would add only
flakiness.

**Config.** `vision.telemetry.always-on.{enabled,sweep-interval}`, documented block in
`application.yaml` with the compiled default `false` preserved, and `VISION_TELEMETRY_ALWAYS_ON_ENABLED:
"true"` in `docker-compose.yml` — same split the U1 flag wave above established. The env-var spelling
gotcha above applies: `always-on` is hyphenated, and this file uses the legacy dashes-to-underscores
form (`..._ALWAYS_ON_ENABLED`), not `Form.UNIFORM`'s `..._ALWAYSON_ENABLED`. Both bind; the legacy
form is this repo's convention.

The context-module half (`UsageTracker#pinTelemetry`/`#unpinTelemetry`, the `#applySample` sink split,
and the `deviceStreamStopped` teardown guard) is in `contexts/vision-perception/MODULE.md`, including
the **doctrine change** wave A2 makes. Wave B3 (below) is built; B1/B2 and waves C/D are not started.

---

## ALWAYS-ON-FLOW wave B3 — durable `Event` history + `GET /api/system/events` (2026-09-06)

`docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` wave B3: `platform.Event` (device online/offline, stream
started/stopped, pipeline errors, geofence/battery/link alerts) had no REST endpoint and no durable
store — the notification bell and `/manage/system` are a pure `computed` over the current
`EventSource`'s in-memory log, so they started empty on every page load and lost everything on an SSE
reconnect. Backend only, per the plan's own domain split (Wave B's B1/B2 and every UI consumer are a
later/parallel wave — `station/vision-web` untouched by this task).

**The port.** `EventHistoryPort` (`core/vision-platform`, own MODULE.md entry) is modeled directly on
`AuditTrailPort`'s query surface and no wider: `record`, `findRecent(limit)`, `findSince(sinceInclusive,
limit)`, both newest-first, both table-wide. Unlike `AuditTrailPort`'s "everything is recorded"
contract, the port's javadoc explicitly allows an implementation to selectively exclude a high-volume
`EventType` — see below.

**The adapter.** `storage/persistence`'s `JpaEventHistory` (`V35__event_history.sql`, own MODULE.md
entries) — always `persist`, table-wide prune-on-write (default 20,000 rows total, not per-stream:
most persisted `EventType`s carry no `streamId` to group by, so a per-stream cap would leave most rows
uncapped). **Single constructor**, `(EntityManagerFactory, int retentionLimit)` — deliberately not
`JpaDetectionRepository`'s 1-arg-default-constant-plus-2-arg-test-seam shape, since
`VisionEventHistoryProperties.Retention` is now the one source of truth for the default; a second,
baked-in constant inside the adapter would just be a second place for that number to drift (CLAUDE.md
rule 1). **Deviation from the literal brief, disclosed**: the brief asked for "a JPA impl and an
in-memory devsupport impl". `station/vision-app/MODULE.md`'s own documented convention (see
"devsupport fallbacks" above) is that no repository-shaped port gets an in-memory fallback —
Postgres is the only store, decided by POSTGRES-ONLY-CONTEXT.md — and `AuditTrailPort`/
`DetectionEventRepositoryPort` (this wave's own precedents) are wired the same unconditional-`Jpa*`
way. Building an `InMemoryEventHistory` would have been new, unprecedented scope this codebase
deliberately doesn't carry anywhere else, so none was built.

**Keeping the durable write off the hot path.** `events.PersistingEventPublisher` decorates
`EventPublisherPort` (`ApplicationServiceWiring#eventPublisherPort`, own entry above) rather than
teaching any of the 9 real `EventPublisherPort#publish` call sites a new collaborator (CLAUDE.md rule
10) — the same shape `LiveUpdateEventPublisher`/`DetectionSessionCleanupEventPublisher` already use.
Two decisions handle write volume:
1. **`EventType.DETECTION` is never even offered to `EventHistoryPort`.** It is this codebase's sole
   hot-path/high-volume event type (raised once per non-empty inference result,
   `StreamPipeline`); every other type is edge-triggered (a link/battery/geofence transition, a
   stream lifecycle change) and low-frequency. Detections are already durable elsewhere
   (`DetectionRepositoryPort`/`DetectionEventRepositoryPort`) and already excluded from the web
   bell's own `systemEventRows` filtering — persisting them again into `event_history` would 100x
   this table's write rate for a type nothing reads back through this port.
2. **Every remaining write runs on its own virtual thread**
   (`Executors.newVirtualThreadPerTaskExecutor()`, the same idiom `LiveUpdateRegistry`'s
   `connectionWriteExecutor` already established for "don't block a hot caller on I/O"), wrapped in a
   try/catch that swallows any `RuntimeException` and logs at `WARNING` via `System.Logger`. The
   delegate's synchronous `publish` always completes and returns first; a durable-write failure
   (a DB outage) never reaches the caller — `PersistingEventPublisherTest`'s
   `aFailingDurableWriteNeverPropagatesToTheCaller` stubs `EventHistoryPort#record` to throw and
   asserts `publish` still returns normally, using `Mockito.timeout(2000)` rather than a synchronous
   `verify` since the write genuinely races the assertion on another thread (`PassportCaptureObserverTest`'s
   established idiom for this exact situation).

**Retention.** `VisionEventHistoryProperties` (`vision.events.history`, own table entry above): `enabled`
(default `false`, the opt-in guardrail — the port bean always exists, only the recording decorator is
gated) and `retention.max-rows` (default `20,000`). The cap is deliberately global, not per-`EventType`
or per-stream — see `JpaEventHistory`'s own javadoc and `storage/persistence/MODULE.md`'s Retention
section for why a keyed cap would under-serve the majority of rows here.

**The endpoint.** `vision-api`'s `SystemEventsController` — `GET /api/system/events?sinceMs&limit`
(`sinceMs` an epoch-millis cursor, optional; `limit` default 50) — returns `List<EventResponse>`
newest-first via `findSince`. **Scoping decision, stated in the controller's own class javadoc**:
`@OpenByDesign`, not `VisibilityScope`-filtered and not added to `EndpointAuthorizationTest`'s legacy
`TEMPORARY_UNSCOPED` ledger. Reasoning: this endpoint durably replays exactly what the already-unscoped,
always-on `event` SSE topic (`EventLiveUpdatePort`, documented in `vision-api`'s own MODULE.md as
needing no auth beyond the connection itself) already broadcasts to any connected caller; gating the
replay tighter than the live feed it backfills would 403 a caller for re-requesting what they were
already shown live — the opposite of what this wave exists to fix. Per-event `VisibilityScope`
narrowing was considered and rejected: most persisted `EventType`s carry no `streamId`/asset key to
filter by at all, and stream-scoped types can outlive the stream's own live-registry entry, leaving no
ownership to resolve against. `IllegalArgumentException` on a non-positive `limit` maps to the
existing 400 (`ApiExceptionHandler`) — no new exception mapping needed.

**Deviation noted, not fixed (pre-existing, out of scope for this wave)**: `SystemStatusController`/
`SystemNetworkController` are prose-documented as "deliberately open" but are actually exempted only
via the legacy `TEMPORARY_UNSCOPED` ledger, not `@OpenByDesign` — `SystemEventsController` uses the
correct, intentional mechanism instead (matching `DeviceProbeController#probe`'s own precedent) rather
than replicating that inconsistency.

**Config.** `vision.events.history.{enabled,retention.max-rows}`, documented block in `application.yaml`
(default `enabled: false`, `retention.max-rows: 20000`) and `VISION_EVENTS_HISTORY_ENABLED: "true"` in
`docker-compose.yml`, same split as wave A's `VISION_TELEMETRY_ALWAYS_ON_ENABLED`.

**Tests.** New: `EventHistoryPort`'s JPA adapter (`PostgresDockerIntegrationTest$EventHistoryRepositoryTests`,
6 cases — round-trip incl. null `streamId`, round-trip with a `StreamId`, `findRecent` newest-first
across every type, `findSince` cursor inclusivity, `findSince` with a null cursor, table-wide retention
pruning verified against far-future timestamps so it cannot collide with a sibling test's rows in the
same shared table — the shared-table filtering technique this class's own tests use throughout mirrors
`AuditTrailRepositoryTests`'s precedent, `storage/persistence`), `PersistingEventPublisherTest` (4
cases, `station/vision-app`), `VisionEventHistoryPropertiesTest` (6 cases, `station/vision-app`),
`SystemEventsControllerTest` (6 cases, MockMvc `standaloneSetup`, no `CurrentUser` collaborator needed
since the endpoint is `@OpenByDesign` — `station/vision-api`).

`./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app -am test -DskipWeb` — **BUILD
SUCCESS** across the full 26-module reactor (`-am` pulls in every upstream module; all green,
`vision-platform` 25/25 unaffected by this wave's javadoc-only edit there), Docker ran (not skipped —
real Testcontainers `postgres:16`, Flyway migrated through `V35`). This wave's own new test methods:
6 in `storage/persistence` (`EventHistoryRepositoryTests`, part of that module's 283-test total, up
from 277 immediately before this wave), 6 in `vision-api` (`SystemEventsControllerTest`, part of its
1052-test total), 10 in `vision-app` (4 `PersistingEventPublisherTest` + 6
`VisionEventHistoryPropertiesTest`, part of its 350-test total) — 22 new test methods overall. No
isolated "before" count exists for `vision-api`/`vision-app`'s own totals specific to just this wave
(this branch already carries other concurrent waves' tests), so those two are reported as their
current green total rather than a before/after delta. `EndpointAuthorizationTest` itself: 2/2,
confirming `SystemEventsController#recent`'s `@OpenByDesign` is recognized with no new
`TEMPORARY_UNSCOPED` ledger entry needed.

## ALWAYS-ON-FLOW wave D1/D2 — per-asset `DetectionPolicy` + the three-question gate (2026-09-06)

This module's half of `docs/plans/active/ALWAYS-ON-FLOW-PLAN.md` waves D1 (a per-asset opt-in to
inference regardless of viewer demand) and D2 (splitting `StreamPipeline`'s single detection gate into
inference/durable/live) — the domain/application-layer half lives in `contexts/vision-perception`, see
that module's own MODULE.md Status entry for the gate-mechanics detail. This wave's file scope was
`contexts/vision-perception`, `station/vision-api`, `station/vision-app`; `vision-api` needed **zero**
code changes (the existing generic `PATCH /api/assets/{id}` `attributes` map already round-trips
`DetectionPolicy.ATTRIBUTE_KEY`, so no new endpoint/DTO/exception mapping was added).

**New in this module**: `cv/DetectionPolicyCache` (self-scheduled `AutoCloseable`, mirroring
`TrackProjectionRunner`'s own caching precedent — `volatile Set<AssetId>` snapshot, refreshed once per
tick from `AssetService#assets(false)`, filtered by `DetectionPolicy.ATTRIBUTE_KEY`); `CvWiring`'s two
new beans, `detectionPolicyCache` (conditional, same "CV switched on at all" expression as
`cvChannelSupervisor`) and `detectionPolicyPort` (unconditional, `ObjectProvider`-lazy lambda, fails
closed when the cache is absent); `VisionCvProperties.Policy(Duration refreshInterval)` (22nd canonical
constructor component, default 15s) — see the properties table and `CvWiring` row above for the full
detail on each.

**The one deliberate deviation from the wave's literal wording, flagged as instructed**: D1's task
text described the policy check as "a fourth OR-term in `LiveAndPollDetectionDemand`." This was not
done. `DetectionPolicyPort` is a wholly separate port instead, consulted independently by
`DefaultStreamService`/`StreamPipeline` — see `vision-perception`'s own MODULE.md Gotchas for the
reasoning (D2's live-gate-must-close-independently-of-inference requirement cannot be expressed if the
policy opt-in is folded into the same boolean live already reads). `LiveAndPollDetectionDemand` itself
is untouched by this wave.

**Deliberately deferred, out of scope**: a fleet-wide inference budget (plan wave D3) —
`maxInFlightInferences` stays a fixed `2`/stream constant, no new property key. Considered and rejected
for this pass: adding one without the budget it would feed into would be a knob nobody could safely
turn, so the constant stays a constant until D3 gives it something to answer to. See
`vision-perception`'s MODULE.md Gotchas for the capacity risk this leaves open (`ALWAYS` is opt-in per
asset but not capacity-aware — an operator can drive concurrent inference past one `cv-service`
instance's ~3-4 stream/10fps ceiling with nothing to warn or throttle across streams).

**A real regression this wave introduced, found and fixed** — `TrackingAssociateE2ETest` was
initially reported as a pre-existing load-dependent flake, on the strength of two ablations that each
cleared their target. It was not. D2's first cut ran the durable plane before the live plane in
`onDetectionResult`, putting a synchronous database write between a detection completing and the read
models a poll observes; a baseline run at the preceding commit was 350/350 green under identical load,
and restoring live-plane-first made the failure vanish (353/353). See the Gotchas entry above — both
for the ordering constraint, which is now load-bearing, and for why the ablations were misleading.

**No docker-compose.yml env var added** for `VisionCvProperties.Policy.refreshInterval` — evaluated
against the "new behavior changing deployment cost needs a compose env var" convention and found not
to qualify: the 15s default is deployment-topology-independent (no host/network addressing, unlike
e.g. `VISION_CV_PULL_RTSP_BASE`), matching the precedent already set by every sibling per-tick tunable
(`Profiles.cacheTtl`, `detectionDemandGrace`, `detectionDemandPollInterval`) — none of which have a
compose-file line either. Spring's own relaxed env-var binding
(`VISION_CV_POLICY_REFRESH_INTERVAL`) still works without one.

`./mvnw -B -pl contexts/vision-perception,station/vision-api,station/vision-app -am test -DskipWeb` —
per-module results: `vision-perception` **717 → 730, all green** (13 new: 5 `DetectionPolicyTest`, 6
`StreamPipelineTest`, 2 `DefaultStreamServiceTest`); `vision-api` **1052/1052, unchanged** (no code
touched); `vision-app` **350 → 353, all green** (3 new: `CvWiringTest` ×2 — `DetectionPolicyCache`
absent by default, `DetectionPolicyPort` present and reading `false` for every asset by default —
`CvEnabledWiringTest` ×1 — `DetectionPolicyCache` present when CV is switched on). Green only after
the `onDetectionResult` ordering fix above; the run before it failed `TrackingAssociateE2ETest`.
Docker ran, not skipped (real Testcontainers `postgres:16`, Flyway through `V35`, same as every other
wave in this file).

## LIVE-POLL-RETIREMENT-PLAN waves L3+L4 — this module's wiring half (2026-09-06, branch `feat/live-topics-zones-system`)

This module's file scope was the wiring/devsupport half of both waves — the domain/port/service
changes live in `contexts/vision-flight` (L3) and `station/vision-api` (both), see those modules' own
MODULE.md entries for the full topic/self-feedback-mitigation writeup.

**L3 (`zones` topic)**: `ApplicationServiceWiring` gained a seventh live-server-push selector,
`geofenceLiveUpdatePort(VisionLiveProperties, @Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry>)`
— byte-identical in shape to the six preceding it, `NoopLiveUpdatePublisher` fallback when
`vision.live.enabled=false`; the `geofenceService` bean now passes it as `DefaultGeofenceService`'s
3rd constructor argument. `NoopLiveUpdatePublisher` gained a matching no-op `publishZoneEvent`
override (now implementing seven `*LiveUpdatePort`s). No new properties record and no new
`application.yaml` block — `VisionLiveProperties#enabled()` already governs this selector exactly like
its six siblings.

**L4 (`system` topic)**: needed **no new selector bean at all** — `vision-api`'s `SystemStatusSampler`
is picked up by ordinary component scan, self-gated by the same `@ConditionalOnProperty` its sibling
live-update beans use (see "Application-service beans" above). The one properties change on this
module's side: `config/properties/VisionApiProperties.Live` (the Spring-bound mirror) gained a 9th
component, `@DefaultValue("5s") Duration systemSample`, threaded through `PublishWiring#toApiSupportProperties`/
`#liveSettings` to the plain vision-api mirror `SystemStatusSampler` actually consumes (see the
properties table above). `application.yaml`'s documented `vision.api.live.*` block gained a commented
`# system-sample: 5s` line alongside its siblings (`coalesce`, `buffer-eviction`, …).

**Test wiring, both waves**: `LiveWiringTest` (the enabled/default counterpart) gained
`geofenceLiveUpdatePort`/`assertInstanceOf(LiveUpdateRegistry.class, ...)` inside its existing
all-ports assertion, plus a new `systemStatusSamplerBeanExists` test asserting the bean is present.
`LiveDisabledWiringTest` gained the matching `Noop` assertion inside its existing test (renamed
`noLiveControllerOrRegistryOrSamplerBeanExistsWhenDisabled`, widened to also assert
`applicationContext.getBeansOfType(SystemStatusSampler.class).isEmpty()` — the sampler must be gated
off too, since with live disabled it would have nowhere to broadcast).

**Docker/build note**: the first attempt used `./mvnw -B -pl station/vision-app -am test -DskipWeb`
per this task's own build instructions, but `-am` pulled `adapter-rtsp` into the reactor as a build
dependency of `vision-app`, and its `MediamtxDockerIntegrationTest` hit a genuine, pre-existing,
unrelated Docker/network flake (RX side never connected to a real mediamtx container within its
1-minute bound) — a module this task's file scope never touches. Switched to the standard
install-then-`-pl`-without-`-am` recipe instead (`./mvnw -B -pl core/vision-kernel,core/vision-platform,
contexts/vision-warehouse,contexts/vision-identity,contexts/vision-flight,contexts/vision-perception,
contexts/vision-map,contexts/vision-events,contexts/vision-learning,contexts/vision-simulation install
-DskipTests` then `./mvnw -B -pl storage/persistence,station/vision-api,station/vision-app test
-DskipWeb`), which reuses `adapter-rtsp`'s already-installed `~/.m2` jar untouched while still
refreshing `vision-flight`'s (this wave's own changed module).

`storage/persistence` **283/283** (unchanged, read-only this wave; Postgres Testcontainers ran for
real), `station/vision-api` **1069/1069** (+9 — see that module's own MODULE.md entry), `station/vision-app`
**353 → 354, all green** (+1: `LiveWiringTest#systemStatusSamplerBeanExists`; `LiveDisabledWiringTest`
widened one existing test in place, no new test method there) — all green, `BUILD SUCCESS`, default-config
bar held throughout both `vision.live.enabled=true` (default) and `=false` wiring tests. Docker ran for
real, not skipped. No new `ApiExceptionHandler` mapping, no new REST endpoint (both waves are pure
SSE-topic additions — see `station/vision-api/MODULE.md`). Nothing deferred except the pre-existing
`everDropped`/`live-updates` `DEGRADED` defect, explicitly out of scope per this wave's own task spec.

### 2026-09-12, CV-ORCHESTRATION wave W1 step 5 "wire mirror" — this module's one-line share

This module's entire file scope for the wave was `stream/LiveFrameFallbackStreamService` — the
domain/application half (`StreamPipeline`'s new `latestObjects()` read model, `StreamService#objects`,
`DefaultStreamService`) lives in `contexts/vision-perception`, and the DTO/controller half in
`station/vision-api`; see those modules' own MODULE.md entries for the full wiring writeup and the
label-filter rule. `LiveFrameFallbackStreamService#objects(StreamId)` is a one-line pure delegation to
`delegate.objects(streamId)`, matching every other method on this decorator (same shape as the
`followStatus` fix recorded above). No wiring bean changed — `LiveFrameFallbackStreamService` is
already assembled by the existing `streamService` bean (see "Application-service beans" above) iff
`vision.publish.source-proxy.enabled=true`; adding a method to an already-wired interface needs no new
selector.

No test file in this module's own scope needed a change: this decorator has no dedicated unit test
(each method is a one-line pass-through verified by inspection, the same precedent the `followStatus`
fix above established), so `station/vision-app`'s own test count is unchanged by this wave.

`./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,storage/persistence,contexts/vision-events,contexts/vision-learning,station/vision-app -am -DskipWeb test` —
`station/vision-app` **354/354, unchanged** (no test file in this module's scope touched).
`contexts/vision-perception` **755 → 760** and `station/vision-api` **1069 → 1074** — see those
modules' own MODULE.md entries for the breakdown. `BUILD SUCCESS`, all green across the whole scoped
reactor. Docker ran for real: this module's own Testcontainers-backed suites (`SessionPersistenceIntegrationTest`,
`PersistenceWiringTest`, `OnboardingWiringTest` and friends) migrated a real `postgres:16` container
through `V35` during this run (confirmed in the build log). `storage/persistence`'s Docker suite also ran for real in the same
reactor (**283 tests, 0 failures** across its 35 `@Nested` classes). Its OUTER
`PostgresDockerIntegrationTest` report reads `Tests run: 0` because that class declares no `@Test`
of its own — not because the `@EnabledIf("dockerAvailable")` gate skipped it. Counting a module by
summing `surefire-reports/*.txt` misses every nested class; read Maven's per-module `Results:` line.
An earlier pass through this wave recorded that mistake as a Docker flake, and it was not one.
Nothing deferred from this module's own file scope. Not committed — the wave owner commits.

### 2026-09-12, CV-ORCHESTRATION wave W1 — the headline `ObjectState` wire-mirror acceptance test

`ObjectStateRoundTripTest` (`src/test/java/com/drones/vision/adapter/cvgrpc/`) is the acceptance test
for the whole wave: it builds a wire-shaped `com.drones.vision.proto.v1.ObjectState` proto message by
hand (every field of all 7 nested groups set to a distinct value — no two numbers equal anywhere in
the message — plus a second, minimal object with only `id`/`lifecycle`/`stream_id` and no groups at
all), wraps both in one `DetectionResponse`, decodes it, maps each object through
`com.drones.vision.api.dto.ObjectStateResponse#from`, serializes with a bare `tools.jackson.databind.json.JsonMapper`
(byte-identical to Spring's configured bean here, since `@JsonInclude(NON_NULL)` lives on the record
type, not on mapper config), and compares the resulting JSON tree against a fixture **committed under
vision-web's own test tree** — `station/vision-web/src/app/core/api/__fixtures__/object-state.wire.json`
— as parsed `JsonNode` trees (object-key order-independent; array order-sensitive, satisfied here since
`candidates` is built in the same order on both sides). On a mismatch the test writes the actual JSON
next to the fixture as `object-state.wire.actual.json` and fails naming both paths plus a `cp`
regeneration hint — it never auto-overwrites the committed fixture. It additionally asserts the minimal
object's JSON has none of the seven group keys (`identity`/`kinematics`/`belief`/`provenance`/`memory`/
`lock`/`timing` all absent, not zeroed) — the same "absent is honest, zero is a lie" invariant
`ObjectState`'s own Java doc comment states.

**Why this test lives in `vision-app`, in `adapter-cvgrpc`'s own package:** this is the only module
that depends on both `adapter-cv-grpc` (the proto message builders) and `vision-api` (the DTO +
Jackson config to serialize with) — `contexts/vision-perception` doesn't know about the DTOs, and
`vision-api` doesn't know about the proto wire types (ArchUnit's `onlyAppMayDependOnAdapterPackages`
would fail it if it did). `DetectionFrameCodec#decode` (the proto→domain mapping step) is
package-private in `adapter-cv-grpc`'s `com.drones.vision.adapter.cvgrpc` package — rather than widen
its visibility or add reflection, the test class sits in that exact same fully-qualified package name
under this module's own `src/test/java` root; the plain (non-JPMS) classpath grants package-private
access by package name alone, regardless of which module/source-root a `.java` file physically lives
under, and this reactor has no `module-info.java` anywhere. This is a **type-mirror-only** precedent
(no other test in this module reaches into an adapter's package-private surface today) — if a second
wave needs the same trick, promote it into a documented convention rather than copy-pasting the
javadoc justification a third time.

**Determinism:** the fixture is a real, hand-committed file (not test-generated on every green run) —
`StreamId.of("aaaaaaaa-…")` replaces `StreamId.random()`, and every numeric field uses either a `k/32`
fraction (21 fields constrained to `[0,1]`) or a `0.125`-multiple (unconstrained fields), both exactly
representable in IEEE-754 single **and** double precision, so the float→double widening across
proto→domain→DTO→JSON never rounds and the committed JSON literals are exact decimals, not
`0.30000001192092896`-style artifacts. The fixture matched the Java-produced tree on the very first
real run — no `.actual.json` regeneration cycle was needed.

**The TypeScript half** (out of this module's own scope, listed here only for the cross-reference) is
`station/vision-web/src/app/core/api/object-state.wire.contract.spec.ts` — it loads the same committed
fixture and asserts it satisfies every `ObjectState`-family interface in `core/api/models.ts` key-for-key
via the `Record<keyof T, true>` compiler-enforced exhaustive-key-list technique (catches a renamed/
removed/added TS field at compile time) paired with a runtime `Object.keys(...).sort()` equality check
(catches drift the compiler-side check alone cannot — an object literal assigned to a narrower
interface type does not trigger TypeScript's excess-property check, so a stray extra key would
otherwise pass silently). No `resolveJsonModule` addition to `tsconfig.spec.json` was needed — the
fixture bundled and the spec ran against Angular's Vite-based `ng test` runner (which resolves JSON
imports at the bundler level, independent of `tsc` type-checking) without any tsconfig change; the
`station/vision-web/tsconfig*.json` file-scope allowance in this wave's brief went unused.

**Builds, both run in full (never filtered, never backgrounded):**

- `./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,station/vision-app -am -DskipWeb test`
  — `BUILD SUCCESS`, all 26 reactor modules green. `station/vision-app` **354 → 355** (exactly the one
  new `ObjectStateRoundTripTest`, no other test file in this module touched): `Tests run: 355,
  Failures: 0, Errors: 0, Skipped: 0`. Total time 08:34 min. The pre-existing uncommitted W1 step-5
  work already in this worktree (`ObjectStateResponse`/`StreamPipeline#applyLabelFilters`'s
  `pullTelemetry` fix, recorded in the entry above) broke nothing — every one of the 354 pre-existing
  tests in this module still passed.
- `npm --prefix station/vision-web run test:ci` (never bare `npx vitest run`, which fakes ~536
  failures per this repo's own [[web-test-command]] memory) — **199 → 199 test files, 3892 → 3904
  tests**, all green: `Test Files 199 passed (199)` / `Tests 3904 passed (3904)`, 6.83s. The new spec
  file contributes exactly 12 of those (one `describe` block, 12 `it`s — 9 key-set assertions for the
  9 grouped interfaces, 2 enum-membership checks, 1 absence check for `minimal`'s 7 missing groups).

Docker ran for real in the Maven build (Postgres via Testcontainers, migrated through `V35` — visible
in the log for `TrackingAssociateE2ETest`/`OnboardingProbeEnabledWiringTest`, both of which ran and
passed in the same module before the new test).

**Nothing in the brief proved unimplementable.** The one open question flagged mid-task — whether
`tsconfig.spec.json` needed `resolveJsonModule` — resolved itself empirically (not needed) rather than
requiring the scoped tsconfig change the brief conditionally allowed. Not committed — the wave owner
commits.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` **wave W2.5 (commit `cdf17816`) is done here**: `CvWiring`
gained `cvInspectClient`/`traceDemandPort` beans and `streamDetectionSupport` grew a 6th constructor
argument (see the `CvWiring` row above); `SystemStatusWiring#cvServiceStatus` widened to a second
parameter (see that row above). `stream/LiveFrameFallbackStreamService` gained two more one-line pure
delegations, `gateLedger(StreamId,int)`/`frameLedger(StreamId,int)` → `delegate.gateLedger(...)`/
`delegate.frameLedger(...)`, the same shape as its existing `objects(StreamId)` delegation from wave
W1 step 5 — no behavior beyond forwarding, no test file in this module's own scope needed a new case
for either (covered by `StreamService`'s own contract test one layer down, `contexts/vision-perception`).
`./mvnw -B -pl contexts/vision-perception,cv/grpc,station/vision-api,station/vision-app -am -DskipWeb
test` — `station/vision-app` **BUILD SUCCESS, 355 tests, all green** (unchanged count — no new test
file in this module's own scope this step); full reactor `BUILD SUCCESS`. Wave W2.6 (profile fold +
`intent`, commit `fc0bfa00`) touched no file in this module at all — `CvProfileWiringConfiguration`'s
`cvProfileResolver`/`cvProfileService` beans are unaffected (same constructor signatures, only the
resolver's internal fold algorithm changed, inside `contexts/vision-perception`). This W2.7 pass is
documentation-only — no source change; the count above is carried forward from W2.5's own measurement
per this wave's "skip the tests" instruction, not re-run for this docs-only step.

**CV-ORCHESTRATION wave W7.2 (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, decision E22 — "a
profile is a patch") touched exactly one test in this module.** `CvProfileWiringConfiguration`'s own
beans are unaffected (same constructor signatures — only `contexts/vision-perception`'s domain shape
and `storage/persistence`'s entity/migration changed underneath them). `PersistenceWiringTest#partialCvProfileWithOnlyIntentAndInferenceFpsRoundTripsThroughTheWiredJpaRepository`
(added in an earlier W7.2 step to spot-check this module's own production wiring, not just
`storage/persistence`'s directly-constructed `EntityManagerFactory`) started as a genuine flake: it
seeded `createdAt`/`updatedAt` with a raw `Instant.now()` (nanosecond precision) and compared it
byte-for-byte against the round-tripped value — `storage/persistence/MODULE.md`'s own Gotchas entry
already documents that a Postgres `TIMESTAMPTZ` round-trip is lossy past millisecond precision. Fixed
by truncating the seeded `Instant` to `ChronoUnit.MILLIS` before constructing the profile, the same
precedent `PostgresDockerIntegrationTest` (`storage/persistence`) already established — not a new
pattern invented here.

`./mvnw -B -pl storage/persistence,station/vision-app -am test -DskipWeb` — this module: **357**
tests, `BUILD SUCCESS`, Docker ran (Testcontainers `postgres:16`, Flyway migrated through `V36`). Full
two-module command green; `station/vision-api`'s own W7.3 DTO/controller rework (a separate commit,
`station/vision-api/MODULE.md`'s own entry) is what this module's `vision-api` dependency actually
compiles against — see that entry for why W7.3 was written before this wave's own build could be
re-verified (Maven's reactor `-am` dependency-graph ordering, not scope creep).

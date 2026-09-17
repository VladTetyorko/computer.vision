# vision-app

Spring Boot assembly: the only module that knows about every adapter, wires domain ports to
concrete implementations, and holds runtime configuration + the ArchUnit/context-boundary rules.

**Depends on:** vision-kernel, vision-platform, all 8 context modules (vision-warehouse,
vision-identity, vision-flight, vision-perception, vision-map, vision-events, vision-learning,
vision-simulation), adapter-simulation, adapter-rtsp, adapter-mjpeg, adapter-mavlink (+ mavlink-core,
test scope), adapter-v4l2, adapter-publish-hls, adapter-cv-grpc, adapter-tiles, adapter-discovery,
adapter-persistence, vision-api, vision-web (static-only jar), spring-boot-starter,
spring-boot-starter-security, spring-boot-starter-actuator, spring-boot-session-jdbc (must be Boot's
own starter, not the bare `org.springframework.session:spring-session-jdbc` — see Gotchas),
flyway-core (must stay `compile` scope — see Gotchas) · test: spring-boot-starter-test,
archunit-junit5, testcontainers-postgresql
**Used by:** nothing (leaf/assembly module; produces the runnable jar via spring-boot-maven-plugin)
**Build/test:** `./mvnw -B -pl station/vision-app test` — needs the rest of the reactor already
installed to `~/.m2` (`-pl` doesn't build siblings from source) and a reachable Docker daemon
(every `@SpringBootTest` needs a real Postgres via Testcontainers — Postgres is the only store, no
toggle exists to turn it off). Always `mvn clean` first — see Gotchas.

## Section index

| Topic | Heading |
|---|---|
| Package layout | Package shape |
| Every `@Configuration` class, its beans, its conditions | Configuration classes (wiring map) |
| `ApplicationServiceWiring`'s context-service beans | Application-service beans |
| Every `@ConfigurationProperties` record, its prefix, its consumer | Properties records |
| `devsupport` no-op fallbacks and when each is selected | devsupport fallbacks |
| ArchUnit + `EndpointAuthorizationTest` rules | ArchUnit rules |
| Wiring/DI idioms to follow | Conventions |
| Library quirks, timing traps, build/verification pitfalls | Gotchas |
| What's real vs. placeholder, the current flag map | Status |
| Wave-by-wave build log ("why") | [`MODULE-HISTORY.md`](MODULE-HISTORY.md) |

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
| `VideoSourceWiring` | Simulation, Rtsp, Mjpeg, V4l2 | `simulatedVideoSource`/`ffmpegVideoSource`/`mjpegVideoSource`/`v4l2VideoSource` (all unconditional `VideoSourcePort`), `videoSourceRegistry`. Exposes static `toFfmpegSettings`/`toMjpegSettings` mappers reused by `FeedTransmitterWiring` |
| `TelemetryWiring` | Simulation, Mavlink, Rc, Onboarding | `simulatedTelemetrySource`, `mavlinkTelemetrySource`, `mavlinkFlightCommander`, `mavlinkManualControlSender` — all unconditional. Static `toMavlinkSettings(...)` reused by `FeedTransmitterWiring`/`DiscoveryWiringConfiguration`/`OnboardingWiringConfiguration`. **MAVLINK-COMMANDS-PLAN P4** rewired `mavlinkFlightCommander` off the 2-arg `(MavlinkTelemetrySource, Duration)` back-compat constructor onto `MavlinkFlightCommander`'s canonical `(MavlinkTelemetrySource, MavlinkSettings)` one, reusing `toMavlinkSettings(...)` a fourth time (now takes `VisionRcProperties`/`VisionOnboardingProperties` too, matching `mavlinkTelemetrySource`'s own parameter list) |
| `PublishWiring` | Publish, Api, Cv, Media | `mediamtxStreamPublisher` (COP `vision.publish.enabled`, default true — own bean so `SystemStatusWiring` observes the *same* instance `streamPublisherPort` routes through), `streamPublisherPort` (unconditional `StreamPublisherPort`; `NoopStreamPublisher` if disabled, else a `PublisherRouter` wrapping the direct publisher + a `MediamtxProxyPublisher`, routed by `vision.publish.source-proxy.enabled`; **fails fast** if source-proxy is on while `vision.cv.frame-transport` is still `push` — see Gotchas), `mediamtxLiveFrameGrabber` (unconditional, cheap/lazy), `replayFrameExtractionPort` (unconditional; `NoopReplayFrameExtractor` if publish disabled), `hlsProxyUpstreamBase: URI`, `snapshotJpegEncoder`, `hlsProxySettings`, `liveSettings` (last 3 bridge Spring-bound `VisionApiProperties` → vision-api's plain mirror of the same simple name — see Gotchas). **ASSET-FLOWS-PLAN §2 S6**: every bean method above that builds a mediamtx URL now additionally takes `VisionMediaProperties mediaProperties`, converted once per call via the new private `toMediaCredentials(VisionMediaProperties)` helper into `adapter-publish-hls`'s `MediaCredentials` — `mediamtxLiveFrameGrabber` embeds the viewer credential in its RTSP read URL, `toPublishSettings`/`toProxySettings` thread it into `PublishSettings#auth`/`MediamtxProxySettings#media` (so `whepUrl`/`playbackUrl`/the RTSP push URL all carry credentials — see adapter-publish-hls/MODULE.md), and `toApiSupportProperties` appends `auth.viewerUsername()`/`auth.viewerPassword()` as `VisionApiProperties.HlsProxy`'s two new trailing components, which `HlsProxyController` (vision-api) sends as an outbound `Authorization: Basic` header (see that module's own MODULE.md Gotchas) |
| `CvWiring` | Cv | `cvGrpcChannel: ManagedChannel` `@Primary` (COE: `cv.enabled` OR `training.enabled` OR `frame-transport=pull` OR `geo.visual.enabled`; built via `CvChannels.forTargets`), `cvTrainingChannel` (COP `vision.cv.training.target` present — independent shutdown), `cvChannelSupervisor` (COE = cvGrpcChannel's expression AND `cv.reconnect.enabled` default true; `@Qualifier("cvGrpcChannel")`), `detectionPort` (unconditional bean, internal branch on `enabled`: `GrpcDetectionPort` w/ `@Qualifier("cvGrpcChannel")` else `NoopDetectionPort`; `destroyMethod=""`), `pulledDetectionPort` (COE `frame-transport=pull`; `@Qualifier("cvGrpcChannel")`), `cvModelRoster` (static constant), `detectionDemandPort` (COE default true, returns concrete `LiveAndPollDetectionDemand`), `streamDefaultConfig`, `streamDetectionSupport`. **ALWAYS-ON-FLOW-PLAN.md wave D1** added `detectionPolicyCache` (`initMethod="start"`/`destroyMethod="close"`, COE = `cvGrpcChannel`'s own "CV switched on at all" expression minus the reconnect AND-clause, mirroring `cvChannelSupervisor`'s precedent for self-scheduled background beans — absent, not merely inert, when CV is entirely off) and `detectionPolicyPort` (unconditional, a lambda over `ObjectProvider<DetectionPolicyCache>` whose `.getIfAvailable()` is deferred inside the lambda body exactly like `hasCameraPose`'s own `TrackProjectionRunner` consumption, avoiding a circular-dependency hazard; reads every asset as `DetectionPolicy.ON_VIEW`/`false` when the cache is absent — fail-closed, unlike `detectionDemandPort`'s fail-open). **CV-ORCHESTRATION wave W2.5** added `cvInspectClient: GrpcCvInspectClient` (`@Qualifier("cvGrpcChannel")`, same COE as `detectionPort` — feeds `SystemStatusWiring#cvServiceStatus`'s capacity enrichment, see below) and `traceDemandPort: LiveAndPollTraceDemand implements TraceDemandPort` (same shape/COE as `detectionDemandPort`, a second SSE/poll-OR demand port — `#watchingTrace(AssetId)` OR a recent `GET .../cv/trace` poll timestamp); `streamDetectionSupport` widened to consume `ObjectProvider<LiveAndPollTraceDemand>` as its 6th constructor argument (`.getIfAvailable()`, `null` when the demand gate isn't wired — same optionality as `demand`). Static `toGrpcCvSettings(...)` and package-private `controlPlaneChannel(cvTrainingChannel, cvGrpcChannel)` (= training channel if present else falls back to inference channel) shared by `TrainingWiringConfiguration`/`VisualGeoWiringConfiguration` |
| `TrainingWiringConfiguration` | Training, Cv | `datasetUploadPort`, `trainingStores`, `replaySources`, `datasetService`, `labelingService` (takes `AssetDirectoryService`, not `AssetService`), `trainingPort`, `trainingJobService` all `@ConditionalOnProperty(vision.training.enabled=true)`, no fallback. `modelRegistryPort`/`modelRegistryService` are a **separate** switch since CV-SETTINGS-PLAN §5 (CV-SETTINGS-CONTEXT.md's W4-app → W5 handoff decoupled the registry from training): `@ConditionalOnProperty(vision.cv.registry.enabled=true)`, whose own `application.yaml` default follows `vision.cv.enabled` via the `${vision.cv.enabled:false}` placeholder — a CV-only deployment gets the registry for free unless it explicitly opts out (`vision.cv.registry.enabled=false`); a training-only deployment does **not** get it for free any more. Channel-consuming beans route through `CvWiring.controlPlaneChannel(...)` |
| `CvProfileWiringConfiguration` | — | **Every bean unconditional**, no `@Conditional*` at all (CV-SETTINGS-PLAN §3.1/§5, CV-SETTINGS-CONTEXT.md's W2 → W5 handoff): `cvProfileCacheSettings` (from `VisionCvProperties.Profiles#cacheTtl()`, default 60s), `cvProfileCache` (write-through, lazy-TTL-reload), `cvProfileResolver` (asset→category→organization→platform fold, shared with `ApplicationServiceWiring#streamService` and `CvWiring#streamDetectionSupport`), `cvProfileService` (`DefaultCvProfileService`, behind `CvProfileController`) — profiles ship built-in (4 seeded rows, `V29__cv_profiles.sql`) regardless of `vision.cv.enabled`/`vision.cv.registry.enabled`, the same "ships built-in" posture `TrackingWiring#cvTrackerRoster` takes |
| `DiscoveryWiringConfiguration` | Discovery, Mavlink | `onvifWsDiscoveryScanner`/`mdnsScanner`/`v4l2Scanner`/`mavlinkHeartbeatScanner` — each COP `vision.discovery.enabled` default true. `mediamtxPathScanner` (ZERO-CONFIG-ONBOARDING Z3) is the fifth scanner, COP `name = {"enabled", "mediamtx.enabled"}` under the `vision.discovery` prefix — Spring ANDs the array together, so it needs **both** `vision.discovery.enabled` and `vision.discovery.mediamtx.enabled` (default true, its own independent off-switch since it is the one scanner that depends on another subsystem, mediamtx/`vision.publish.enabled`, being present at all). Built from `MediamtxScannerSettings(publishProperties.mediamtx().apiBase(), publishProperties.mediamtx().rtspBase(), properties.mediamtx().pathPrefix())` — takes `VisionPublishProperties` as a plain constructor param (registered by `PublishWiring`'s own `@EnableConfigurationProperties`, not this class's; autowires here as an ordinary bean regardless of `vision.publish.enabled` since only the publisher beans themselves are conditional on that flag) rather than inventing a parallel `vision.discovery.mediamtx.api-url`/`rtsp-base` pair, so the two consumers of "where mediamtx is" can never disagree. `discoveryService` and `mavlinkPort` **unconditional** (`DiscoveryController` needs the service regardless; `DefaultDiscoveryService` tolerates an empty port list). `mavlinkHeartbeatScanner` lives in adapter-mavlink, not adapter-discovery like its other 3 siblings, because it borrows `mavlinkTelemetrySource`'s open socket and adapters can't depend on each other. **SOURCE-ONBOARDING-2 wave C** added three more beans, all consumed by `vision-api`'s `SystemNetworkController`/`DiscoveryStatusController`: `videoPushPort`/`videoPushPathPrefix` (both `@Bean` methods COP `vision.publish.enabled=true`, matchIfMissing=true — genuinely absent, not null-valued, when mediamtx publish is off, since a required `@Autowired` constructor parameter elsewhere cannot accept a null-valued bean; see `vision-api`'s MODULE.md Conventions for why the consumer takes these through `ObjectProvider<T>`) and `discoveryStatusFacts` (unconditional `Supplier<DiscoveryStatusFacts>`, taking `MavlinkTelemetrySource`/`VisionDiscoveryProperties`/`VisionPublishProperties` plus two `ObjectProvider`s — `DiscoveryInboxRunner` and `MediamtxPathScanner` — since either may be conditionally absent; an absent runner reports `lastSweepAt` as `null`, an absent scanner reports `readyPaths` as empty, both honestly rather than fabricated) |
| `DiscoveryInboxWiringConfiguration` | Discovery | `discoveryInboxService(DiscoveryCandidateRepositoryPort, AssetService, DeviceService, VisionLiveProperties, VisionDiscoveryProperties, ObjectProvider<LiveUpdateRegistry>)` → `DefaultDiscoveryInboxService`, **unconditional** (`DiscoveryInboxController`, vision-api, needs it regardless of whether the sweep runs — an operator can still read/register/dismiss by hand with the runner off); **SOURCE-ONBOARDING-2 wave C** widened this bean method to take `DeviceService` (fixing a pre-existing 2-arg→3-arg constructor-arity bug — `DefaultDiscoveryInboxService`'s canonical constructor already needed it, the wiring just hadn't been updated) and to wrap the delegate in `com.drones.vision.app.events.LiveUpdateDiscoveryInboxService` when both `VisionLiveProperties#enabled()` and `VisionDiscoveryProperties.Live#enabled()` are true (both default true — see "Application-service beans" below and that decorator's own javadoc for the deliberate concrete-`LiveUpdateRegistry` dependency). `discoveryInboxRunner` (`initMethod="start"`, `destroyMethod="close"`) COP `vision.discovery.inbox.enabled=true` (matchIfMissing=true, the default — ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 Z2c deliberately overrides CLAUDE.md's usual opt-in-guardrail default, since shipping the sweep off by default would defeat the whole zero-config purpose); its `lastSweepAt()` accessor (SOURCE-ONBOARDING-2 wave C) reports the instant the runner last **completed** a sweep (not merely attempted one), `null` before the first completion — feeds `discoveryStatusFacts` above |
| `FeedTransmitterWiring` | Publish, Rtsp, Mjpeg, Mavlink, Rc, Onboarding | `rtspFeedTransmitter`, `mjpegFeedTransmitter` (`destroyMethod="close"` — owns a shared `HttpServer`), `mavlinkFeedTransmitter`, `feedTransmitterRegistry` — all unconditional |
| `PersistenceWiringConfiguration` | Persistence | **Every bean unconditional**, no `@Conditional*` anywhere — 23 one-line `Jpa*Repository(entityManagerFactory)` ports (WAREHOUSE-UX W3 added `maintenanceRepositoryPort`/`assetNoteRepositoryPort`; ZERO-CONFIG-ONBOARDING Z2c added `discoveryCandidateRepositoryPort`) + `persistenceEntityManagerFactory` (`destroyMethod="close"`, opens a real JDBC connection eagerly). No in-memory fallback exists for any repository port (Postgres is the only store). **AUTH-ROLES wave B5** split the pooled connection out into its own bean, `visionDataSource` (`destroyMethod=""` — `persistenceEntityManagerFactory` closes the same pool transitively via `ClosingDatasourceConnectionProvider`, and Spring destroys the dependent bean first, so a second close on this one is avoided) built via `PersistenceUnit.buildDataSource` (now public); `persistenceEntityManagerFactory` takes that `DataSource` plus `properties.seedDevUsers()` and binds via the new `PersistenceUnit.start(DataSource, boolean)` overload rather than building its own pool. A third new bean, `visionSessionTransactionManager` (`JdbcTransactionManager`, bound to the same `visionDataSource`), exists solely so Spring Session JDBC wraps its own `SPRING_SESSION`/`SPRING_SESSION_ATTRIBUTES` reads/writes in a real transaction rather than falling back to Spring Session's own no-op `ResourcelessTransactionManager` — no `Jpa*Repository` uses it, each still manages its own transaction natively via `EntityManager`/`EntityTransaction`. This is the first `DataSource`/`PlatformTransactionManager` bean this app has ever exposed to Spring — needed because Spring Session JDBC's autoconfiguration binds to one, and this app's persistence layer otherwise never goes through Spring's own `DataSourceAutoConfiguration`. |
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
| `ApplicationServiceWiring` | Cv, Live, Rc, Application, Publish, Simulation, EventHistory | The largest class — every `vision-application`/context `DefaultXService` bean. See "Application-service beans" below |

### Application-service beans (`ApplicationServiceWiring`)

- **Live server-push selectors (7, not 6):** `fleetLiveUpdatePort`/`telemetryLiveUpdatePort`/`detectionLiveUpdatePort`/`mapLiveUpdatePort`/`eventLiveUpdatePort`/`trackCorrectionLiveUpdatePort`/`geofenceLiveUpdatePort` — each takes `VisionLiveProperties` + `@Qualifier("liveUpdateRegistry") ObjectProvider<LiveUpdateRegistry>`; returns the registry if `properties.enabled()` (default true) else `new NoopLiveUpdatePublisher()`. `trackCorrectionLiveUpdatePort` backs the `geo:<assetId>` topic (visual-geo), same flag. `geofenceLiveUpdatePort` (LIVE-POLL-RETIREMENT-PLAN.md §3 D2, wave L3) is byte-identical in shape to the six preceding it — added because `contexts/vision-flight`'s `GeofenceLiveUpdatePort` backs the new `zones` SSE topic (see `station/vision-api/MODULE.md`'s "Live updates" section); `geofenceService`'s bean method now also takes it, passed as the 3rd constructor arg to `DefaultGeofenceService`.
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

### Properties records (`config/properties/`, all `@ConfigurationProperties`)

| Record | Prefix | Primary consumer |
|---|---|---|
| `VisionApplicationProperties` | `vision.application` | `vision-application`'s pipeline/replay/simulation/fleet/stats settings |
| `VisionPersistenceProperties` | `vision.persistence` | `adapter-persistence`'s `PersistenceUnit`/pool/telemetry-batch settings |
| `VisionLiveProperties` | `vision.live` | selects `LiveUpdateRegistry` vs. `NoopLiveUpdatePublisher` (all 6 ports). **SOURCE-ONBOARDING-2 wave C** widened this from a single-field to a 2-component canonical constructor (`enabled`, `streamStatePush`), gaining a nested `StreamStatePush(boolean enabled)` record (default true) that gates the new `streamStateObserver` selector above; no back-compat 1-arg convenience constructor was added since a repo-wide grep found zero existing `new VisionLiveProperties(...)` call sites to preserve (CLAUDE.md rule 10) |
| `VisionTrainingProperties` | `vision.training` | gates `TrainingWiringConfiguration`'s whole bean cluster |
| `VisionCvProperties` | `vision.cv` | `CvWiring`/`adapter-cv-grpc` channel + detection settings. **ALWAYS-ON-FLOW-PLAN.md wave D1** added a `Policy policy` component (right after `profiles`, 22nd of the canonical constructor — every pre-existing `new VisionCvProperties(...)` call site with the old 21-arg shape needed a trailing `null` appended, `VisionCvPropertiesTest`'s 5 sites among them), with a null-fallback to `new Policy(Policy.DEFAULT_REFRESH_INTERVAL)` in the compact ctor. `record Policy(Duration refreshInterval)` (`@DefaultValue("15s")`) — `CvWiring#detectionPolicyCache`'s own re-list cadence; the *record* is always present regardless of deployment (no feature flag gates the config shape, and binding the property costs nothing even unused), but the *bean* that consumes it is conditional — see the `CvWiring` row below. No `docker-compose.yml` env var added: like its sibling per-tick tunables (`Profiles.cacheTtl`, `detectionDemandGrace`/`detectionDemandPollInterval`), the 15s default is deployment-topology-independent, so Spring's own relaxed env-var binding (`VISION_CV_POLICY_REFRESH_INTERVAL`) is sufficient without a compose-file line, consistent with the precedent that none of those siblings have one either |
| `VisionDiscoveryProperties` | `vision.discovery` | `adapter-discovery` scan budgets; ZERO-CONFIG-ONBOARDING Z2c added two nested records to the canonical constructor's 4th/5th components — `Lobby(boolean enabled)` (default `true`, `DiscoveryInboxWiringConfiguration`'s `mavlinkTelemetrySource.holdLobby(...)` gate) and `Inbox(boolean enabled, int sweepSeconds, int scanTimeoutSeconds)` (defaults `true`/`30`/`5`, compact-constructor validated both ints `> 0`) — both default to `true`/on, a deliberate exception to the repo's usual opt-in-guardrail default (see `DiscoveryInboxWiringConfiguration` row above). Z3 added a 6th component, `Mediamtx(boolean enabled, String pathPrefix)` (defaults `true`/`"ingest/"`, compact-constructor rejects a blank `pathPrefix`) — consumed by `DiscoveryWiringConfiguration#mediamtxPathScanner`'s COP and settings-record construction (see that row above); `apiBase`/`rtspBase` are deliberately **not** on this record — they come from `VisionPublishProperties.Mediamtx` instead, so this record only owns the one property that's genuinely this module's own concern. C4 added a 7th component, `Live(boolean enabled)` (default `true`) — gates whether `DiscoveryInboxWiringConfiguration#discoveryInboxService` decorates its delegate with `LiveUpdateDiscoveryInboxService` (see `vision-api`'s MODULE.md Live-updates section for the `discovery` SSE topic this feeds); ships on by default (SOURCE-ONBOARDING-2-PLAN.md §3.2 C4, D8) |
| `VisionSimulationProperties` | `vision.simulation` | `adapter-simulation` video/telemetry settings + resume-on-boot |
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
graph): every handler must reach `CurrentUser.scope()`/`.viewer()`/`.authority()` or a type whose name
ends in `Access` or `Authority` (`AUTHORITY_METHODS` includes `"authority"` alongside `"scope"`/
`"viewer"`; `reachesAuthorityCheck`'s owner-name test accepts `owner.endsWith("Access") ||
owner.endsWith("Authority")` — AUTH-ROLES wave B4, for `AssetAuthority`/`CapabilityAssetAuthority`), or
be `@OpenByDesign(reason)` (method- or class-level). `TEMPORARY_UNSCOPED` is a shrinking ledger of
known-unscoped handlers; a second assertion fails the build the moment an entry no longer maps to a
real handler, so it can't quietly outlive the gap it records.

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
- **Security seam contract:** `PrincipalResolver` (vision-api port) is implemented here by
  `DevPrincipalResolver`/`SecurityContextPrincipalResolver`, both providing `role()` and `authority()`
  alongside `scope()`/`viewer()` — `DevPrincipalResolver` fixes `Role.ADMIN`/`Authority.full()` (auth
  off always resolves to unbounded everything), `SecurityContextPrincipalResolver` derives `role()`
  from its own `topRoleOf(User)` and `authority()` from `RoleAuthority.capabilitiesOf(...)`.
  `SecuritySessionAuthenticator#login(request, response, user, boolean kiosk)` sets the new session's
  max-inactive-interval from `VisionAuthProperties.Session.idleTimeout`/`kioskIdleTimeout` depending on
  `kiosk` (a caller whose top role is exactly `VIEWER` gets the long-lived kiosk window);
  `NoopSessionAuthenticator` mirrors the parameter, unused. Any new implementation of either port must
  keep these method sets.
- A `StreamService`/similar decorator that exists only to widen or narrow a source (e.g.
  `LiveFrameFallbackStreamService`) implements every delegate method as a pure one-line forward with no
  dedicated per-method unit test — correctness is the wrapped implementation's contract test one layer
  down; add new methods here the same way, verified by inspection, not a new test file.
- A cross-module wire-contract acceptance test (proto/domain wire shape → DTO → JSON, matched against a
  fixture another module's tests also load) lives in **this** module when it is the only one depending
  on both sides — e.g. `adapter-cv-grpc` (proto/domain) and `vision-api` (DTO + Jackson config): see
  `ObjectStateRoundTripTest`. To reach a package-private type in an adapter module, put the test class
  under this module's own `src/test/java` using that adapter's exact package name — the plain
  (non-JPMS) classpath grants package-private access by package name alone, regardless of which
  module/source-root a `.java` file physically lives under.

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
  `false`. Exactly one `SecurityFilterChain` bean is ever registered; it logs one boot `WARNING` naming
  `vision.auth.enabled=false` whenever the permit-all chain activates, mirroring the web UI's own red
  "unsecured" banner. CSRF is disabled in **both** chains deliberately (JSON-only API, same-origin — a
  documented deferred hardening step, not an oversight in the permit-all chain). `application.yaml`'s
  own explicit `vision.auth.enabled` value is `true` — this file's compiled default and every
  deployment's actual behavior agree; `false` is something an operator must opt back into explicitly
  for a local/demo run, not the out-of-the-box posture.
- **The secured chain's matcher list, as it stands today:** `/api/**`, `/ws/**` and `/hls/**` all
  require a session; the permit-all exceptions inside that requirement are `/api/auth/login`,
  `/api/auth/logout`, `/api/auth/bootstrap`, plus the static/SPA routes — both filter chains share this
  same exception list. The session cookie is `http-only: true`, `same-site: strict`,
  `secure: ${vision.auth.session.cookie-secure:false}` (`application.yaml`, fixed platform decisions
  except `secure`, which is the one axis that varies by deployment TLS termination).
- **Session store must be Boot's own starter, not the bare library.** `pom.xml` depends on
  `org.springframework.boot:spring-boot-session-jdbc`, never the bare `org.springframework.session:
  spring-session-jdbc` — Spring Boot 4 moved session auto-configuration out of
  `spring-boot-autoconfigure` into per-technology starter modules (the same restructuring that removed
  `TestRestTemplate`, below); the bare library sits on the classpath with no `SessionRepositoryFilter`
  ever registered, so Tomcat silently keeps serving in-memory `JSESSIONID` cookies and the JDBC-backed
  `spring_session` table never gains a row, with no error anywhere. `spring.session.store-type` has not
  existed since Boot 3 and is not a key in `application.yaml`.
- **The JDBC session store java-serializes the whole `SecurityContext`.** Every type reachable from the
  session principal (`VisionUserDetails` → `User`/`Ownership`, `core/vision-kernel`'s `UserId`/
  `GroupId`) must implement `java.io.Serializable`, or a real login 500s with
  `NotSerializableException` the instant the store is genuinely active — `SecurityContextPrincipalResolver`
  never puts `VisibilityScope`/`Authority` in the session (both are recomputed per-request from
  `ScopeResolver`), so the blast radius is exactly the principal's own reachable graph, not wider.
- **This app's login bypasses Spring Security's own authentication filter, so session-fixation
  protection is hand-rolled.** Login calls `AuthService` directly rather than through
  `UsernamePasswordAuthenticationFilter`, so `sessionManagement()`'s automatic
  `ChangeSessionIdAuthenticationStrategy` never fires (`SecurityConfig` declares no `sessionManagement()`
  DSL at all). `SecuritySessionAuthenticator#login` calls `request.getSession(true)` then
  `request.changeSessionId()` *before* building/writing the `SecurityContext` — removing either call
  reopens a session-fixation gap.
- **A `MockMvc` built via `webAppContextSetup(...).addFilters(<the one security filter bean>)` never
  engages Boot's own `SessionRepositoryFilter`** (a `FilterRegistrationBean` against the real
  `ServletContext`, only picked up by `@AutoConfigureMockMvc` or a real embedded server) — every other
  `@SpringBootTest` in this module shares the cached `MOCK` context and gets a plain in-memory
  `MockHttpSession` the servlet-mock layer invents on the spot, never a JDBC-backed row, so nothing
  there exercises Java serialization or proves the JDBC store is genuinely active. A test that must
  prove that (cookie name is `SESSION` not `JSESSIONID`, a `spring_session` row actually lands) needs
  its own `@SpringBootTest(webEnvironment = RANDOM_PORT)` and a real HTTP round trip instead —
  `SessionPersistenceIntegrationTest` is the one test in this module with that shape.
- **`TestRestTemplate` does not exist in Boot 4/Spring Framework 7** — `spring-boot-test-4.x.jar` has no
  `org.springframework.boot.test.web.client` package at all. Its replacement for a live-server round
  trip is `org.springframework.test.web.servlet.client.RestTestClient` (same `spring-test` artifact
  `MockMvc` already lives in, no `pom.xml` change needed): `RestTestClient.bindToServer().baseUrl(
  "http://localhost:" + port).build()`, fluent API shaped like `WebTestClient`
  (`.post().uri(...).exchange()` → `ExchangeResult` via `.returnResult()`). `@LocalServerPort` is
  unaffected by the restructuring.
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
- **A backgrounded `./mvnw` build is killed the moment the issuing turn ends.** `run_in_background` on
  a shell tool (or a bare `&`) does not survive to a later turn the way a normal long-running build
  does when run to completion synchronously — a "completed" status reported after the fact can describe
  a log that was actually abandoned mid-flight. Always run a verification build in the foreground with a
  generous timeout; never trust a backgrounded Maven result.
- **Testing two modules that both changed, as two separate `-pl <module> test` invocations in the same
  session, resolves the second against the first's stale `~/.m2` jar even with no unrelated staleness at
  all** — the first invocation's `test` goal never installs its own output. Misleading "cannot find
  symbol"/constructor-arity errors follow, indistinguishable from a real defect. Run
  `-pl <modA>,<modB> -am -Dmaven.test.skip=true install` once across every touched module first (fresh
  jars for the whole set), *then* test each `-pl` target — or simply combine them into one `-pl
  <modA>,<modB> test` call.
- **`-am` on `station/vision-app` reactor-includes every adapter it depends on, `adapter-rtsp` among
  them** — its own `MediamtxDockerIntegrationTest` is a genuine, pre-existing Docker/network flake
  unrelated to most vision-app changes. When a change never touches `adapter-rtsp`, prefer the
  install-then-`-pl`-without-`-am` recipe (install every upstream context module with `-DskipTests`,
  then `-pl storage/persistence,station/vision-api,station/vision-app test`) over `-am`, to avoid an
  unrelated flake blocking an unrelated change.
- **Counting a module's tests by summing `surefire-reports/*.txt` undercounts nested test classes.**
  `PostgresDockerIntegrationTest`'s outer report reads `Tests run: 0` because that class declares no
  `@Test` of its own — its dozens of `@Nested` classes hold the real tests and are **not** double-gated
  by `@EnabledIf("dockerAvailable")` a second time. Read Maven's own per-module `Results:` line (or the
  reactor summary), not a grep over report files, or a fully-green Docker suite reads as skipped.
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

- **Real and default-on** (both compiled `application.yaml` default and `docker-compose.yml`): video/
  telemetry source registries, mediamtx publish (`vision.publish.enabled=true`), discovery scanning,
  persistence (Postgres/Flyway, unconditional), auth (secured filter chain, `vision.auth.enabled=true`),
  tracking (`default-mode=ASSOCIATE`), idle-stream reaping, after-action evidence assembly (no flag),
  idle-usage-close sweep (no flag — `UsageIdleCloseRunner`, sweeps once at startup then every
  `vision.usage.sweep-period`, default 60s).
- **Real, compiled default `false`, but turned on for real deployments in `docker-compose.yml`**
  (`application.yaml` stays off on purpose — ~26 `@SpringBootTest` classes and a bare
  `mvn spring-boot:run` depend on the off default; only the deployment manifest overrides it):
  CV inference (`vision.cv.enabled`), CV training (`vision.training.enabled`), API rate limiting
  (`vision.api.rate-limit.enabled` — requires `vision.auth.enabled=true` to mean anything, since rate
  limiting keys its bucket on the acting principal), vehicle-onboarding probe + passport
  (`vision.onboarding.probe.enabled`/`vision.onboarding.passport.enabled` — passport is inert without
  probe, so the two are flipped together) and remediation message interval
  (`vision.onboarding.remediate.message-interval.enabled`), fixed-camera geo
  (`vision.geo.fixed-camera.enabled`), visual geo (`vision.geo.visual.enabled` — the one flag here with
  a real steady-state cost: one gRPC geo session per flying asset with a resolvable stream, pulling
  keyframes from mediamtx), the two-seat crew-control gate (`vision.crew.enabled` — requires
  `vision.auth.enabled=true`; `seatService` itself is wired unconditionally, but `SeatAccess`'s
  pass-through and the RC-release-hook registration both stay inert until this flips), always-on
  telemetry pinning (`vision.telemetry.always-on.enabled`), and durable event history
  (`vision.events.history.enabled`). `vision.cv.registry.enabled`'s own default *follows*
  `vision.cv.enabled` via the `${vision.cv.enabled:false}` placeholder, so it is on wherever CV is.
  **Env-var spelling for the hyphenated keys among these** (`fixed-camera`, `rate-limit`,
  `message-interval`, `always-on`): Spring accepts both the dashless `Form.UNIFORM` name and the legacy
  dashes-to-underscores name; `docker-compose.yml` uses the legacy form throughout (e.g.
  `VISION_API_RATE_LIMIT_ENABLED`, not `VISION_API_RATELIMIT_ENABLED`) — the wrong guess silently binds
  nothing and ships the flag green but inert.
- **Real and default-off in both places:** mediamtx source-proxy (`vision.publish.source-proxy.enabled`).
- `GET /api/system/status` is real (4 `SubsystemStatusPort` beans: cv-service, mavlink-link,
  video-publish, live-updates). `GET /api/system/events` is real (durable `Event` history, gated by
  `vision.events.history.enabled` above; the port itself always exists, only the recording decorator is
  gated).
- `EndpointAuthorizationTest`'s `TEMPORARY_UNSCOPED` ledger is a live, shrinking list — read the test
  source for its current size rather than trusting a number in this doc.

Wave-by-wave history: [`MODULE-HISTORY.md`](MODULE-HISTORY.md).

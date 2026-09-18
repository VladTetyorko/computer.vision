# vision — agent context

Multi-protocol computer-vision & streaming platform. Hexagonal, multi-module Maven, Java 21 + Spring Boot 4 core, Python CV service (gRPC). Full design: [ARCHITECTURE.md](ARCHITECTURE.md). Phase specs: [docs/](docs/).

## Module docs — mandatory workflow

Each module has a `MODULE.md` (format: `.claude/skills/module-docs/SKILL.md`).

- **Before modifying a module: read its `MODULE.md`** and those of its direct dependencies — do NOT re-read sources for API surface the doc already gives you.
- **After modifying a module: update its `MODULE.md`** in the same task (surface, conventions, gotchas, status).
- Doc missing/stale → fix it as part of the task.

## Module index

| Module | Path | Purpose |
|---|---|---|
| vision-kernel | `core/vision-kernel/` | Shared kernel: ids + pure value objects, no third-party deps, depends on nothing |
| vision-platform | `core/vision-platform/` | Cross-cutting seams every context writes to (events, audit trail, visibility scope); depends only on vision-kernel |
| vision-warehouse | `contexts/vision-warehouse/` | Asset/device inventory, categories, discovery, fleet summaries, usage records. The pure leaf — no other context |
| vision-identity | `contexts/vision-identity/` | Users, auth, assignment, visibility-scope resolution |
| vision-flight | `contexts/vision-flight/` | Flight sessions (AssetUsage), telemetry, geofencing, manual control |
| vision-perception | `contexts/vision-perception/` | StreamPipeline, detection, device probing |
| vision-map | `contexts/vision-map/` | Tactical map layers and marks |
| vision-events | `contexts/vision-events/` | Replay capture, usage timeline — a downstream sink, reads every context, nothing reads it back |
| vision-learning | `contexts/vision-learning/` | CV training datasets, labeling, model promotion |
| vision-simulation | `contexts/vision-simulation/` | Synthetic flight-plan/telemetry simulation orchestration |
| vision-proto | `cv/vision-proto/` | gRPC codegen from `proto/vision/v1/cv.proto` |
| adapter-simulation | `simulation-sources/sim/` | Synthetic video + telemetry sources (`sim`) |
| adapter-rtsp | `video-input/rtsp/` | RTSP/FFmpeg ingest |
| adapter-mjpeg | `video-input/mjpeg/` | MJPEG HTTP ingest + TX simulator |
| adapter-mavlink | `drone-link/mavlink/` | MAVLink 2 UDP telemetry ingest + TX flight-plan simulator |
| adapter-carrier-udp | `drone-link/carrier-udp/` | Binds the MAVLink lobby UDP socket at boot, registers it on a `LinkRegistry` |
| adapter-carrier-serial | `drone-link/carrier-serial/` | Hotplug-polls serial ports (jSerialComm), opens/registers a `SerialLink` per matched ground-radio/bench port |
| adapter-v4l2 | `video-input/v4l2/` | USB/V4L2 local camera ingest (RX only) |
| adapter-publish-hls | `video-output/publish-hls/` | H.264 RTSP push → mediamtx (HLS viewing) |
| adapter-discovery | `device-discovery/onvif-mdns-v4l2/` | ONVIF / mDNS / V4L2 scanners |
| adapter-cv-grpc | `cv/grpc/` | DetectionPort via gRPC to cv-service |
| adapter-tiles | `cv/tiles/` | HTTP reference-tile fetch (Esri/Wayback) for visual geolocation |
| adapter-persistence | `storage/persistence/` | JPA/Postgres repositories (unconditional; Postgres is the only store) |
| vision-api | `station/vision-api/` | REST + static web console (driving adapter) |
| vision-app | `station/vision-app/` | Spring Boot assembly, wiring, devsupport, ArchUnit |
| vision-web | `station/vision-web/` | Angular 21 SPA (built into the jar via frontend-maven-plugin; `-DskipWeb` to skip) |
| cv-service | `cv/cv-service/` | Python gRPC CV service (echo stub; YOLO in Phase 2) |

## Cross-cutting facts

- **Dependency rule (ArchUnit-enforced):** kernel ← platform ← contexts (warehouse is the pure leaf; identity/flight/perception/map/events/learning/simulation form the measured DAG over it, see `docs/plans/active/DOMAIN-SEPARATION-W1.md` §16) ← adapters ← app; adapters never depend on each other; Spring only in vision-app, vision-api and the adapters (never a context module).
- **Ids:** entity ids wrap `java.util.UUID` (`X.random()`, `X.of(String)` → IllegalArgumentException on bad input). `CategoryId` is a kebab-case slug. `DeviceType` enum no longer exists — categories are data.
- **Asset model:** users interact with `Asset` (owned, categorized, 1..n devices, attributes map); `Device` is low-level plumbing; `AssetUsage` records sessions + telemetry.
- **Validation idiom:** domain records validate in compact constructors with manual `if (…) throw new IllegalArgumentException(…)`; application layer uses `Objects.requireNonNull`.
- **Jackson 3** (`tools.jackson.*`) under Spring Boot 4 — not `com.fasterxml.jackson.databind`; `java.time` serializes natively; annotations still `com.fasterxml.jackson.annotation`.
- **Build:** `./mvnw -B verify` (full; FFmpeg natives + docker-based tests, allow ~10 min cold). Single module: `./mvnw -B -pl <path> test`. Adapter integration tests use the real `docker` CLI (mediamtx) and skip without docker.
- **Never run reactor-wide builds while another agent's task holds modules red** — scope builds with `-pl`.

## Delegation model (how this repo is built)

Plans in `docs/*-PLAN.md` are authoritative specs; implementation is delegated to subagents with disjoint file scopes; every task ends with its scoped build green and MODULE.md updated.

## Agentic rules

Work in agents, keep responsibility and slave separation:
Fable - for architecture only, no code, no tests 
Opus - for thinking on module lvl, can create a code and configuration. Responsible for flow
Sonnet - is a slave, for coding, tests etc. Not included in planing

One task is one branch. If there are many sub-tasks related to one big - create a subbranch, then merge 
Context - for taking the context use mainly documentation, not the code
Before starting - create a file with a context, then start working on it
After finishing - summarize the context and update the dockumentation. 

## Overall rules for architecture and building

1) No magic numbers and hardcoding of 
values should be in the configuration files, like application.properties in root lvl, or, if it's a Mathematic constant - in the code
If the configuration can varry and be changed during the runtime - chose between having it in database as a constant or in request parameters
If the configuration can be changed during the runtime - use the database and cache layer to store it
2) Layered architecture and object orientations are the best practices: Use inversion of control, follow java standard practices and use packages as separators of layers. For example: Controller -> high-lvl service -> "orcestration", feature-based service -> maybe cache->banch of lower services -> banch of repositories -> database.
3) Standalone principle on modules lvl: Module is independent and doesn't know nothing about who uses it and who listends to it. Communication contract similar to interfaces lvl. Every module should have API description
4) For docks - prefer to use diagrams, markdowns, but not code snippets. Dock should be useful for both, agent and person who works with the code.
5) For code comments - prefer to use java docks, but not markdowns. javadocks should be short, and explain WHY, not What. DO not use pure comments untill it's not Understandable from first sight
6) Follow the SOLID principles, while architecturing and implementing
7) Records are ok to use in the code
8) Configuration files should be in the root of the project, and appliable to all modules. If the module is "independent" - changes on main should be reflected on it too
9) Failsaife and up-to-date are one of priorities in our project. Newest data/telemetry/detections etc should be used, even if previous is still available.
10) A new collaborator means updating the call sites, or bundling into a settings record — never one more constructor overload, and never a parameter whose contract is "null means the feature is off". The old "N-1-arg convenience constructor" convention is **withdrawn**: it produced ten constructors on `UsageTracker` and nine on `StreamPipeline`. Rule and rationale in `.claude/skills/java-clean-code/SKILL.md` §3.

## Deployment maintenance
- I will run this application on different servers, so the run of application should be reflected in docker-compose.yml
- If the module is not "core" related - it should be scalable.
- if the module is "core" related - it should be scalable too, if it contains the calculations logic

## Separate and Standalone
-  As a developer, i prefer the code where i can add new feature based on previous code.
- That's why - code and flows should be understandable by it's responsability. 
- Follow the SOLID principles,
- Follow IoC principle,
- Have a layers of application: from dumb - the repositories should work with each table in the database, DTO's service - should work only with it's repository. Next lvl of service - should work with lower services and handle them. The higher services are - the hogher is abstraction of feature. I'ts the main rule for architecturing
Example: 
- Controller /position-of-drone - checks the authorisation and authentication, if ok - calls
- Service /DroneInFLightPositioningService calls 
- DroneTelemetryService(for telemetry of drone in scape) and DroneGPSService(for drone's position on map) calls
- Repository /DroneTelemetryRepository, /DroneGPSRepository etc.

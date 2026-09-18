# vision — agent context

Multi-protocol computer-vision & streaming platform. Hexagonal, multi-module Maven, Java 21 + Spring
Boot 4 core, Python CV service (gRPC). Full design: [ARCHITECTURE.md](ARCHITECTURE.md). Specs:
[docs/plans/](docs/plans/README.md) — that README, not a plan's own header, is the status authority.

## Module docs — mandatory workflow

Every module carries **two** docs. Format and the keep/move boundary: `.claude/skills/module-docs/SKILL.md`.

| File | Read when |
|---|---|
| `<module>/MODULE.md` | **always**, before touching the module — plus the `MODULE.md` of modules whose ports/models you use |
| `<module>/MODULE-HISTORY.md` | **only** when you need to know *why* something is the way it is |

- Read the **sections you need**. A doc long enough to carry a section index is not read front-to-back.
- After modifying a module, **update its `MODULE.md` in place**. Never append a wave/status section —
  git and the plan doc already hold the history; `MODULE-HISTORY.md` takes it if it's worth narrating.
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
| mavlink-core | `drone-link/mavlink-core/` | Framework-free MAVLink 2 codec/session/service library (no Spring) |
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

- **Dependency rule (ArchUnit-enforced):** kernel ← platform ← contexts (warehouse is the pure leaf;
  identity/flight/perception/map/events/learning/simulation form the measured DAG over it, see
  `docs/plans/active/DOMAIN-SEPARATION-W1.md` §16) ← adapters ← app; adapters never depend on each
  other; Spring only in vision-app, vision-api and the adapters (never a context module).
- **Ids:** entity ids wrap `java.util.UUID` (`X.random()`, `X.of(String)` → IllegalArgumentException
  on bad input). `CategoryId` is a kebab-case slug. `DeviceType` enum no longer exists — categories are data.
- **Asset model:** users interact with `Asset` (owned, categorized, 1..n devices, attributes map);
  `Device` is low-level plumbing; `AssetUsage` records sessions + telemetry.
- **Validation idiom:** domain records validate in compact constructors with manual
  `if (…) throw new IllegalArgumentException(…)`; application layer uses `Objects.requireNonNull`.
- **Jackson 3** (`tools.jackson.*`) under Spring Boot 4 — not `com.fasterxml.jackson.databind`;
  `java.time` serializes natively; annotations still `com.fasterxml.jackson.annotation`.

## Build

| | Command |
|---|---|
| Full | `./mvnw -B verify` — FFmpeg natives + docker-based tests, allow ~10 min cold |
| One module | `./mvnw -B -pl <path> test` |
| Several modules in one session | `./mvnw -B -pl <a>,<b> -am -Dmaven.test.skip=true install` **first**, then test each — without it, `-pl` resolves a stale `~/.m2` jar and invents "cannot find symbol" |
| Web | `cd station/vision-web && npm run test:ci` — **never** bare `npx vitest run`, it fakes ~536 failures |

- **Never run reactor-wide builds while another agent's task holds modules red** — scope with `-pl`.
- **Never background a build** (`&`, `run_in_background`): it is killed when the turn ends, and the
  wave is left unverified. Run it synchronously in the foreground.
- **Keep the log on disk, not in context.** `./mvnw … > /tmp/b.log 2>&1; tail -5 /tmp/b.log;
  grep -E '^\[ERROR\]|Tests run:.*Fail|BUILD' /tmp/b.log | head -40` — then open the log for detail.
  An `[INFO]`-anchored grep hides failures; never verify with one.

## Delegation model

Plans in `docs/plans/` are authoritative specs; implementation is delegated to subagents with
**disjoint file scopes**; every task ends with its scoped build green and `MODULE.md` updated in place.

- **Fable** — architecture only. No code, no tests.
- **Every implementing agent and sub-agent is Sonnet.** Opus orchestrates: it decides the waves, the
  scopes and the flow, and may write code and configuration itself when a wave is too small to delegate.
- **One task is one branch.** Many sub-tasks under one big task → sub-branches, then merge.
- **Commit each wave as it lands.** An agent never commits, but the orchestrator must: uncommitted
  work in a shared tree is destroyed by the next branch switch, and a killed agent leaves a
  half-edited file behind.
- **Context comes from the documentation, not from re-reading the code.** Write the context file
  before starting; summarise it and update the docs after finishing.

## Architecture rules

1. **No magic numbers, no hardcoding.** Values live in configuration at the project root
   (`application.properties`, `.env`) — or in code only if they are mathematical constants.
   Varies per deployment → config. Varies at runtime → database + cache layer. Varies per call →
   request parameter. Root configuration applies to **all** modules, a standalone module included —
   a change on main must reach it too.
2. **Layers are packages, and abstraction rises with each one.** Repository (one table) → DTO service
   (its own repository only) → feature service (composes lower services) → orchestration service →
   controller (authn/authz, nothing else). Example: `/position-of-drone` → `DroneInFlightPositioningService`
   → `DroneTelemetryService` + `DroneGPSService` → their repositories.
3. **A module is standalone.** It knows nothing about who calls it or who listens. The contract is the
   interface; every module carries its API description in `MODULE.md`.
4. **SOLID and IoC**, in design and in implementation. Records are fine. Details and the checklist:
   `.claude/skills/java-clean-code/SKILL.md` — read it before adding any interface, service or
   constructor parameter.
5. **A new collaborator means updating the call sites**, or bundling into a settings record — never one
   more constructor overload, and never a parameter whose contract is "null means the feature is off".
   The "N-1-arg convenience constructor" convention is **withdrawn** (it produced ten constructors on
   `UsageTracker`, nine on `StreamPipeline`). Rationale: `java-clean-code` §3.
6. **Docs are diagrams and markdown, not code snippets**, and must serve both an agent and a person.
   Code comments are javadoc, short, explaining **why** — not what. No plain comment for something
   obvious at first sight.
7. **Failsafe and up-to-date are priorities.** The newest telemetry/detection/data wins, even when an
   older sample is still available. Degrade honestly: never show a stale value *as if* it were current.
8. **Build a feature on the previous feature.** Code and flows are understandable by their
   responsibility — that is what makes the next feature cheap.

## Deployment

- The app runs on many servers: every run path must be reflected in `docker-compose.yml`.
- Non-core modules must be scalable. Core modules must be scalable too when they hold calculation logic.

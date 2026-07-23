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
| vision-domain | `vision-domain/` | Framework-free domain: models + ports (in/out) |
| vision-application | `vision-application/` | Use-case services, StreamPipeline, UsageTracker |
| vision-proto | `vision-proto/` | gRPC codegen from `proto/vision/v1/cv.proto` |
| adapter-simulation | `adapters/adapter-simulation/` | Synthetic video + telemetry sources (`sim`) |
| adapter-rtsp | `adapters/adapter-rtsp/` | RTSP/FFmpeg ingest |
| adapter-mjpeg | `adapters/adapter-mjpeg/` | MJPEG HTTP ingest + TX simulator |
| adapter-publish-hls | `adapters/adapter-publish-hls/` | H.264 RTSP push → mediamtx (HLS viewing) |
| adapter-discovery | `adapters/adapter-discovery/` | ONVIF / mDNS / V4L2 scanners |
| adapter-cv-grpc | `adapters/adapter-cv-grpc/` | DetectionPort via gRPC to cv-service |
| adapter-overlay | `adapters/adapter-overlay/` | Placeholder (Phase 2): overlay rendering |
| adapter-persistence | `adapters/adapter-persistence/` | Placeholder (Phase 2): JPA/Postgres |
| vision-api | `vision-api/` | REST + static web console (driving adapter) |
| vision-app | `vision-app/` | Spring Boot assembly, wiring, devsupport, ArchUnit |
| vision-web | `vision-web/` | Angular 21 SPA (built into the jar via frontend-maven-plugin; `-DskipWeb` to skip) |
| cv-service | `cv-service/` | Python gRPC CV service (echo stub; YOLO in Phase 2) |

## Cross-cutting facts

- **Dependency rule (ArchUnit-enforced):** domain ← application ← adapters ← app; adapters never depend on each other; Spring only in vision-app/vision-api/adapters (never domain/application).
- **Ids:** entity ids wrap `java.util.UUID` (`X.random()`, `X.of(String)` → IllegalArgumentException on bad input). `CategoryId` is a kebab-case slug. `DeviceType` enum no longer exists — categories are data.
- **Asset model:** users interact with `Asset` (owned, categorized, 1..n devices, attributes map); `Device` is low-level plumbing; `AssetUsage` records sessions + telemetry.
- **Validation idiom:** domain records validate in compact constructors with manual `if (…) throw new IllegalArgumentException(…)`; application layer uses `Objects.requireNonNull`.
- **Jackson 3** (`tools.jackson.*`) under Spring Boot 4 — not `com.fasterxml.jackson.databind`; `java.time` serializes natively; annotations still `com.fasterxml.jackson.annotation`.
- **Build:** `./mvnw -B verify` (full; FFmpeg natives + docker-based tests, allow ~10 min cold). Single module: `./mvnw -B -pl <path> test`. Adapter integration tests use the real `docker` CLI (mediamtx) and skip without docker.
- **Never run reactor-wide builds while another agent's task holds modules red** — scope builds with `-pl`.

## Delegation model (how this repo is built)

Plans in `docs/*-PLAN.md` are authoritative specs; implementation is delegated to subagents with disjoint file scopes; every task ends with its scoped build green and MODULE.md updated.

# MEDIA-SOT — execution context

Working ledger for [MEDIA-SOT-PLAN.md](MEDIA-SOT-PLAN.md). The plan is the spec; this file
is state. Update the status column as waves land; keep decisions that the plan did not
foresee in §3 so the next agent (or the next context window) inherits them.

**Branch:** `feat/media-sot`, cut from `feat/cv-rate-control` at `610b114`.
Waves commit onto this branch with disjoint file scopes. One commit per wave.

## 1. Wave status

| Wave | Scope | Depends on | Status |
|---|---|---|---|
| M0 | `cv-service/spikes/pull/` — measure, decide decoder + clock mode | — | **done — GO** |
| M1 | `proto/vision/v1/cv.proto` + `vision-proto` | — | **done** |
| M2 | `vision-domain` — `PulledDetectionPort`, `proxiesSource` | — | **done** |
| M3 | `cv-service/cv_service/pull/` — the worker | M0, M1 | **done** |
| M4 | `adapters/adapter-cv-grpc` — Java pull port | M1, M2 | **done** |
| M5 | `vision-application` + `vision-api` — pull-mode pipeline | M2, M4 | **done** |
| M6 | `adapters/adapter-publish-hls` — proxy publisher + frame grab | M2 | **done** |
| M7 | `vision-app` + `docker-compose.yml` — wiring, flags | M5, M6 | **done** |
| M8 | `vision-web` — client overlay truth + WHEP box timing | M1 | **done** |
| M9 | `docs/` — measure, amend CV-SCALE §S5 | M7, M8 | **done** |

## 2. Gate

**M0 returned GO** (2026-08-12). `CV_PULL_DECODER=opencv`, `CV_PULL_CLOCK_MODE=anchor`, both
chosen by number — see [CV-PULL-SPIKE.md](../../conclusions/CV-PULL-SPIKE.md). The gate is
discharged; M3/M5/M6 may proceed against the amended contract.

**M9 re-measured every §1 claim against the real, live stack and all five held** — see
[MEDIA-SOT-RESULTS.md](../../conclusions/MEDIA-SOT-RESULTS.md). The programme is closed: nothing
in this plan gates further work. Two items remain open, gated on hardware this repo does not own
yet (the H1 camera, TWO-TARGETS-PLAN.md), not on anything left to build here: real-camera
glass-to-glass, and §6's clock-drift re-measurement against a real camera oscillator.

## 3. Decisions taken during execution (not in the plan)

- **`burnedIn` on `StartStreamResponse`/`ActiveStreamResponse` is M5's**, not M7's or M8's.
  §5.4 froze the field but §8 assigned no owner; M5 already owns the `vision-api` DTOs.
  M8 therefore treats an absent `burnedIn` as `true` — today's behaviour, per D1.
- **M0's three corrections are folded into the plan** (§5.3, §5.5, §6), so the plan stays the
  single source of truth. Do not read the pre-amendment §5.3 from an earlier context window.
  The blocking one: mediamtx's Control API is IP-gated and `MTX_API: "yes"` alone yields 401
  from a sibling container — **M7 must mount a config or set credentials**, and the
  `MTX_AUTHINTERNALUSERS` env shortcut was measured not to work.
- **The GB4005 ceiling is inference, not decode** (~198 ms p50, PyTorch CPU → ~5 fps). Decode is
  1.9–4.3 ms there. This does not change any wave's scope, but it is the number that decides how
  many streams that box can serve, and OpenVINO IR was cited from `DEPLOY-GPU.md`, not reproduced.
- **`anchor` drift is a floor, not a ceiling** — the spike had no independent camera oscillator.
  M9 must re-measure against a real H1 camera before the 100 ms budget is treated as settled.
- **D12 (new, in the plan): `PullTelemetry` rides on `DetectionResult`, and M5 adds it.** M4 could
  decode wire fields 16–21 but four of them had no domain home. Mapping M5 must implement and
  document: `source_fps`→`sourceFps`, `achieved_fps`→`submittedFps`, `missed_deadlines`→
  `missedDeadlines`, `dropped_frames`→`droppedInFlight` (the worker's latest-wins discards are the
  pull analogue of the JVM's in-flight drops), `decode_millis`→the new `decodeMillisP50`.
  `capture_skew_millis` has no read-model home — log it, do not widen the frozen §5.4.
- **M7 has a hard compile dependency, not just wiring:** M4 grew `GrpcCvSettings`'s canonical
  constructor by the new pull fields, and `vision-app`'s `CvWiring#toGrpcCvSettings` still calls the
  old arity. `vision-app` will not compile until M7 updates it. Expected, but it means **no
  reactor-wide build will pass until M7 lands** — keep every build scoped until then.
- **M6 found two things M7 must respect.** (1) With `source-proxy.on-demand=true` mediamtx never
  dials until a viewer connects, so the readiness poll is skipped in that mode — without that skip
  every on-demand start would time out. (2) A proxied source pointing at another path on the *same*
  mediamtx must use the container-internal RTSP port, not the host-mapped one; a wrong address
  fails silently as a readiness timeout, not a create error.
- **M3 found a real constraint the contract does not mention:** OpenCV's
  `OPENCV_FFMPEG_CAPTURE_OPTIONS` is **process-global and read at `open()`**, so `rtsp_transport`
  cannot be set per connection. M3 serializes opens on a module lock and restores the previous
  value. If a deployment ever needs two transports at once on one worker, that is the thing that
  breaks first — and it is an argument for the PyAV fallback, not a bug in the loop.
- **`CV_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS` (default 100 ms) is new**, added by M3: §5.1/§5.5 name
  a re-anchor threshold but pin no number, so M3 reused the plan's own 10-minute drift gate as the
  per-event trigger rather than inventing a second constant.
- **`MediamtxLiveFrameGrabber` is built but not wired** into `StreamService` — M7 plumbing.
- **Boxes-mode logic lives in `shared/player/detection-overlay-logic.ts`**, not `fly-logic.ts` as
  §8 M8 assumed — the wall tile needed the identical burn-in-aware cycle.
- **M9 found `docker-compose.yml` never set `VISION_PUBLISH_MEDIAMTX_API_BASE` for `vision-app`**,
  unlike its `rtsp-base`/`hls-base`/`whep-base` siblings — flagged and deliberately deferred by
  M7's own hardening pass, fixed in M9 (the one product-adjacent line M9's scope allowed): container
  port `9997` at the compose-internal `mediamtx` hostname, not the loopback-bound `19997` host
  mapping.
- **M9 found a real defect in pull mode's rate readout, unrelated to anything M9 built:**
  `DetectionRateWindow.snapshotPull()` hard-codes `submitted=0L`, so `DetectionRate.dropRatio()`
  (`droppedInFlight / (submitted+droppedInFlight+droppedOutage)`) evaluates to a permanent `1.0`
  whenever the worker's latest-wins loop has discarded anything — which is the normal, healthy case
  whenever source fps exceeds target fps. D8's actual promise (drops counted, never silent) holds;
  the defect is one level up, in a derived field `DetectionRateWindowPullModeTest` never asserts.
  Reported in [MEDIA-SOT-RESULTS.md](../../conclusions/MEDIA-SOT-RESULTS.md) §6, not fixed — out of
  a docs-only wave's scope.
- **M9 also found the repo has no `.dockerignore` anywhere**, so `docker compose build cv-service`
  transfers `cv-service/.venv` (1.7 GB) plus the untracked `spikes/geo/`/`demo/` residue §11.7
  already named as debt — together explaining a 1.97 GB build-context transfer that helped exhaust
  this session's own disk mid-measurement (MEDIA-SOT-RESULTS.md §1). Reported, not fixed — out of
  scope for the same reason.
- **The `vision_postgres-data` docker volume in this dev environment held Flyway checksums from an
  older jar** than M9's build — a pre-existing, unrelated staleness, not something this wave
  introduced. M9 sidestepped it (`VISION_PERSISTENCE_ENABLED=false`, `VISION_AUTH_ENABLED=false`
  for the measurement session only, via an uncommitted `docker-compose.override.yml`) rather than
  reset the volume, since resetting it is exactly the kind of destructive action a docs-only wave
  should not take unilaterally.

## 4. Working rules for wave agents

- Read the module's `MODULE.md` and its direct dependencies' before touching code; update
  it in the same wave. Do not re-read sources for surface the doc already gives.
- Scoped builds only (`-pl <module>`). Never a reactor-wide build — other waves run
  concurrently and may hold their modules red.
- Stay inside your wave's file scope. Scopes are disjoint by design; crossing one is how
  two agents lose each other's work.
- Do not commit. The orchestrator commits per wave, by pathspec.

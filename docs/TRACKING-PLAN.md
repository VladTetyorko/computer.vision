# TRACKING-PLAN — the tracking engine (detect-then-track duty cycle)

Status: **draft for review** (2026-08-11). Implements **S1** of docs/TWO-TARGETS-PLAN.md ("the one
real hole in the core", matrix row **K2**), extended with the detect-then-track duty-cycle
architecture the user specified: two perception loops on the same stream, running at very different
rates, cooperating so that **detection stops loading the machine once a target is captured**.

Priorities, in the user's order: **extensibility, scalability, performance — then pipeline
mechanics.** The tracker is a first-class, pluggable, per-stream-configurable stage of the CV
pipeline, built the way `ModelRegistry` was built, not a bolt-on.

Two further hard requirements, added after the first draft and folded in as **§3.3** and **§3.4**:

- **Portable, not just scalable** — the same cv-service package, contract, registries and env-var
  settings must run on a laptop, on the GB4005 LAN box, **and onboard a Raspberry Pi companion
  computer flying on the airframe** (§3.3).
- **Generic, not just scalable** — the pipeline emits one **sink-agnostic track update stream**;
  the same coordinates drive a user interface on the server and (deferred, gated) steer the aircraft
  onboard, with no consumer-specific logic anywhere in the pipeline (§3.4).

**Neither required a single change to the frozen wire contract of §4 — that is the point of both.**

> **Companion doc: [docs/TRACKING-ORCHESTRATION.md](TRACKING-ORCHESTRATION.md)** — component
> boundaries (one responsibility per file), the per-frame orchestration sequence, the separated
> configuration layering, DTO scalability rules, and the flow-visibility contract. It amends §4 in
> three additive ways (a `detector_reason` field, `TrackingTelemetry` on `DetectionResult`, and
> nested track objects in JSON); **all three are written into §4 below**, so §4 remains the single
> source of truth for the wire. Every wave reads §4 here and its own row in that doc's §9.

---

## 1. Goal, in the operator's terms

> "The detector finds things. Once it's found the thing I care about, the tracker takes over and
> follows it frame by frame — cheaply — and the detector drops to a slow double-check that re-approves
> the coordinates and re-finds it if it's lost. Every box keeps the same number as it crosses the
> frame, through a brief occlusion. And I can click a box to lock onto it."

Made precise:

- **Mode A — `ASSOCIATE` (ambient, multi-object).** Every detection carries a stable `trackId` that
  survives across sampled frames and through a short occlusion. Costs **~0.75 ms/frame** on top of
  the detector pass (measured, §8). This is what makes `trackId` exist everywhere, and it is what S2
  (fixed-camera geolocation), C2 (click-to-follow), C12 (cross-sensor fusion) and target
  trajectories are all blocked on.
- **Mode B — `FOLLOW` (locked, single/few-target).** A cheap per-frame visual tracker holds one
  locked target at the source's frame rate; the full detector drops to a **duty-cycled verify pass**
  (default every 2 s, or on trigger) that re-approves the box and re-anchors the coordinates. The
  measured per-frame tracker cost is **0.37 ms** (§8) against **41–343 ms** for a YOLO pass
  (cv-service/MODULE.md) — that is the whole point of the design.
- **Mode `OFF`** is today's behavior, byte-identical, and is the default until the final wave.

---

## 2. Current state (honest)

| Layer | Today | Gap this plan closes |
|---|---|---|
| `TrackedObject` (domain) | `record TrackedObject(long trackId, Detection detection, Instant firstSeen, Instant lastSeen)` — `vision-domain/.../model/TrackedObject.java:13`. Referenced by **its own test and nothing else in any `src/main`** | becomes the application-layer track-book entry, and the read model behind `GET /api/streams/{id}/tracks` |
| `Detection` (domain) | `record Detection(String label, double confidence, BoundingBox box, ModelRef model)` — 4 components, no identity across frames | gains one nullable component, `TrackRef track` |
| `proto` | `Detection{label, confidence, box}`; `FrameRequest` fields 1–10; `DetectionResponse` fields 1–7 | additive: track fields on `Detection`, `TrackingConfig` on `FrameRequest`, tracking telemetry on `DetectionResponse` |
| cv-service | `InferenceServicer.DetectStream` is **stateless per stream** apart from `_StreamReader`; `_handle_request` runs one detector pass per received frame (`cv_service/grpc/servicers.py:282–356`) | a per-stream `StreamTrackingSession` created inside `DetectStream` (same shape as `_StreamReader`) owning the duty-cycle scheduler and the tracker instance |
| cv-service extensibility | `ModelRegistry` — lazy `{model_id → YoloDetector}`, roster logged once at startup (`cv_service/inference/registry.py`) | a `TrackerRegistry` — lazy `{engine_id → TrackerEngine}`, same shape, same roster log |
| `StreamPipeline` | 927 lines; `onDetectionResult` already fans one filtered result out to 5 consumers (`StreamPipeline.java:845–860`) | **one more line** on that existing fan-out (`trackBook.accept(filtered)`) + one accessor. No new responsibility |
| `DetectionExtrapolator` | matches boxes between the last two results by an IoU gate (`extrapolationMatchGate`) | matches by `trackId` when present — exact instead of heuristic. Strictly better, contained in that class |
| adapter-cv-grpc | `DetectionFrameCodec.encode/decode` maps every proto field 1:1; sticky per-`StreamId` bidi session (`DetectionStreamSession`) | encode `TrackingConfig`, decode track fields. The per-stream session is **already** the right shape for per-stream tracker state |
| adapter-persistence | `detection_results` table, `detections` is a **jsonb blob** of `List<Detection>` (`V3__history.sql:48–57`) | **no migration needed** — a new `Detection` component rides along in the jsonb for free (§4.C) |
| vision-api SSE | topic `detections:<assetId>`, envelope `type:"detections"`, DTO `DetectionResponse(label, confidence, box, modelId, modelVersion)` | DTO gains the track fields; topic/envelope/endpoint all unchanged |
| PATCH surface | `PATCH /api/streams/{id}/config` — confidence, fps, labelFilter, detectionEnabled, model (docs/CV-CONTROL-PLAN.md §3) | one more optional object, `tracking` — including the target lock. **No new endpoint for click-to-follow** |
| vision-web | Fly CV control panel (`features/fly/cv-control-panel.*` + `-logic.ts`); player canvas overlay with hover hit-testing over `drawnBoxes` (`shared/player/player.ts:1963`) | tracking controls in the existing panel; `#id` on boxes, trails, click-to-follow off the existing hit-test |

**`TrackedObject` being dead is the whole reason S1 exists** (matrix K4's dead-type audit found it).
This plan is the one that makes it live.

---

## 3. Where the two loops live, what they emit, and where it all runs

**Both loops live in cv-service, per stream.** Not in Java. The reasons are structural, not stylistic:

1. Frames already flow Java→Python over `Inference.DetectStream`. Tracking in Java would mean either
   shipping frames a second time or decoding them twice — the inference box exists to carry CV load.
2. The tracker must see **every** frame it is given, in order, with the pixels. cv-service already
   holds the decoded `np.ndarray` (`decode_frame`); Java holds a `ByteBuffer` it deliberately
   downscales and JPEG-encodes on the way out (`DetectionFrameCodec`).
3. The duty-cycle scheduler needs to decide *per frame* whether to spend a detector pass. Putting
   that decision behind a network hop would make it a round-trip-latency problem instead of a local
   branch.
4. Keeping it cv-service-internal keeps the wire contract small: Java states **what it wants**
   (mode, engine, cadence, lock); cv-service decides **when to spend the detector**.

**Java stays protocol-thin.** The application layer does *not* re-implement tracking; it keeps a
**track book** — a read model of what arrived — plus the lifecycle timestamps `TrackedObject` needs.
There is exactly one tracker implementation in the tree, and it is in Python.

### 3.1 The duty cycle, precisely

Per received frame, inside one stream's session:

| Mode | Every frame | Detector pass when |
|---|---|---|
| `OFF` | — | every received frame (today's behavior, unchanged) |
| `ASSOCIATE` | associate the detector's boxes to existing tracks (~0.75 ms) | every received frame — cv-service only ever receives *sampled* frames at `inferenceFps`, so there is nothing to duty-cycle here |
| `FOLLOW` | `engine.update(frame)` on the locked target (~0.37 ms) | **any** of: (a) `now − last_detector_pass ≥ verify_every_millis` (default 2000); (b) the tracker reports failure or sub-threshold confidence; (c) no lock is held (re-acquire); (d) the predicted box has left the frame or collapsed; (e) the track has been `COASTING` for more than `max_age_frames` |

On a `FOLLOW` verify pass, the detector's boxes are matched to the tracked box by IoU:

- **IoU ≥ `redetect_iou_threshold`** (default 0.3) → **re-anchor**: the tracker is re-initialized on
  the detector's box, label/confidence refresh, state `CONFIRMED`, source `DETECTOR` for that frame.
- **IoU < threshold** → keep tracking, state `COASTING`, `age_frames` increments. After
  `max_age_frames` consecutive misses → `LOST`, the lock is dropped, and the next verify pass
  re-acquires per policy.

**Hard rule: the tracker update never acquires `InferenceGate`.** The gate exists to bound
concurrent *YOLO* passes across streams (`cv_service/inference/concurrency.py`); a 0.4 ms tracker
update queueing behind a 300 ms `orion12l` pass on another stream would destroy the entire design.
This gets its own test (§7, T1).

### 3.2 Track lifecycle

`TENTATIVE → CONFIRMED → COASTING → LOST → (expired, id retired)`

- `TENTATIVE` — born; fewer than `min_hits` (default 3) detector confirmations. **Not published
  with an id-bearing state to the UI as a stable track** — this is the anti-flicker gate the
  TWO-TARGETS spec asks for.
- `CONFIRMED` — `min_hits` reached; a real object.
- `COASTING` — tracker-predicted only; the detector has not re-confirmed it on the most recent pass.
  Renders differently (dashed box) so the operator can see the system is extrapolating, not seeing.
- `LOST` — unmatched for `max_age_frames` (default 30, ≈2 s at 15 fps, and the same value ByteTrack's
  own `track_buffer` uses). Kept in the buffer so a re-appearance after an occlusion **recovers the
  same id** — this is what makes the "same box number through a pole" outcome real.
- Expired — dropped from the book; the id is never reused within the stream's life.

### 3.3 Deployment portability: server ↔ companion computer

**Hard requirement.** The whole tracking flow — services, engines, models, config — must be
**repeatable on the drone as well as on the server**. Not "portable in principle": the *same*
`cv-service` package, running the *same* `Inference.DetectStream` contract, the *same*
`TrackerRegistry`/`ModelRegistry`, configured by the *same* `CV_*` env vars, in three placements:

| # | Placement | Frames reach cv-service by | Status |
|---|---|---|---|
| **(a)** | **Co-located** — laptop/server, cv-service beside vision-app | localhost gRPC | works today |
| **(b)** | **LAN inference box** — GB4005, `vision.cv.endpoint` points at it, rsync deploys | LAN gRPC (REMOTE-CV keepalive tuning) | works today |
| **(c)** | **Onboard companion computer** — Raspberry Pi 4/5, ARM64, flying on the airframe | localhost gRPC, fed by an onboard frame-feeder (§3.3.2) | **the new requirement**; needs H4 hardware |

**cv-service is the deployable unit.** It has no notion of which placement it is in — `Settings`,
the registries and the servicers are placement-blind, and that is exactly why (c) costs no
redesign. Two properties of *this plan's own choices* are what make (c) reachable rather than
aspirational, and they are therefore **design invariants, not conveniences**:

> **Invariant P1 — no x86-only or CUDA-only dependency may enter the `cv` extra, ever. Every
> shipped tracker engine must be pure core-OpenCV or pure Python.**
>
> **Invariant P2 — per-stream tracking state is configured declaratively, per frame
> (`TrackingConfig`, §4.A). No out-of-band control channel, no session-establishment handshake.**

P1 is why §5.B's roster is `bytetrack` / `lk` / `ncc` and not something CUDA-accelerated. P2 is why
a lossy, high-latency radio link cannot desynchronize the tracker: every frame restates the whole
desired state, so a dropped frame costs nothing and a reconnect needs no resynchronization step.
(OpenVINO stays a **Docker-image-only** dependency, exactly as today — deliberately not in the `cv`
extra, so it never becomes an x86 shackle. The Pi does not want it; see §3.3.3.)

**The frozen wire contract of §4 needs no change for any of this, and that is the point.** The same
`FrameRequest`/`DetectionResponse` that flows laptop→localhost flows Java→GB4005 and
feeder→localhost-on-the-Pi. Portability was bought by *not* putting placement, transport or
consumer assumptions into the contract in the first place.

#### 3.3.1 What the Pi actually changes

Nothing in waves T0–T8. It changes three operational facts, all folded into T1:

- **Dependency pins must be aarch64-real.** Verified by downloading the actual wheels (§8):
  `opencv-python 5.0.0.93` ships `cp37-abi3-manylinux2014_aarch64` (50.6 MB), `lap 0.5.13` ships
  `cp312-cp312-manylinux2014_aarch64` (1.7 MB), `ultralytics 8.4.104` is `py3-none-any`, and
  `torch 2.13.0` ships `cp312-cp312-manylinux_2_28_aarch64` (**427 MB**). glibc ≥ 2.28 is required
  by the torch wheel — Raspberry Pi OS Bullseye (2.31) and Bookworm (2.36) both satisfy it.
- **R2's `lap` pin matters double.** A Pi in flight is by definition offline. Ultralytics'
  AutoUpdate reaching for `lap` over the network mid-stream is an annoyance on a LAN box and a
  **guaranteed in-flight failure** onboard. Pin it (T1).
- **On aarch64 there is no `--extra-index-url .../whl/cpu` step** — PyPI's plain `torch` for ARM
  *is* the CPU build (there are no generic-ARM CUDA wheels). The extra index is an x86-only
  concern; the ARM install line is simply `pip install -e '.[cv]'`.

#### 3.3.2 The onboard topology, honestly scoped

Onboard, **the Java stack stays on the ground.** vision-app, the SPA, Postgres and mediamtx are
ground infrastructure and stay there. So something on the Pi must feed frames to the co-located
cv-service. Two options:

- **(a) Ground-side vision-app keeps sending frames over the radio link.** Zero new code — it is
  literally placement (b) with a slower link. But it ships **MB/s of video down and detections back
  up**, which is the exact cost the onboard placement exists to avoid. It defeats the purpose.
- **(b) A thin onboard frame-feeder.** A small Python process on the Pi: capture from the Pi camera
  / a UVC camera via OpenCV, speak the **existing, frozen** `Inference.DetectStream` contract to
  `localhost:50051`, and forward only the `DetectionResponse`s up the link — **KB/s, telemetry-class
  bandwidth**, instead of MB/s of video. Video stays local (or is published separately at whatever
  bitrate the link affords, independently, exactly as it is today).

**(b) is the real onboard story.** It maps to **H4** in docs/TWO-TARGETS-PLAN.md (companion
computer, deliberately sequenced after H3).

> **Named deferred deliverable: the onboard frame-feeder.** Its interface is **fixed now** — it
> speaks the §4.A proto and nothing else. It is therefore buildable later without touching a single
> line of waves T0–T8, and it is **not** a wave in this plan: **H4 hardware does not exist yet**, and
> building a feeder against an imagined camera and an imagined link would be exactly the fake-capability
> this repo's doctrine forbids. It is ~150 lines of Python when the hardware lands.

#### 3.3.3 Image strategy on ARM: documented pip + rsync, not a multi-arch image

**Decision: do not build a multi-arch (`linux/amd64,linux/arm64`) image. Document a plain
`pip install -e '.[cv]'` + rsync path for ARM**, matching the GB4005 pattern the repo already
operates and `cv-service/DEPLOY-GPU.md` already documents.

Rationale: the amd64 image's whole reason to exist is the **OpenVINO IR export** baked in at build
time (`Dockerfile:65-68`) — an Intel-CPU optimization. OpenVINO's ARM plugin is a complication the Pi
does not want and would not benefit from proportionally. Maintaining a second architecture of a
1.5 GB image whose defining feature is inapplicable to that architecture is cost with no payoff. The
Pi path is plain PyTorch CPU (or an NCNN/ONNX export, below), installed the same way a developer
installs it locally, deployed by the same rsync the GB4005 already uses. **T1 adds the documented
ARM install path to `cv-service/MODULE.md`; it does not add a buildx pipeline.**

**The torch escape hatch, stated honestly.** 427 MB of torch on a Pi is real, and dropping it is not
free: ultralytics imports torch, and `bytetrack` comes from ultralytics — so a torch-free onboard
profile loses the associator too. That is precisely the eventuality `TrackerRegistry` is insurance
against (§5.C): the torch-free profile is `onnxruntime` (or NCNN) for the detector plus an in-repo
~200-line IoU+Kalman engine registered under the same `engine_id`. **Documented seam, not built** —
build it if and when a measured Pi says torch is the blocker, not before.

#### 3.3.4 Models on the Pi

**No new mechanism is needed.** `ModelRegistry`'s directory-scan roster (`discover_roster` globs
`*.pt` / `*_openvino_model` under `Settings.model_dir`) plus the `active_model.json` marker plus
rsync **is already the model-export mechanism**, and it works identically on the Pi: drop a
checkpoint in, restart, `ListModels` shows it, `PromoteModel` selects it, and the marker survives
the restart. A model trained on a GPU box and rsync'd to GB4005 today is rsync'd to a Pi tomorrow by
the same command.

The realistic Pi detector budget is a **YOLO nano `.pt` at `CV_IMGSZ=320`** rather than 416.
**And this is where the duty cycle stops being an optimization and becomes the enabling
mechanism**: continuous detection on a Pi is not viable, but a verify pass every ~2 s against a
0.4 ms/frame tracker plausibly is. **Caveat, stated plainly: no Pi number in this plan is measured —
there is no H4 hardware. Everything above is a designed-for budget, and it stays labelled as such
until someone runs it on real silicon.**

### 3.4 What the pipeline produces: a sink-agnostic track update stream

The pipeline is required to be **generic, not merely scalable**. The same tracking pipeline runs on
the server and on the drone, and its *outcome* — where the target is — is consumed **differently per
placement**: on the server those coordinates drive a user interface; on the drone the same
coordinates steer the aircraft or its gimbal toward the target.

**The pipeline's product is a track update stream**, and that stream is the plan's core abstraction:

| Quantity | Where it comes from |
|---|---|
| stable track id | `Detection.track_id` |
| lifecycle state | `Detection.track_state` |
| normalized box | `Detection.box` (origin top-left, `[0,1]`) |
| velocity | `Detection.velocity_x/y` — normalized units per second |
| which loop saw it | `Detection.source` + `DetectionResponse.detector_ran` |
| the held target | `DetectionResponse.locked_track_id` |
| derived center | `(box.x + box.width/2, box.y + box.height/2)` — **computed by the consumer** |

**That table is exactly the frozen contract of §4. There is no separate "TrackUpdate" message, and
the wire contract needs zero changes to serve any of the sinks below — that is the point.** The
genericity was bought by keeping consumer concepts out of the contract, not by adding a layer.

> **Invariant P3 — no consumer-specific logic ever enters cv-service or the application-layer track
> book. cv-service knows nothing about user interfaces, maps, camera poses, fields of view, or
> autopilots. The track book is a read model serving any registered consumer, not the SSE topic's
> private cache.**

#### 3.4.1 The three sinks

All three consume the identical stream. None of them requires the others.

**Sink 1 — UI (server).** SSE → boxes, ids, trails, click-to-follow. Waves T5–T7. Consumer-local
knowledge it adds: screen size, canvas scale, colour palette. **Built by this plan.**

**Sink 2 — Geo / COP (server, S2).** Box bottom-center + camera pose → ground coordinate → a COP
mark carrying the track id, so a tracked object leaves a **trail on the map**. Consumer-local
knowledge it adds: camera lat/lon/AGL/yaw/pitch/FOV (asset attributes) and `GeoProjection`.
**docs/TWO-TARGETS-PLAN.md §S2 — this plan supplies its input, and nothing more.**

**Sink 3 — Guidance (onboard, deferred + gated).** The locked track's **offset from frame center is
the error signal for a centering controller**. Consumer-local knowledge it adds: the camera's FOV
(from asset attributes) and the vehicle's mode/authority state.

```
DetectionResponse{locked_track_id, Detection{box, velocity_x/y, track_state}}   ← frozen contract, unchanged
  → offset      = (box.centerX - 0.5, box.centerY - 0.5)          normalized, consumer-computed
  → angularErr  = (offset.x * hFovDeg, offset.y * vFovDeg)        needs FOV — consumer-side only
  → P/PD controller (deadband, rate limit; track_state LOST/COASTING → hold, then abort)
  → actuation:  GIMBAL_MANAGER_SET_PITCHYAW              (gimbal centering)
             |  SET_POSITION_TARGET_LOCAL_NED velocity   (airframe steering, GUIDED)
```

> **Named deferred deliverable: the guidance controller.** The *interface* is frozen now — the error
> signal is fully derivable from §4.A. The *actuation* half is *not built here and is gated exactly
> like RC-CONTROL Phase 2 and command TX (matrix I-e): it requires an explicit user go and its own
> plan.* This plan builds C2's **advisory** tier — a locked box on a screen — and stops there. No
> wave in §7 touches an airframe.

#### 3.4.2 Derived quantities stay consumer-side — the genericity boundary

Center offset, angular error, and ground coordinate are **all** derivable from the frozen wire
fields plus knowledge only the consumer has (FOV, camera pose, screen geometry, vehicle authority).
They are therefore **not wire fields**, and the contract stays frozen.

This is the right boundary because the alternative compounds: adding `center_offset` for the
guidance sink invites `ground_lat`/`ground_lon` for the geo sink and `canvas_x`/`canvas_y` for the
UI sink, and cv-service would end up coupled to every consumer that will ever exist — needing a
camera pose it has no business knowing, on a box that in placement (c) is flying. A normalized box
and a velocity are the least cv-service can emit that every sink can use, and the least is correct.

---

## 4. Frozen wire contract

Everything in §4 is frozen. All waves code against it and may parallelize. Field numbers, enum
names, JSON property names and status codes are pinned exactly.

### 4.A `proto/vision/v1/cv.proto` — full additive diff

Proto3 additive rules hold throughout: every new field/enum defaults to zero-value, so an **old
client against a new server** sends no `tracking` (→ `OFF`, today's behavior) and an **old server
against a new client** returns no track fields (→ `trackId` absent, Java treats it as untracked).
No existing field number, name or type changes. Enum value names are prefixed because proto3 enum
values are namespace-scoped to the enclosing `package` (the existing `JobState` already occupies the
bare `RUNNING`/`SUCCEEDED`/`FAILED` names).

```proto
// ---------------------------------------------------------------- NEW enums

// How the two perception loops cooperate for one stream (docs/TRACKING-PLAN.md §3).
enum TrackingMode {
  TRACKING_MODE_UNSPECIFIED = 0;  // old client / not stated -> treated as OFF
  TRACKING_MODE_OFF         = 1;  // detector only, no track ids -- byte-identical to pre-tracking
  TRACKING_MODE_ASSOCIATE   = 2;  // Mode A: associate every detection across frames
  TRACKING_MODE_FOLLOW      = 3;  // Mode B: per-frame visual tracker, detector duty-cycled
}

// Lifecycle state of the track a Detection belongs to.
enum TrackState {
  TRACK_STATE_UNSPECIFIED = 0;  // no tracking ran for this detection
  TRACK_STATE_TENTATIVE   = 1;  // born, below min_hits -- not yet a stable identity
  TRACK_STATE_CONFIRMED   = 2;  // confirmed by the detector on this or a recent pass
  TRACK_STATE_COASTING    = 3;  // tracker-predicted; detector has not re-confirmed yet
  TRACK_STATE_LOST        = 4;  // unmatched past max_age_frames; kept alive for re-acquisition
}

// Which loop produced this particular box on this particular frame.
enum DetectionSource {
  DETECTION_SOURCE_UNSPECIFIED = 0;  // old server / not stated
  DETECTION_SOURCE_DETECTOR    = 1;  // a full detector pass ran on this frame and produced this box
  DETECTION_SOURCE_TRACKER     = 2;  // the per-frame visual tracker produced this box
}

// -------------------------------------------------------------- NEW messages

// WHY a detector pass was spent on this frame. `detector_ran` says whether; this says why.
// Values match the trigger list of §3.1 one-for-one. See TRACKING-ORCHESTRATION.md §5.1 --
// without this, "why is my detector still running in FOLLOW mode?" is answerable only by
// reading cv-service logs, which on a flying companion computer is not answerable at all.
enum DetectorReason {
  DETECTOR_REASON_UNSPECIFIED    = 0;  // old server, or no detector pass ran on this frame
  DETECTOR_REASON_ALWAYS         = 1;  // mode OFF/ASSOCIATE -- every received frame gets a pass
  DETECTOR_REASON_CADENCE        = 2;  // (a) verify_every_millis elapsed
  DETECTOR_REASON_TRACKER_FAILED = 3;  // (b) tracker reported failure / sub-threshold confidence
  DETECTOR_REASON_NO_LOCK        = 4;  // (c) no lock held -- re-acquire
  DETECTOR_REASON_BOX_INVALID    = 5;  // (d) predicted box left the frame or collapsed
  DETECTOR_REASON_COASTED_OUT    = 6;  // (e) COASTING longer than max_age_frames
}

// Which object FOLLOW mode should hold.
//
// Identified by `lock_seq`: cv-service applies a lock only when `lock_seq` is strictly
// greater than the last one it applied for this stream. Restating the identical lock on
// every frame is therefore idempotent -- which is REQUIRED, see TrackingConfig below.
message TargetLock {
  int64 lock_seq  = 1;  // monotonic per stream; 0 = no lock has ever been issued
  int64 track_id  = 2;  // lock onto an existing track; 0 = use point/box instead
  float point_x   = 3;  // normalized [0,1] click point, used when track_id == 0
  float point_y   = 4;
  BoundingBox box = 5;  // optional explicit box; unset -> a server-side default box around the point
  bool  release   = 6;  // true = drop the current lock, fall back to the config's mode policy
}

// Declarative per-stream tracking state, restated on EVERY FrameRequest.
//
// Deliberately declarative, never an imperative one-shot control message: cv-service's
// LatestOnlyMailbox silently DROPS a frame when the sender gets ahead of the consumer
// (cv-service/MODULE.md, "LatestOnlyMailbox drops a frame with no response at all"), so a
// one-shot control message could be lost with no error and no retry. A restated desired
// state is self-healing -- the next frame carries it again.
message TrackingConfig {
  TrackingMode mode             = 1;
  string       engine_id        = 2;  // "" = server default for the mode
  int32  verify_every_millis    = 3;  // FOLLOW detector re-verify cadence; <=0 = server default (2000)
  float  redetect_iou_threshold = 4;  // re-anchor when detector<->tracker IoU >= this; <=0 = default (0.3)
  int32  max_age_frames         = 5;  // unmatched frames before LOST;      <=0 = default (30)
  int32  min_hits               = 6;  // detector hits TENTATIVE -> CONFIRMED; <=0 = default (3)
  TargetLock lock               = 7;  // FOLLOW only; absent = no lock held
}

// ---------------------------------------------------------- CHANGED messages

message FrameRequest {
  string stream_id             = 1;
  int64 sequence               = 2;
  int64 timestamp_millis       = 3;
  int32 width                  = 4;
  int32 height                 = 5;
  ImageEncoding encoding       = 6;
  bytes data                   = 7;
  string model_id              = 8;
  string model_version         = 9;
  float confidence_threshold   = 10;
  TrackingConfig tracking      = 11;  // NEW -- absent => TRACKING_MODE_OFF
}

message Detection {
  string label            = 1;
  float confidence        = 2;
  BoundingBox box         = 3;
  int64 track_id          = 4;  // NEW -- 0 = untracked. Real ids start at 1, per stream.
  TrackState track_state  = 5;  // NEW
  DetectionSource source  = 6;  // NEW
  float velocity_x        = 7;  // NEW -- normalized frame-WIDTHS per second (matches BoundingBox units)
  float velocity_y        = 8;  // NEW -- normalized frame-HEIGHTS per second
  int32 track_age_frames  = 9;  // NEW -- frames since this track was born
}

message DetectionResponse {
  string stream_id             = 1;
  int64 sequence               = 2;
  int64 timestamp_millis       = 3;
  string model_id              = 4;
  string model_version         = 5;
  repeated Detection detections = 6;
  int64 inference_millis       = 7;
  int64 tracker_millis         = 8;   // NEW -- per-frame tracker cost; 0 when no tracker ran
  bool  detector_ran           = 9;   // NEW -- false => this frame was tracker-only (duty-cycled)
  string tracker_engine_id     = 10;  // NEW -- engine that actually served this frame ("" = none)
  int64 locked_track_id        = 11;  // NEW -- FOLLOW: the track currently held (0 = none)
  DetectorReason detector_reason = 12; // NEW -- why the pass ran; UNSPECIFIED when it did not
}
```

**`track_id == 0` means "untracked"** and must never reach the Java domain as a track. cv-service
allocates ids starting at **1** per stream (ultralytics' `BYTETracker` already does exactly this —
verified, the first allocated id is `1`).

**`detector_ran` is the honesty field.** It is what lets the UI render a tracker-predicted box
differently, lets the app layer weight trust, and lets the demo *show* the CPU drop: in `FOLLOW` at
15 fps with a 2 s verify cadence, roughly **1 frame in 30** carries `detector_ran = true`.

### 4.B Domain additions (`vision-domain`)

Three new types plus **one** new component on each of two existing records. The repo's
N-1-arg convenience-constructor idiom (used by `PipelineConfig`, `Telemetry`, `Asset`) keeps every
existing call site compiling unchanged.

```java
public enum TrackState  { TENTATIVE, CONFIRMED, COASTING, LOST }
public enum DetectionSource { DETECTOR, TRACKER }

/** Per-frame track facts observed for one detection. */
public record TrackRef(long trackId, TrackState state, DetectionSource source,
                       double velocityX, double velocityY, int ageFrames) {
    // trackId >= 1  (0 is the wire's "untracked" sentinel and must never reach here)
    // state, source non-null; velocities finite; ageFrames >= 0
    public TrackRef(long trackId, TrackState state, DetectionSource source) { … 0, 0, 0 }
}

/** NEW canonical 5-arg; the existing 4-arg becomes a convenience ctor delegating track = null. */
public record Detection(String label, double confidence, BoundingBox box, ModelRef model,
                        TrackRef track) { … }   // track is NULLABLE = "untracked"

/** Per-stream tracking configuration; joins PipelineConfig. */
public record TrackingConfig(TrackingMode mode, String engineId, int verifyEveryMillis,
                             int followFps, int redetectIouPercent, int maxAgeFrames, int minHits,
                             TargetLock lock) {
    public static TrackingConfig off();       // the default -- mode OFF, no lock
    public static TrackingConfig defaults();  // ASSOCIATE, engineId "", cadences per §4.A
}

public enum TrackingMode { OFF, ASSOCIATE, FOLLOW }

/** Nullable on TrackingConfig; lockSeq is allocated by the application layer, never by a client. */
public record TargetLock(long lockSeq, Long trackId, Double pointX, Double pointY, boolean release) { … }

public enum DetectorReason { ALWAYS, CADENCE, TRACKER_FAILED, NO_LOCK, BOX_INVALID, COASTED_OUT }

/** Per-FRAME tracking facts (as opposed to TrackRef's per-DETECTION facts). */
public record TrackingTelemetry(boolean detectorRan, DetectorReason reason, Duration trackerLatency,
                                String engineId, long lockedTrackId) { … }

/** NEW canonical 6-arg; the existing 5-arg becomes a convenience ctor delegating tracking = null. */
public record DetectionResult(StreamId streamId, long frameSequence, Instant capturedAt,
                              List<Detection> detections, Duration inferenceLatency,
                              TrackingTelemetry tracking) { … }   // tracking NULLABLE = tracking off
```

**`TrackingTelemetry` closes a real gap, not a nicety** (TRACKING-ORCHESTRATION §5.2): `DetectionResult`
had five components and none could carry `detector_ran`, so §4.G's `detectorRan` DTO field had no
source — T4 would have decoded the field and dropped it. It is **one nullable component, not five flat
ones**, exactly as `TrackRef` is on `Detection`; the existing 5-arg canonical constructor lives on as a
convenience constructor, so every existing call site compiles unchanged. **T2 adds it, T4 must map it.**

`PipelineConfig` gains **one** component, appended last: `TrackingConfig tracking`. The current
9-arg canonical constructor becomes a convenience constructor delegating `TrackingConfig.off()`; the
new 10-arg becomes canonical. The 6-/7-/8-arg convenience chain is preserved. **`defaults()` returns
`TrackingConfig.off()` through waves T2–T7**, and is flipped to `TrackingConfig.defaults()`
(`ASSOCIATE`) in the final wave T8 — see §6.C.

`TrackedObject` is **not modified**. It becomes the application layer's track-book entry:
`trackId` is the book's key, `detection` its latest observation (whose `track()` carries the state),
`firstSeen`/`lastSeen` the lifetime. Note `TrackedObject.trackId` deliberately does **not** get a
compact-constructor invariant tying it to `detection.track().trackId()` — that would break the
record's existing tests and its ability to hold an untracked detection.

`redetectIouPercent` is an `int` percent (0–100), not a `double`, so the whole record is trivially
JSON- and PATCH-friendly and there is one fewer float-comparison test. Wire-side it converts to the
proto `float`.

### 4.C Persistence — no migration, and that is the honest answer

`detection_results.detections` is a **jsonb** column holding the serialized `List<Detection>`
(`V3__history.sql:48–57`). Adding a nullable `TrackRef` component makes the track facts ride along
in that blob **for free**, via Jackson record serialization. Consequences, stated plainly:

- **No Flyway migration in this plan.** The next free version stays `V13` for whoever needs it.
- **Rows written before this change must still deserialize**, with `track` reading as `null`. That
  is an explicit acceptance criterion (§7, T6), not an assumption.
- **Tracks are not queryable by id in SQL.** A durable, indexed `tracks` table (trajectory history,
  replay, cross-session identity) is **deferred to S2**, where trails on the map are the thing that
  actually needs it. Building it now would be building an index for a query nobody issues yet.

### 4.D REST — `PATCH /api/streams/{streamId}/config` gains one object

The existing endpoint, contract and status codes (docs/CV-CONTROL-PLAN.md §3) are unchanged. One
optional nested object is added; absent = leave tracking as-is.

```json
{
  "tracking": {
    "mode": "FOLLOW",
    "engineId": "lk",
    "verifyEveryMillis": 2000,
    "followFps": 15,
    "redetectIouPercent": 30,
    "maxAgeFrames": 30,
    "minHits": 3,
    "lock": { "trackId": 7 }
  }
}
```

- `lock` accepts exactly one of: `{"trackId": <long>}`, `{"pointX": <0..1>, "pointY": <0..1>}`, or
  `{"release": true}`. Two of the three present → **400**.
- **`lockSeq` is never sent by a client.** `DefaultStreamService` allocates it per stream
  (monotonic `AtomicLong`), so a client cannot replay a stale lock and the UI never tracks a counter.
- `mode` accepts `"OFF" | "ASSOCIATE" | "FOLLOW"`; anything else → **400**.
- **Click-to-follow is this call.** No new endpoint, no new controller. That is C2's advisory tier.

Response body gains one boolean alongside the existing `modelReArmed`:

```json
{ "streamId": "…", "modelReArmed": false, "trackingChanged": true }
```

`trackingChanged` is `true` iff the `tracking` object was present **and** produced a different
`TrackingConfig` than the running one. A mode/engine change never re-arms the detector — tracking is
a hot knob throughout, exactly like confidence and fps.

`StartStreamRequest` / `StartAssetStreamRequest` gain the same optional `tracking` object with the
same shape (minus `lock`, which is meaningless before a stream has tracks). Absent = default.

### 4.E REST — `GET /api/streams/{streamId}/tracks` (NEW)

Reads the in-memory track book of a running stream. Never errors; unknown/stopped stream → `200`
with an empty list (the same forgiving idiom `GET /api/streams/{id}/detections` already uses).

```json
{
  "streamId": "…",
  "lockedTrackId": 7,
  "tracks": [
    { "trackId": 7, "label": "car", "confidence": 0.82,
      "box": { "x": 0.31, "y": 0.44, "width": 0.09, "height": 0.07 },
      "state": "CONFIRMED", "source": "TRACKER",
      "velocityX": 0.012, "velocityY": -0.001, "ageFrames": 143,
      "firstSeen": "2026-08-11T10:22:31.104Z", "lastSeen": "2026-08-11T10:22:40.671Z" }
  ],
  "stats": {
    "mode": "FOLLOW", "engineId": "lk", "windowSeconds": 30,
    "detectorPasses": 12, "trackerFrames": 348, "dutyRatio": 0.034,
    "trackerMillisP50": 0.4, "trackerMillisP95": 0.9,
    "lastDetectorReason": "CADENCE",
    "byState": { "TENTATIVE": 0, "CONFIRMED": 3, "COASTING": 1, "LOST": 2 }
  }
}
```

`lockedTrackId` is `0` when no lock is held. Tracks are ordered by `trackId` ascending.

**`stats` is computed Java-side** by `TrackingStatsWindow` (a peer of `TrackBook`, T3) from responses
that already arrive — **no new wire field, no new endpoint, and no read-model concern inside
cv-service** (invariant P3). It is what makes the flow visible in the product rather than in `htop`,
and it works identically in every placement, including onboard. `engineId` reports the engine that is
actually *serving* (from `tracker_engine_id`), which is not necessarily the one requested — see R11.
TRACKING-ORCHESTRATION §5.4 and §7.

### 4.F REST — `GET /api/cv/trackers` (NEW)

The engine roster for the picker. **Config-backed static list in vision-app**, exactly mirroring the
frozen decision `GET /api/cv/models` already made (docs/CV-CONTROL-PLAN.md §D) — the roster changes
at deploy time, not runtime. Never errors; always returns at least the built-ins.

```json
{
  "trackers": [
    { "id": "bytetrack", "displayName": "ByteTrack (multi-object)", "modes": ["ASSOCIATE"],
      "needsAssets": false, "costHint": "~0.8 ms/frame" },
    { "id": "lk", "displayName": "Optical flow (fast follow)", "modes": ["FOLLOW"],
      "needsAssets": false, "costHint": "~0.4 ms/frame" },
    { "id": "ncc", "displayName": "Template match (robust follow)", "modes": ["FOLLOW"],
      "needsAssets": false, "costHint": "~0.6 ms/frame" }
  ]
}
```

### 4.G SSE — additive DTO fields only

Topic (`detections:<assetId>`), envelope (`type:"detections"`), event name (unnamed → `message`),
coalescing tick and ring-buffer capacity are **all unchanged**. `DetectionResponse` (the vision-api
DTO at `dto/DetectionResponse.java:14`) gains five optional fields, `@JsonInclude(NON_NULL)` so an
untracked detection's payload is byte-identical to today's:

```json
{ "label": "car", "confidence": 0.82, "box": { … },
  "modelId": "yolo26n.pt", "modelVersion": "latest",
  "track": { "id": 7, "state": "CONFIRMED", "source": "TRACKER",
             "velocityX": 0.012, "velocityY": -0.001 } }
```

`DetectionResultResponse` gains a sibling `"tracking"` object so the client can render a duty-cycled
frame honestly:

```json
// a tracker-only frame (the common case in FOLLOW: ~29 of every 30)
{ "tracking": { "detectorRan": false, "trackerMillis": 0.4, "engineId": "lk", "lockedTrackId": 7 } }

// the verify frame that re-anchored it
{ "tracking": { "detectorRan": true, "detectorReason": "CADENCE", "trackerMillis": 0,
                "engineId": "lk", "lockedTrackId": 7 } }
```

**`detectorReason` is present iff `detectorRan` is true** — it answers *why the pass ran*, so it is
absent (proto `UNSPECIFIED`) on a tracker-only frame, and `TrackingTelemetry` enforces that pairing.
The "what was the last reason" question the flow strip asks is answered by `stats.lastDetectorReason`
(§4.E), which is a window over frames, not a fact about this one.

**Track facts nest; they are not five flat fields** (TRACKING-ORCHESTRATION §5.3 and §6 rule 1). An
untracked payload stays **byte-identical to today** — one absent key under `NON_NULL` instead of
five — and the client gets a single null check (`d.track?.id`) gating all track rendering rather than
five optional fields that can disagree. Ten bytes per tracked box at 6 ticks/second does not buy a
widening DTO. **The proto stays flat, deliberately**: a nested submessage allocates an extra object
per detection per frame, and proto3 field numbers are already its scaling mechanism. The layers
differ because their costs differ.

`ageFrames` is deliberately **not** on the SSE payload — it is book-keeping the tracks endpoint
carries, not something a box needs 6× per second.

---

## 5. Design decisions, with rationale

### A. `TrackerRegistry` mirrors `ModelRegistry` exactly — that is the extensibility answer

```
cv_service/tracking/
  params.py      TrackingParams + resolve(wire_config, settings) -- the ONLY place a <=0 sentinel
                   becomes a number. Resolved on config CHANGE, never per frame
  scheduler.py   DutyCycleScheduler.decide(now, state) -> Decision(run_detector, reason) -- PURE policy
  track.py       Track + TrackBook: the born->confirmed->coasting->lost->expired state machine
  lock.py        LockArbiter: lock_seq monotonicity + target selection
  registry.py    TrackerRegistry: lazy {engine_id -> factory}, TWO rosters, startup constructibility
                   probe, roster logged once at INFO
  engines/
    base.py      TWO protocols -- Associator (ASSOCIATE) and SingleObjectTracker (FOLLOW)
    bytetrack.py ByteTrackEngine  -- ultralytics BYTETracker            (Associator)
    lk.py        LkFlowEngine     -- Lucas-Kanade sparse optical flow   (SingleObjectTracker, default)
    ncc.py       NccEngine        -- matchTemplate NCC in a search win  (SingleObjectTracker)
  session.py     StreamTrackingSession: COMPOSITION ONLY, ~80 lines, ONE INSTANCE PER STREAM
```

**Two protocols, not one** — `bytetrack` can never implement `update(frame)` and `lk` can never
implement `associate(detections)`; a single protocol forces every engine to stub half of itself, on
the seam the whole extensibility story rests on. `GET /api/cv/trackers` already returns `modes: []`
per engine (§4.F) — the wire anticipated this. **And the decision logic is split out of `session.py`**:
everything above except `engines/` is pure stdlib, so the scheduler, the lifecycle machine and the
lock arbiter are testable with a fake clock — no frames, no OpenCV, no gRPC. Charters, the per-frame
sequence and the resolve-once rule: TRACKING-ORCHESTRATION §2.1, §2.2, §3.1, §4.2.

Same conventions as `cv_service/inference/`: **no module imports `cv_pb2`** (`grpc/servicers.py`
stays the sole translation point); engines are lazily constructed; an unknown `engine_id` logs once
and falls back to the mode's default; an engine that fails to construct degrades to the next mode
down, never to an exception. `discover_roster`'s "log the roster once at INFO so an operator can see
what's routable without reading code" convention is copied verbatim.

**Engine state is per stream, never shared.** `TrackerRegistry` hands out *factories*, not
singletons — the opposite of `ModelRegistry`, which caches one detector per id because a YOLO model
is stateless across streams and expensive to load. A tracker is the reverse: cheap to build,
inherently stateful. Getting this backwards is the single easiest way to corrupt every stream at
once, so it is called out here and gets its own test.

### B. The engine roster is dictated by what is actually installed — and it is not what you'd expect

**Measured in this repo's own `cv-service/.venv` (§8), not assumed.** The venv has
**opencv-python 5.0.0.93**, and in OpenCV 5 **`TrackerKCF`, `TrackerCSRT` and `TrackerMOSSE` no
longer exist**, and `cv2.legacy` is absent. The only `Tracker*` symbols present are `TrackerMIL`,
`TrackerDaSiamRPN`, `TrackerNano`, `TrackerVit` — and the last three each raise on construction
because they need ONNX weights that opencv-python does not ship.

So the plan does **not** ship KCF/CSRT/MOSSE, and does **not** add `opencv-contrib-python`
(pyproject.toml already documents why a second `cv2`-providing package silently clobbers the first
on disk — the same trap as `opencv-python-headless`). Instead:

| `engine_id` | Mechanism | Measured cost | Assets | Verdict |
|---|---|---|---|---|
| **`bytetrack`** | ultralytics `BYTETracker` — IoU + Kalman motion, two-stage high/low-confidence association | **0.75 ms** (10 dets, mean; p95 0.80 ms) | none — already a dependency | **ASSOCIATE default** |
| **`lk`** | `cv2.calcOpticalFlowPyrLK` over ≤40 `goodFeaturesToTrack` corners inside the box; median translation + scale | **0.37 ms** (40 pts, 640×360) | none — core OpenCV `video` module, confirmed built | **FOLLOW default** |
| **`ncc`** | `cv2.matchTemplate` (`TM_CCOEFF_NORMED`) of the box template in a 2× search window | **0.60 ms** (80×80 tpl in 160×160 win) | none — core OpenCV | **FOLLOW alternative** — more robust on feature-poor targets |
| `mil` | `cv2.TrackerMIL` | **24.4 ms** | none | **rejected** — 65× the budget; documented so nobody re-proposes it |
| `nano` / `vit` / `dasiamrpn` | `cv2.TrackerNano` / `TrackerVit` / `TrackerDaSiamRPN` | not measured | **ONNX from opencv_zoo, not shipped** | **deferred** — a documented seam; the registry makes them a file drop plus a Dockerfile fetch, not a redesign |

This is a **better** outcome than KCF/CSRT would have been: `lk` and `ncc` are ~4× and ~2.5× cheaper
than KCF typically is, and they carry zero new dependencies onto a 2-core box.

### C. `bytetrack` is used as a library, not reimplemented — with the licence noted

`ultralytics.trackers.BYTETracker` is already in the tree (ultralytics 8.4.104, AGPL-3.0, already a
dependency for YOLO itself). Its `update(results)` takes any object exposing `.conf`, `.xywh`,
`.cls`, `__len__` and boolean `__getitem__` — a ~15-line adapter over cv-service's own plain
`Detection` dataclasses, no ultralytics `Results` needed. It returns an `(N, 8)` array
`[x1, y1, x2, y2, track_id, conf, cls, det_idx]`. Verified end-to-end in this survey.

Writing our own IoU+Kalman associator would be ~200 lines to reproduce something already installed,
tested and tuned. **But**: because ultralytics is AGPL and a future "swap ultralytics out" decision
would now also lose the tracker, `TrackerRegistry` is the insurance — `bytetrack` is one engine
among several, behind a protocol, replaceable in one file.

### D. `FOLLOW` raises the sample rate through the existing sampling machinery — one line

In `FOLLOW`, the tracker wants frames faster than `inferenceFps` (default 10). `StreamPipeline`
already recomputes `sampleEveryNthFrame` on **every** frame from `config.inferenceFps()`
(`everyNth()`, `StreamPipeline.java:689`), reading the volatile config live. So the entire change is
making `everyNth` divide by the **effective** rate:

```java
private int effectiveInferenceFps() {
    TrackingConfig t = config.tracking();
    return t.mode() == TrackingMode.FOLLOW ? Math.max(config.inferenceFps(), t.followFps())
                                           : config.inferenceFps();
}
```

`followFps` defaults to **15, not "every frame"** — deliberately. At 15 fps a downscaled 640px JPEG
is ~1.2 MB/s per stream, fine on a LAN and survivable over the VPN link REMOTE-CV-PLAN contemplates;
"every frame" at 30 fps doubles that for a tracker that gains almost nothing from the extra samples
at typical target speeds. It is configurable, and the honest number to tune against measurement.

`maxInFlightInferences` stays at 2. In `FOLLOW`, ~29 of every 30 responses cost ~0.4 ms of server
work instead of 41–343 ms, so the round trip is network-dominated and the in-flight bound essentially
stops biting. That is a **measured acceptance criterion** (§7, T4), not a claim.

### E. The application layer keeps a book, not a tracker

`StreamPipeline.onDetectionResult` already fans one filtered `DetectionResult` out to five
consumers (`StreamPipeline.java:845–860`). Tracking adds **one line** to that existing list:

```java
trackBook.accept(filtered);   // sits beside extrapolator.accept / eventEngine.accept
```

plus one accessor, `List<TrackedObject> tracks()`, mirroring `latestDetections()`. `TrackBook` is a
new ~120-line class in `vision-application/pipeline` holding `Map<Long, TrackedObject>` with
`firstSeen`/`lastSeen` maintenance and expiry of `LOST` tracks. It **does no association** — it books
what arrived. This is deliberately not a tenth `StreamPipeline` responsibility (matrix K3 / S3 wants
that class decomposed, not grown); it is one more entry on a fan-out list that already exists, and
`TrackBook` is a peer of `DetectionExtrapolator`/`DetectionEventEngine`, carved cleanly so S3's
decomposition inherits a well-shaped perception stage rather than a fatter method.

### F. `trackId` makes `DetectionExtrapolator` exact — a free win

The extrapolator today matches boxes between the last two results with an IoU gate
(`extrapolationMatchGate`) because it has no identity to match on. When both results carry
`trackId`, matching by id is exact: no gate, no mismatch when two same-class objects cross, correct
velocity through an occlusion. Fall back to the IoU gate when either side is untracked. Fully
contained in `DetectionExtrapolator`, and it makes burned-in boxes visibly better on the demo.

### G. Guardrail — the default leaves every existing test green, and the flip is a named step

`PipelineConfig.defaults()` ships `TrackingConfig.off()` for waves **T2–T7**. Consequences:

- Every existing `PipelineConfig`, `StreamPipeline`, adapter and API test compiles and passes
  unchanged (the 9-arg canonical constructor lives on as a convenience constructor).
- `FrameRequest.tracking` serializes as absent → cv-service takes the `OFF` branch → byte-identical
  behavior to today.
- `Detection.track()` is `null` everywhere → the SSE payload, the jsonb blob and the burned overlay
  are byte-identical to today.

**Wave T8 flips the default to `ASSOCIATE`** in one line, with the end-to-end test that proves it.
Two steps instead of one, on purpose: every wave lands independently green, and the moment behavior
changes is a single reviewable commit rather than a side effect of wave T2.

There is **no** new boot flag. `vision.cv.enabled` already kills CV wholesale, and
`TrackingMode.OFF` is a per-stream off-switch that is strictly better than a JVM-wide one — the same
reasoning docs/CV-CONTROL-PLAN.md §C used for `detectionEnabled`.

### H. Stream→worker affinity becomes load-bearing

docs/CV-SCALE-PLAN.md §S4 already commits to **sticky-least-loaded** routing —
"a given stream's frames always go to the same CV worker" — and `adapter-cv-grpc` already holds one
bidi `DetectionStreamSession` per `StreamId`. Today that stickiness is an *optimization*. After this
plan it is a **correctness requirement**: tracker state lives in the worker, so a stream re-routed
mid-flight silently resets every track id. This plan does not build the pool, but it records the
constraint on it, and `PooledDetectionPort` must carry a test for it when it is built.

### I. Graceful degradation, at every level

| Failure | Behavior |
|---|---|
| Tracker engine unavailable / fails to construct | fall back `FOLLOW → ASSOCIATE`; log once per engine id (WARNING), same shape as `ModelRegistry`'s load-failure handling |
| `bytetrack` unavailable (e.g. `lap` missing) | fall back `ASSOCIATE → OFF`; detections still flow, just without ids |
| Model unavailable | echo, exactly as today — tracking never changes the model-unavailable contract |
| Tracker raises mid-frame | that frame reports the detector's boxes untracked; the session resets the engine; the stream never dies (same "one bad frame must not kill the stream" rule as `_handle_request`) |
| Old cv-service, new Java | no track fields on the response → `track()` null → `ASSOCIATE` behaves as `OFF`. No error |
| Old Java, new cv-service | no `tracking` on the request → `OFF`. No error |

### J. Portability is an invariant, not an aspiration

**No x86-only or CUDA-only dependency may enter the `cv` extra; every shipped tracker engine must be
pure core-OpenCV or pure Python** (§3.3, invariant P1). This is the rule that makes the *same*
cv-service package deployable to a laptop, to GB4005, and to an onboard Raspberry Pi. It is stated
as a rule rather than left implicit because it is the kind of constraint that is free to hold and
expensive to recover: a single CUDA-gated engine, or OpenVINO promoted from the Dockerfile into the
extra, silently ends the onboard story. The measured engine roster (§5.B) already satisfies it —
`bytetrack` is pure Python over numpy, `lk` and `ncc` are core OpenCV — which is a second, unplanned
reason the OpenCV 5 finding turned out well.

### K. The genericity boundary: normalized geometry out, consumer knowledge in

cv-service emits a normalized box and a velocity. It does **not** emit center offsets, angular
errors, or ground coordinates, because each of those requires knowledge that belongs to exactly one
consumer — screen geometry, camera FOV, camera pose — and putting any of them on the wire would
couple cv-service to every sink that will ever exist (§3.4.2). The same stream therefore drives a
UI, a map projection, and (deferred, gated) a centering controller with **zero pipeline rework** —
each sink adds its own local knowledge and nothing is added to the contract. This is what "generic,
not just scalable" means concretely, and it is why §4 could be frozen before any sink but the UI
existed.

---

## 6. Non-goals / deferred (named, not dropped)

- **Cross-sensor fusion (C12)** — one deduped track from two cameras. Needs this plan's track ids
  *and* S2's geolocation first. Not built here.
- **Geolocation projection of tracks (S2)** — tracks are its input; the projection, the camera-pose
  attributes and the calibration flow are docs/TWO-TARGETS-PLAN.md §S2, not this plan.
- **A durable `tracks` table / trajectory replay** — §4.C. Tracks ride the existing jsonb; the
  indexed table lands with S2, which is the first thing that queries it.
- **Trails burned into the published video.** `Java2DOverlayRenderer` is stateless and `render()`
  has no stream identity in its signature (`AnnotatedFrame` is single-frame), so a burned trail would
  need either a domain change or per-stream renderer state. Trails are a **client vector overlay**
  concern in v1 — `DetectionsStore` already accumulates results client-side, so the SPA gets trails
  nearly free. The burned overlay gets `#7 person 0.87` and a dashed box for `COASTING`; that is
  enough for an HLS-only viewer.
- **Commanded click-to-follow** (gimbal slew, aircraft GUIDED follow — C2's D1/D3 tiers). This plan
  builds the **advisory** tier only: a locked box in the UI. Anything that moves an airframe is
  gated exactly like I-e and needs its own explicit go.
- **The guidance controller (sink 3, §3.4.1).** Its *input interface is frozen now* — the error
  signal is fully derivable from §4.A — but the P/PD controller and the
  `GIMBAL_MANAGER_SET_PITCHYAW` / `SET_POSITION_TARGET_LOCAL_NED` actuation are **a separate,
  explicitly-gated plan**, on the same footing as RC-CONTROL Phase 2 and command TX (I-e). No wave
  in §7 touches an airframe.
- **The onboard frame-feeder (§3.3.2).** Its interface is frozen now — it speaks the §4.A proto and
  nothing else, so it is buildable later without touching waves T0–T8. Deliberately **not** a wave:
  **H4 hardware does not exist yet**, and building it against an imagined camera and an imagined
  radio link would be fake capability. ~150 lines of Python when the hardware lands.
- **A torch-free onboard profile** (`onnxruntime`/NCNN detector + an in-repo IoU+Kalman engine) —
  a documented seam (§3.3.3), built only if a *measured* Pi says 427 MB of torch is the blocker.
- **A multi-arch container image.** ARM deploys by documented `pip install -e '.[cv]'` + rsync,
  matching the GB4005 pattern; §3.3.3 gives the rationale.
- **ONNX SOT engines** (`nano`, `vit`, `dasiamrpn`) — deferred behind the registry, §5.B.
- **Re-identification across streams or across a stream restart.** Ids are per stream, per session,
  never reused within a session, and start fresh on restart.
- **Text-prompted YOLOE, cross-stream batching, pull-based frames** — unchanged non-goals of
  CV-CONTROL-PLAN / CV-SCALE-PLAN respectively.
- **UI redesign.** The tracking controls join the existing Fly CV panel; track ids and trails join
  the existing player canvas. No new page, no new IA.

---

## 7. Implementation waves (disjoint file scopes)

Each wave ends **independently green** with its scoped build and its `MODULE.md` updated in the same
task. Sequencing:

```
T0 (proto) ─┬─> T1 (cv-service)          ─┐
            └─> T4 (adapter-cv-grpc) ─┐   │
T2 (domain) ─┬─> T3 (application) ────┼───┼─> T8 (default flip + demo)
             ├─> T4                   │   │
             ├─> T5 (adapter-overlay) ─┤   │
             └─> T6 (api/app/persist) ─┘   │
T7 (web-ui, against the frozen contract) ──┘
```

**T0 and T2 start immediately and in parallel.** T7 starts immediately too and integrates last.

---

### T0 — proto: freeze the wire — `proto/vision/v1/cv.proto`, `vision-proto/**`
**Agent: adapter-builder.** Build: `./mvnw -B -pl vision-proto test`.

- Apply §4.A **verbatim** — field numbers, enum names, comments included. Nothing else changes.
- Regenerate and confirm both codegens: `vision-proto`'s Maven protoc build, and
  `cv-service/scripts/gen_proto.sh` (generated tree is not committed — just confirm it runs).
- Acceptance: a `FrameRequest` built with no `tracking` serializes to the **byte-identical** payload
  it did before this wave (a round-trip test proving proto3 additivity, not an assertion in prose).
- `vision-proto/MODULE.md` updated with the new messages/enums and the "0 = untracked" sentinel.

### T1 — cv-service: the tracking engine + duty cycle — `cv-service/**`
**Agent: general-purpose (Python).** Build: `cv-service/scripts/test.sh`. Depends on T0.

- Build `cv_service/tracking/` per §5.A — **the 8-file split, one charter per file**. Every module
  stdlib+cv2+numpy only — **no `cv_pb2` import** — and everything except `engines/` must be **pure
  stdlib**, so `scheduler`/`track`/`lock`/`params` test against a fake clock with no frames and no
  OpenCV. Charters: TRACKING-ORCHESTRATION §2.1.
- **Two protocols** (`Associator`, `SingleObjectTracker`), §5.A / TRACKING-ORCHESTRATION §2.2. No
  engine stubs a method it cannot implement.
- **Separated configuration:** new `CV_TRACK_*` knobs resolved in `cv_service/config.py` `Settings`
  and nowhere else (the module's existing one-place rule); `params.resolve()` is the only place a
  `<=0` wire sentinel becomes a number; **no cadence/threshold/count literal may appear in
  `scheduler.py`, `session.py` or an engine.** Resolve on config *change*, never per frame
  (TRACKING-ORCHESTRATION §4.2) — compare the last-applied wire message by equality.
- Emit `detector_reason` on every response per §4.A: the scheduler already computes the reason as
  part of its decision, so this is a field on `Decision`, not a second code path.
- `TrackerRegistry` with the three engines of §5.B, lazy, **probing constructibility at startup** and
  logging the roster it actually got (R3, R11) rather than the one it hoped for.
  Engine instances are **per stream** (factories, not singletons).
- `StreamTrackingSession` created inside `DetectStream` (the same place `_StreamReader` is) and
  threaded into `_handle_request(request, session)`; the duty-cycle scheduler of §3.1 and the
  lifecycle of §3.2 live in it. `TargetLock` applied only on a strictly-greater `lock_seq`.
- **`pyproject.toml`: pin `lap>=0.5.12` into the `cv` extra, and pin an explicit `opencv-python`
  range.** Both are real defects today, see §9 R2/R3. Add a startup check that logs which engines
  are actually constructible on this box rather than assuming.
- `Dockerfile`: no new install step needed for the three shipped engines (verify, don't assume).
  Add a commented, ready-to-uncomment `curl` of the opencv_zoo ONNX assets for the deferred
  `nano`/`vit` engines, mirroring how the OpenVINO export step is documented. **Do not add a buildx
  multi-arch target** — §3.3.3.
- **ARM64 portability (§3.3).** Every pin chosen must have a real aarch64 wheel — verified for
  `opencv-python 5.0.0.93`, `lap 0.5.13`, `ultralytics 8.4.104` and `torch 2.13.0` (§8); re-verify
  whatever versions actually get pinned, don't inherit this list on faith. Note in `pyproject.toml`
  that the `--extra-index-url .../whl/cpu` line is **x86-only** (PyPI's ARM `torch` is already the
  CPU build). Assert invariant **P1** in the same comment: nothing x86- or CUDA-only enters this
  extra. `cv-service/MODULE.md` gains a short **"Running on ARM64 / a companion computer"** section:
  the `pip install -e '.[cv]'` + rsync path, the glibc ≥ 2.28 requirement of the torch wheel, the
  `CV_IMGSZ=320` Pi budget, and an explicit "no Pi numbers are measured yet" line.
- Tests: engine roster + unknown-engine fallback; per-stream isolation (two sessions, ids never
  collide or leak); `TENTATIVE→CONFIRMED` needs `min_hits`, so a 1-frame flicker spawns **no**
  confirmed id; a track occluded for < `max_age_frames` **recovers the same id**;
  `FOLLOW` runs the detector at the configured cadence and no more (count `detector_ran=true`
  responses over N frames); **the tracker update never acquires `InferenceGate`** (a gate of size 0…
  or, deterministically: a fake gate that records acquisitions, asserting zero acquisitions on
  tracker-only frames); a raising engine degrades that frame to untracked without killing the
  stream; `mode` absent → byte-identical `OFF` responses.
- Report **measured** per-frame tracker cost and `FOLLOW` detector duty ratio on this box, into
  `cv-service/MODULE.md` — a new "Tracking engine" section beside "Model registry & composite mode".

### T2 — domain: `TrackRef`, `TrackingConfig`, `TrackedObject` goes live — `vision-domain/**`
**Agent: domain-modeler.** Build: `./mvnw -B -pl vision-domain test`. Parallel with T0/T1.

- Add §4.B's `TrackState`, `DetectionSource`, `TrackRef`, `TrackingMode`, `TargetLock`,
  `TrackingConfig`, **`DetectorReason`, `TrackingTelemetry`**. Manual-`if`-throw compact-constructor
  validation, per the repo idiom.
- `Detection`: new canonical 5-arg ctor; existing 4-arg becomes a convenience ctor delegating
  `track = null`. **`DetectionResult`: new canonical 6-arg; existing 5-arg becomes a convenience ctor
  delegating `tracking = null`** — this is the gap fix of TRACKING-ORCHESTRATION §5.2, without which
  `detectorRan` never reaches the API. `PipelineConfig`: new canonical 10-arg; existing 9-arg becomes
  a convenience ctor delegating `TrackingConfig.off()`; the 6-/7-/8-arg chain preserved.
- `PipelineConfig.defaults()` returns `TrackingConfig.off()` — **not** `defaults()`. §5.G.
- `TrackedObject` is **not modified** — javadoc it as the application-layer track-book entry and
  delete the "dead type" note wherever it appears in `vision-domain/MODULE.md`.
- Tests: `TrackRef` rejects `trackId == 0` and negative `ageFrames`; ctor-chain equivalence (an
  existing 4-arg `Detection` equals a 5-arg with `track = null`); `defaults()` asserts `OFF`
  (documenting the deliberate two-step flip); `TargetLock` rejects two-of-three lock forms.
- `vision-domain/MODULE.md` updated.

### T3 — application: the track book + follow sampling + patch fold — `vision-application/**`
**Agent: application-service.** Build: `./mvnw -B -pl vision-application test`. Depends on T2.

- New `TrackBook` in `application/pipeline` (§5.E), a peer of `DetectionExtrapolator`. Javadoc it as
  **a read model serving any registered consumer, not the SSE topic's private cache** (invariant P3,
  §3.4): the UI sink reads it today, the geo sink (S2) and a guidance sink read the same book later.
  Concretely: no consumer-specific field, no map/FOV/screen concept, and no SSE reference in it.
- New `TrackingStatsWindow`, a **peer** of `TrackBook` (not a field on it): rolling counters over the
  `TrackingTelemetry` that now rides every result — detector passes, tracker frames, duty ratio,
  tracker-ms p50/p95, last reason, state histogram — over `vision.tracking.stats-window-seconds`.
  This is what backs §4.E's `stats` object and the T7 flow strip. TRACKING-ORCHESTRATION §2.3, §5.4.
- `StreamPipeline`: **two lines** in `onDetectionResult`'s existing fan-out (`trackBook.accept` and
  `trackingStats.accept` — a deliberate deviation from §5.E's "one line": folding the counters into
  `TrackBook` would give it two responsibilities to save a line in a list that already has five);
  `List<TrackedObject> tracks()` and `TrackingStats stats()` accessors; `effectiveInferenceFps()` per
  §5.D; clear the book **and the stats window** on a model re-arm exactly as `updateConfig` already
  clears the extrapolator.
- `DetectionExtrapolator`: match by `trackId` when both sides carry one, IoU gate otherwise (§5.F).
- `PipelineConfigPatch` gains a nullable `TrackingConfig tracking`; `DefaultStreamService.updateConfig`
  folds it, allocates `lockSeq` from a per-stream `AtomicLong`, and returns
  `UpdateOutcome(modelReArmed, trackingChanged)`.
- Tests (hand-fake ports, no Spring): a result carrying `trackId` books a `TrackedObject` with
  `firstSeen` preserved and `lastSeen` advanced across two results; a `LOST` track expires out of the
  book; `FOLLOW` raises the effective sample rate and `OFF`/`ASSOCIATE` do not; the extrapolator
  matches two crossing same-label boxes correctly by id where the IoU gate would have swapped them;
  `lockSeq` increments per lock and never decreases; a patch with no `tracking` leaves it untouched.
- `vision-application/MODULE.md` updated.

### T4 — adapter-cv-grpc: encode config, decode tracks — `adapters/adapter-cv-grpc/**`
**Agent: adapter-builder.** Build: `./mvnw -B -pl adapters/adapter-cv-grpc test`. Depends on T0 + T2.

- `DetectionFrameCodec.encode`: map `PipelineConfig.tracking` → `FrameRequest.tracking` (including
  `TargetLock`; `redetectIouPercent / 100f` → `redetect_iou_threshold`).
- `DetectionFrameCodec.decode`: map the per-frame fields (`detector_ran`, `detector_reason`,
  `tracker_millis`, `tracker_engine_id`, `locked_track_id`) → **`TrackingTelemetry` on
  `DetectionResult`** — dropping them here is the defect TRACKING-ORCHESTRATION §5.2 exists to
  prevent; map track fields → `TrackRef`; **`track_id == 0` → `track = null`**,
  never a `TrackRef(0, …)`. Unknown/`UNSPECIFIED` enum values map defensively (`UNSPECIFIED` state →
  no `TrackRef` at all rather than a guessed one), following this module's existing
  `JOB_STATE_UNSPECIFIED → RUNNING` "never guess terminal" posture.
- Tests: exact `TrackingConfig`/`TargetLock` captured server-side on the wire; a response with
  `track_id = 0` decodes to an untracked `Detection`; a response with no track fields at all (old
  server) decodes byte-identically to today; a malformed track field fails **only that one frame's
  future**, per this module's existing per-response failure contract.
- **Measure and record** the `FOLLOW`-at-15 fps in-flight behavior against `maxInFlightInferences=2`
  (§5.D) — an honest number in MODULE.md, or an honest "not measurable at unit level, deferred to
  the vision-app E2E" if it isn't reachable here.
- `adapters/adapter-cv-grpc/MODULE.md` updated.

### T5 — adapter-overlay: `#id`, track colour, coasting — `adapters/adapter-overlay/**`
**Agent: adapter-builder.** Build: `./mvnw -B -pl adapters/adapter-overlay test`. Depends on T2.
Parallel with T4.

- `DetectionBoxPainter.draw`: label text becomes `#7 person 0.87` when `detection.track()` is
  present, unchanged (`person 0.87`) when it is not.
- **Add `colorForTrack(long)`; do not touch `colorForLabel`** — its palette length is baked into
  pixel-probe test expectations (`Java2DOverlayRenderer.java:57`, MODULE.md). A tracked detection
  colours by track id (so one object keeps one colour as its label flips); an untracked one keeps
  today's label colour exactly.
- `TrackState.COASTING` draws a **dashed** border. Per this module's determinism convention, the
  dashed path must not be pixel-probed — assert it structurally (e.g. via a recording `Graphics2D`),
  or assert only that the solid path is unchanged for `CONFIRMED`.
- **No trails** — §6.
- Tests: untracked detections render byte-identically to today (a pixel probe, this being the
  regression that matters); tracked detections carry `#id` in the label bar; two tracks with
  different ids get different colours.
- `adapters/adapter-overlay/MODULE.md` updated.

### T6 — api / app / persistence: PATCH, tracks endpoint, SSE, jsonb — `vision-api/**`, `vision-app/**`, `adapters/adapter-persistence/**`
**Agent: spring-integrator.** Builds: `./mvnw -B -pl vision-api test`, `-pl vision-app test`,
`-pl adapters/adapter-persistence test`. Depends on T2 + T3.

- `StreamController`: `PATCH …/config` accepts §4.D's `tracking` object → `PipelineConfigPatch`;
  the one-of-three `lock` rule and a bad `mode` are **400**; response gains `trackingChanged`.
  New `GET /api/streams/{streamId}/tracks` per §4.E.
- `DetectionResponse` / `DetectionResultResponse` DTOs gain §4.G's **nested `track` / `tracking`
  objects** (not flat fields — §4.G, TRACKING-ORCHESTRATION §6 rule 1) with `@JsonInclude(NON_NULL)`;
  the `from(...)` static factories map them. New `TrackResponse`, `TrackStatsResponse` DTOs; the
  tracks endpoint returns §4.E's `stats` object beside `tracks`.
- **Deployment configuration lives here, not in the domain:** `vision.tracking.*` properties in
  vision-app (`default-mode`, `follow-fps`, `verify-every-millis`, `stats-window-seconds`) seed
  **new** streams only and never reach into a running one. `vision-domain` keeps pure literals.
  TRACKING-ORCHESTRATION §4.1, §4.3.
- `StartStreamRequest` / `StartAssetStreamRequest` gain the optional `tracking` object;
  `mergeOntoDefaults()` folds it.
- New `CvTrackersController` (or fold into `CvModelsController`): `GET /api/cv/trackers` from a
  `vision.cv.trackers` config-backed list in **vision-app**, mirroring `GET /api/cv/models`'s frozen
  decision. Never errors.
- **adapter-persistence: no migration.** Add the regression test that matters — a `detection_results`
  row whose jsonb was written **before** this change (a hand-written jsonb literal with no `track`
  key) still deserializes, with `track` reading `null`. Plus a round-trip test for a tracked
  detection. Note in MODULE.md that tracks are not SQL-queryable and why (§4.C).
- ArchUnit stays green (no adapter imports from vision-api).
- All three `MODULE.md`s updated.

### T7 — web-ui: tracking controls, ids, trails, click-to-follow — `vision-web/**`
**Agent: web-ui.** Build: `npm test` + `tsc` + production build, from `vision-web/`. Parallel against
the frozen contract; integrates last.

- `core/api/models.ts`: `Detection` gains **`track?: DetectionTrack`** (nested — one null check gates
  all track rendering); `DetectionResult` gains **`tracking?: FrameTracking`**; new
  `TrackingConfigRequest`, `TargetLockRequest`, `StreamTracksResponse`, `TrackStats`, `CvTracker`;
  `UpdateStreamConfigRequest` gains `tracking?`; `PatchStreamConfigResponse` gains `trackingChanged`.
- `core/api/vision-api.ts` + `core/fleet/fleet-store.ts`: `getCvTrackers()`, `getStreamTracks(streamId)`;
  `patchStreamConfig` already carries the new field for free.
- `features/fly/cv-control-panel.*` + `cv-control-panel-logic.ts`: a **Tracking** section — mode
  segmented control (Off / Associate / Follow), engine picker from the roster, verify-cadence and
  follow-fps sliders shown only in Follow, and a "Following #7 — release" chip **shown only when a
  response confirms the lock, never on click** (the honesty rule, TRACKING-ORCHESTRATION §3.3).
  Pure logic (patch building, mode gating) goes in `-logic.ts` with its spec, per the folder's
  established `x.ts / x.html / x.css / x-logic.ts / x-logic.spec.ts` convention.
- **The flow strip** — the visible-flow deliverable, in the same panel, fed by §4.E's `stats`:
  `DETECT 0.5/s ▸ TRACK 15/s · 1 in 30 · lk 0.4 ms · cadence`. It puts the duty-cycle claim on
  screen, so the plan's touchable outcome #2 stops requiring `htop` on a remote box. Show the engine
  that is **actually serving** (R11), not the one requested. TRACKING-ORCHESTRATION §7.
- `shared/player/player.ts` + `detection-overlay-logic.ts`: box label becomes `#7 car 82%`; box hue
  keyed on `trackId` when present (a new pure helper beside `modelHue`); a **dashed** stroke for
  `COASTING`; **trails** — a new pure helper accumulating box centres per `trackId` across
  `DetectionsStore.results()` (which already retains history), bounded to ~2 s of points and cleared
  on stream change; **click-to-follow** off the existing `drawnBoxes` hit-test (`player.ts:1963`) →
  emits the track id up to the cockpit, which PATCHes `{tracking:{mode:"FOLLOW", lock:{trackId}}}`.
- Load the `frontend-style` skill before styling; three-file components, no inline templates.
- Vitest for every new pure helper; `tsc` clean; production build green; component docs updated.

### T8 — flip the default, and prove the outcome — `vision-domain` (1 line) + `vision-app` E2E
**Agent: spring-integrator.** Builds: `-pl vision-domain test`, `-pl vision-app test`. Last.

- `PipelineConfig.defaults()` → `TrackingConfig.defaults()` (`ASSOCIATE`). One line. §5.G.
- Update the domain test that asserted `OFF` to assert `ASSOCIATE`, with a comment naming this wave.
- A `vision-app` end-to-end test against a real in-test gRPC server (the shape
  `CvDetectionE2ETest` already uses): a stream configured `ASSOCIATE` receives detections carrying
  stable `trackId`s across three frames, and the `GET …/tracks` endpoint reports them.
- **Portability acceptance (§3.3): the `cv` extra resolves clean on aarch64.** There is no ARM CI in
  this repo, so do **not** promise a green ARM build — the criterion is a *resolution* check, which
  needs no ARM hardware: `pip download --no-deps --platform manylinux2014_aarch64 --python-version
  312 --only-binary=:all:` succeeds for every pinned member of the extra. Record the command and its
  result in `cv-service/MODULE.md`. If someone has a Pi by then, an actual `pip install -e '.[cv]'`
  + `python -m cv_service.grpc.server` smoke run supersedes it; until then this is the honest bound.
- Run the §10 touchable-outcome checklist and record the **measured** CPU numbers in
  `cv-service/MODULE.md`.

---

## 8. Measured evidence (this survey, not estimates)

Measured in `cv-service/.venv` on this dev box (12 logical cores, under normal IDE load — directional,
not a clean isolated benchmark, same caveat cv-service/MODULE.md applies to its own numbers):

| What | Result |
|---|---|
| `opencv-python` version present | **5.0.0.93** |
| `cv2` `Tracker*` symbols present | `TrackerMIL`, `TrackerDaSiamRPN`, `TrackerNano`, `TrackerVit` — **no KCF, no CSRT, no MOSSE** |
| `cv2.legacy` | **absent** (`ModuleNotFoundError`) |
| ultralytics version | 8.4.104; ships `BYTETracker`, `BOTSORT`, `OCSORT`, `DeepOCSORT`, `FASTTracker`, `TRACKTRACK` + yaml defaults |
| `BYTETracker.update`, 10 detections | **0.75 ms** mean, 0.80 ms p95, 2.49 ms max; output `(N, 8)` = `[x1,y1,x2,y2,track_id,conf,cls,idx]`; first id allocated = **1** |
| `cv2.calcOpticalFlowPyrLK`, 40 corners, 640×360 | **0.37 ms** mean, 0.47 ms p95 |
| `cv2.matchTemplate` 80×80 template in 160×160 window | **0.60 ms** mean |
| `cv2.TrackerMIL.update`, 640×360 | **24.4 ms** mean — 65× the per-frame budget, rejected |
| `cv2.TrackerVit/Nano/DaSiamRPN` construction | raises — ONNX weights not shipped with opencv-python |
| YOLO detector pass, for contrast (cv-service/MODULE.md) | `yolo26n` ~41 ms · `yoloe-26s-seg-pf` ~85–100 ms · `orion12l` ~343 ms |

**The ratio that justifies the whole design: 0.37 ms vs 41–343 ms — between 110× and 930×.**

**ARM64 wheel availability (§3.3)** — verified by actually downloading each wheel for
`manylinux*_aarch64` / cp312, not by reading a compatibility table:

| Package | aarch64 wheel | Size |
|---|---|---|
| `opencv-python 5.0.0.93` | `cp37-abi3-manylinux2014_aarch64` | 50.6 MB |
| `lap 0.5.13` | `cp312-cp312-manylinux2014_aarch64` | 1.7 MB |
| `ultralytics 8.4.104` | `py3-none-any` (pure Python) | 1.4 MB |
| `torch 2.13.0` | `cp312-cp312-manylinux_2_28_aarch64` | **427 MB** — glibc ≥ 2.28 (Pi OS Bullseye 2.31 / Bookworm 2.36 both OK) |

Every dependency the tracking engine needs runs on ARM64 today. **No Pi runtime number is measured —
there is no H4 hardware.** Everything about onboard *performance* in this plan is a designed-for
budget, explicitly labelled as such.

---

## 9. Risks & decisions

| # | Risk / decision | Severity | Call |
|---|---|---|---|
| **R1** | **OpenCV 5 removed KCF/CSRT/MOSSE; `cv2.legacy` absent.** The obvious tracker roster does not exist on this box | **High — confirmed, not hypothetical** | Ship `lk` + `ncc` (core OpenCV, measured cheaper than KCF would have been) + `bytetrack`. **Do not add `opencv-contrib-python`** — pyproject.toml already documents the silent `cv2` file-clobbering that a second cv2-providing package causes |
| **R2** | **`lap` is a hidden runtime dependency of `BYTETracker`, installed by ultralytics' AutoUpdate — i.e. a `pip install` from the network, mid-process.** Observed live during this survey | **High** | Pin `lap>=0.5.12` in the `cv` extra (T1). On the offline/air-gapped GB4005 box or a running container this would otherwise fail or hang **inside a live stream**, not at startup |
| **R3** | **cv-service pins no OpenCV version at all** — it floats transitively from ultralytics and today lands on 5.0.0.93. A rebuild could land a different major with a different tracker API | Medium | Pin an explicit `opencv-python` range in the `cv` extra (T1) **and** have `TrackerRegistry` probe constructibility at startup and log the real roster, rather than assuming (the same defensive posture `discover_roster` already takes for models) |
| **R4** | **2-core GB4005 budget.** All §8 numbers are from a 12-thread box | Medium | The <10 ms/frame tracker target has ~25× headroom at `lk`'s 0.37 ms, so it survives a large penalty. The **detector** is what hurts on 2 cores — which is exactly what the duty cycle removes. Make the measured CPU drop an acceptance criterion (T8), not a claim |
| **R5** | **`FOLLOW` raises the frame rate into `maxInFlightInferences=2` and the `LatestOnlyMailbox`** | Medium | `followFps` defaults to 15, not "every frame" (§5.D). A dropped frame costs one 66 ms tracker gap and produces **no response** for that sequence (cv-service/MODULE.md) — which is precisely why the lock is **declarative and restated per frame**, not a one-shot command (§4.A). Measure in T4/T8 |
| **R6** | **Stream→worker affinity becomes a correctness requirement**, not an optimization | Medium | Recorded as a hard constraint on docs/CV-SCALE-PLAN.md §S4's `PooledDetectionPort`; that wave must carry a test that a stream never migrates workers mid-flight. Not built here |
| **R7** | **Old jsonb rows** in `detection_results` predate the `track` key | Low | Explicit regression test in T6. Jackson maps a missing property on a nullable record component to `null`; **verify, don't assume** |
| **R8** | **ultralytics is AGPL-3.0**, and using its `BYTETracker` deepens the dependency | Low | Already a dependency for YOLO itself, so no new exposure today. `TrackerRegistry` is the insurance: `bytetrack` is one engine behind a protocol, replaceable in one file by a ~200-line in-repo IoU+Kalman associator if licensing ever forces it |
| **R9** | **Composite mode label prefixes** (`orion12l:tank` vs `yolo11n:person`) with a class-agnostic associator | Low | `BYTETracker`'s first-stage association is IoU/motion-based, not class-gated, so a composite-mode object should yield **one** track whose label may flip between members. Cosmetic; confirm empirically in T1 and document, do not special-case |
| **D1** | Both loops in cv-service, Java protocol-thin | — | §3. Decided |
| **D2** | Lock is declarative + `lock_seq`-idempotent, not an imperative control message | — | §4.A. Decided — forced by the mailbox drop semantics |
| **D3** | `TrackRef` bundled onto `Detection` rather than 6 flat components | — | §4.B. Keeps `Detection` at 5 components and makes "untracked" one `null` |
| **D4** | No new REST endpoint for click-to-follow | — | §4.D. It is a `PATCH …/config` |
| **D5** | No Flyway migration; tracks ride the existing jsonb | — | §4.C. The indexed table lands with S2 |
| **D6** | No trails in the burned overlay; trails are client-side | — | §6. The renderer is stateless and has no stream identity |
| **R10** | **Onboard (Pi) performance is entirely unmeasured**, and three things could bite: torch's 427 MB aarch64 wheel (long install, ~1.5 GB footprint, tight on a 8/16 GB card); a Pi 4/5 detector pass at `imgsz=320` being far slower than the 41 ms this box sees; and **thermal throttling on a flying Pi is real**, not theoretical — sustained CV load in a sealed airframe bay derates the SoC | **Medium — but structurally contained** | Every dependency is confirmed aarch64-available (§8), so this is a *performance* unknown, not a *feasibility* one. **The duty cycle is the mitigation**: a verify pass every ~2 s against a 0.4 ms/frame tracker is the only reason onboard detection is plausible at all. Escape hatches, both documented not built: torch-free profile (§3.3.3), and `verify_every_millis` is already an operator knob that trades re-anchor freshness for CPU with no code change. **Label every Pi number as a budget until H4 hardware measures it** |
| **D7** | Default flips to `ASSOCIATE` in its own wave (T8) | — | §5.G |
| **D8** | Portability is an invariant (P1/P2), not a later port | — | §3.3, §5.J. The same package in all three placements; the frozen contract needed **zero** changes to make onboard reachable |
| **D9** | ARM ships by documented pip + rsync, not a multi-arch image | — | §3.3.3. The amd64 image exists for its OpenVINO export, which the Pi neither wants nor benefits from |
| **D10** | Derived quantities (center offset, angular error, ground coordinate) stay consumer-side | — | §3.4.2, §5.K. Keeps cv-service uncoupled from every future sink |
| **D11** | Guidance/actuation and the onboard feeder are frozen-interface, deferred deliverables — not waves | — | §3.3.2, §3.4.1, §6. H4 hardware doesn't exist; actuation is gated like I-e |
| **R11** | **Engine-roster drift** — `GET /api/cv/trackers` is a static vision-app list; cv-service may fail to construct an engine it advertises | Low | **No roster RPC** — that grows the contract to solve a display problem. cv-service probes constructibility at startup and logs the roster it actually got (T1); `tracker_engine_id` reports per frame which engine really served; the UI shows the serving engine, not the requested one (T7) |
| **D12** | `followFps` default **15**, not 30 | — | §5.D. ~1.2 MB/s/stream survives the VPN link REMOTE-CV contemplates; a knob, tuned against measurement |
| **D13** | Click-to-follow is **advisory only** | — | §6. A locked box on a screen. Nothing slews a gimbal or an airframe; that stays gated like RC-CONTROL Phase 2 / I-e |
| **D14** | ONNX SOT engines (`nano`/`vit`) **deferred** | — | §5.B. Keeps P1 clean, ships no image assets; `lk` at 0.37 ms already has ~25× headroom |
| **D15** | T8 default flip is **its own commit** | — | §5.G. Every wave lands independently green; the behaviour change is one reviewable line |
| **D16** | Two engine protocols (`Associator` / `SingleObjectTracker`), not one | — | §5.A. One protocol forced every engine to stub half of itself, on the seam extensibility rests on |
| **D17** | `TrackingTelemetry` on `DetectionResult`; track facts nest in JSON, stay flat in proto | — | §4.B, §4.G. Closes the `detectorRan` gap; groups rather than widens; proto flat because a submessage allocates per detection per frame |
| **D18** | `stats` is computed Java-side, not on the wire | — | §4.E. No new wire field, no read-model concern in cv-service (P3), identical in every placement |

---

## 10. Touchable outcome

The TWO-TARGETS rule: done means **visible**, not "a test passed". Three things, in order:

1. **Stable box numbers on live video, surviving occlusion.** Point a camera at a street. Each car
   keeps `#7` as it crosses the frame — and keeps `#7` after passing behind a pole. Show it: the box
   goes dashed while it is `COASTING`, then solid again with the *same* number. That dashed interval
   is the system visibly saying "I am extrapolating, not seeing", which is the honest-UI doctrine
   made pixel-level.

2. **Follow-lock from the Fly cockpit, with the CPU drop on screen.** Click a box → it locks →
   the tracker follows it at 15 fps. Then show the number: `htop` (or the `tracker_millis` /
   `detector_ran` fields the response already carries) on the inference box, before and after the
   lock. Detector passes drop from ~10/s to ~0.5/s on that stream. **That number is the feature** —
   record the measured before/after in `cv-service/MODULE.md`, not a rounded claim.

3. **A trail behind a tracked car in the player.** Not on the map — that is S2. In the video
   surface, a fading line behind each tracked box, which is the first time the app visibly shows
   *history* rather than a snapshot.

If (1) and (2) are not demonstrable on real camera footage, this plan is not done, regardless of
green builds.

---

## 11. What this unblocks

**S2 fixed-camera geolocation** (its direct input: a stable id per ground-contact point, so a
projected mark leaves a trail instead of a scatter) · **C2 click-to-follow** (advisory tier lands
here; the commanded tier stays gated) · **C12 cross-sensor fusion** (needs ids on both sensors
before it can dedupe) · **C8 / target trajectories** · **matrix K2**, and half of **K4** (one dead
type, resurrected rather than deleted).

**H4 and the Class-C commanded tier become genuinely receivable.** The bandwidth argument against a
companion computer has always been that video is expensive and the link is not. An onboard
cv-service inverts it: the aircraft emits **tracks at telemetry-class KB/s** instead of **video at
MB/s** — the same `DetectionResponse` messages, over a link that can actually carry them (§3.3.2).
That is what makes "one link carrying both video and telemetry, adaptive bitrate, the commanded
tier" (docs/TWO-TARGETS-PLAN.md H4) a bandwidth budget rather than a wish.

**And because the pipeline's product is sink-agnostic (§3.4), C2's full story — click a box, the
drone centers on it — plus any future autonomous-follow behavior are a pure *consumer* addition with
zero pipeline rework.** The error signal a centering controller needs is already derivable from the
frozen contract; what remains is a controller and an explicit go, not a redesign.

# TRACKING-ORCHESTRATION — boundaries, flow, configuration, visibility

Status: **authoritative companion to [docs/plans/done/TRACKING-PLAN.md](../plans/done/TRACKING-PLAN.md)** (2026-08-11).

TRACKING-PLAN answers *what* the tracking engine is, *why* the detect-then-track duty cycle is the
design, and *which waves* build it. It is not superseded by anything here.

This doc answers the five constraints the build was handed before implementation started:

> **single responsibility · modularity · fast communication · scalable DTOs · a visible flow**

and it is where the **orchestration** and the **separated configuration** are specified. It also
fixes three defects TRACKING-PLAN carried against those constraints, found while designing this:

1. **One engine protocol doing two unrelated jobs** — `associate() | init_lock() | update() | reset()`
   forces `bytetrack` to stub methods it will never implement and `lk` to stub `associate()`. An
   interface-segregation violation on the plan's single most extension-sensitive seam (§2.2).
2. **Per-frame tracking telemetry had nowhere to live in the domain.** `DetectionResult` has five
   components and none of them can carry `detector_ran`, so wave T6's `"detectorRan"` on the SSE DTO
   was unreachable — the adapter would decode it and drop it on the floor (§5.2).
3. **No observability design at all.** The ingredients were scattered across four sections and the
   plan's own touchable outcome #2 required reading `htop` on a remote box to see the feature work
   (§7).

**Where this doc changes the frozen contract, the change has been written into TRACKING-PLAN §4
itself** — that file stays the single source of truth for the wire, so an agent that reads only §4
is never wrong. §5 below lists every amendment and its justification.

---

## 1. The five constraints, made checkable

A constraint that cannot be violated by a specific line of code is a slogan. Each one below names
what it forbids and where it is enforced.

| Constraint | Forbids, concretely | Enforced at |
|---|---|---|
| **Single responsibility** | a class that both *decides* and *does*: no scheduler that also tracks pixels, no track book that also aggregates stats, no session that also resolves config | §2.1 module table — each file's one-line charter is its acceptance criterion |
| **Modularity** | an engine that must know the mode it serves; a protocol whose implementers stub methods | §2.2 two protocols; registry hands out **factories**, per stream |
| **Fast communication** | anything per-frame that is not O(1) and allocation-light: no config re-resolution, no dict rebuild, no gate acquisition on the tracker path, no per-frame INFO log | §3.1 hot path; §4.2 resolve-once rule |
| **Scalable DTOs** | a sixth flat field on a DTO that already has five track-related ones; a parallel `v2` DTO; a consumer-shaped field in the pipeline | §6 six rules |
| **Visible flow** | a feature whose behaviour can only be confirmed by reading logs on the box it runs on | §7 four tiers, all derived from data already on the wire |

---

## 2. Component map — one responsibility each

### 2.1 cv-service — `cv_service/tracking/`

The plan gave this package six files; this splits the decision-making out of `session.py`, which was
carrying four responsibilities (scheduling, lifecycle, lock arbitration, composition). One charter
per file, and the charter is what the wave's review checks:

| File | Single responsibility | Imports `cv2`? |
|---|---|---|
| `params.py` | `TrackingParams` frozen dataclass + `resolve(wire_config, settings) → TrackingParams`. The **only** place a request sentinel (`<=0`) becomes a number | no |
| `scheduler.py` | `DutyCycleScheduler.decide(now, state) → Decision(run_detector, reason)`. Pure policy — §3.1's table and nothing else | no |
| `track.py` | `Track` + `TrackBook` — the `TENTATIVE→CONFIRMED→COASTING→LOST→expired` machine. Owns identity and age; owns no pixels and no policy | no |
| `lock.py` | `LockArbiter` — `lock_seq` monotonicity, point/box/track-id target selection, release. Rejects a stale or equal `lock_seq` | no |
| `engines/base.py` | the **two** protocols of §2.2 — and nothing else | no |
| `engines/{bytetrack,lk,ncc}.py` | pixels/geometry only. An engine never sees `TrackingParams`, never decides cadence, never knows what a lock is | yes (`lk`, `ncc`) |
| `registry.py` | `TrackerRegistry` — `{engine_id → factory}`, startup constructibility probe, roster logged once at INFO | no |
| `session.py` | **composition only**: hold the per-stream parts, run §3.1's sequence, ~80 lines. If it grows a policy branch, that branch belongs in `scheduler.py` | no |

**Consequence worth stating: everything except `engines/` is pure stdlib.** The scheduler, the
lifecycle machine and the lock arbiter are unit-testable with a fake clock, no frames, no OpenCV, no
gRPC — millisecond tests, which is what makes the duty-cycle logic actually get tested rather than
smoke-tested through a stream. The `cv_pb2` rule is unchanged: `grpc/servicers.py` remains the sole
translation point, so **no file in `tracking/` imports `cv_pb2`**.

### 2.2 Two engine protocols, not one — the ISP fix

TRACKING-PLAN §5.A specifies one `TrackerEngine` protocol with
`associate() | init_lock() | update() | reset()`. The two modes need genuinely different things:
`ASSOCIATE` hands N boxes to something that assigns ids; `FOLLOW` hands one frame to something that
moves a box. `bytetrack` can never implement `update(frame)` and `lk` can never implement
`associate(detections)` — one protocol forces every engine to stub half of itself, and it is the
seam the whole extensibility story rests on.

```python
class Associator(Protocol):          # ASSOCIATE — many objects, no pixels
    engine_id: str
    def associate(self, detections: list[Detection], now: float) -> list[Observation]: ...
    def reset(self) -> None: ...

class SingleObjectTracker(Protocol):  # FOLLOW — one object, needs pixels
    engine_id: str
    def init(self, frame: np.ndarray, box: Box) -> None: ...
    def update(self, frame: np.ndarray) -> TrackerUpdate | None: ...   # None = lost
    def reset(self) -> None: ...
```

Registry keeps **two rosters** behind one `TrackerRegistry`, which is why `GET /api/cv/trackers`
already returns a `modes: []` array per engine (TRACKING-PLAN §4.F) — the wire anticipated this; the
protocol had not. An engine that legitimately serves both modes implements both protocols; nothing
forces it to.

**Engines are per stream, never shared** (unchanged, TRACKING-PLAN §5.A) — the registry hands out
factories, not singletons. A tracker is cheap to build and inherently stateful; sharing one across
streams corrupts every stream at once.

### 2.3 Java side

| Component | Single responsibility | Module |
|---|---|---|
| `TrackBook` | identity + lifetime: `Map<Long, TrackedObject>`, `firstSeen`/`lastSeen`, expiry. Books what arrived; **associates nothing** | vision-application |
| `TrackingStatsWindow` | rolling counters over the response stream: duty ratio, tracker-ms p50/p95, state histogram, last reason | vision-application |
| `DetectionFrameCodec` | wire ↔ domain translation, both directions. No policy | adapter-cv-grpc |
| `TrackingProperties` | deployment defaults for **new** streams (`vision.tracking.*`) | vision-app |

`TrackBook` and `TrackingStatsWindow` are **two peers on `StreamPipeline`'s existing fan-out, so two
lines, not the one line TRACKING-PLAN §5.E promised.** Stated plainly because it is a deliberate
deviation: folding the counters into `TrackBook` would give that class two responsibilities to save
one line in a fan-out list that already has five entries. The fan-out is a list of consumers by
design; adding a consumer to it is not a new responsibility for `StreamPipeline`, and S3's
decomposition inherits two clean classes instead of one muddled one.

---

## 3. Orchestration

### 3.1 The per-frame hot path — the one sequence that must stay fast

Inside one stream's `DetectStream`, per received frame:

```
servicers.DetectStream                       ← sole cv_pb2 translation point
  │
  ├─ frame ← mailbox.get()                   latest-wins; a drop costs one tracker gap, never state
  ├─ params ← session.params                 ALREADY RESOLVED (§4.2) — no parsing, no dict build
  │
  ├─ decision ← scheduler.decide(now, state) PURE, ~µs, BEFORE any pixel is touched
  │
  ├─ if decision.run_detector:
  │     dets ← registry.resolve(model_id) → detect_composite(…, gate)   ◄── THE ONLY GATE ACQUISITION
  │
  ├─ mode ASSOCIATE:  obs ← associator.associate(dets, now)
  │  mode FOLLOW:
  │     decision.run_detector → tracker re-anchors on best-IoU det (≥ params.iou) or COASTS
  │     otherwise           → obs ← tracker.update(frame)     ◄── NEVER TOUCHES THE GATE
  │
  ├─ book.apply(obs, now)                    lifecycle transitions, id allocation
  ├─ stats.record(decision, timings)         counters only; no formatting, no logging
  └─ → DetectionResponse{detections, detector_ran, detector_reason, tracker_millis,
                         tracker_engine_id, locked_track_id}
```

Three invariants this sequence exists to make unmissable:

- **The gate is acquired on exactly one branch.** A 0.4 ms tracker update queueing behind a 343 ms
  `orion12l` pass on another stream destroys the entire design. This is TRACKING-PLAN §3.1's hard
  rule, given a single visible location.
- **The decision precedes the work.** `decide()` reads a clock, a lock state and a counter. It never
  touches a frame, so it can never be the slow thing, and it can be tested exhaustively without one.
- **Nothing per-frame allocates configuration.** See §4.2.

### 3.2 Configuration change — PATCH to pixels

```
UI → PATCH /api/streams/{id}/config {tracking:{…}}
   → StreamController                validates: one-of-three lock form, known mode      → 400 else
   → PipelineConfigPatch(tracking)
   → DefaultStreamService.updateConfig    allocates lockSeq (AtomicLong, server-side only)
   → StreamPipeline volatile PipelineConfig       ← hot knob; never re-arms the detector
   → next frame: DetectionFrameCodec.encode → FrameRequest.tracking
   → cv-service session: wire config != last applied ?  → params.resolve(…)  → maybe rebuild engine
                                              else      → no work at all
```

The comparison in that last step is what makes a **restated-every-frame** config cheap: the session
holds the last applied wire message and compares by equality (a protobuf `==`, no allocation), and
re-resolves only on a real change. Restating is for robustness (a dropped frame or a reconnect
cannot desynchronize state — TRACKING-PLAN invariant P2); it is not a per-frame cost.

### 3.3 Lock lifecycle — and the honesty rule

```
click box #7 → PATCH {tracking:{mode:FOLLOW, lock:{trackId:7}}}
             → server allocates lockSeq = N          (clients never send lockSeq)
             → restated on EVERY FrameRequest        (idempotent by construction)
             → cv-service applies iff N > last_applied_seq
             → every DetectionResponse echoes locked_track_id
             → UI shows "Following #7" ONLY once a response confirms it
release      → PATCH {tracking:{lock:{release:true}}} → lockSeq = N+1
```

**The UI reflects confirmed state from the wire, never local intent.** A lock is a request until
`locked_track_id` comes back saying otherwise — the same honesty doctrine the dashed `COASTING` box
expresses at the pixel level, applied to control. It also means a lock that cv-service could not
honour (target already `LOST`) shows as *not locked* instead of a lying chip.

### 3.4 Degradation — one rule per level

Unchanged from TRACKING-PLAN §5.I, restated here as orchestration because it is where the fallbacks
actually fire: `FOLLOW → ASSOCIATE → OFF`, each step logged once per engine id, never an exception,
never a dead stream. A raising engine costs **that frame** its track facts and resets the engine; the
stream continues. Every fallback is visible via `tracker_engine_id` on the response — the operator
sees `""` or a different engine than they asked for, rather than silence.

---

## 4. Configuration — separated, layered, resolved once

### 4.1 The layers, and who wins

Configuration is a **layer, not a field on the thing being configured**. Four layers, and precedence
runs strictly left to right:

```
per-stream request (PATCH / StartStream)  >  deployment env  >  code default
     TrackingConfig on the wire              CV_TRACK_* (py)     TrackingParams / TrackingConfig.off()
                                             vision.tracking.* (java)
```

- **`vision-domain` holds pure literals only.** `TrackingConfig.off()` / `.defaults()` are constants
  in a framework-free module — no Spring, no env. That is the dependency rule, and it is why the
  deployment defaults live in vision-app instead.
- **`vision.tracking.*` (vision-app) seeds *new* streams only.** It never reaches into a running
  stream — a running stream's config is its own state, changed only by PATCH. Restarting a stream is
  how you pick up a changed deployment default, and that is the honest behaviour.
- **`CV_TRACK_*` resolves the wire's `<=0 = server default` sentinels.** cv-service's existing
  one-place rule holds without exception: these are read in `cv_service/config.py` `Settings` and
  nowhere else. `params.py` is the only consumer.

### 4.2 The resolve-once rule — this is what "fast communication" means here

> **No cadence, threshold, or count may appear as a literal in `scheduler.py`, `session.py`, or any
> engine. They read `TrackingParams`, which is resolved on config change — never per frame.**

Two properties fall out. Performance: the hot path does zero parsing and zero dict construction for a
message that arrives 15×/second/stream. Testability: a scheduler test constructs `TrackingParams`
directly and needs no environment, no request, and no server. It is also the rule that keeps
configuration *separated* rather than smeared through the logic, which is the actual ask — a literal
`2000` inside a scheduling branch is configuration hiding in code.

### 4.3 Knob inventory

| Knob | Wire / REST | Python env | Java property | Default |
|---|---|---|---|---|
| mode | `tracking.mode` | — | `vision.tracking.default-mode` | `OFF` (→ `ASSOCIATE` at T8) |
| engine | `tracking.engineId` | `CV_TRACK_ASSOCIATE_ENGINE` / `CV_TRACK_FOLLOW_ENGINE` | — | `bytetrack` / `lk` |
| verify cadence | `tracking.verifyEveryMillis` | `CV_TRACK_VERIFY_MS` | `vision.tracking.verify-every-millis` | 2000 |
| re-anchor IoU | `tracking.redetectIouPercent` | `CV_TRACK_IOU` | — | 30 (%) |
| max age | `tracking.maxAgeFrames` | `CV_TRACK_MAX_AGE` | — | 30 |
| min hits | `tracking.minHits` | `CV_TRACK_MIN_HITS` | — | 3 |
| follow fps | `tracking.followFps` | — (Java-side sampling) | `vision.tracking.follow-fps` | **15** |
| stats window | — | — | `vision.tracking.stats-window-seconds` | 30 |
| engine roster | `GET /api/cv/trackers` | — | `vision.cv.trackers[]` | 3 built-ins |

`followFps` has no Python knob on purpose: it is the *Java* sampler's rate (TRACKING-PLAN §5.D), and
cv-service must never care how often it is fed.

---

## 5. Contract amendments — all written into TRACKING-PLAN §4

Three amendments, all additive, all made **before** any wave started so no agent codes against a
stale shape. They are live in TRACKING-PLAN §4; recorded here with their reasons.

### 5.1 `DetectorReason` + `DetectionResponse.detector_reason = 12` (§4.A)

`detector_ran` says *whether* the detector spent a pass; nothing said *why*. Without it, "why is my
detector still running 10×/s in FOLLOW mode?" is answerable only by reading cv-service logs — on a
LAN box, or on a Pi in flight where they are unreachable. One enum, seven values matching §3.1's
trigger list exactly (`CADENCE`, `TRACKER_FAILED`, `NO_LOCK`, `BOX_INVALID`, `COASTED_OUT`,
`ALWAYS`, `UNSPECIFIED`), zero bytes when unset. It is the *why* beside the plan's own "honesty
field", and it is the difference between a flow that is visible and one that is merely instrumented.

### 5.2 `TrackingTelemetry` on `DetectionResult` (§4.B) — the gap fix

`DetectionResult` is `(streamId, frameSequence, capturedAt, detections, inferenceLatency)`. Nothing
in it can carry `detector_ran`, so T6's `"detectorRan"` DTO field had no source. Fixed the same way
`Detection` carries `TrackRef` — **one nullable component**, not five flat ones:

```java
public record TrackingTelemetry(boolean detectorRan, DetectorReason reason, Duration trackerLatency,
                                String engineId, long lockedTrackId) { }
// DetectionResult gains a 6th component: TrackingTelemetry tracking  (NULLABLE = tracking was off)
// the existing 5-arg canonical ctor becomes a convenience ctor delegating tracking = null
```

Same convenience-constructor idiom as everywhere else, so every existing call site compiles
unchanged. This is wave **T2**, and **T4 must map it** or the field is decoded and dropped.

### 5.3 Track facts nest in JSON (§4.E, §4.G)

The SSE `DetectionResponse` DTO was to gain five flat fields (`trackId`, `trackState`,
`trackSource`, `velocityX`, `velocityY`) and `DetectionResultResponse` a flat `detectorRan`. Nested
instead, mirroring the domain:

```json
{ "label": "car", "confidence": 0.82, "box": { … }, "modelId": "…", "modelVersion": "…",
  "track": { "id": 7, "state": "CONFIRMED", "source": "TRACKER",
             "velocityX": 0.012, "velocityY": -0.001 } }
```

and `DetectionResultResponse` gains a `"tracking": { "detectorRan": …, "detectorReason": …,
"trackerMillis": …, "engineId": …, "lockedTrackId": … }` object. Untracked payloads stay
**byte-identical to today** (`@JsonInclude(NON_NULL)`, one absent key instead of five). The client
gets one null check — `d.track?.id` — gating all track rendering, instead of five optional fields
that can disagree with each other. Ten extra bytes per tracked box at 6 SSE ticks/second is not a
cost worth trading a widening DTO for.

**Proto stays flat, deliberately** — a nested submessage allocates one extra object per detection per
frame, and proto3's additive field numbers are already the scaling mechanism. The layers differ
because their costs differ; that is the reason, and it is not an inconsistency to be tidied later.

### 5.4 `stats` on `GET /api/streams/{id}/tracks` (§4.E)

A sibling object beside `tracks`, **computed Java-side** by `TrackingStatsWindow` from responses that
already arrive — no new wire field, no new endpoint, and cv-service stays free of read-model concerns
(invariant P3). It works identically for every placement, including a Pi.

---

## 6. DTO scalability rules

Six rules. Every wave that adds a field obeys them; a reviewer can check them mechanically.

1. **Group, don't widen.** A new track fact is a key inside the existing group (`TrackRef`,
   `"track"`, `TrackingTelemetry`, `"tracking"`), never a new sibling field on the parent. Two flat
   track fields would have been fine; five is where a DTO starts to rot, and this plan adds five.
2. **Absent means untracked — exactly one spelling per layer.** `track_id == 0` on the wire,
   `track == null` in the domain, key absent in JSON. Never an empty object, never `-1`, never a
   `hasTrack` boolean.
3. **No consumer-shaped field in the pipeline** (invariant P3). Center offsets, angular errors,
   ground coordinates and canvas positions are derived by the consumer that has the FOV, the camera
   pose or the screen. Stats are pipeline facts (counts, timings), not a UI shape.
4. **Additive-only growth.** A new fact is one proto field + one domain component *inside* the group
   + one JSON key. Never a parallel DTO, never a `/v2` endpoint, never a breaking rename.
5. **Per-frame payload budget.** `TrackingConfig` restated every frame is ~30–40 bytes against a
   ~80 KB downscaled JPEG — **≈0.05%**. Recorded so the restated-config design is never re-litigated
   on bandwidth grounds; if a future field would move that materially, it does not belong per-frame.
6. **A convenience constructor is for *construction*, never *reconstruction*.** Any code that
   rebuilds a record from an existing one — a filter, a mapper, a copy-with-one-change — must use the
   **canonical** constructor, or it silently drops every component the convenience ctor defaults.
   This is the cost of the N-1-arg idiom that keeps call sites compiling, and it fails **silently**:
   the build stays green and a field just goes missing at runtime. Wave T3 found exactly two of these
   left by T2 (`applyLabelFilter` dropping `tracking()`, `extrapolate` dropping `track()`), which
   between them would have emptied every label-filtered stream's stats window and stripped `#id` and
   the `COASTING` dashes from every burned-in box. **When adding a component to an existing record,
   grep for every site that rebuilds it** — the compiler will not.

---

## 7. Flow visibility

The plan's own touchable outcome #2 — "show the CPU drop" — required `htop` on the inference box.
That is a demo instruction, not a product. Four tiers, **every one derived from data already crossing
the wire**; no metrics dependency, no second telemetry channel, nothing new to deploy.

| Tier | What it shows | Where |
|---|---|---|
| **Wire** | `detector_ran`, `detector_reason`, `tracker_millis`, `tracker_engine_id`, `locked_track_id`; per-detection `source` + `track_state` | already in §4.A (+ §5.1) |
| **API** | `stats`: duty ratio, detector passes vs tracker frames, tracker-ms p50/p95, state histogram, last reason, engine actually serving | `GET …/tracks` (§5.4) |
| **UI** | a **flow strip** in the Fly CV panel: `DETECT 0.5/s ▸ TRACK 15/s · 1 in 30 · lk 0.4 ms · cadence`; dashed `COASTING` boxes; the confirmed follow chip | wave T7 |
| **Logs** | roster once at INFO; lock changes at INFO; state transitions at DEBUG. **Never per-frame at INFO** — cv-service/MODULE.md already carries that burn | wave T1 |

That flow strip is the point. It turns the feature's core claim — *the detector stopped running and
the tracker took over* — into something an operator reads off the screen while it happens, on any
placement, including one that is flying. The number the plan calls "the feature" becomes part of the
product instead of a benchmark someone has to reproduce.

---

## 8. Decisions resolved

Four decisions were open at the end of planning. All four take the plan's own recommended default —
the low-risk option in every case, and none of them blocks a wave:

| # | Decision | Call | Why |
|---|---|---|---|
| **D12** | `followFps` default | **15** | ~1.2 MB/s/stream survives the VPN link REMOTE-CV contemplates; 30 doubles it for a tracker that gains little at typical target speeds. A knob, tuned against measurement (§4.3) |
| **D13** | Click-to-follow scope | **advisory only** | A locked box on a screen. Nothing slews a gimbal or an airframe; that stays gated exactly like RC-CONTROL Phase 2 and command TX (I-e), needing its own explicit go |
| **D14** | ONNX SOT engines (`nano`/`vit`) | **deferred** | Keeps invariant P1 clean and ships no image assets. `lk` at 0.37 ms already has ~25× headroom; the registry makes them a later file drop, not a redesign |
| **D15** | T8 default flip | **its own commit** | Every wave lands independently green, and the moment behaviour changes is one reviewable line rather than a side effect of T2 |

New risk, from §2.3:

| # | Risk | Severity | Call |
|---|---|---|---|
| **R11** | **Engine-roster drift.** `GET /api/cv/trackers` is a static vision-app list; cv-service may fail to construct an engine it advertises | Low | Do **not** add a roster RPC — that grows the contract to solve a display problem. cv-service probes constructibility at startup and logs the real roster (T1), and `tracker_engine_id` reports per frame which engine actually served. The UI shows the engine that is *serving*, not the one requested (T7) |

---

## 9. Required reading per wave

Every wave reads TRACKING-PLAN §4 (the frozen contract) and its own module's `MODULE.md`. Beyond
that:

| Wave | Also read here |
|---|---|
| **T0** proto | §5.1 (the new enum + field) |
| **T1** cv-service | §2.1, §2.2, §3.1, §3.4, §4.1–4.3, §7 (log tier) |
| **T2** domain | §5.2 (the gap fix — this wave is where it lands), §6 |
| **T3** application | §2.3, §3.2, §5.4, §6 |
| **T4** adapter-cv-grpc | §5.1, §5.2 (**must map `TrackingTelemetry`**), §6 rule 2 |
| **T5** adapter-overlay | §6 rule 2 |
| **T6** api/app/persistence | §4.1 (properties), §5.3, §5.4, §6 |
| **T7** web-ui | §3.3 (the honesty rule), §5.3, §7 (the flow strip), R11 |
| **T8** flip + demo | §7 — the measured numbers land in the flow strip and MODULE.md, not only in prose |

# TRACKING-V2-PLAN — object identity, memory and ego-motion in cv-service

**Branch:** `feat/tracking-v2` (worktree, based on `master`).
**Origin:** `docs/conclusions/TRACKING-REVIEW.md` — the review that named the defect.
**Predecessor:** `docs/plans/done/TRACKING-PLAN.md` (waves T0–T8, delivered). This plan does not
replace it; it fixes what T1–T8 deliberately deferred.

> **The one-line goal.** Identity stops being a side effect of frame adjacency computed inside
> ByteTrack, and becomes a first-class thing cv-service owns, remembers, and can re-acquire.

---

## 0. Governing constraint — this is a cv-service plan

The operator's instruction is **least possible change on the Java side**. That is also the correct
reading of responsibility: deciding *who an object is* is cv-service's charter, and every tier this
plan adds is a tier that needs pixels or per-frame state. So:

- **No Java source file is modified by any wave.** Not `StreamPipeline`, not `DetectionFrameCodec`,
  not the domain records.
- **The proto grows, additively, in ways that require zero Java edits.** See §2 for the rule that
  makes that checkable, and §2.1 for why it is not merely a preference.
- Java-side follow-ups that would *improve* results (populating `CameraPose`, raising the ASSOCIATE
  sample rate) are specified here as one-liners for a future, separately-approved task. cv-service
  must work — and be measurably better — without either of them.

---

## 1. Decisions taken (the review's §7 open questions, closed)

| # | Question | Decision | Why |
|---|---|---|---|
| D1 | Raise the ASSOCIATE frame rate above 10 fps? | **No.** cv-service is made robust *at* 10 fps instead. | Raising it is a `PipelineConfig` change (Java) and a bandwidth cost. Ego-motion compensation + appearance + memory attack the same failure without touching the sampler. Recorded as a Java one-liner if the harness later shows rate is still the binding constraint. |
| D2 | `CameraPose` on the wire now or later? | **Freeze the field now, consume it now, populate it later.** | The field costs zero Java edits. cv-service ships `pose` motion compensation that activates the moment anything fills it, and `flow` compensation that works today with nothing filled in. S2 geolocation gets the field for free. |
| D3 | Report re-acquisition honestly? | **Yes** — `identity_confidence` and `dormant_millis` on `Detection`. | Matches the `detector_ran` honesty doctrine already established. Old clients ignore unknown scalars. |
| D4 | Cross-stream re-acquisition? | **Out of scope, kept reachable.** | `Descriptor` is a plain serializable value and `ObjectMemory` is keyed so a future per-asset tier can hold the same records. No wave depends on it. |
| D5 | New proto **enum values**? | **Forbidden.** | `DetectionFrameCodec` maps every wire enum with an **exhaustive Java switch expression**. A new enum constant is a Java compile error, not a compatible addition. `DORMANT` is therefore an internal state only — which is also correct, since a dormant track has no box on any frame and never crosses the wire. |
| D6 | Solver dependency for assignment? | **Pure-stdlib Hungarian in `assign.py`.** | `tracking/` outside `engines/` must import no `cv2`/`numpy`/`ultralytics` (existing invariant, tested). `lap` and `scipy` both violate it. At N ≤ ~40 boxes an O(n³) Python solver is well under the frame budget, and it keeps the whole identity core testable with no `cv` extra. |
| D7 | Appearance descriptor to ship first? | **Colour histogram** (`engines/histogram.py`). | Near-free, no new dependency, no model asset, works on the CPU-only GB4005 box. The port makes an ROI-pooled or OpenVINO re-ID descriptor a later file drop, not a redesign. |

---

## 2. Frozen wire contract — the complete additive diff

**The rule that keeps Java untouched:** *scalar fields and new messages only; never a new value in an
existing enum.*

```proto
// NEW message — camera attitude at capture time, for ego-motion compensation
// (this plan) and fixed-camera geolocation (TWO-TARGETS §S2). Every field
// optional; an all-zero/absent CameraPose means "unknown", and cv-service
// falls back to flow-based compensation.
message CameraPose {
  float yaw_degrees            = 1;   // camera boresight heading, degrees
  float pitch_degrees          = 2;   // positive up
  float roll_degrees           = 3;
  float hfov_degrees           = 4;   // 0 = unknown -> pose compensation disabled
  float vfov_degrees           = 5;   // 0 = derive from hfov and the frame aspect
  int64 pose_timestamp_millis  = 6;   // 0 = same instant as the frame
}

message FrameRequest {
  // ... fields 1-11 unchanged ...
  CameraPose camera_pose = 12;        // NEW -- absent => flow-based compensation only
}

message Detection {
  // ... fields 1-9 unchanged ...
  float identity_confidence = 10;     // NEW -- 0 = not a re-acquisition; >0 = memory match score
  int64 dormant_millis      = 11;     // NEW -- how long this identity was dormant before recovery
}

message DetectionResponse {
  // ... fields 1-12 unchanged ...
  bool  detector_roi      = 13;       // NEW -- this pass was a crop around a prediction, not full-frame
  int64 motion_millis     = 14;       // NEW -- ego-motion estimation cost
  string motion_engine_id = 15;       // NEW -- compensator that served this frame ("" = none)
}

message TrackingConfig {
  // ... fields 1-7 unchanged ...
  string motion_engine_id     = 8;    // NEW -- "" = server default for the mode
  string appearance_engine_id = 9;    // NEW -- "" = server default; "off" disables appearance
  int32  memory_ttl_millis    = 10;   // NEW -- dormant gallery TTL; <=0 = server default
}
```

Compatibility, stated as checkable claims:
- Every addition is a scalar or a new message → `DetectionFrameCodec`'s switches stay exhaustive.
- Java never sets 8/9/10 on `TrackingConfig` → they arrive as proto3 zero → `params.resolve()`
  applies the `CV_TRACK_*` default, exactly as it already does for fields 2–6.
- Java never sets `camera_pose` → `hfov_degrees == 0` → `pose` compensation reports itself
  unavailable and the registry degrades to `flow`.
- **Acceptance:** `./mvnw -B -pl vision-proto compile` is green with **zero Java source edits**, and
  a `TRACKING_MODE_OFF` stream still serializes byte-identically to the pre-T1 service.

### 2.1 Java-side follow-ups, specified but NOT in this plan

- `DetectionFrameCodec.toFrameRequest` populates `camera_pose` from the asset's freshest telemetry
  (the same `UsageTracker#latestTelemetry` that `GeolocateSpec` already reads). ~8 lines.
- `PipelineConfig.defaults()` raises `inferenceFps` if the harness proves rate is still binding.

---

## 3. Frozen Python contracts

Package layout — one charter per file, extending the existing convention:

```
cv_service/tracking/
  params.py     EXTENDED  new knobs, still the only place a <=0 sentinel becomes a number
  scheduler.py  EXTENDED  ROI-pass policy; still pure policy, still no literal
  track.py      EXTENDED  DORMANT, time-based expiry, predicted box
  lock.py       unchanged
  registry.py   EXTENDED  motion + appearance rosters beside the two engine rosters
  session.py    REWIRED   still composition only
  predict.py    NEW  constant-velocity prediction + warp            pure stdlib
  assign.py     NEW  gated cost matrix + Hungarian                  pure stdlib
  memory.py     NEW  dormant gallery + re-acquisition               pure stdlib
  engines/
    base.py     EXTENDED  Transform, Descriptor, 2 new protocols    pure stdlib
    bytetrack.py unchanged   lk.py FIXED   ncc.py unchanged
    flow_gmc.py   NEW  sparse-flow global motion            cv2
    pose_gmc.py   NEW  attitude-derived global motion       stdlib math only
    histogram.py  NEW  colour-histogram descriptor          cv2
tools/trackeval/  NEW  recorder + offline replay + accuracy metrics
```

**The package invariant is unchanged and still tested:** everything under `tracking/` except
`engines/{bytetrack,lk,ncc,flow_gmc,histogram}.py` imports no `cv2`/`numpy`/`ultralytics`.
`pose_gmc.py` is deliberately on the pure-stdlib side — it is trigonometry, not image processing,
which is exactly why the onboard/no-OpenCV profile can still have ego-motion compensation.

### 3.1 `engines/base.py` additions

```python
@dataclass(frozen=True)
class Transform:
    """Affine normalized-image -> normalized-image map: how the CAMERA moved.

        x' = a*x + b*y + c
        y' = d*x + e*y + f

    IDENTITY means "no ego-motion known or none happened". Applied to a
    track's predicted box to express it in the current frame's coordinates
    BEFORE any IoU is computed against this frame's detections.
    """
    a: float = 1.0; b: float = 0.0; c: float = 0.0
    d: float = 0.0; e: float = 1.0; f: float = 0.0

    @property
    def identity(self) -> bool: ...
    def apply_point(self, x: float, y: float) -> tuple[float, float]: ...
    def apply_box(self, box: Box) -> Box: ...      # corner-transform, re-axis-aligned


@dataclass(frozen=True)
class Descriptor:
    """An appearance signature. `engine_id` guards the metric: comparing two
    descriptors from different engines is a max-distance no-match, never a
    silently meaningless number."""
    engine_id: str
    values: tuple[float, ...]

    def distance(self, other: "Descriptor | None") -> float:   # [0, 1], 1 = no match
        ...
    def blend(self, other: "Descriptor", alpha: float) -> "Descriptor":   # EMA
        ...


class MotionCompensator(Protocol):
    engine_id: str
    def estimate(self, frame, pose) -> Transform: ...   # frame may be None for pose engines
    def available(self, pose) -> bool: ...              # pose engine with no FOV -> False
    def reset(self) -> None: ...


class AppearanceExtractor(Protocol):
    engine_id: str
    def describe(self, frame, boxes: Sequence[Box]) -> list[Descriptor | None]: ...
    def reset(self) -> None: ...
```

### 3.2 `predict.py`

```python
@dataclass(frozen=True)
class Prediction:
    box: Box
    confidence: float     # decays with elapsed time; feeds the association gate

def predict(track, now: float, transform: Transform = IDENTITY) -> Prediction
    """Constant-velocity extrapolation from `track.last_seen` to `now`, then
    warped by `transform`. THE fix for review finding C1: the box moves while
    the object is unseen instead of freezing where it was last confirmed."""
```

### 3.3 `assign.py`

```python
@dataclass(frozen=True)
class Candidate:   # one live track offered to the matcher, ALREADY predicted+warped
    key: object; box: Box; label: str; descriptor: Descriptor | None; confirmed: bool

@dataclass(frozen=True)
class Target:      # one detection offered to the matcher
    box: Box; label: str; confidence: float; descriptor: Descriptor | None; det_index: int

@dataclass(frozen=True)
class Assignment:
    matches: tuple[tuple[int, int], ...]        # (candidate_index, target_index)
    unmatched_candidates: tuple[int, ...]
    unmatched_targets: tuple[int, ...]

@dataclass(frozen=True)
class AssignWeights:  iou: float; appearance: float; label: float
@dataclass(frozen=True)
class AssignGates:    max_cost: float; min_iou: float; max_appearance: float; high_confidence: float

class CostAssociator:
    """The platform's own matcher. Two stages, ByteTrack's insight preserved:
    high-confidence targets first, then low-confidence ones against whatever
    is still unmatched -- so a fading detection sustains a track without
    being able to create one."""
    engine_id = "cost"
    def assign(self, candidates, targets) -> Assignment: ...

def hungarian(cost: Sequence[Sequence[float]]) -> list[tuple[int, int]]
    """O(n^3) rectangular assignment, pure stdlib. INF entries are forbidden
    matches and are never returned as a pair."""
```

Cost, and the one place it is defined:

```
cost(c, t) = w_iou · (1 − IoU(c.box, t.box))
           + w_app · c.descriptor.distance(t.descriptor)
           + w_lab · (0 if labels compatible else 1)

forbidden when  IoU < min_iou  OR  appearance distance > max_appearance  OR  cost > max_cost
```

### 3.4 `memory.py`

```python
@dataclass(frozen=True)
class Recovery:
    track_id: int; confidence: float; dormant_millis: int

class ObjectMemory:
    """The dormant gallery. Bounded by construction: at most `capacity`
    identities, each holding at most `gallery_size` descriptors, each expiring
    `ttl_millis` after it was last seen."""
    def remember(self, track, descriptor, now) -> None: ...
    def match(self, *, box, label, descriptor, now) -> Recovery | None: ...
    def forget_expired(self, now) -> None: ...
    def claim(self, track_id) -> None: ...     # an id handed back is no longer dormant
    def size(self) -> int: ...
```

Match gates, all four required (a single appearance score is not enough to reissue an operator's id):
1. **label compatible**, 2. **appearance distance ≤ threshold**, 3. **motion plausible** — the object
could have travelled from its last known box to this one in the elapsed time at a bounded speed, and
4. **within TTL**. `confidence` is reported, never assumed.

---

## 4. Waves

Every wave ends with: its scoped `pytest` green, `cv-service/MODULE.md` updated in the same task, and
one commit. Waves are sequential — they share `session.py`.

| Wave | Scope | Acceptance |
|---|---|---|
| **C0** | proto diff · `engines/base.py` additions · `tools/trackeval/` | `vision-proto compile` green with zero Java edits; harness replays a synthetic sequence and reports id-switches / fragmentation / re-acquisitions / mean track life; existing 320 tests still green |
| **C1** | `predict.py` · `session.py` · `track.py` · `engines/lk.py` | Predict-on-coast, confidence trigger (b), LK re-seed + forward-backward, no book wipe on engine raise, time-based LOST. Harness shows fewer fragments than C0's baseline on the same sequence |
| **C2** | `engines/{flow_gmc,pose_gmc}.py` · `registry.py` · `session.py` · `params.py` | A synthetic pan sequence that C1 loses is tracked through; `motion_engine_id` on the response; absent pose degrades to flow, absent OpenCV degrades to none |
| **C3** | `assign.py` · `engines/histogram.py` · `session.py` · `registry.py` | Crossing-targets sequence keeps both ids; ByteTrack still selectable and still passing its own tests |
| **C4** | `memory.py` · `track.py` · `session.py` · servicer wire fields | A target occluded past `max_age` returns with **the same id**, `identity_confidence > 0`, `dormant_millis` ≈ the gap |
| **C5** | `session.py` · `scheduler.py` · `servicers.py` | FOLLOW emits the whole scene, not one box; a reconnect within the grace window keeps ids; ROI pass detects a small target a full-frame pass misses |

---

## 5. Non-goals

Unchanged from TRACKING-PLAN §6, plus: no re-ID neural network in this plan (the port makes it a
file drop); no cross-stream identity; no durable trajectory table; nothing that touches an airframe;
no Java source edits.

---

## 6. Invariants every wave must preserve

- **P1** `TRACKING_MODE_OFF` stays byte-identical to the pre-T1 service.
- **P2** The tracker/motion/appearance paths never acquire `InferenceGate`.
- **P3** `tracking/` outside `engines/` imports no `cv2`/`numpy`/`ultralytics`.
- **P4** No cadence, threshold or count literal outside `params.py` and an engine's own constants.
- **P5** Every degradation is logged once and never raises: a missing compensator, a missing
  descriptor, an empty memory each cost accuracy, never the stream.
- **P6** No Java source file is modified.

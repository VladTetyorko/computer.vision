# R4 — Wire Contract for a Tracked Object

Scope: the wire contract for one tracked object, proto → `cv/grpc` codec → `contexts/vision-perception`
domain → `station/vision-api` JSON → `station/vision-web` TypeScript, plus what has already been decided
about scaling CV. Pipeline flow is out of scope (other R-agents cover it). All claims below are VERIFIED
against source with `file:line`; anything not confirmed by reading source is marked DOC.

## Part A — field-by-field matrix

Status vocabulary: **POPULATED** (flows end-to-end to at least the JSON hop), **DEAD** (domain/wire field
exists but nothing ever sets it to a non-default value), **DROPPED-at-codec** (wire carries it, `cv/grpc`
never reads/writes it), **DERIVED-Java-side** (JSON/TS value computed in Java, not sourced from this wire
field), **DECLARED-unused** (present on wire and Java reads it, but no JSON/TS consumer exists).

### A.1 `CameraPose` (`proto/vision/v1/cv.proto:74-81`, request-only, no response counterpart)

| proto field | Java field | JSON | TS | Status |
|---|---|---|---|---|
| `yaw_degrees`=1 | `CameraAttitude.yawDegrees` (`.../perception/domain/model/CameraAttitude.java:42`), sourced from `Telemetry.headingDegrees` — **airframe** heading, not camera boresight (`CameraAttitude.java:27-31`) | — | — | POPULATED (request only; not surfaced on any JSON/TS hop) |
| `pitch_degrees`=2 | `CameraAttitude.pitchDegrees`; `ofYaw()` factory always passes `0.0` (`CameraAttitude.java:75-77`) — no gimbal feedback is decoded into this path | — | — | DEAD (defaults to wire's own "unknown" sentinel) |
| `roll_degrees`=3 | same as pitch — always `0.0` via `ofYaw()` | — | — | DEAD |
| `hfov_degrees`=4 | `CameraAttitude.hfovDegrees`, config-supplied; `0` disables pose compensation (`CameraAttitude.java:37`) | — | — | POPULATED when configured |
| `vfov_degrees`=5 | always `0.0` via `ofYaw()` — server derives from aspect ratio (`CameraAttitude.java:39,76`) | — | — | DEAD (by design, server-derived) |
| `pose_timestamp_millis`=6 | `CameraAttitude.at` → `toEpochMilli()` (`DetectionFrameCodec.java:~300`) | — | — | POPULATED |
| whole message | `DetectionFrameCodec.encode(...)` sets `camera_pose` only if `attitude != null && attitude.known()` (`DetectionFrameCodec.java:164-183`) | — | — | conditional-POPULATED |

### A.2 `TrackingConfig` (`cv.proto:104-123`, request-only)

| proto field | Java field | JSON | TS | Status |
|---|---|---|---|---|
| `mode`=1 | `TrackingConfig.mode` (`.../domain/model/TrackingConfig.java`) → `toWireTrackingMode` (`DetectionFrameCodec.java:333-338`) | `TrackingConfigRequest.mode` | `TrackingConfigRequest.mode` | POPULATED |
| `engine_id`=2 | `.engineId()` | `.engineId` | `.engineId` | POPULATED |
| `verify_every_millis`=3 | `.verifyEveryMillis()` | `.verifyEveryMillis` | `.verifyEveryMillis` | POPULATED |
| `redetect_iou_threshold`=4 | `.redetectIouPercent()/100f` (`DetectionFrameCodec.java:325`) | `.redetectIouPercent` (int percent, Java-side unit conversion) | same | POPULATED (unit converted) |
| `max_age_frames`=5 | `.maxAgeFrames()` | `.maxAgeFrames` | `.maxAgeFrames` | POPULATED |
| `min_hits`=6 | `.minHits()` | `.minHits` | `.minHits` | POPULATED |
| `lock`=7 (`TargetLock`) | `.lock()`, see A.4 | `.lock` | `.lock` | POPULATED (see A.4 for `box` gap) |
| `motion_engine_id`=8 | **no field** — domain `TrackingConfig` record carries no motion-engine-id component at all | — | — | DEAD-never-set (no domain home) |
| `appearance_engine_id`=9 | **no field** — same | — | — | DEAD-never-set |
| `memory_ttl_millis`=10 | **no field** — same | — | — | DEAD-never-set |
| `capability_level`=11 | `.capabilityLevel()` (TRACKING-V3-BAND1 wave J2) | `.capabilityLevel` | `.capabilityLevel` | POPULATED |
| 12-13 reserved | — | — | — | n/a (reserved, not assigned) |
| `reupdate_max_gap_millis`=14 | `.reupdateMaxGapMillis()` | `.reupdateMaxGapMillis` | `.reupdateMaxGapMillis` | POPULATED |

`followFps` on the domain record has **no wire field at all** — it is the Java sampler's own rate
(`TrackingConfig.java` javadoc). One domain field with nothing on the wire, mirroring the three wire
fields above with nothing in the domain.

### A.3 `TargetLock` (`cv.proto:88-95`, nested in `TrackingConfig`/`PullControl`)

| proto field | Java field | JSON | TS | Status |
|---|---|---|---|---|
| `lock_seq`=1 | `TargetLock.lockSeq` → `.setLockSeq()` (`DetectionFrameCodec.java:353`) | `TargetLockRequest` has no `lockSeq` — allocated server-side | — | POPULATED (request); lockSeq itself is a Java-allocated monotonic counter, never client-supplied |
| `track_id`=2 | `.trackId()` (nullable `Long`) | `TargetLockRequest.trackId?` | `.trackId?` | POPULATED |
| `point_x`=3 | `.pointX()` | `.pointX?` | `.pointX?` | POPULATED |
| `point_y`=4 | `.pointY()` | `.pointY?` | `.pointY?` | POPULATED |
| `box`=5 | **never set** — domain `TargetLock` carries no box component; explicit javadoc: "the wire's optional explicit-box override is a cv-service-only affordance this adapter has nothing to populate it from" (`DetectionFrameCodec.java:346-350`) | — | — | DEAD-never-set |
| `release`=6 | `.release()` | `.release?` | `.release?` | POPULATED |

### A.4 `BoundingBox` (`cv.proto:169-174`)

| proto field | Java | JSON | TS | Status |
|---|---|---|---|---|
| `x,y,width,height` | `BoundingBox(x,y,width,height)` (`DetectionFrameCodec.java:510-511`) | `BoundingBoxResponse{x,y,width,height}` | `box:{x,y,width,height}` | POPULATED, normalized [0,1], origin top-left |

### A.5 `Detection` (`cv.proto:176-191`, per-object facts)

| proto field | Java field | JSON | TS | Status |
|---|---|---|---|---|
| `label`=1 | `Detection.label` — wire value is already `track.elected_label` from cv-service, not the raw per-frame detector label (Python `tracking/session.py:851,1761,2338`) | `DetectionResponse.label` (DTO) / `TrackResponse.label` | `.label` | POPULATED |
| `confidence`=2 | `Detection.confidence` | `.confidence` | `.confidence` | POPULATED |
| `box`=3 | `Detection.box`, see A.4 | `.box` | `.box` | POPULATED |
| `track_id`=4 | `TrackRef.trackId`; wire `0` short-circuits to `track()==null` (`DetectionFrameCodec.java:407-410`) | `DetectionTrackResponse.id` / `TrackResponse.trackId` | `DetectionTrack.id` / `StreamTrack.trackId` | POPULATED |
| `track_state`=5 | `TrackRef.state`; `UNSPECIFIED`/unrecognized → whole `TrackRef` `null`, never guessed (`DetectionFrameCodec.java:412-415`) | `.state` (string) | `.state` (`TrackState` type) | POPULATED |
| `source`=6 | `TrackRef.source`; same defensive-null rule | `.source` | `.source` | POPULATED |
| `velocity_x`=7 | `TrackRef.velocityX` | `.velocityX` | `.velocityX` | POPULATED |
| `velocity_y`=8 | `TrackRef.velocityY` | `.velocityY` | `.velocityY` | POPULATED |
| `track_age_frames`=9 | `TrackRef.ageFrames` | `TrackResponse.ageFrames` only (not on nested `DetectionTrackResponse` — deliberate split) | `StreamTrack.ageFrames` | POPULATED (only on `/tracks`, not on `/detections`) |
| `identity_confidence`=10 | `TrackRef.identityConfidence` (TRACK-FOLLOW-PLAN W1) | not on any Detection/Track DTO — only surfaces via `FollowResponse.recoveryConfidence` for the *locked* track | not on `StreamTrack`/`Detection`; only `FollowStatus.recoveryConfidence` | POPULATED-but-narrow: decoded, carried on every `TrackRef`, but JSON only republishes it for the one FOLLOW-locked track, not per-detection |
| `dormant_millis`=11 | `TrackRef.dormantMillis` | same narrowing — only `FollowResponse.recoveredAfterMillis` for the locked track | same | POPULATED-but-narrow |
| 12-13 reserved | — | — | — | n/a |
| `reupdated`=14 | `TrackRef.reupdated` | `DetectionTrackResponse.reupdated` / `TrackResponse.reupdated` | `DetectionTrack.reupdated` / `StreamTrack.reupdated` | POPULATED |

### A.6 `DetectionResponse` envelope (`cv.proto:194-236`)

| proto field | Java field | JSON | TS | Status |
|---|---|---|---|---|
| `stream_id`=1 | **never read** — no `response.getStreamId()` call anywhere in `DetectionFrameCodec.java` (grep confirmed 0 hits); `decode(StreamId, DetectionResponse)` uses the caller-supplied `StreamId` instead | — | — | DROPPED-at-codec (redundant with session context, but genuinely unread) |
| `sequence`=2 | `DetectionResult.frameSequence` (`DetectionFrameCodec.java:203`) | `DetectionResultResponse.frameSequence` | `DetectionResult.frameSequence` | POPULATED |
| `timestamp_millis`=3 | `DetectionResult.capturedAt` | `.capturedAt` | `.capturedAt` | POPULATED |
| `model_id`=4 | `ModelRef(getModelId(),...)` (`DetectionFrameCodec.java:198`), response-level only | `DetectionResponse.modelId` **replicated per-detection** in JSON | `Detection.modelId` | POPULATED, DERIVED-Java-side replication (one wire value fanned out per box) |
| `model_version`=5 | same | `.modelVersion` per detection | `.modelVersion` | POPULATED, same replication |
| `detections`=6 | `List<Detection>`, see A.5 | `.detections[]` | `.detections[]` | POPULATED |
| `inference_millis`=7 | `DetectionResult.inferenceLatency` (`Duration`) | `DetectionResultResponse.inferenceMillis` | `.inferenceMillis` | POPULATED |
| `tracker_millis`=8 | `TrackingTelemetry.trackerLatency` (`DetectionFrameCodec.java:255,276`) | `FrameTrackingResponse.trackerMillis` (fractional double, not rounded) | `FrameTracking.trackerMillis` | POPULATED |
| `detector_ran`=9 | `TrackingTelemetry.detectorRan` | `.detectorRan` | `.detectorRan` | POPULATED |
| `tracker_engine_id`=10 | `TrackingTelemetry.engineId` | `.engineId` | `.engineId` | POPULATED |
| `locked_track_id`=11 | `TrackingTelemetry.lockedTrackId` | `.lockedTrackId` | `.lockedTrackId` | POPULATED |
| `detector_reason`=12 | `TrackingTelemetry.reason` (`DetectorReason` enum, only meaningful iff `detectorRan`) | `.detectorReason` | `.detectorReason` | POPULATED |
| `detector_roi`=13 | **never read** — `toTrackingTelemetry` (`DetectionFrameCodec.java:253-292`) does not call `getDetectorRoi()`; confirmed by exhaustive grep for the method name (0 hits outside the wire type itself) | — | — | DROPPED-at-codec |
| `motion_millis`=14 | **never read**, same method, same grep | — | — | DROPPED-at-codec |
| `motion_engine_id`=15 | **never read**, same method, same grep (this is the drop the task brief already named) | — | — | DROPPED-at-codec |
| `decode_millis`=16 | `PullTelemetry.decodeMillis` (`toPullTelemetry`, `DetectionFrameCodec.java:221-234`) | `DetectionRateResponse.decodeMillisP50` (aggregated, not raw per-frame) | `DetectionRate.decodeMillisP50` | POPULATED, aggregated Java-side into a P50 rather than passed raw |
| `source_fps`=17 | `PullTelemetry.sourceFps` | `DetectionRateResponse.sourceFps` | `.sourceFps` | POPULATED |
| `achieved_fps`=18 | `PullTelemetry.achievedFps` | not directly on `DetectionRateResponse` (which has `targetFps`/`submittedFps`/`demandFps` instead) — folded into those Java-side rate concepts | not directly exposed | DERIVED-Java-side (absorbed into pre-existing `DetectionRate` concepts, not passed through 1:1) |
| `dropped_frames`=19 | `PullTelemetry.droppedFrames` | `DetectionRateResponse.droppedOutage`/`droppedInFlight` (split, not 1:1) | same split | DERIVED-Java-side (one wire counter maps onto two JSON counters by cause) |
| `missed_deadlines`=20 | `PullTelemetry.missedDeadlines` | `DetectionRateResponse.missedDeadlines` | `.missedDeadlines` | POPULATED |
| `capture_skew_millis`=21 | `PullTelemetry.captureSkewMillis` | not found on any response DTO in the `/tracks`,`/detections` surfaces | not found | DECLARED-unused (decoded, has nowhere to land in JSON) |
| `reupdate_millis`=22 | `TrackingTelemetry.reupdateLatency` | `FrameTrackingResponse.reupdateMillis` | `FrameTracking.reupdateMillis` | POPULATED |
| `reupdated_tracks`=23 | `TrackingTelemetry.reupdatedTracks` | `.reupdatedTracks` | `.reupdatedTracks` | POPULATED |
| `detection_lag_millis`=24 | `TrackingTelemetry.detectionLag` | `.detectionLagMillis` | `.detectionLagMillis` | POPULATED |
| `capability_level_served`=25 | `TrackingCapability.levelServed`; `0` decodes to `capability()==null` not a validation throw (`DetectionFrameCodec.java` javadoc ~268-292) | `TrackingCapabilityResponse.levelServed` | `TrackingCapability.levelServed` | POPULATED |
| `capability_level_reason`=26 | `TrackingCapability.reason` | `.reason` | `.reason` | POPULATED |

`toTrackingTelemetry` and `toPullTelemetry` each apply an "absent means absent" all-zero check across
their own field set (10 fields, 6 fields respectively) before returning `null` rather than a fabricated
zero object — this is a deliberate wire idiom, not a gap (`DetectionFrameCodec.java:238-252,272-292`).

### A.7 Model registry / health — brief note (not a per-object track fact, so not tabulated field-by-field)

`ModelInfo{id,version,stage,map<string,float> metrics}` / `ModelList` (`cv.proto:261-270`) is consumed by
`GrpcModelRegistryPort.models()/active()` (`cv/grpc/.../GrpcModelRegistryPort.java:120-148`), which maps
onto `ModelRef(id,version)` only — **`stage` is collapsed into which entry `active()` returns rather than
carried as a field, and `metrics` has no home at all and is dropped** (`GrpcModelRegistryPort.java:64-70`,
explicit javadoc naming this a deliberate gap, deferred to a future wire-passthrough REST surface if
needed). There is **no dedicated Health RPC** in `cv.proto` at all: health is inferred purely from gRPC
`ConnectivityState` by `CvChannelSupervisor.state()` (`cv/grpc/.../CvChannelSupervisor.java:161-162`), fed
to `CvStatusProvider` (`.../CvStatusProvider.java:23-40`) which reports `Health.UNKNOWN` when reconnect
supervision itself is disabled. No cv-service-reported health fact (queue depth, model load state, GPU/CPU
load) exists on the wire anywhere.

## Part B — gaps against "a mirror of the real object"

| Facet | Wire today | Internal-only (cv-service) | Nowhere |
|---|---|---|---|
| Velocity / displacement since last update | **EXISTS**: `Detection.velocity_x/y` (`cv.proto:183-184`) | — | — |
| Predicted next box (t+1) | — | **EXISTS internally, not sent**: `tracking/predict.py`, `class Prediction`, `def predict(track, now)` — constant-velocity extrapolation used for coasting/gating only | — |
| Confidence history / smoothed confidence | — | **partially internal**: `Track._label_votes` (deque) backs `elected_label` election (`tracking/track.py` ~lines 195-244), a confidence-weighted decayed tally — but the tally itself, or a smoothed confidence scalar, never reaches the wire (only the single current-frame `confidence` does) | a genuine smoothed-confidence-over-time series |
| Elected label + label distribution (top-k) | **EXISTS partially**: `Detection.label` on the wire *is* `track.elected_label`, not the raw frame label | the full vote tally / top-k distribution behind that election (`_label_votes`, `_label_challenger`, `_label_challenger_streak`) never leaves `track.py` | top-k distribution |
| Identity lifecycle state (TENTATIVE/ACTIVE/COASTING/DORMANT/LOST) | **EXISTS partially**: `TrackState` enum has TENTATIVE/CONFIRMED/COASTING/LOST (`cv.proto:31-37`) | `DORMANT` is a Python-only concept (`tracking/memory.py`, `class DormantIdentity`) — a track that leaves `LOST` and enters the per-stream gallery has no wire state; the wire's `LOST` is the last state ever reported for it | `DORMANT` as a wire-visible state |
| Re-ID / memory match evidence (which gallery entry, similarity) | **EXISTS partially**: `identity_confidence`/`dormant_millis` (fields 10-11) are the only recovery facts on the wire | which gallery entry matched, candidate count, and the `closest_match` distance computation live only in `tracking/memory.py` (`ObjectMemory`, `DormantIdentity.closest_match` = `min(candidate.distance(other) for candidate in candidates)`) and are only logged (`"tracking: recovered track #%d after %d dormant identities considered"`) | which-entry-matched / candidate-count on the wire |
| Appearance signature | — | **EXISTS internally, not sent**: `Track.descriptor: Optional[Descriptor]` — EMA-updated from DETECTOR-sourced observations only (`tracking/track.py` docstring ~134-193) | — |
| Ego-motion compensation applied (which engine, transform) | **EXISTS partially**: `motion_engine_id`/`motion_millis` (fields 14-15) are on the wire — **and then DROPPED-at-codec** (Part A.6), so effectively absent end-to-end today | the actual 6-parameter affine `Transform(a,b,c,d,e,f)` (`tracking/engines/base.py:234-303`) composed per-frame by `TrackBook.warp()` never serializes at all — only the aggregate cost/engine-name fields exist on the wire, and even those don't reach Java | the transform itself |
| Geolocation (lat/lon/alt) | **EXISTS, but is two unrelated things**: (1) `geo:<assetId>`/`CorrectionResponse` is the **aircraft's own** corrected position (`contexts/vision-flight/.../TrackCorrection.java`), nothing to do with a tracked ground object; (2) `GET /api/map/tracks`/`ProjectedTrack` (`contexts/vision-map/.../ProjectedTrack.java`) **is** a tracked ground object's lat/lon, but it is computed **entirely Java-side** from `TrackBook` box-bottom-center + camera pose and never touches `cv.proto` at all | — | a cv-service-computed ground-object geolocation |
| Track age/hits/misses | **age exists**: `track_age_frames` (field 9) | `hits`/`misses` counters exist on `Track` (`tracking/track.py` dataclass fields) but never reach the wire | hits/misses |
| Source of this update (detector vs tracker vs prediction) | **EXISTS**: `DetectionSource` enum (`cv.proto:40-44`), only DETECTOR/TRACKER — "prediction" (ORU-reconstructed) is instead flagged separately via `reupdated` (field 14), not folded into `source` | — | a third `source` value for pure prediction (ORU marks it as an orthogonal boolean instead, which is arguably more honest) |
| Per-component provenance (which evidence terms drove association) | — | **EXISTS internally, not sent**: `tracking/assign.py`, `CostAssociator.cost()` computes `total = weights.iou*(1-overlap) + weights.appearance*distance + weights.label*(...)` — the per-term breakdown is computed and discarded every frame, never logged or sent | — |
| Lock/follow state | **EXISTS on the control side** (`TargetLock`, `locked_track_id`) but the rich lifecycle (`FollowState` REQUESTING/HOLDING/COASTING/LOST/RELEASED) is **entirely Java-derived** from `lockedTrackId`+`TrackState`/`DetectionSource` transitions (`.../application/pipeline/FollowTracker.java`) — cv-service has no `FollowState` concept at all | — | — |
| Time since last detector confirmation | — | `Track.last_confirmed` exists and only moves on `SOURCE_DETECTOR` (never on a tracker-coast) (`tracking/track.py` docstring) | never reaches the wire as a duration; the closest wire proxy is `track_age_frames`, which counts frames since birth, not since last detector hit |

## Part C — prior scaling decisions (from `docs/plans/README.md` status table)

**CV-SCALE-PLAN** (`docs/plans/active/CV-SCALE-PLAN.md`) — goals 1-4 **shipped**; goal 5 (a pool of 2-5 CV
workers with failover) is **open, 0% code** — `GrpcCvSettings` is still single-target, no `PooledDetectionPort`
exists. Decided pivot: demand+affinity (detection demand = SSE subscription refcount driving sticky-least-
loaded worker routing), phased S1 (live reconfigure, done) → S2 (on-demand gating, done) → S3 (model roster
+ health) → S4 (multi-worker pool — the open goal-5 work) → S5 (pull-based frames, **DECIDED: GO**, executed
separately as MEDIA-SOT-PLAN M0-M9 and now merged). Explicit verdict: "keep gRPC; do not move the CV
transport to WebSockets" — considered and rejected. Non-goals/guardrails section fences the plan to
detection-serving scale only, not training or geolocation scale.

**TRACKING-V3-PLAN** (+`TRACKING-V3-BAND1-CONTEXT.md`) — **merged to master** (band 1, `331a6a77`): the
capability ladder L1 RELAY (~13MiB stdlib) → L2 FILL (+cv2) → L3 DETECT (+ultralytics) → L4 IDENTIFY
(+OpenVINO re-ID) → L5 STUDY (+training) is wired end to end, `capability_level` is a ceiling not a demand
(decision E12), degradation is always downward and logged once (P5), wire contract is identical at every
level. §6 waves V0-V8: V3 (ORU) marked "Delivered" with measured nonlinear-coast ADE improving 39.8→9.1px.
§6b lists five **open findings O1-O5** (FOLLOW not benefiting from late-detection correction; `ObservationRing.
before()` assumes non-decreasing timestamps; harness blind to non-physical state; ORU guards only cover the
`cost` associator, not bytetrack; FOLLOW shape-check cost unmeasurable without real aerial footage) — all
five need real aerial footage to resolve, none are merged fixes, all remain specced-only.

**DOMAIN-SEPARATION-PLAN** (perception/worker/NATS/lease parts only) — **W1 merged** (`00879827`): the 8
contexts including `vision-perception` are now independent Maven modules. Everything else is **open**: D3
decides NATS JetStream as the broker (not Kafka, despite one memory note recording the user wanting Kafka);
D7 decides the lease unit is the **Asset**, not the stream — one worker can hold leases for several streams
of the same asset, never split across workers. Urgency classes U0 (sync control, direct gRPC/dial) through
U3 (workflow/history, async work queue) classify which traffic may ever move off direct dial onto the
broker. `vision-perception`'s worker role (W3) — pulling frames, holding asset-unit leases, publishing
detections onto JetStream instead of (or alongside) direct gRPC — is entirely **specced, not built**.

**CV-PULL-SPIKE** (`docs/conclusions/CV-PULL-SPIKE.md`) — a completed M0 spike for MEDIA-SOT-PLAN, decision
**GO**. Findings: `opencv` is the fastest decoder (2.3-4.1ms); yolo26n costs 36-58ms on a laptop, 197-200ms
on the GB4005 Celeron; the latest-wins rate-discipline design (D8) is proven correct; capture-clock drift on
the anchor candidate passes the 100ms/10min gate with margin; a blocking finding for wave M7 was corrected
(mediamtx Control API is IP-gated, not open); GB4005 clock offset measured at -7.9ms±9.7ms. Final config
decisions: `CV_PULL_DECODER=opencv`, `CV_PULL_CLOCK_MODE=anchor`. This spike is **done and its decisions are
now load-bearing production config**, not merely specced.

**ALWAYS-ON-FLOW-PLAN §3** ("the honest ceiling") — **A + B3 + C + D1/D2 built** 2026-09-06 per user memory;
D3 remains **open**. The critical finding: "there is **no platform-wide inference budget**. `maxInFlightInferences`
is 2 per stream and has no property key at all... `vision.cv.inference.targets` is gRPC `pick_first`
**failover**, not load balancing... one cv-service saturates at roughly 3-4 streams at 10fps (yolo26n) — and
about 0.3 streams for `orion12l`." An explicit anti-goal is recorded: do **not** build `CV-DEMAND-PLAN §7`'s
proposal to push `detection_enabled` down the pull wire, because in pull mode the worker is already
always-on and the JVM already just discards unwanted results (`StreamPipeline:1347`) — rejected as solving
a problem that doesn't exist in pull mode. §4 wave D3 (a fleet-wide inference budget/scheduler) is the
explicitly named missing piece: "without this, D1/D2 convert a viewer ceiling into a queueing collapse."

## Surprises / defects noticed

1. **Three response fields silently dropped at the codec, not one.** The task brief names `motion_engine_id`
   as a known drop; reading `toTrackingTelemetry`'s exact field list and confirming with a targeted grep for
   `getMotionMillis|getMotionEngineId|getDetectorRoi` across `DetectionFrameCodec.java` shows **zero** call
   sites for any of the three. `detector_roi` (field 13) and `motion_millis` (field 14) are dropped
   alongside `motion_engine_id` (field 15) — cv-service populates all three (Python response builder sets
   `motion_engine_id=motion_engine_id` around `tracking/session.py:~604`), Java reads none. The ego-motion
   compensation story is therefore *worse* than "the engine name is missing" — cost, engine identity, and
   whether the detector pass was a full-frame or ROI crop are all computed and all discarded.
2. **`DetectionResponse.stream_id` (field 1) is also never read.** Not previously flagged anywhere in the
   module docs. `decode(StreamId streamId, DetectionResponse response)` uses the caller-supplied `streamId`
   exclusively; a grep for `getStreamId` across the codec returns zero hits. Almost certainly benign
   (session-scoped decode already knows which stream it's decoding), but it means a cv-service bug that
   mislabels its own `stream_id` in a response would go completely undetected client-side.
3. **`TrackingConfig`'s request-side dead trio has no domain home at all**, not merely an unwired mapping:
   `motion_engine_id`/`appearance_engine_id`/`memory_ttl_millis` (fields 8-10) have no corresponding field
   on the domain `TrackingConfig` record (`contexts/vision-perception/.../TrackingConfig.java`) — there is
   no operator control, REST field, or TS field for any of the three anywhere in the stack. An operator can
   never select an appearance engine or configure the dormant-gallery TTL from the UI even in principle.
4. **`TargetLock.box` (field 5) is deliberately never set** — confirmed via explicit javadoc at
   `DetectionFrameCodec.java:346-350` citing `docs/plans/done/TRACKING-PLAN.md §4.D`: the REST PATCH surface
   only ever accepted `trackId`/point/`release`, so the wire's explicit-box override is unreachable from
   this codebase by design, not oversight.
5. **`identity_confidence`/`dormant_millis` decode onto every `TrackRef` but only reach JSON for the one
   FOLLOW-locked track** (via `FollowResponse`), never per-detection on `/tracks` or `/detections`. A
   non-locked track that was itself a re-ID recovery has that fact decoded into the domain and then
   discarded before JSON — an operator watching the full track list cannot see it, only the tracker's own
   `follow` panel can.
6. **`achieved_fps` (18) and `dropped_frames` (19) are absorbed rather than passed through 1:1** — the JSON
   `DetectionRateResponse` recomputes/splits these into its own pre-existing rate vocabulary
   (`targetFps`/`submittedFps`/`demandFps`, `droppedInFlight`/`droppedOutage`) rather than exposing the raw
   wire counters, so a discrepancy between cv-service's own accounting and Java's derived numbers would be
   invisible without cross-referencing logs.
7. **`capture_skew_millis` (21) is decoded into `PullTelemetry` but never reaches any JSON DTO** on the
   `/tracks` or `/detections` surfaces — a genuinely DECLARED-unused field, distinct from the DROPPED-at-codec
   ones because Java *does* read it, it just has no downstream consumer today.
8. **TS `DetectionState` may be missing `RUNNING_UNWATCHED`.** `models.ts` was read with a 3-value type
   (`'OFF'|'IDLE_NO_VIEWERS'|'RUNNING'`) while the Java enum (`.../domain/model/DetectionState.java:21-42`)
   has four values including `RUNNING_UNWATCHED` (added by ALWAYS-ON-FLOW-PLAN wave D2, itself marked BUILT).
   Not independently re-verified against the live file at write time — flagged as an open question below
   rather than asserted as a confirmed bug, since it's equally possible the frontend deliberately collapses
   `RUNNING_UNWATCHED` into `RUNNING` for display purposes.
9. **Two unrelated "geolocation" concepts share the vocabulary but not the wire.** `geo:<assetId>` SSE /
   `TrackCorrection` (vision-flight) is the aircraft's own corrected position; `GET /api/map/tracks` /
   `ProjectedTrack` (vision-map) is a tracked ground object's projected position. Only the second is what
   Part B's "mirror object" question is actually asking about, and it is **entirely a Java-side geometric
   projection** (box-bottom-center + camera pose) — cv-service has no visual-geolocation concept for a
   tracked *object*, only for the aircraft itself (the `Geolocation` gRPC service in `cv.proto` is 100%
   aircraft-position, confirmed by reading all of its messages: `GeoTelemetry`, `GeoFix`, `GeoEvidence`, etc.).
10. **Per-detection `model_id`/`model_version` in JSON is a one-to-many fan-out of a response-level wire
    field**, not a wire capability — every detection in a frame currently shares one model by construction
    (single active model per stream), so this is currently harmless, but it is a JSON shape promise
    (independent model per box) that the wire cannot actually satisfy if a future capability needs mixed
    models in one response.

## Open questions for the architect

1. Is the `detector_roi`/`motion_millis`/`motion_engine_id` triple-drop (surprise 1) intentional debt, or
   should a Band-2-style wave wire it onto `TrackingTelemetry` the same way TRACK-FOLLOW-PLAN W1 wired
   `identity_confidence`/`dormant_millis`? The domain record (`TrackingTelemetry.java`) currently has no
   field for any of the three, so this is a domain-modeler + adapter-builder wave, not a one-line fix.
2. Should per-detection `identity_confidence`/`dormant_millis` be surfaced on `TrackResponse`/`StreamTrack`
   for *every* recovered track, not only the FOLLOW-locked one (surprise 5)? Today an operator watching
   ASSOCIATE mode has no visibility into which of the tracks on screen are fresh identities vs. recoveries.
3. Is `TargetLock.box` (never set, surprise 4) worth exposing on the PATCH surface, or is the "server picks
   a default box around the point" behavior considered final?
4. For the "mirror object" ambition specifically: the biggest internally-computed-but-discarded facts are
   the ego-motion `Transform` itself, the appearance `Descriptor`, and the per-component association cost
   breakdown (`iou`/`appearance`/`label` terms). None of these were ever intended to leave cv-service per
   the code's own docstrings — does the architect want a new wire wave to expose any of them, or are they
   deliberately kept server-internal as implementation detail that would leak cv-service's tracking
   algorithm choice onto the wire if exposed?
5. Should `DetectionSource` gain a third value for ORU-reconstructed boxes, or does keeping `reupdated` as
   an orthogonal boolean (current design) better represent "this is still fundamentally a detector or
   tracker box, just backfilled" — worth confirming this is a considered choice, not an oversight.
6. Please confirm/deny the TS `DetectionState` 3-vs-4-value question (surprise 8) — a quick check of
   `station/vision-web/src/app/core/api/models.ts` against `DetectionState.java` would settle it in minutes
   and this agent's scope/budget did not allow re-verifying it against the live file before writing.
7. `ModelInfo.metrics` (a `map<string,float>`) is dropped entirely at `GrpcModelRegistryPort` by its own
   documented design, deferred to "a wire-level passthrough if a later wave needs it" — is a model
   quality/metrics surface (mAP, loss, etc.) in the near-term roadmap, which would make this the right time
   to build that passthrough rather than defer it again?

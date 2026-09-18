# vision-proto — wave history

Newest first. Current wire contract lives in [`MODULE.md`](MODULE.md); this file only narrates
*why* and *when* it got there.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W5b

2026-09-13, §4.9 "As built in W5" / §8 decision E23. Widened `FrameLedger` (fields 13–15, additive
only) so a **traced** frame carries the detector's own raw per-frame boxes — the input
`tools/trackeval` needs to replay a saved trace against real detector noise, since
`ObjectState.detector_box` exists only for matched objects and would silently drop everything the
associator rejected. Added `TracedDetection` (1 new message) deliberately separate from
`Detection`, so a fixture meant to test the tracker against detector noise could never let the
tracker's own output back in through its track fields. Purely additive — no existing field
renumbered or removed. `./mvnw -B -pl cv/vision-proto -am test -DskipWeb`: `TrackingProtoAdditivityTest`'s
existing byte-level cases pass unchanged (the same additivity check every prior wave relied on).
`cv/cv-service/scripts/gen_proto.sh` regenerates the Python side clean — `cv_pb2.pyi` picks up
`TracedDetection` and `FrameLedger.detections`/`.frame_width`/`.frame_height`.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W4

§4.9, decision E11. Added the `Detector` service — the stateless half of a tracker/detector split,
so a detector instance may be scaled to N instances behind an ordered target list while the tracker
session stays sticky to one stream. 3 new messages (`DetectRequest`, `DetectResponse`,
`DetectorTarget`), 1 new RPC (`Detector.Detect`), `ProcessFacts` widened with 5 new fields (6–10).
Purely additive, no existing field renumbered. `./mvnw -B -pl cv/vision-proto -am -q compile -DskipWeb`
regenerates and compiles clean.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W1

§4.5, decision E14 (dead-field deprecation), E8 (no enum ever gains a value), E5 (fusion-ready key),
E7 (object vs. algorithm split). Added `ObjectState` — the mirror — plus its two dedicated enums
(`ObjectLifecycle`, `EvidenceSource`, used only by `ObjectState` so no pre-existing enum needed a
new value), `DetectionResponse.objects`=27 and `.ledger`=28, `FrameRequest.trace`=13 and
`PullControl.trace`=12. Also deprecated-by-comment 8 dead fields across `DetectionResponse`,
`TrackingConfig` and `TargetLock` (see MODULE.md's Deprecated fields table) — each had been
populated by cv-service and read by nobody; R4's review had traced each one to a Java codec with
zero call sites for it. 10 new messages, 3 new enums, 1 new RPC total across W0+W1, purely
additive; `TrackingProtoAdditivityTest`'s byte-level cases still passed unchanged, which is the
check that proved it.

## docs/plans/active/CV-ORCHESTRATION-PLAN.md — wave W0

§4.4. Added `Inference.Inspect(InspectRequest) returns (InspectResponse)` — a read-only debug
surface entirely off the response path, so a client that never calls it cannot tell it exists.
6 new messages (`LedgerEntry`, `ObjectEvidence`, `ObjectClaims`, `FrameLedger`, `SessionFacts`,
`ProcessFacts`, `InspectRequest`, `InspectResponse` — 8 counting the request/response pair), 1 new
enum (`LedgerOutcome`), 1 new RPC. Also the wave that made `DetectionResponse.dropped_frames`(19,
originally an M1 field) populate under **push mode** (`DetectStream`) too, from the same
`LatestOnlyMailbox.dropped` counter push mode already maintained — before W0 it was pull-mode only.
Purely additive.

## docs/plans/done/VISUAL-GEO-V2-PLAN.md — wave H1

§3.1/§5 H1, applied exactly as frozen — a minimised subset of the parked branch `feat/visual-geo`'s
own geo proto (the plan governs, the branch was reference only). Added the wholly new `Geolocation`
service: 11 new messages (`GeoTelemetry`, `GeoPrior`, `GeoControl`, `GeoEvidence`, `GeoFix`,
`ReferencePackChunk`, `ReferenceIndexStats`, `ReferenceIndexProgress`, `RegionRef`, `RegionInfo`,
`RegionList`), 1 new enum (`GeoStatus`), 1 new gRPC service. Pull-only by design (D2), same doctrine
as `Inference.DetectPulled`. The one place this wave deliberately diverged from the parked branch:
`ReferenceIndexStats.never_accept_cells` (a 10th field the branch never had) and
`RegionInfo.north`/`.south`/`.east`/`.west` (4 fields the branch never had) — both the plan's own
additions, not a deviation from it. This is also the wave proto3's explicit `optional` keyword on
scalar fields first appears in this file (`GeoTelemetry`'s 11 of 12 fields, `GeoControl.telemetry`/
`.prior`, `GeoFix`'s position fields, `ReferenceIndexProgress.stats`) — verified stable under
protoc 3.25.5 by both `./mvnw -pl cv/vision-proto test` (Java) and a manual `grpc_tools.protoc` run
against the venv (Python) in the same wave. `TrackingProtoAdditivityTest` was extended with one
byte-level case pinning `PullControl` (the one pre-existing message with no additivity coverage
before this wave) alongside its 3 pre-existing cases — 5 tests total, all green.
`./mvnw -B -pl cv/vision-proto -am test` regenerated, compiled and ran green (×3 per the plan's own
preamble); `cv/cv-service/scripts/gen_proto.sh` regenerated the Python side clean, `cv_pb2.pyi`
picking up `GeoStatus`/`GeoTelemetry`/`GeoControl`/`GeoFix`/`ReferenceIndexStats`/`RegionInfo` etc.
and `cv_pb2_grpc.py` picking up `GeolocationStub`/`GeolocationServicer`.

## docs/plans/done/MEDIA-SOT-PLAN.md — wave M1

§5.1, applied exactly as frozen. Added the pull-mode control plane: `PullControl` (new message, 11
fields at the time — `trace`=12 came later, W1), `Inference.DetectPulled` (new RPC — the worker
dials mediamtx and decodes the stream itself instead of the client sending frames), and
`DetectionResponse` fields 16–21 (new, pull-mode only at the time — the accounting a Java-side
round trip used to give for free, which pull mode now has to report explicitly since there is no
longer a Java→Python frame round trip to measure it from). Purely additive — `DetectStream`'s wire
shape and generated code were untouched, and the 712-test cv-service suite (`./scripts/test.sh`,
mock/hand-fake based, exercises `DetectStream` end-to-end) was unaffected.

## docs/plans/active/TRACKING-V3-PLAN.md — wave V6

§4.5, late-detection back-correction. Added `DetectionResponse.detection_lag_millis`=24 (measured
capture→association lag; 0 = unknown). Also corrected `history.py`'s own instrument (an
observation's timestamp semantics) — the wave's own §4.1 notes this correction was made mid-wave.
"The wave that makes L1 worth carrying" per the plan's waves table (§6): an offboard detector is a
late detector by definition, and this is what lets the wire report it rather than hide it inside
`tracker_millis`. Delivered as part of the V1–V7 + band-1 set the plan's status line records as
landed (commit `331a6a77`); §6b lists 5 findings (O1–O5) deliberately left open, none of them
wire-shaped.

## docs/plans/active/TRACKING-V3-PLAN.md — wave V3

§4.2, ORU (Observation-Centric Re-Update) — reconstructs the longest gap, in milliseconds, between
two real bracketing observations. Added `Detection.reupdated`=14, `DetectionResponse.reupdate_millis`=22
and `.reupdated_tracks`=23, `TrackingConfig.reupdate_max_gap_millis`=14 (fields 12–13 on both
`Detection` and `TrackingConfig` were deliberately left reserved for a later wave rather than used
here). Measured result the plan records: `nonlinear` coast ADE **39.8 → 9.1 px (−77%)**, PT→MT;
29 of 30 rows byte-identical; reproduction proven with `CV_TRACK_REUPDATE_MAX_GAP_MILLIS=0`.
`long_occlusion` was struck from the acceptance criterion — its motion is genuinely
constant-velocity, so the pre-V3 estimate already converged to the right answer; there was no drift
there to remove.

## docs/plans/active/TRACKING-V3-PLAN.md — wave V1

§5, the capability ladder — one pipeline, five affordability tiers. Added `TrackingConfig.capability_level`=11
(0 = auto-probe the highest level this host affords, 1–5 = an explicit ceiling — decision E12: a
level is a ceiling, `capability_level_served` is always ≤ requested, never a refusal) and
`DetectionResponse.capability_level_served`=25 / `.capability_level_reason`=26. Acceptance per the
plan's waves table (§6): five levels resolve; `capability_level=0` auto-probes; **L1 provably
imports no `cv2`, `numpy`, `ultralytics` or model asset** (asserted by inspecting `sys.modules`, not
by reading source); every downgrade logged exactly once.

## docs/plans/done/TRACKING-PLAN.md — wave T0

§4.A, applied verbatim. Added the tracking wire contract: `FrameRequest.tracking`=11
(`TrackingConfig`, new), `Detection` fields 4–9 (`track_id`, `track_state`, `source`,
`velocity_x`/`velocity_y`, `track_age_frames`), `DetectionResponse` fields 8–12 (`tracker_millis`,
`detector_ran`, `tracker_engine_id`, `locked_track_id`, `detector_reason`), `TargetLock` (new
message) and `TrackingConfig` (new message). Purely additive — `git diff` on the `.proto` was
insertion plus three widened-but-unchanged messages' field lists, zero existing field
numbers/names/types touched. This is also the wave that added the module's one hand-written source,
`TrackingProtoAdditivityTest` (and with it, the `org.junit.jupiter:junit-jupiter` test-scope
dependency this module previously had none of at all) — the additive guarantee needed to become a
checked fact once the wire started growing wave over wave, not just a claim.

## docs/plans/done/TRACKING-V2-PLAN.md — waves C0–C5

Delivered as one block (plan §5b, all waves green, branch `feat/tracking-v2`, zero Java source
files changed — `adapter-cv-grpc` re-verified compiling against the extended proto). Added
`CameraPose` (wave C0, decision D2: freeze the field now, consume it later — it cost zero Java
edits, and cv-service could ship `pose` motion compensation the moment anything filled it while
`flow` compensation kept working with nothing filled in). Wave C2 (ego-motion engines) added what
became `DetectionResponse.motion_millis`=14/`.motion_engine_id`=15; wave C5 (the ROI rescue pass)
added what became `.detector_roi`=13 — all three were later deprecated by comment in CV-ORCHESTRATION
wave W1 once R4's review found zero Java call sites for any of them. Wave C4 (the dormant gallery,
`memory.py`) added `Detection.identity_confidence`=10/`.dormant_millis`=11 (decision D3: report
re-acquisition honestly rather than pretend continuity) — a target occluded past `max_age` returns
with the same id, `identity_confidence > 0`, `dormant_millis` ≈ the gap.

## docs/plans/done/CV-TRAINING-V2-PLAN.md — wave W0

§1. Added `Training.UploadDataset(stream DatasetChunk) returns (UploadAck)`, plus `DatasetChunk` and
`UploadAck` — the client-streamed YOLO dataset archive upload path. Purely additive:
`StartTraining`/`ListModels`/`PromoteModel` and every other pre-existing message/RPC were unchanged.
The Java client (`adapter-cv-grpc`, its own wave W3) and the Python servicer (`cv-service`, its own
wave W2) that actually consume this shape landed in later, disjoint waves — this module only ever
carried the generated shapes.

## docs/plans/done/PHASE0-PLAN.md — Task T3

The plan calls this a "Task", not a "wave" — kept as named. Initial codegen wiring: the
`protobuf-maven-plugin`/`os-maven-plugin` setup, `protoSourceRoot` pointed at the repo-root
`proto/vision/v1/cv.proto`, and the first generated surface (`Inference`/`Training` services and
their original messages). No hand-written test class yet — `TrackingProtoAdditivityTest` came later,
with TRACKING-PLAN wave T0.

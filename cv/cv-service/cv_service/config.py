"""Centralized environment configuration for cv-service.

``Settings.from_env()`` is the **one place** every ``CV_*`` environment
variable is read and parsed. Before this module existed, five different
variables were read piecemeal across ``server.py``/``inference.py``/
``trainer.py``/``concurrency.py`` -- three of them (``CV_DEVICE``,
``CV_MAX_CONCURRENT_INFERENCES``, ``CV_DATASET_DIR``) resolved once at
*import* time, so a value baked into a module-level constant could never be
changed without a process restart even though nothing else about the code
required that; the other two (``CV_MODEL``, ``CV_IMGSZ``) had their forgiving
"unset/garbage -> default" parsing logic duplicated in more than one module.

The fix: resolve a :class:`Settings` once at process startup (see
``cv_service/grpc/server.py``'s ``main()``/``serve()``) and pass it down
explicitly. Nothing downstream reads ``os.environ`` directly anymore --
``YoloDetector``/``ultralytics_train``/etc. that still support being
constructed with a value omitted call :meth:`Settings.from_env` themselves
(which re-parses fresh every call, exactly matching the old per-construction
-refresh behavior for ``CV_MODEL``/``CV_IMGSZ``), so the *parsing logic*
still lives in exactly one place even though it may be invoked from more than
one call site.

Frozen ``@dataclass``, matching this codebase's existing idiom (``Detection``,
``TrainingSpec``, ``EpochProgress`` are the other three frozen dataclasses in
cv-service).
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger("cv_service.config")

# cv-service/ checkout directory -- this file's grandparent
# (cv_service/config.py -> cv_service/ -> cv-service/). Same directory
# `server._MODEL_SEARCH_DIR` used to compute from cv_service/server.py, and
# where the Dockerfile's WORKDIR puts things too -- unchanged by this move,
# both files sit at the same depth under cv-service/.
_BASE_DIR = Path(__file__).resolve().parent.parent

# --- defaults (byte-identical to the literals they replace) -----------------

DEFAULT_PORT = 50051
DEFAULT_MODEL = "yolo26n.pt"
DEFAULT_IMGSZ = 416
DEFAULT_MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024  # 2 GiB
DEFAULT_GRPC_WORKERS = 10
DEFAULT_SHUTDOWN_GRACE_SECONDS = 5

# --- process role (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) ---
#
# Which servicer(s) `cv_service.grpc.server.serve()` registers on this
# process's gRPC server -- see that module's docstring for the split this
# closes (a `Training`/`Geolocation` job sharing one GIL with the `Inference`
# hot path). `all` (the default) is byte-identical to every deployment that
# predates this knob: one process, all three servicers, one shared model
# registry. `inference`/`training` are the two halves of a split deployment
# (`cv_service.grpc.server_inference`/`server_training`, same image), never
# both -- see MODULE.md "Split deployment" for the one behavior change the
# split causes (a `PromoteModel` on the training process does not update an
# already-running inference process's in-memory registry).
DEFAULT_ROLE = "all"
ROLE_INFERENCE = "inference"
ROLE_TRAINING = "training"
# CV-ORCHESTRATION wave W4 (§4.9). Two more roles, and the split they name is a
# DIFFERENT axis from `inference`/`training`: that one separates a latency-
# sensitive servicer from long-running ones, this one separates STATEFUL work
# (identity, gallery, lock -- sticky to a stream) from STATELESS work (pixels
# to boxes -- scalable to N instances). `inference` therefore keeps meaning
# exactly what it meant before this wave.
#
# - `detector` -- the `Detector` servicer ONLY. No sessions, no tracker
#   registry, no identity of any kind. Scale this one horizontally.
# - `tracker`  -- `Inference` only, like `inference`, but declaring that this
#   process does NOT intend to detect in-process. It is the role a deployment
#   sets alongside `CV_DETECTOR_TARGETS`; with no targets set it degrades to
#   exactly `inference` and says so once, because refusing to start would turn
#   a misconfiguration into an outage.
ROLE_DETECTOR = "detector"
ROLE_TRACKER = "tracker"
_ROLE_CHOICES = (DEFAULT_ROLE, ROLE_INFERENCE, ROLE_TRAINING, ROLE_DETECTOR, ROLE_TRACKER)

# --- detector placement (CV-ORCHESTRATION §4.9, wave W4) --------------------
#
# `CV_DETECTOR_TARGETS` is the ORDERED list a tracker session tries, first to
# last, falling through on RESOURCE_EXHAUSTED or unavailability. Order is the
# whole mechanism: it is `pick_first`, not balancing, which is what makes one
# tracker's stream land on the same detector while it is healthy (§4.9
# "affinity is a deployment requirement, not code"). Entries are `host:port`,
# or the literal `local` for this process's own in-process detector -- so
# `a:50051,local` reads "prefer the box, fall back to myself".
#
# UNSET IS THE DEFAULT AND MEANS `local`: every deployment that predates this
# wave keeps the exact single-process behaviour it has today.
DETECTOR_TARGET_LOCAL = "local"
# How many passes may be WAITING for a gate permit before a `detector`-role
# process answers RESOURCE_EXHAUSTED instead of queueing. Not a throughput
# knob -- a FRESHNESS one. A yolo26n pass costs 135-230ms on the deploy box
# (MODULE.md "StartTraining runs on CPU"), so two already-queued passes mean
# the third's answer would describe a frame ~0.5s stale; at the 10 fps
# sampling default that is five frames old, and CLAUDE.md rule 9 says the
# newest data wins. Failing over to another instance -- or dropping the pass
# and saying so in the ledger -- beats answering late.
DEFAULT_DETECTOR_MAX_QUEUE = 2
# Deadline on one pooled `Detect` call. Matches `adapter-cv-grpc`'s own
# `RESPONSE_TIMEOUT_SECONDS` (2s), so a pooled hop can never be the thing that
# outlives the Java caller's patience: the tracker gives up on a detector
# before its own client gives up on the tracker.
DEFAULT_DETECTOR_TIMEOUT_MILLIS = 2000
_DATASET_DIRNAME = "datasets"

# --- tracking (docs/plans/done/TRACKING-PLAN.md §4.A / TRACKING-ORCHESTRATION §4.3) ----
#
# These back the wire's `<=0 = server default` sentinels on
# `TrackingConfig` -- `cv_service/tracking/params.py`'s `resolve()` is their
# ONLY consumer, and this module stays the only place they are read from the
# environment (the one-place rule, see this module's docstring). The values
# are byte-identical to the defaults named in TRACKING-PLAN §4.A, with one
# deliberate exception recorded at the constant itself:
# DEFAULT_TRACK_ASSOCIATE_ENGINE, which TRACKING-V2 wave C3 flipped.
# `cost` rather than `bytetrack` since TRACKING-V2 wave C3, on measurement
# rather than preference (the evidence is in MODULE.md's "Association
# inversion" section): better on `crossing`, `occlusion` and `pan_step`,
# equal on `linear`, `dropout`, `pan` and `clutter`, worse on nothing. The
# one risk `cost` carries that `bytetrack` does not is a crowd -- a solver
# minimising TOTAL cost can buy a cheap overall assignment out of
# individually absurd pairs -- so `clutter` exists specifically to test that,
# and both engines score IDSW 0 with every object mostly-tracked.
# `bytetrack` remains fully supported and one env var away.
DEFAULT_TRACK_ASSOCIATE_ENGINE = "cost"
DEFAULT_TRACK_FOLLOW_ENGINE = "lk"
# How often FOLLOW may spend a detector pass trying to RE-ACQUIRE a target it
# is no longer holding. Deliberately far shorter than the verify cadence: an
# operator whose target reappears wants it back at once, so this bounds a
# runaway rather than economising. Without it, scheduler trigger (c) fires on
# EVERY frame for as long as nothing is re-acquired -- more detector passes
# than ASSOCIATE would spend, and the end of the duty cycle FOLLOW exists for.
DEFAULT_TRACK_REACQUIRE_MILLIS = 250
DEFAULT_TRACK_VERIFY_MILLIS = 2000
DEFAULT_TRACK_IOU = 0.3
DEFAULT_TRACK_MAX_AGE_FRAMES = 30
DEFAULT_TRACK_MIN_HITS = 3
# TRACKING-V2-PLAN wave C1 (review finding B7): `max_age_frames` means
# "consecutive failed verify passes", which is ~3s of real time in ASSOCIATE
# at the documented 10 fps sample rate but could be a full minute in FOLLOW,
# where a verify pass only happens on cadence. 3000ms = 30 frames / 10 fps is
# chosen specifically so this new wall-clock backstop is BEHAVIOUR-PRESERVING
# for ASSOCIATE at that rate (see `track.py`'s `_settle`) while giving FOLLOW
# the same real-world meaning instead of the frame-count's inflated one.
DEFAULT_TRACK_MAX_AGE_MILLIS = 3000
# TRACKING-V2-PLAN wave C1 (review finding C2): below this, a `SingleObject
# Tracker`'s own reported confidence (LK's surviving-corner fraction, NCC's
# match score) is treated as an early warning that the target may be
# drifting, bringing the next verify pass forward (scheduler trigger (b))
# instead of waiting for the tracker to fail outright. Conservative by
# construction: a track that has lost half its corners or dropped to a weak
# correlation is worth double-checking, not yet worth declaring lost.
DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE = 0.5
# TRACKING-V2-PLAN wave C2: which `MotionCompensator` a stream gets when its
# `TrackingConfig.motion_engine_id` is blank (proto field 8, frozen at C0).
# `flow` -- pixel-based, needs no telemetry -- is the default because it
# works on every client today; `pose` only activates once a client actually
# populates `camera_pose` (TRACKING-V2-PLAN §2.1's Java one-liner, not yet
# built). `"off"` is a legitimate value here too, disabling compensation
# fleet-wide without touching a single stream's request.
DEFAULT_TRACK_MOTION_ENGINE = "flow"

# TRACKING-V2-PLAN wave C3: which `AppearanceExtractor` a stream gets when
# its `TrackingConfig.appearance_engine_id` is blank (proto field 9, frozen
# at C0). Measured (see MODULE.md "Wave C3" for the harness table): `cost`
# IS the `CV_TRACK_ASSOCIATE_ENGINE` default (`DEFAULT_TRACK_ASSOCIATE_
# ENGINE` above) -- this default therefore matters on every stream out of
# the box, not only one that has opted in. `bytetrack` never resolves an
# appearance extractor at all (it has no descriptor input to feed, see
# `session.py`'s `_run_cost_associate`), so this knob is inert for a stream
# that has opted OUT of `cost` back to `bytetrack`. `"off"` disables
# appearance evidence outright, same shape as `DEFAULT_TRACK_MOTION_ENGINE`'s
# "off". (TRACK-IDENTITY-PLAN wave L2, docs/plans/active/TRACK-IDENTITY-
# RESEARCH.md §2 D-D: this comment previously claimed the opposite -- fixed
# as a stale-doc ride-along, no behaviour change.)
DEFAULT_TRACK_APPEARANCE_ENGINE = "histogram"

# TRACKING-V2-PLAN wave C3: `assign.CostAssociator`'s cost weights and gates
# (`AssignWeights`/`AssignGates`, `assign.py`), resolved straight from
# `Settings` -- like `track_max_age_millis`/`min_tracker_confidence` before
# them, TRACKING-V2-PLAN §2's frozen wire diff adds no per-request field for
# any of these, so there is nothing to fall back FROM. `resolve()` builds the
# two frozen dataclasses directly (`params.py`'s "the only place a sentinel
# becomes a number", extended here to "and the only place these become an
# `AssignWeights`/`AssignGates`").
#
# `appearance` defaults NON-zero so that opting into `cost` PLUS the default
# `histogram` extractor actually does something -- `session.py`'s
# `_run_cost_associate` is what zeroes this back to 0.0 for a stream where no
# appearance extractor resolved (`"off"`, or nothing constructible), so a
# `cost` stream with no appearance evidence still behaves as pure geometry
# rather than geometry-plus-a-constant (see `assign.py`'s own
# `test_appearance_is_ignored_entirely_when_it_is_not_weighted`).
DEFAULT_TRACK_COST_WEIGHT_IOU = 1.0
DEFAULT_TRACK_COST_WEIGHT_APPEARANCE = 0.5
# TRACK-IDENTITY-PLAN wave L2 (`docs/plans/done/TRACK-IDENTITY-PLAN.md`):
# 0.0 -> 0.3. A SOFT penalty, never a hard gate -- a label gate would split
# a track on every residual flip, the exact failure TRACKING-PLAN R9 set 0
# to avoid, and wave L1's election already gives `Candidate.label` a stable
# operand (`session.py`'s `_run_cost_associate` passes `track.elected_label`,
# not the raw per-frame one) so the penalty compares a hysteresis-gated
# opinion against the fresh detection's raw label, not noise against noise.
# `_labels_compatible` (`assign.py`) still passes an unknown/composite-prefix
# label pair through for free, so this only ever fires on a genuine,
# elected-label disagreement. Measured, not merely reasoned (`BASELINE.md`'s
# 2026-08-20 L2 section has the full trial table): isolated via
# `CV_TRACK_COST_GATE_MIN_IOU=0 CV_TRACK_COST_GATE_MAX_COST=inf` against the
# other two L2 knobs, this alone reproduces all 30 `tools/trackeval` rows
# byte-identical to the pre-L2 baseline -- the harness has no scenario where
# two live candidates compete for one detection under conflicting elected
# labels, so the penalty is provably inert on every pinned scenario while
# still tightening the real multi-object case it targets.
DEFAULT_TRACK_COST_WEIGHT_LABEL = 0.3
# TRACK-IDENTITY-PLAN wave L2: measured and REVERTED, stays `0.0`. The plan
# proposed 0.05 ("a track may no longer absorb a detection it doesn't even
# touch"), reasoning ego-motion warping would keep a genuine match's IoU
# comfortably clear of it. Measured instead: isolating this knob alone (every
# other L2 knob held at its OLD default) against the full `tools/trackeval`
# suite regresses 4 of 15 scenarios' ASSOCIATE row -- `latency` (`IDSW`
# 0->16, `recov%` 100->0, `life_mean` 52.0->2.9), `occlusion` and `nonlinear`
# (each `recov%` 100->0, an `IDSW` appears where there was none), `tiny_fast`
# (`IDSW` 2->4) -- at EVERY tested value from `0.05` down to `0.0001`, with
# identical numbers at every step: the true IoU between the predicted
# candidate and the reappearing/lagging/reversing target in each of these
# four rows is exactly `0.0`, not merely small, so no strictly-positive gate
# survives them (`nonlinear`'s own `BASELINE.md` writeup already named the
# mechanism: constant-velocity extrapolation runs the WRONG WAY across an
# occlusion/reversal, landing the prediction with zero overlap on the true
# box). This IS the exact "wide-displacement case ego-motion compensation
# exists to recover" the pre-L2 comment on this line named -- confirmed by
# measurement to be a real, currently load-bearing behaviour, not a
# hypothetical worry, so the knob reverts per the plan's own "a measured
# retreat beats an unmeasured win" clause rather than shipping a harder gate
# on an unmeasured guess. `max_cost` below is this wave's answer to the same
# goal (bounding a worst-of-everything match) without a hard per-axis floor.
DEFAULT_TRACK_COST_GATE_MIN_IOU = 0.0
# Above this Hellinger distance ([0, 1], 1 = no match) an appearance-weighted
# pair is forbidden outright rather than merely penalised -- tuned so two
# genuinely different objects (e.g. `crossing`'s red/green pair) gate out
# even at moderate geometric ambiguity, while a colour shift from motion blur
# or exposure does not.
DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE = 0.6
# TRACK-IDENTITY-PLAN wave L2: inf -> 1.5, a starting point per the plan,
# kept as-is (no further tuning needed -- see below). The full cost range
# under the defaults above is `1.0*(1-iou) + 0.5*appearance + 0.3*label <=
# 1.8`; 1.5 forbids only a pairing that is bad on EVERY axis at once
# (near-zero overlap, near-maximum appearance distance, AND a label
# disagreement) -- the "worst of everything" pairing a pure cost matrix with
# no ceiling would otherwise still accept as merely expensive. Measured, not
# merely reasoned (`BASELINE.md`'s 2026-08-20 L2 section has the full trial
# table): isolated via `CV_TRACK_COST_WEIGHT_LABEL=0
# CV_TRACK_COST_GATE_MIN_IOU=0` against the other two L2 knobs, and again
# combined with `DEFAULT_TRACK_COST_WEIGHT_LABEL=0.3` at `min_iou=0.0` (this
# file's own final L2 configuration), both produce the identical 30-row
# `tools/trackeval` table as the pre-L2 baseline -- no pinned scenario's cost
# ever approaches 1.5, so the ceiling is inert on every scenario measured
# today and stands as a forward guard against the failure it targets, not
# (yet) a demonstrated fix for one.
DEFAULT_TRACK_COST_GATE_MAX_COST = 1.5
# The high/low confidence split for `CostAssociator`'s two-stage match
# (`assign.py`'s own docstring: "high-confidence targets first, then low").
# Matches `engines/bytetrack.py`'s own `_TRACK_HIGH_THRESH` so `cost` and
# `bytetrack` draw the same low-confidence line even though each engine
# tunes it as its own separate constant.
DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE = 0.25

# The confidence the DETECTOR actually runs at while tracking is ACTIVE, as
# opposed to the operator's `confidence_threshold`, which becomes a REPORTING
# threshold (docs/conclusions/CV-RATE-BUDGET.md §4 "the confidence finding").
#
# Both associators split their match into a high- and a low-confidence stage --
# `bytetrack`'s `_TRACK_LOW_THRESH`, `cost`'s `AssignGates.high_confidence`
# above -- because a weak box that lands on a predicted position is strong
# evidence of continuity even though it is weak evidence of existence. That
# entire second stage was unreachable: the operator threshold was applied at
# `model.predict(conf=)`, so no box below it was ever created and the low list
# was always empty.
#
# Set BELOW `DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE` on purpose -- a floor at
# or above the split would leave the low stage just as empty as before. Applies
# only when tracking is active: with tracking OFF the operator threshold still
# goes straight to the detector, keeping that path byte-identical (invariant P1).
DEFAULT_DETECT_FLOOR = 0.15

# The confidence a detector pass uses when the wire field is unset. NOT a CV_*
# env knob -- it is inference behaviour, not deployment configuration -- but it
# lives here rather than in `inference/detector.py` because `grpc/servicers.py`
# now needs it too (to know what threshold to REPORT at), and that module must
# stay importable without the `cv` extra, which importing `detector` would break.
# `detector.py` re-exports it, exactly as it does DEFAULT_MODEL/DEFAULT_IMGSZ.
DEFAULT_CONFIDENCE = 0.25

# TRACKING-V2-PLAN wave C4: `ObjectMemory`'s `MemoryParams` (`memory.py`),
# resolved straight from `Settings` -- `resolve()` builds the dataclass
# directly, same "no wire field for the rest of it" shape the cost weights/
# gates above already use. Mirrors `MemoryParams`' OWN dataclass defaults
# exactly (unlike the cost weights, where the dataclass default is a
# deliberately inert "no appearance evidence" fallback for a bare
# constructor call, `MemoryParams`' own defaults ARE already documented as
# the considered production values -- "deliberately conservative: a wrong
# recovery is worse than a missed one" -- so there is no reason to diverge).
DEFAULT_TRACK_MEMORY_TTL_MILLIS = 30_000
DEFAULT_TRACK_MEMORY_CAPACITY = 32
DEFAULT_TRACK_MEMORY_GALLERY_SIZE = 4
DEFAULT_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE = 0.45
DEFAULT_TRACK_MEMORY_MAX_SPEED = 1.0
DEFAULT_TRACK_MEMORY_BLEND_ALPHA = 0.7
DEFAULT_TRACK_MEMORY_MIN_CONFIDENCE = 0.35

# TRACKING-V2-PLAN wave C5b (review finding B5): `SessionRegistry`
# (`cv_service/tracking/sessions.py`) pools `StreamTrackingSession` by
# `stream_id` instead of minting one per `DetectStream` call, so a
# reconnecting stream resumes its book/gallery/lock. These two are
# SERVICER/pool-level knobs, not per-stream `TrackingParams` -- unlike every
# `CV_TRACK_*` knob above, they are never threaded through `params.resolve()`
# (there is no wire field for either, and there never should be: a client
# choosing its own grace window or the pool's total capacity would let one
# stream affect every other stream's resource bound).
#
# `grace_millis`: how long a DISCONNECTED session is held before its book/
# gallery/lock are discarded for good. Longer than the Java-side outage
# backoff's own upper bound (`GrpcDetectionPort`'s `MAX_BACKOFF_NANOS`=10s,
# adapter-cv-grpc/MODULE.md) so a stream that survives a couple of backoff
# cycles still finds its session waiting, not evicted mid-recovery.
DEFAULT_TRACK_SESSION_GRACE_MILLIS = 15_000
# How many DISCONNECTED sessions the registry holds at once across every
# stream_id, bounding memory for a fleet where streams disconnect faster
# than their grace window expires. Never evicts a session that is still
# connected (`in_use`) -- that population is bounded elsewhere, by the gRPC
# server's own thread pool (`CV_GRPC_WORKERS`), not by this cap.
DEFAULT_TRACK_SESSION_CAPACITY = 64

# CV-ORCHESTRATION wave W0 (plan §4.4, tier HOT): how many per-frame
# `FrameLedger`s one session keeps for the `Inspect` RPC to read back. The
# ledger is the debug surface, so it has to span a human's reaction time --
# 64 frames is ~6s at the fleet's 10 fps sampling default
# (`CV_PULL_TARGET_FPS`), long enough that an operator noticing "the boxes
# just went away" can still ask why. Bounded by construction
# (`orchestration/ledger.LedgerRing`): a few KB per stream, no I/O, and
# nothing is serialised unless somebody actually calls `Inspect`.
DEFAULT_LEDGER_RING = 64

# TRACKING-V2-PLAN wave C5b (review finding C6): how many tracks FOLLOW
# holds and emits between verify passes -- the locked target plus up to
# `follow_top_k - 1` other, non-locked targets, each with its own
# `SingleObjectTracker` instance (`session.py`'s `_extras_verify_
# observations`/`_extras_observations`). No wire field exists (a
# deployment-only knob, same "resolved straight from Settings" shape as
# `track_max_age_millis` before it).
#
# TRACK-IDENTITY-PLAN wave L4 raised the default from `1` to `2` -- ONE
# extra target alongside the locked one, not the "opt a fleet into full
# situational awareness" `3+` the comment above used to gate behind. Two
# measurements on the dev box justify the move (both via `tools.trackeval`
# and a direct `time.perf_counter()` probe against a real `StreamTracking
# Session`, `clutter`/`crowd_recall`/`pan_step` scenarios, `lk` engine):
#   - Cost: a tracker-only FOLLOW frame at K=2 costs one MORE `lk` `update()`
#     call than K=1 -- MODULE.md's own benchmark puts that at 0.525ms mean /
#     0.619ms p95 on this box. Measured end-to-end (200-frame direct probe,
#     `perf_counter`, not the harness's own ms-quantized column) K=1 vs K=2
#     were statistically indistinguishable (~2.4-2.6ms mean, ~3.0ms p95
#     either way) -- the marginal cost is noise against total per-frame
#     overhead, and trivial against the default 2000ms verify cadence and a
#     23ms YOLO pass (GB4005 is CPU-only/OpenVINO -- no budget headroom to
#     lose, but this does not touch it).
#   - Safety: the LOCKED target's own `locked_track_id` and box geometry are
#     byte-identical between K=1 and K=2 runs of the full `tools.trackeval`
#     harness on every scenario checked (`clutter`, `crowd_recall`,
#     `pan_step`) -- an extra fails independently of the lock (P2, unchanged
#     by L4). `clutter`'s IDSW/FM columns DO move at K=2; that is the same
#     greedy-IoU-matcher harness limitation already proven for K=3 (this
#     file's "Multi-target FOLLOW" section) re-confirmed here, not a real
#     regression -- the locked box itself never moves.
# `3+` still needs its own opt-in per the reasoning below (more engine
# instances, more on-screen boxes) -- L4 only asked whether ONE extra fits
# the budget, and it measurably does.
DEFAULT_TRACK_FOLLOW_TOP_K = 2

# TRACKING-V2-PLAN wave C5c (review §4.6, "detection recall -- the other
# half of the complaint"): whether a CONFIRMED track the full-frame pass
# left unmatched gets one bounded, gated second detector pass over a crop
# around its own predicted box, at native resolution -- the standard fix for
# a target too small/distant to survive downscaling to `imgsz`
# (`session.py`'s `_roi_rescue`). No wire field exists (TRACKING-V2-PLAN §2's
# frozen diff adds none for it) -- deployment-only, same "resolved straight
# from Settings" shape as `track_max_age_millis`/`follow_top_k` before it.
#
# `False` -- the SAME "ship the less-proven behaviour opt-in" posture
# `DEFAULT_TRACK_FOLLOW_TOP_K`'s own comment documents, and for a stronger
# reason than that knob had: this one's cost is not a handful of sub-
# millisecond SOT updates, it is a genuine SECOND detector pass -- gated by
# `InferenceGate` exactly like any other (P2) and bounded to at most one per
# frame, but real CPU/GPU on every deployment that flips it on. An operator
# sets `CV_TRACK_ROI_ENABLED=1` to opt a fleet into it once its own measured
# cost (MODULE.md's own harness table: extra `det/s` bought against the
# `small_target` fragmentation it fixes) fits their hardware budget.
# Enabled since TRACKING-V2 wave C5c, on measurement rather than preference:
# better or equal on every scenario, worse on none (`small_target`
# fragmentation 19 -> 2 and coverage 32 -> 78 of 80 frames; `dropout`
# incidentally 11 -> 4; every other row byte-identical, no id switches
# anywhere). It shipped off first, with a `clutter` regression of six id
# swaps, until the rescue was given its own IoU gate -- see
# DEFAULT_TRACK_ROI_MIN_IOU. The cost is conditional: a pass only ever runs
# when a CONFIRMED track went unmatched, so a healthy stream pays nothing.
DEFAULT_TRACK_ROI_ENABLED = True
# How many times the rescued candidate's OWN predicted box's larger
# dimension the crop's side extends to, centered on it. Large enough that an
# object well under the detector's reliable apparent size becomes
# comfortably above it once cropped (see `tools/trackeval/replay.py`'s
# `DetectorNoiseConfig.reliable_size` and `sequences.py`'s `small_target`
# scenario for the harness's own model of this -- a `small_target` object at
# 0.035 of the frame against a crop 4x its own size is 0.25 of the CROP,
# comfortably above that scenario's 0.10 reliable threshold); small enough
# that the crop stays a genuine close look, not a second full frame.
DEFAULT_TRACK_ROI_CROP_FACTOR = 4.0
# Minimum overlap between a track's PREDICTED box and a detection found by a
# ROI rescue, before that detection may be adopted. Deliberately stricter
# than the primary match's own IoU gate: the crop is taken BECAUSE the object
# was predicted there, so a genuine rescue overlaps the prediction well,
# while a neighbour the wide crop happens to admit does not. Reusing the
# primary gate instead cost `clutter` six id swaps -- measured, not supposed.
DEFAULT_TRACK_ROI_MIN_IOU = 0.2

# TRACKING-V3-PLAN wave V1 (§5): the capability ladder's deployment-wide default
# CEILING -- `0` (auto-probe, `cv_service.tracking.levels.probe()` decides the highest
# level this host affords) or `1`-`5` (an explicit level, TRACKING-V3-PLAN §5.2). Same
# "<=0 = server default" wire sentinel shape as `verify_every_millis`/`max_age_frames`
# above, EXCEPT here `0` is also the deployment default's own natural meaning (auto),
# not merely "unset" -- so the request layer, the deployment layer and "no opinion at
# all" all collapse onto the identical sentinel, which is exactly what decision E12's
# "a level is a ceiling" wants: an operator who deploys a fleet of ARMv6 relays sets
# `CV_TRACK_CAPABILITY_LEVEL=1` fleet-wide without touching a single client.
DEFAULT_TRACK_CAPABILITY_LEVEL = 0

# TRACKING-V3-PLAN wave V3 (§4.2): the longest gap ORU (`cv_service.
# tracking.reupdate`) will reconstruct from the two real observations that
# bracket it, before deferring to `memory.py`'s dormant-gallery recovery
# instead (`reupdate()`'s own docstring: "a gap too long to reconstruct
# honestly"). Same `<=0`-is-a-legitimate-disabled-value shape as
# `DEFAULT_TRACK_MEMORY_TTL_MILLIS` -- `_parse_int_allow_nonpositive`, not
# `_parse_positive_int` -- because ORU needs a fleet-wide off switch for
# invariant P7 (reversibility: with it off, the harness must reproduce
# `BASELINE.md` exactly), and "no gap this ceiling will ever accept" (every
# real gap is positive) is that switch.
#
# 15 seconds, not a round number picked in the abstract, and not simply
# `long_occlusion`'s own 90-frame/9-second OCCLUSION window either --
# measured directly against what `ObservationRing.before()` actually
# brackets from in that scenario's FOLLOW replay (`tools/trackeval/
# sequences.py`, `DEFAULT_FPS=10.0`), one of the two scenarios this wave's
# acceptance criterion is measured against. The ring's last REAL entry
# there is NOT the frame occlusion starts -- FOLLOW's own verify cadence
# (`DEFAULT_TRACK_VERIFY_MILLIS=2000`) means the LAST successful cadence
# re-anchor lands two seconds before that, and reacquisition after the
# object reappears does not land on the exact frame it becomes visible
# either (`CV_TRACK_REACQUIRE_MILLIS=250`'s own cadence, tried repeatedly
# once the wall clock has declared the target LOST and unbound it). The
# actually-measured bracketed gap in that replay is ~11.1s, not 9s -- this
# ships with real margin above that rather than a value that happens to
# clear it by a few hundred milliseconds, while staying well short of
# `DEFAULT_TRACK_MEMORY_TTL_MILLIS` (30s): a track ORU can still see (its
# `ObservationRing` never wiped, only LOST in `state`) is, by construction,
# a track that has not yet been retired from `TrackBook` at all, which
# happens on a much shorter, `max_age_frames`-driven schedule than the
# dormant gallery's own TTL. This keeps the two recovery mechanisms' roles
# distinct: ORU for a still-live track's ring-remembered gap, `memory.py`
# for one that has genuinely been deleted and re-adopted under a recovered
# identity (whose ring, per `track.py`'s own `_adopt` docstring, starts
# fresh -- nothing left for ORU to bracket from there regardless of this
# ceiling).
DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS = 15_000

# TRACKING-V3-PLAN wave V6 (§4.5): whether `session.py` applies late-
# detection back-correction at all when a caller measures a positive
# `detection_lag_millis` for a frame (today, only `DetectPulled`'s own
# capture-skew estimate -- `cv_service/pull/clock.py`). Independent of
# `DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS` above, which still bounds HOW FAR
# any one correction may reconstruct (reused, not duplicated) -- this is
# the dedicated P7 off switch for the correction MECHANISM itself, so a
# fleet can keep post-occlusion ORU on while ruling this specific
# correction out, or the reverse, without one knob answering two different
# questions. Defaults on: §5.2 calls back-correction the normal case for an
# offboard detector, not an opt-in.
DEFAULT_TRACK_DETECTION_LAG_CORRECTION_ENABLED = True

# 2026-08-14 repair, part 2 (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md`
# §4/§8): `reupdate()`'s `max_gap_millis` ceiling above bounds HOW LONG a gap
# it will bridge, but says nothing about WHETHER the two real observations
# bracketing it are plausibly the same object -- on MOT17 they frequently are
# not (a crowd, a weak detector), and the reconstruction still returns a
# number, just a physically impossible one, that then propagates forward as
# a prediction and costs an identity. Measured: ORU made IDSW worse in 15 of
# 21 MOT17 scene/detector pairs (+470 net, worst case +277 on
# `MOT17-04-DPM`), and its own implausible-velocity count rose in 19 of 21
# (`MOT17-04-DPM` 157 -> 441). `reupdate.py`'s own guard refuses the
# reconstruction outright when this bound is exceeded -- it does not clamp,
# because a wrong bracket has no salvageable answer.
#
# Units are normalized frame-WIDTHS/HEIGHTS per second, the same units
# `Detection.velocity_x`/`_y` already carry on the wire (each axis checked
# independently, never combined into one Euclidean magnitude -- the same
# reason `tools/trackeval/metrics.py`'s own `MAX_PLAUSIBLE_VELOCITY_PER_
# SECOND` keeps its two axes separate). Derived the SAME way that constant
# was, kept consistent with it BY HAND rather than by import -- this module
# must not import from `tools/`:
#   - `tools/trackeval/sequences.py`'s `TINY_FAST_CAMERA_VELOCITY` (0.06
#     frame-widths/frame at `DEFAULT_FPS=10`) is 0.6 frame-widths/sec, the
#     single fastest apparent motion any scenario in that harness produces
#     -- already a deliberately extreme synthetic pan.
#   - `docs/conclusions/CV-RATE-BUDGET.md` §2's own most aggressive
#     documented case -- a 90 deg/s "aggressive" search yaw -- is 1.5
#     frame-widths/sec of purely camera-induced apparent motion, faster than
#     the harness's own synthetic worst case.
# A target crossing the ENTIRE frame in under a fifth of a second (5.0/sec)
# clears both by more than 3x, so neither a legitimate fast pan nor a fast
# real target ever trips it, while the divergent reconstructions this bound
# exists to refuse (peak measured 12.9/sec under the `cost` engine,
# `TRACKING-BENCHMARK-RESULTS.md` §5) trip it with wide margin.
#
# `<=0` disables the guard entirely, reproducing `reupdate()`'s pre-repair
# behaviour exactly (invariant P7) -- same `<=0`-is-a-legitimate-disabled-
# value shape as `DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS` above:
# `_parse_float_allow_nonpositive`, not `_parse_positive_float`.
DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND = 5.0

# 2026-08-15 density gate (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md`
# §4b/§8 item 2): the velocity guard directly above tests whether a bracket's
# IMPLIED motion is plausible; it has nothing to say about whether the
# bracket was ever trustworthy to begin with. §4b's own re-run of all 21
# MOT17 ORU pairs, split by scene density, is what exposes the gap that
# leaves open: at >=10 detections/frame ORU improved **0 of 7** scenes (net
# +320 IDSW); below that line it improved 5 of 14 (net +57). ORU has never
# once helped a crowded scene -- crowding, not velocity, is what makes two
# bracketing observations likely to be two different objects, and a velocity
# bound cannot see density at all.
#
# `reupdate.py`'s guard gets a second, independent gate: refuse the
# reconstruction outright (the SAME `None` contract, never a clamp) when the
# scene is too crowded for the bracket to be trusted. **Measured "density"
# vs. what this gate actually reads**: the benchmark above measures
# detections/frame, but `reupdate()`/`late_correction()` are pure functions
# that take a `Track` and an `ObservationRing` and nothing else -- no
# reference to `TrackBook` or to the frame's raw detection list. Threading
# the actual per-frame detection count down to them would mean a new
# argument through every one of `TrackBook.apply()`'s callers, across both
# ASSOCIATE engines and every FOLLOW branch in `session.py` (already
# flagged oversized in its own module docstring) -- and some of those
# callers (a tracker-only FOLLOW frame) have no detector pass to count in
# the first place. The number of LIVE TRACKS in the book at the moment of
# re-anchor is reachable at both real call sites with no threading at all
# (`TrackBook._observe` already has `self._tracks`; `StreamTrackingSession.
# _late_corrected_box` already has `self._book.tracks`), and it rises and
# falls with scene crowding the same way detections/frame does. It is a
# PROXY, stated plainly as one -- correlated with what was measured, not
# identical to it.
#
# `<=0` disables the gate entirely, reproducing today's exact behaviour
# (invariant P7) -- same `_parse_int_allow_nonpositive` shape as `DEFAULT_
# TRACK_REUPDATE_MAX_GAP_MILLIS` above.
#
# Defaulted to `0` (DISABLED), deliberately -- unlike the velocity bound
# above, which was derived from this platform's own documented worst-case
# motion, nobody has yet swept a live-track ceiling against the real MOT17
# matrix. §4b's table says the dense half is unanimous; it does not say
# what count actually separates "trustworthy" from "not" on real footage.
# Picking a number here from intuition would be exactly the un-evidenced
# default this results doc argues against (§8 item 2: "gate ORU on scene
# density... The honest next experiment..."). `CV_TRACK_REUPDATE_MAX_TRACK_
# COUNT` swept across `benchmarks/` is what sets this value, not this
# comment.
DEFAULT_TRACK_REUPDATE_MAX_TRACK_COUNT = 0

# 2026-08-15 bracket-identity check (`docs/conclusions/TRACKING-RECOVERY-
# RESEARCH.md` §2.1): the velocity guard above tests whether a bracket's
# IMPLIED motion is physically possible, and the density gate tests whether
# the SCENE is too crowded to trust -- neither ever tests the bracket's own
# two observations against EACH OTHER for being plausibly the same object.
# `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b/§4c measured that
# both existing guards, while each worth keeping on its own merits, leave
# ORU net negative on real MOT17 footage (§4b: velocity guard alone, +377
# IDSW vs ORU off; §4c: no density-gate threshold swept across
# 6/8/10/12/15/20/30 tracks made ORU pay). This constant and the one below it
# back a THIRD and FOURTH gate, both operating on the bracket's own two
# boxes directly rather than on a proxy of the scene around them -- see
# `cv_service.tracking.reupdate`'s own module docstring for the full
# derivation of each, including the reasoning behind choosing a size-scaled
# centre distance over IoU for the motion check.
#
# Check A -- shape consistency: refuses when `|ln(t2.width/t1.width)|` or
# `|ln(t2.height/t1.height)|` exceeds this bound. A log-ratio, not a raw
# ratio, so growth and shrinkage are symmetric around "no change" and one
# number expresses both directions; scale-invariant, so the same bound
# serves a target filling a hundredth of the frame and one filling a tenth.
#
# `<=0` disables the check entirely, the SAME `_parse_float_allow_nonpositive`
# shape `DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND` above already uses.
#
# `0.40` (a target may grow ~1.5x or shrink to ~2/3 across a gap and still be
# believed) is SWEPT, not chosen: seven bounds x 21 real MOT17 scene/detector
# pairs, `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4d. It is the first
# ORU configuration that beats not running ORU at all -- -97 IDSW against the
# ORU-off baseline where the velocity guard alone cost +377, with recovery up
# 0.7pp.
#
# Read the sweep honestly before retuning this: the IDSW surface is JAGGED
# (0.40 -> -97, 0.45 -> +175, 0.50 -> +14), and one pathological pair,
# `MOT17-04-DPM` (21.3 dets/frame on the weakest detector), swings between
# +64 and +284 by itself and dominates every total. What IS robust, with that
# pair excluded, is the break between <=0.5 (consistently negative, i.e.
# better than no ORU) and >=0.55 (consistently positive): -277/-109/-50
# versus +179/+252/+198. So trust the BAND, not this exact digit, and expect
# to re-derive it on aerial footage where our own target density lives.
# ENABLED AT `0.40` BY EXPLICIT DECISION (2026-08-15), with a known cost.
#
# The sweep earns it: seven bounds x 21 real MOT17 scene/detector pairs
# (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4d) make this the first
# ORU configuration in three attempts that beats not running ORU at all --
# -97 IDSW against the ORU-off baseline where the velocity guard alone cost
# +377, and the only change so far to move RECOVERY (+0.7pp) rather than
# leave it flat.
#
# The cost, recorded rather than buried: enabling it moves one synthetic row,
# `pan`/FOLLOW's `implaus_n` 0 -> 8. Position is untouched there -- coast
# ADE/FDE, MT/PT/ML, IDSW and lifetime are all identical -- so what degrades
# is the reported VELOCITY, which is the one defect shape every other column
# is blind to and O3 exists to catch. FOLLOW cannot be measured on real
# footage until aerial data with pixels exists, so that row is the only
# evidence there is on that side. `CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO=0`
# reverts, and O5 (`TRACKING-V3-PLAN.md` §6b) tracks resolving it.
#
# Trust the BAND, not this digit, if you retune: the IDSW surface is jagged
# (0.40 -> -97, 0.45 -> +175, 0.50 -> +14) and one pathological pair,
# `MOT17-04-DPM` (21.3 dets/frame, weakest detector), swings between +64 and
# +284 by itself and dominates every total. With that pair excluded the break
# is sharp and monotonic: <=0.5 consistently beats no ORU (-277/-109/-50),
# >=0.55 consistently loses (+179/+252/+198).
DEFAULT_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO = 0.40

# Check B -- motion plausibility: forward-predicts the bracket's earlier
# observation to the later one's own timestamp using the TRACK's own
# PRE-GAP velocity (the same constant-velocity arithmetic `predict.py`
# implements, applied from the bracket rather than the track's current,
# possibly-drifted box), and refuses when that forecast lands more than this
# many BOX-DIAGONALS (`math.hypot(width, height)`, averaged between the two
# boxes) from the real later observation's centre. A size-scaled centre
# distance, not IoU -- `cv_service.tracking.reupdate`'s own module docstring
# has the full reasoning, briefly: IoU degrades to a hard `0.0` the instant
# two boxes fail to overlap at all, which is the ORDINARY case after a
# multi-second gap on a fast or small target, so a strict IoU gate would
# refuse almost every honest long-gap bracket this check is supposed to let
# through leniently.
#
# `<=0` disables the check entirely, the SAME shape every other ceiling in
# this section already uses.
#
# Defaulted to `0.0` (DISABLED) because the sweep MEASURED IT HARMFUL, not
# because nobody has looked: 1.0/2.0/3.0 box-diagonals scored +434/+550/+451
# IDSW against the ORU-off baseline, every one of them WORSE than the +377 of
# leaving this check off entirely (`TRACKING-BENCHMARK-RESULTS.md` §4d). The
# tension `reupdate.py`'s docstring predicted is the likely reason -- the
# pre-gap velocity this check forecasts from is exactly the estimate ORU
# exists because it distrusts, so the forecast rejects honest brackets more
# often than dishonest ones.
#
# Kept rather than deleted because that verdict is regime-bound: it was
# measured on dense, largely static pedestrian footage, and a sparse scene
# with strong coherent ego-motion -- a drone -- is precisely where a pre-gap
# velocity is most trustworthy. Re-sweep it on aerial footage before
# concluding anything general.
DEFAULT_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE = 0.0

# TRACK-IDENTITY-PLAN wave L1: backs `track.py`'s per-track label election
# (`_seed_label_election`/`_update_label_election`) -- the fix for
# TRACK-IDENTITY-RESEARCH.md's six-layer causal chain, where an open-
# vocabulary model's one-argmax-per-pass label re-rolls every observation
# and every downstream layer repeats the newest roll verbatim. No wire
# field exists for any of the three -- deployment-only, same "resolved
# straight from Settings" shape as `track_follow_top_k`/`track_roi_enabled`
# above -- because election is a *deployment* posture (how patient the
# fleet is with a challenger label), not a per-request tuning the operator
# UI exposes.
#
# How many of a track's most recent SOURCE_DETECTOR observations feed the
# label tally (older votes fall off the ring AND are exponentially
# decayed -- see `track.py`'s `_LABEL_VOTE_DECAY` -- so this bounds memory,
# not just history depth). `10` mirrors the shipped
# `CV_TRACK_VERIFY_MILLIS`-adjacent cadence: at the detector's own pass
# rate this is roughly a second of evidence, long enough to outvote a
# single-pass mis-class, short enough that a genuine identity change (the
# tracked object itself changes) is not held hostage for many seconds.
DEFAULT_TRACK_LABEL_VOTE_WINDOW = 10
# A challenger label must out-score the incumbent elected label by this
# multiple before it is even eligible to start a switch streak (see
# `DEFAULT_TRACK_LABEL_SWITCH_STREAK` below) -- a >1.0 margin so a
# challenger barely ahead of decayed noise cannot immediately contest the
# incumbent every single pass. `1.5` -- not a bare majority (`1.0`, which a
# single strong-confidence outlier could clear) and not a landslide
# (`3.0`+, which would make legitimate identity changes sluggish) -- the
# same "clearly ahead, not merely ahead" posture `DEFAULT_TRACK_COST_GATE_
# HIGH_CONFIDENCE` takes for its own threshold.
DEFAULT_TRACK_LABEL_SWITCH_MARGIN = 1.5
# The challenger must hold the margin above for this many CONSECUTIVE
# SOURCE_DETECTOR passes before the election actually switches -- a single
# lucky pass (even a decisive one) never flips the emitted label; the
# streak resets to zero the moment any other label leads. `3` consecutive
# passes is the same order of magnitude as `DEFAULT_TRACK_MIN_HITS` (the
# gate a brand-new track's own existence must clear before CONFIRMED) --
# long enough that transient noise cannot win, short enough that a real
# identity change is visible in under a second at the detector's own pass
# rate.
DEFAULT_TRACK_LABEL_SWITCH_STREAK = 3

# --- pull (docs/plans/done/MEDIA-SOT-PLAN.md §5.5, wave M3) --------------
#
# Back `cv_service.pull.{source,clock,loop}` -- the worker's own decode loop
# for `Inference.DetectPulled`, none of which has a `PullControl` wire
# counterpart EXCEPT the two that do (see each constant's own comment): these
# are deployment knobs, resolved once like every `CV_TRACK_*` knob above, not
# per-request state.
DEFAULT_PULL_DECODER = "opencv"  # M0's measured choice (CV-PULL-SPIKE.md §7); D7's other named backends
DEFAULT_PULL_RTSP_TRANSPORT = "tcp"
# `PullControl.target_fps <= 0` falls back to this (§5.1 field 7) -- the
# floor `DeadlineSampler` samples at when the Java rate controller (which
# still owns the actual demand decision, MEDIA-SOT-PLAN §7) sends no opinion.
DEFAULT_PULL_TARGET_FPS = 10.0
# `PullControl.detect_width <= 0` falls back to this (§5.1 field 8) --
# matches `MAX_DETECT_WIDTH` (adapter-cv-grpc/GrpcCvSettings.java), so a
# worker with no explicit instruction downscales exactly as much as the push
# path's own default does.
DEFAULT_PULL_MAX_WIDTH = 640
DEFAULT_PULL_OPEN_TIMEOUT_MILLIS = 5000
DEFAULT_PULL_STALL_TIMEOUT_MILLIS = 5000
DEFAULT_PULL_CLOCK_MODE = "anchor"  # M0's measured choice (CV-PULL-SPIKE.md §7); "arrival" remains selectable
_PULL_CLOCK_MODES = ("anchor", "arrival")
# NOT named by MEDIA-SOT-PLAN §5.1/§5.5 -- §6 only says "re-anchoring when
# measured skew exceeds A THRESHOLD" without pinning the number.
# `cv_service.pull.clock.DEFAULT_REANCHOR_THRESHOLD_MILLIS` (100ms) is that
# number's home; this is its `CV_*`-configurable override, kept here rather
# than hardcoded in `clock.py` per rule 1 (no un-configurable magic numbers).
DEFAULT_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS = 100.0

# --- geo (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.6/§9.9, wave H4) ------
#
# Backs `cv_service.geo.*` -- `LocalizeStream`/`BuildReferenceIndex`/`ListRegions`/
# `DeleteRegion` (`cv_service/grpc/servicers.py#GeolocationServicer`). The geo pull session
# reuses `pull_decoder`/`pull_rtsp_transport`/`pull_open_timeout_millis`/
# `pull_stall_timeout_millis` above rather than duplicating them -- D2's own words: "the
# shipped ... `cv_service/pull/` machinery already does exactly this".
_GEO_DIRNAME = "geo"
_GEO_MODEL_CACHE_DIRNAME = ".model-cache"
DEFAULT_GEO_ENCODER = "eigenplaces_r18_512"  # O11 -- harvested unchanged, the best measured real result
# §3.6's table names `CV_GEO_MAX_CANDIDATES` (retrieval depth) and §9.9 amendment 1 separately
# fixes `CV_GEO_RERANK_K=20` (re-rank depth). Every H0/H0c driver script measured with ONE k
# feeding both stages (`localize(..., max_candidates=K)` and `rerank(..., top_k=K)`, the same
# `K`) -- so H4 consolidates them into this single knob rather than inventing a
# retrieve-more-than-you-rerank policy nothing measured. See MODULE.md.
DEFAULT_GEO_RERANK_K = 20
_GEO_MATCHERS = ("xfeat", "loftr")  # §9.9 amendment 1: default xfeat; loftr the slow high-recall option.
# lightglue_aliked/lightglue_disk/eloftr are NOT ported -- see MODULE.md.
DEFAULT_GEO_MATCHER = "xfeat"
DEFAULT_GEO_MATCH_FLOOR = 12  # harvested `verify.DEFAULT_MATCH_FLOOR` -- a cheap pre-RANSAC skip, not a gate
DEFAULT_GEO_INLIER_FLOOR = 8  # G-a, §4.2 -- precision 1.0 at >=8 inliers (§12.11)
DEFAULT_GEO_PROMOTION_INLIER_FLOOR = 16  # harvested; reserved for a future promotion feature, see MODULE.md
DEFAULT_GEO_MIN_INLIER_RATIO = 0.35  # G-b, §4.2
DEFAULT_GEO_MAX_REPROJECTION_RMS_PX = 4.0  # G-c, §4.2 -- the residual ceiling that applies at every N
DEFAULT_GEO_MIN_RERANK_MARGIN = 0.15  # G-d, §4.2
DEFAULT_GEO_RECTIFY = True
# Below this pitch-from-nadir the view is already close enough to nadir that IPM's own warp cost
# buys little -- the degraded (raw-frame) path is used instead. Not a §12.11/§13.4 measured
# number; a conservative, documented placeholder (rectify.py's own MIN_DEPRESSION_DEGREES=15 is
# the geometric floor this sits well inside of).
DEFAULT_GEO_RECTIFY_MIN_PITCH_DEG = 10.0
DEFAULT_GEO_SEQUENCE = True
DEFAULT_GEO_SEQUENCE_PARTICLES = 4000
DEFAULT_GEO_SEQUENCE_TEMPERATURE = 0.02
DEFAULT_GEO_SEQ_MIN_SUPPORTING_FRAMES = 4  # §4.4 Change 2 G-b
DEFAULT_GEO_SEQ_MIN_BASELINE_M = 40.0  # §4.4 Change 2 G-b -- meters of telemetry-derived platform motion
DEFAULT_GEO_OSM_WEIGHT = 0.0  # D8 -- inert until measured; osm_fingerprint.py is not ported this wave (MODULE.md)
DEFAULT_GEO_MAX_PACK_BYTES = 8 * 1024 * 1024 * 1024  # 8 GiB, harvested
# Texture gate (§4.1 node B). Not named in §3.6's table but named as UNVALIDATED placeholders in
# harvested `localize.py` itself -- rule 1 (no un-configurable magic numbers) puts them here.
DEFAULT_GEO_MIN_LAPLACIAN_VARIANCE = 50.0
DEFAULT_GEO_MIN_ENTROPY = 3.0
# `GeoControl.target_fps <= 0` falls back to this -- mirrors `DEFAULT_PULL_TARGET_FPS`'s own
# contract. §9.9 amendment 2's own default (`vision.geo.visual.keyframe-fps: 1.0`, Java side).
DEFAULT_GEO_TARGET_FPS = 1.0

_ENV_MAX_CONCURRENT_INFERENCES = "CV_MAX_CONCURRENT_INFERENCES"


def _default_max_concurrent_inferences() -> int:
    """``min(2, cpu_count // 2)``, floored at 1.

    Two, not `cpu_count`, is deliberate: a single Ultralytics/PyTorch
    `detect()` call already spreads itself across several intra-op threads
    on its own -- verified in this task's dev environment,
    `torch.get_num_threads()` reported `6` on a 12-logical-core box, entirely
    PyTorch's own default heuristic, nothing this module configures. So a
    handful of *concurrent* `detect()` calls can already contend for every
    core; `cpu_count // 2` keeps total demand (concurrent calls x each
    call's own intra-op threads) in the right ballpark relative to the
    machine instead of naively scaling the gate with core count, and it's
    capped at 2 because the realistic demo load (1-3 simultaneous streams)
    never needs more anyway (see MODULE.md for the honest OpenVINO caveat).
    Floored at 1 so a 1-2 logical-core box still runs (serially, which is the
    correct degradation) instead of constructing an invalid `Semaphore(0)`.
    """
    cpu = os.cpu_count() or 4
    return max(1, min(2, cpu // 2))


def _resolve_max_concurrent_inferences(raw: Optional[str]) -> int:
    default = _default_max_concurrent_inferences()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning(
            "%s=%r is not a valid integer; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    if value <= 0:
        LOGGER.warning(
            "%s=%r must be positive; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    return value


def _parse_imgsz(raw: Optional[str], default: int = DEFAULT_IMGSZ) -> int:
    """Parse the `CV_IMGSZ` env var into a positive int, default on garbage.

    Inference size is a performance knob, not something a missing/malformed
    env var should be able to crash the service over -- unset, non-numeric,
    or non-positive values all silently fall back to `default`.
    """
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("CV_IMGSZ=%r is not a valid integer; using default %d", raw, default)
        return default
    if value <= 0:
        LOGGER.warning("CV_IMGSZ=%r must be positive; using default %d", raw, default)
        return default
    return value


def _parse_device(raw: Optional[str]) -> Optional[str]:
    """Parse the `CV_DEVICE` env var: unset/blank -> None ("ultralytics auto").

    Any non-blank value (`"cpu"`, `"cuda"`, `"cuda:0"`, `"0"`, ...) is passed
    through as-is -- ultralytics/torch own validating device strings, this
    function doesn't enumerate or second-guess them. Whitespace is stripped
    so a stray env-file trailing space doesn't turn into a bogus device
    string.
    """
    if raw is None:
        return None
    stripped = raw.strip()
    return stripped or None


def _parse_positive_int(raw: Optional[str], default: int, var_name: str) -> int:
    """Generic forgiving-parse for the newer, simpler int knobs (port,
    worker/grace/upload-cap counts) -- same idiom as `_parse_imgsz`/
    `_resolve_max_concurrent_inferences` above, just not tied to one
    specific env var name."""
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid integer; using default %d", var_name, raw, default)
        return default
    if value <= 0:
        LOGGER.warning("%s=%r must be positive; using default %d", var_name, raw, default)
        return default
    return value


def _parse_unit_fraction(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for a normalized `(0, 1]` threshold knob (`CV_TRACK_IOU`).

    Same "unset/garbage -> default, never raise" contract as the int helpers
    above. Deliberately rejects values outside `(0, 1]` with a warning rather
    than clamping: the single most likely mistake is writing the *percent*
    the REST/Java layer uses (`30`) into the *fraction* this layer wants
    (`0.3`), and silently clamping `30` to `1.0` would turn a typo into a
    tracker that never re-anchors, which is far harder to diagnose than a
    logged fallback to the documented default.
    """
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if not 0.0 < value <= 1.0:
        LOGGER.warning(
            "%s=%r must be a fraction in (0, 1] (0.3, not 30); using default %s",
            var_name,
            raw,
            default,
        )
        return default
    return value


def _parse_unit_interval(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for a `[0, 1]` gate/threshold knob where 0 is a
    legitimate value (TRACKING-V2-PLAN wave C3's cost gates).

    Deliberately NOT `_parse_unit_fraction`: that helper rejects `0.0`
    because it backs `CV_TRACK_IOU`'s FOLLOW re-anchor threshold, where a
    literal zero would silently mean "match anything" -- a functional bug.
    A cost gate's `0.0` means "this gate is off", which is a normal,
    intended configuration, not a typo to guard against.
    """
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if not 0.0 <= value <= 1.0:
        LOGGER.warning(
            "%s=%r must be a fraction in [0, 1]; using default %s", var_name, raw, default
        )
        return default
    return value


def _parse_nonnegative_float(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for an unbounded `>= 0` knob (a cost weight, or a cost
    cap that may legitimately be very large or infinite)."""
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if value < 0.0:
        LOGGER.warning("%s=%r must not be negative; using default %s", var_name, raw, default)
        return default
    return value


def _parse_int_allow_nonpositive(raw: Optional[str], default: int, var_name: str) -> int:
    """Forgiving-parse for an int knob where `<=0` is a legitimate DISABLE
    value, not a typo to guard against (TRACKING-V2-PLAN wave C4's
    `CV_TRACK_MEMORY_TTL_MILLIS` -- see that constant's own comment on why
    disabling the gallery is a deployment-layer decision).

    Unlike `_parse_positive_int`, a non-positive parsed value is accepted
    as-is rather than replaced by `default`; only genuinely non-numeric
    input falls back, same "never raise" contract every parser here shares.
    """
    if not raw:
        return default
    try:
        return int(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid integer; using default %d", var_name, raw, default)
        return default


def _parse_float_allow_nonpositive(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for a float knob where `<=0` is a legitimate DISABLE
    value, not a typo to guard against -- the float counterpart of
    `_parse_int_allow_nonpositive` immediately above
    (`CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND`: see
    `DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND`'s own comment for why
    ORU's plausibility guard needs a fleet-wide off switch the same shape as
    `DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS`'s).

    Unlike `_parse_positive_float`, a non-positive parsed value is accepted
    as-is rather than replaced by `default`; only genuinely non-numeric
    input falls back, same "never raise" contract every parser here shares.
    """
    if not raw:
        return default
    try:
        return float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default


def _parse_engine_id(raw: Optional[str], default: str) -> str:
    """Unset/blank -> `default`; anything else passes through stripped.

    Deliberately does NOT validate against the engine roster: the roster is
    discovered at startup by `cv_service.tracking.registry.TrackerRegistry`
    (which probes what is actually constructible on this box), and an
    unknown id there is already a log-once-and-fall-back-to-the-default
    outcome -- exactly the posture `ModelRegistry` takes for an unknown
    `model_id`. Validating here would duplicate that in a second place and
    make a typo a startup crash instead of a degradation.
    """
    if raw is None:
        return default
    stripped = raw.strip()
    return stripped or default


def _parse_string(raw: Optional[str], default: str) -> str:
    """Unset/blank -> `default`; anything else passes through stripped.

    For freeform string knobs with no fixed roster to validate against here
    -- the consuming module degrades gracefully for a value it doesn't
    recognize (e.g. `cv_service.pull.source.open_source`'s `CV_PULL_DECODER`
    fallback logs a warning and uses `opencv` instead of raising), so there
    is nothing for this parser to reject.
    """
    if raw is None:
        return default
    stripped = raw.strip()
    return stripped or default


def _parse_targets(raw: Optional[str]) -> tuple:
    """`CV_DETECTOR_TARGETS` -> an ordered, de-duplicated tuple of targets.

    ORDER IS THE CONTRACT (§4.9): it is the failover order, and therefore the
    affinity mechanism, so this parser preserves it exactly and only drops a
    REPEAT -- a target listed twice would silently double its share of the
    retry budget while looking like two instances. Unset or blank yields the
    empty tuple, which every reader takes as "detect in-process", this
    module's pre-W4 behaviour.

    Deliberately does not validate `host:port` shape: an unreachable target is
    already a first-class, reported outcome (the pool moves to the next one and
    `Inspect` shows the error), so a typo degrades instead of crash-looping a
    process at startup -- the same posture `_parse_engine_id` takes.
    """
    if not raw:
        return ()
    seen: "list[str]" = []
    for piece in raw.split(","):
        target = piece.strip()
        if target and target not in seen:
            seen.append(target)
    return tuple(seen)


def _parse_choice(raw: Optional[str], default: str, choices: tuple, var_name: str) -> str:
    """Forgiving-parse for a small FIXED roster (`CV_PULL_CLOCK_MODE`'s
    `anchor`/`arrival`) -- unlike `_parse_string` above, an unrecognized
    non-blank value here IS a typo to guard against: the consuming module
    (`cv_service.pull.clock.CaptureClock`) raises on an unknown mode rather
    than degrading, so this validates against `choices` and falls back with
    a logged warning instead of ever passing a bad value through to that
    raise. Case-insensitive, matching this file's other roster-adjacent
    parsers.
    """
    if not raw:
        return default
    stripped = raw.strip().lower()
    if stripped in choices:
        return stripped
    LOGGER.warning("%s=%r is not one of %s; using default %r", var_name, raw, choices, default)
    return default


_BOOL_TRUE = {"1", "true", "yes", "on"}
_BOOL_FALSE = {"0", "false", "no", "off"}


def _parse_bool(raw: Optional[str], default: bool, var_name: str) -> bool:
    """Forgiving-parse for an on/off knob (TRACKING-V2-PLAN wave C5c's
    `CV_TRACK_ROI_ENABLED`, the first boolean `CV_TRACK_*` knob this module
    has needed).

    Unset/blank -> `default`. Case-insensitive `1`/`true`/`yes`/`on` ->
    `True`, `0`/`false`/`no`/`off` -> `False`; anything else logs and falls
    back to `default` -- same "never raise" contract every parser here
    shares.
    """
    if not raw:
        return default
    lowered = raw.strip().lower()
    if lowered in _BOOL_TRUE:
        return True
    if lowered in _BOOL_FALSE:
        return False
    LOGGER.warning("%s=%r is not a valid boolean; using default %s", var_name, raw, default)
    return default


def _parse_capability_level(raw: Optional[str], default: int, var_name: str) -> int:
    """Forgiving-parse for `CV_TRACK_CAPABILITY_LEVEL` (TRACKING-V3-PLAN §5).

    `0` (or unset) means auto-probe -- `cv_service.tracking.levels.probe()`
    decides. `1`-`5` pins a deployment-wide CEILING (decision E12: still a
    ceiling here too -- a client's own positive per-request value still wins
    over this default, exactly like every other `CV_TRACK_*` sentinel
    `params.resolve()` interprets). The valid range is duplicated here
    (`0`-`5`, not imported from `cv_service.tracking.levels.MAX_LEVEL`)
    deliberately -- this module reads zero other `cv_service` modules today,
    and `tests/test_config.py` cross-checks the literal against
    `levels.MAX_LEVEL` so the two cannot silently drift. Out-of-range or
    non-numeric input falls back to `default` rather than clamping, same
    "a typo should be loud" posture `_parse_unit_fraction` documents.
    """
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid integer; using default %d", var_name, raw, default)
        return default
    if not 0 <= value <= 5:
        LOGGER.warning(
            "%s=%r must be 0 (auto-probe) or 1-5; using default %d", var_name, raw, default
        )
        return default
    return value


def _parse_positive_float(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for an unbounded `> 0` float knob (TRACKING-V2-PLAN
    wave C5c's `CV_TRACK_ROI_CROP_FACTOR` -- a crop-size multiplier, where
    zero or negative is a misconfiguration, not a legitimate "disable"
    value: that is `CV_TRACK_ROI_ENABLED`'s job, see its own comment above).

    Same "unset/garbage/non-positive -> default, never raise" contract as
    `_parse_positive_int`, just for a float.
    """
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if value <= 0.0:
        LOGGER.warning("%s=%r must be positive; using default %s", var_name, raw, default)
        return default
    return value


@dataclass(frozen=True)
class Settings:
    """Every ``CV_*``-configurable knob cv-service has, resolved once.

    Construct via :meth:`from_env` at process startup (``grpc/server.py``'s
    ``main()``); everything downstream takes the resolved value as an
    explicit argument instead of reading the environment itself.
    """

    port: int = DEFAULT_PORT
    model: str = DEFAULT_MODEL
    imgsz: int = DEFAULT_IMGSZ
    device: Optional[str] = None
    max_concurrent_inferences: int = field(default_factory=_default_max_concurrent_inferences)
    dataset_dir: Path = field(default_factory=lambda: _BASE_DIR / _DATASET_DIRNAME)
    model_dir: Path = field(default_factory=lambda: _BASE_DIR)
    max_upload_bytes: int = DEFAULT_MAX_UPLOAD_BYTES
    grpc_workers: int = DEFAULT_GRPC_WORKERS
    shutdown_grace_seconds: int = DEFAULT_SHUTDOWN_GRACE_SECONDS
    role: str = DEFAULT_ROLE
    #: Ordered detector targets; empty (the default) means "detect in-process",
    #: which is every pre-W4 deployment. See `DETECTOR_TARGET_LOCAL`.
    detector_targets: tuple = ()
    detector_max_queue: int = DEFAULT_DETECTOR_MAX_QUEUE
    detector_timeout_millis: int = DEFAULT_DETECTOR_TIMEOUT_MILLIS
    track_associate_engine: str = DEFAULT_TRACK_ASSOCIATE_ENGINE
    track_follow_engine: str = DEFAULT_TRACK_FOLLOW_ENGINE
    track_reacquire_millis: int = DEFAULT_TRACK_REACQUIRE_MILLIS
    track_verify_millis: int = DEFAULT_TRACK_VERIFY_MILLIS
    track_iou: float = DEFAULT_TRACK_IOU
    track_max_age_frames: int = DEFAULT_TRACK_MAX_AGE_FRAMES
    track_min_hits: int = DEFAULT_TRACK_MIN_HITS
    track_max_age_millis: int = DEFAULT_TRACK_MAX_AGE_MILLIS
    track_min_tracker_confidence: float = DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE
    track_motion_engine: str = DEFAULT_TRACK_MOTION_ENGINE
    track_appearance_engine: str = DEFAULT_TRACK_APPEARANCE_ENGINE
    track_cost_weight_iou: float = DEFAULT_TRACK_COST_WEIGHT_IOU
    track_cost_weight_appearance: float = DEFAULT_TRACK_COST_WEIGHT_APPEARANCE
    track_cost_weight_label: float = DEFAULT_TRACK_COST_WEIGHT_LABEL
    track_cost_gate_min_iou: float = DEFAULT_TRACK_COST_GATE_MIN_IOU
    track_cost_gate_max_appearance: float = DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE
    track_cost_gate_max_cost: float = DEFAULT_TRACK_COST_GATE_MAX_COST
    track_cost_gate_high_confidence: float = DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE
    detect_floor: float = DEFAULT_DETECT_FLOOR
    track_memory_ttl_millis: int = DEFAULT_TRACK_MEMORY_TTL_MILLIS
    track_memory_capacity: int = DEFAULT_TRACK_MEMORY_CAPACITY
    track_memory_gallery_size: int = DEFAULT_TRACK_MEMORY_GALLERY_SIZE
    track_memory_max_appearance_distance: float = DEFAULT_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE
    track_memory_max_speed: float = DEFAULT_TRACK_MEMORY_MAX_SPEED
    track_memory_blend_alpha: float = DEFAULT_TRACK_MEMORY_BLEND_ALPHA
    track_memory_min_confidence: float = DEFAULT_TRACK_MEMORY_MIN_CONFIDENCE
    track_session_grace_millis: int = DEFAULT_TRACK_SESSION_GRACE_MILLIS
    track_session_capacity: int = DEFAULT_TRACK_SESSION_CAPACITY
    ledger_ring: int = DEFAULT_LEDGER_RING
    track_follow_top_k: int = DEFAULT_TRACK_FOLLOW_TOP_K
    track_roi_enabled: bool = DEFAULT_TRACK_ROI_ENABLED
    track_roi_crop_factor: float = DEFAULT_TRACK_ROI_CROP_FACTOR
    track_roi_min_iou: float = DEFAULT_TRACK_ROI_MIN_IOU
    track_capability_level: int = DEFAULT_TRACK_CAPABILITY_LEVEL
    track_reupdate_max_gap_millis: int = DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS
    track_detection_lag_correction_enabled: bool = DEFAULT_TRACK_DETECTION_LAG_CORRECTION_ENABLED
    track_reupdate_max_velocity_per_second: float = DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND
    track_reupdate_max_track_count: int = DEFAULT_TRACK_REUPDATE_MAX_TRACK_COUNT
    track_reupdate_max_shape_log_ratio: float = DEFAULT_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO
    track_reupdate_max_motion_center_distance: float = (
        DEFAULT_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE
    )
    track_label_vote_window: int = DEFAULT_TRACK_LABEL_VOTE_WINDOW
    track_label_switch_margin: float = DEFAULT_TRACK_LABEL_SWITCH_MARGIN
    track_label_switch_streak: int = DEFAULT_TRACK_LABEL_SWITCH_STREAK
    pull_decoder: str = DEFAULT_PULL_DECODER
    pull_rtsp_transport: str = DEFAULT_PULL_RTSP_TRANSPORT
    pull_target_fps: float = DEFAULT_PULL_TARGET_FPS
    pull_max_width: int = DEFAULT_PULL_MAX_WIDTH
    pull_open_timeout_millis: int = DEFAULT_PULL_OPEN_TIMEOUT_MILLIS
    pull_stall_timeout_millis: int = DEFAULT_PULL_STALL_TIMEOUT_MILLIS
    pull_clock_mode: str = DEFAULT_PULL_CLOCK_MODE
    pull_clock_reanchor_threshold_millis: float = DEFAULT_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS
    geo_data_dir: Path = field(default_factory=lambda: _BASE_DIR / _GEO_DIRNAME)
    geo_model_cache: Path = field(default_factory=lambda: _BASE_DIR / _GEO_MODEL_CACHE_DIRNAME)
    geo_encoder: str = DEFAULT_GEO_ENCODER
    geo_device: Optional[str] = None
    geo_rerank_k: int = DEFAULT_GEO_RERANK_K
    geo_matcher: str = DEFAULT_GEO_MATCHER
    geo_match_floor: int = DEFAULT_GEO_MATCH_FLOOR
    geo_inlier_floor: int = DEFAULT_GEO_INLIER_FLOOR
    geo_promotion_inlier_floor: int = DEFAULT_GEO_PROMOTION_INLIER_FLOOR
    geo_min_inlier_ratio: float = DEFAULT_GEO_MIN_INLIER_RATIO
    geo_max_reprojection_rms_px: float = DEFAULT_GEO_MAX_REPROJECTION_RMS_PX
    geo_min_rerank_margin: float = DEFAULT_GEO_MIN_RERANK_MARGIN
    geo_rectify: bool = DEFAULT_GEO_RECTIFY
    geo_rectify_min_pitch_deg: float = DEFAULT_GEO_RECTIFY_MIN_PITCH_DEG
    geo_sequence_enabled: bool = DEFAULT_GEO_SEQUENCE
    geo_sequence_particles: int = DEFAULT_GEO_SEQUENCE_PARTICLES
    geo_sequence_temperature: float = DEFAULT_GEO_SEQUENCE_TEMPERATURE
    geo_seq_min_supporting_frames: int = DEFAULT_GEO_SEQ_MIN_SUPPORTING_FRAMES
    geo_seq_min_baseline_m: float = DEFAULT_GEO_SEQ_MIN_BASELINE_M
    geo_osm_weight: float = DEFAULT_GEO_OSM_WEIGHT
    geo_max_pack_bytes: int = DEFAULT_GEO_MAX_PACK_BYTES
    geo_min_laplacian_variance: float = DEFAULT_GEO_MIN_LAPLACIAN_VARIANCE
    geo_min_entropy: float = DEFAULT_GEO_MIN_ENTROPY
    geo_target_fps: float = DEFAULT_GEO_TARGET_FPS

    @classmethod
    def from_env(cls) -> "Settings":
        """Read every ``CV_*`` var from ``os.environ`` fresh and resolve one
        :class:`Settings`. This is the ONLY function in cv-service that reads
        ``os.environ`` -- everything else takes a `Settings` (or one of its
        already-resolved fields) as a plain argument.
        """
        return cls(
            port=_parse_positive_int(os.environ.get("CV_PORT"), DEFAULT_PORT, "CV_PORT"),
            model=os.environ.get("CV_MODEL", DEFAULT_MODEL),
            imgsz=_parse_imgsz(os.environ.get("CV_IMGSZ")),
            device=_parse_device(os.environ.get("CV_DEVICE")),
            max_concurrent_inferences=_resolve_max_concurrent_inferences(
                os.environ.get(_ENV_MAX_CONCURRENT_INFERENCES)
            ),
            dataset_dir=Path(
                os.environ.get("CV_DATASET_DIR", str(_BASE_DIR / _DATASET_DIRNAME))
            ),
            model_dir=Path(os.environ.get("CV_MODEL_DIR", str(_BASE_DIR))),
            max_upload_bytes=_parse_positive_int(
                os.environ.get("CV_MAX_UPLOAD_BYTES"), DEFAULT_MAX_UPLOAD_BYTES, "CV_MAX_UPLOAD_BYTES"
            ),
            grpc_workers=_parse_positive_int(
                os.environ.get("CV_GRPC_WORKERS"), DEFAULT_GRPC_WORKERS, "CV_GRPC_WORKERS"
            ),
            shutdown_grace_seconds=_parse_positive_int(
                os.environ.get("CV_SHUTDOWN_GRACE"),
                DEFAULT_SHUTDOWN_GRACE_SECONDS,
                "CV_SHUTDOWN_GRACE",
            ),
            role=_parse_choice(os.environ.get("CV_SERVICE_ROLE"), DEFAULT_ROLE, _ROLE_CHOICES, "CV_SERVICE_ROLE"),
            detector_targets=_parse_targets(os.environ.get("CV_DETECTOR_TARGETS")),
            detector_max_queue=_parse_positive_int(
                os.environ.get("CV_DETECTOR_MAX_QUEUE"),
                DEFAULT_DETECTOR_MAX_QUEUE,
                "CV_DETECTOR_MAX_QUEUE",
            ),
            detector_timeout_millis=_parse_positive_int(
                os.environ.get("CV_DETECTOR_TIMEOUT_MILLIS"),
                DEFAULT_DETECTOR_TIMEOUT_MILLIS,
                "CV_DETECTOR_TIMEOUT_MILLIS",
            ),
            track_associate_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_ASSOCIATE_ENGINE"), DEFAULT_TRACK_ASSOCIATE_ENGINE
            ),
            track_follow_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_FOLLOW_ENGINE"), DEFAULT_TRACK_FOLLOW_ENGINE
            ),
            track_reacquire_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_REACQUIRE_MILLIS"),
                DEFAULT_TRACK_REACQUIRE_MILLIS,
                "CV_TRACK_REACQUIRE_MILLIS",
            ),
            track_verify_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_VERIFY_MS"),
                DEFAULT_TRACK_VERIFY_MILLIS,
                "CV_TRACK_VERIFY_MS",
            ),
            track_iou=_parse_unit_fraction(
                os.environ.get("CV_TRACK_IOU"), DEFAULT_TRACK_IOU, "CV_TRACK_IOU"
            ),
            track_max_age_frames=_parse_positive_int(
                os.environ.get("CV_TRACK_MAX_AGE"),
                DEFAULT_TRACK_MAX_AGE_FRAMES,
                "CV_TRACK_MAX_AGE",
            ),
            track_min_hits=_parse_positive_int(
                os.environ.get("CV_TRACK_MIN_HITS"), DEFAULT_TRACK_MIN_HITS, "CV_TRACK_MIN_HITS"
            ),
            track_max_age_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_MAX_AGE_MILLIS"),
                DEFAULT_TRACK_MAX_AGE_MILLIS,
                "CV_TRACK_MAX_AGE_MILLIS",
            ),
            track_min_tracker_confidence=_parse_unit_fraction(
                os.environ.get("CV_TRACK_MIN_TRACKER_CONFIDENCE"),
                DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE,
                "CV_TRACK_MIN_TRACKER_CONFIDENCE",
            ),
            track_motion_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_MOTION_ENGINE"), DEFAULT_TRACK_MOTION_ENGINE
            ),
            track_appearance_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_APPEARANCE_ENGINE"), DEFAULT_TRACK_APPEARANCE_ENGINE
            ),
            track_cost_weight_iou=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_IOU"),
                DEFAULT_TRACK_COST_WEIGHT_IOU,
                "CV_TRACK_COST_WEIGHT_IOU",
            ),
            track_cost_weight_appearance=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_APPEARANCE"),
                DEFAULT_TRACK_COST_WEIGHT_APPEARANCE,
                "CV_TRACK_COST_WEIGHT_APPEARANCE",
            ),
            track_cost_weight_label=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_LABEL"),
                DEFAULT_TRACK_COST_WEIGHT_LABEL,
                "CV_TRACK_COST_WEIGHT_LABEL",
            ),
            track_cost_gate_min_iou=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_MIN_IOU"),
                DEFAULT_TRACK_COST_GATE_MIN_IOU,
                "CV_TRACK_COST_GATE_MIN_IOU",
            ),
            track_cost_gate_max_appearance=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_MAX_APPEARANCE"),
                DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE,
                "CV_TRACK_COST_GATE_MAX_APPEARANCE",
            ),
            track_cost_gate_max_cost=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_GATE_MAX_COST"),
                DEFAULT_TRACK_COST_GATE_MAX_COST,
                "CV_TRACK_COST_GATE_MAX_COST",
            ),
            track_cost_gate_high_confidence=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_HIGH_CONFIDENCE"),
                DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE,
                "CV_TRACK_COST_GATE_HIGH_CONFIDENCE",
            ),
            detect_floor=_parse_unit_interval(
                os.environ.get("CV_DETECT_FLOOR"),
                DEFAULT_DETECT_FLOOR,
                "CV_DETECT_FLOOR",
            ),
            track_memory_ttl_millis=_parse_int_allow_nonpositive(
                os.environ.get("CV_TRACK_MEMORY_TTL_MILLIS"),
                DEFAULT_TRACK_MEMORY_TTL_MILLIS,
                "CV_TRACK_MEMORY_TTL_MILLIS",
            ),
            track_memory_capacity=_parse_positive_int(
                os.environ.get("CV_TRACK_MEMORY_CAPACITY"),
                DEFAULT_TRACK_MEMORY_CAPACITY,
                "CV_TRACK_MEMORY_CAPACITY",
            ),
            track_memory_gallery_size=_parse_positive_int(
                os.environ.get("CV_TRACK_MEMORY_GALLERY_SIZE"),
                DEFAULT_TRACK_MEMORY_GALLERY_SIZE,
                "CV_TRACK_MEMORY_GALLERY_SIZE",
            ),
            track_memory_max_appearance_distance=_parse_unit_interval(
                os.environ.get("CV_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE"),
                DEFAULT_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE,
                "CV_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE",
            ),
            track_memory_max_speed=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_MEMORY_MAX_SPEED"),
                DEFAULT_TRACK_MEMORY_MAX_SPEED,
                "CV_TRACK_MEMORY_MAX_SPEED",
            ),
            track_memory_blend_alpha=_parse_unit_interval(
                os.environ.get("CV_TRACK_MEMORY_BLEND_ALPHA"),
                DEFAULT_TRACK_MEMORY_BLEND_ALPHA,
                "CV_TRACK_MEMORY_BLEND_ALPHA",
            ),
            track_memory_min_confidence=_parse_unit_interval(
                os.environ.get("CV_TRACK_MEMORY_MIN_CONFIDENCE"),
                DEFAULT_TRACK_MEMORY_MIN_CONFIDENCE,
                "CV_TRACK_MEMORY_MIN_CONFIDENCE",
            ),
            track_session_grace_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_SESSION_GRACE_MILLIS"),
                DEFAULT_TRACK_SESSION_GRACE_MILLIS,
                "CV_TRACK_SESSION_GRACE_MILLIS",
            ),
            track_session_capacity=_parse_positive_int(
                os.environ.get("CV_TRACK_SESSION_CAPACITY"),
                DEFAULT_TRACK_SESSION_CAPACITY,
                "CV_TRACK_SESSION_CAPACITY",
            ),
            ledger_ring=_parse_positive_int(
                os.environ.get("CV_LEDGER_RING"),
                DEFAULT_LEDGER_RING,
                "CV_LEDGER_RING",
            ),
            track_follow_top_k=_parse_positive_int(
                os.environ.get("CV_TRACK_FOLLOW_TOP_K"),
                DEFAULT_TRACK_FOLLOW_TOP_K,
                "CV_TRACK_FOLLOW_TOP_K",
            ),
            track_roi_enabled=_parse_bool(
                os.environ.get("CV_TRACK_ROI_ENABLED"),
                DEFAULT_TRACK_ROI_ENABLED,
                "CV_TRACK_ROI_ENABLED",
            ),
            track_roi_crop_factor=_parse_positive_float(
                os.environ.get("CV_TRACK_ROI_CROP_FACTOR"),
                DEFAULT_TRACK_ROI_CROP_FACTOR,
                "CV_TRACK_ROI_CROP_FACTOR",
            ),
            track_roi_min_iou=_parse_unit_fraction(
                os.environ.get("CV_TRACK_ROI_MIN_IOU"),
                DEFAULT_TRACK_ROI_MIN_IOU,
                "CV_TRACK_ROI_MIN_IOU",
            ),
            track_capability_level=_parse_capability_level(
                os.environ.get("CV_TRACK_CAPABILITY_LEVEL"),
                DEFAULT_TRACK_CAPABILITY_LEVEL,
                "CV_TRACK_CAPABILITY_LEVEL",
            ),
            track_reupdate_max_gap_millis=_parse_int_allow_nonpositive(
                os.environ.get("CV_TRACK_REUPDATE_MAX_GAP_MILLIS"),
                DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS,
                "CV_TRACK_REUPDATE_MAX_GAP_MILLIS",
            ),
            track_detection_lag_correction_enabled=_parse_bool(
                os.environ.get("CV_TRACK_DETECTION_LAG_CORRECTION_ENABLED"),
                DEFAULT_TRACK_DETECTION_LAG_CORRECTION_ENABLED,
                "CV_TRACK_DETECTION_LAG_CORRECTION_ENABLED",
            ),
            track_reupdate_max_velocity_per_second=_parse_float_allow_nonpositive(
                os.environ.get("CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND"),
                DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND,
                "CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND",
            ),
            track_reupdate_max_track_count=_parse_int_allow_nonpositive(
                os.environ.get("CV_TRACK_REUPDATE_MAX_TRACK_COUNT"),
                DEFAULT_TRACK_REUPDATE_MAX_TRACK_COUNT,
                "CV_TRACK_REUPDATE_MAX_TRACK_COUNT",
            ),
            track_reupdate_max_shape_log_ratio=_parse_float_allow_nonpositive(
                os.environ.get("CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO"),
                DEFAULT_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO,
                "CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO",
            ),
            track_reupdate_max_motion_center_distance=_parse_float_allow_nonpositive(
                os.environ.get("CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE"),
                DEFAULT_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE,
                "CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE",
            ),
            track_label_vote_window=_parse_positive_int(
                os.environ.get("CV_TRACK_LABEL_VOTE_WINDOW"),
                DEFAULT_TRACK_LABEL_VOTE_WINDOW,
                "CV_TRACK_LABEL_VOTE_WINDOW",
            ),
            track_label_switch_margin=_parse_positive_float(
                os.environ.get("CV_TRACK_LABEL_SWITCH_MARGIN"),
                DEFAULT_TRACK_LABEL_SWITCH_MARGIN,
                "CV_TRACK_LABEL_SWITCH_MARGIN",
            ),
            track_label_switch_streak=_parse_positive_int(
                os.environ.get("CV_TRACK_LABEL_SWITCH_STREAK"),
                DEFAULT_TRACK_LABEL_SWITCH_STREAK,
                "CV_TRACK_LABEL_SWITCH_STREAK",
            ),
            pull_decoder=_parse_string(os.environ.get("CV_PULL_DECODER"), DEFAULT_PULL_DECODER),
            pull_rtsp_transport=_parse_string(
                os.environ.get("CV_PULL_RTSP_TRANSPORT"), DEFAULT_PULL_RTSP_TRANSPORT
            ),
            pull_target_fps=_parse_positive_float(
                os.environ.get("CV_PULL_TARGET_FPS"), DEFAULT_PULL_TARGET_FPS, "CV_PULL_TARGET_FPS"
            ),
            pull_max_width=_parse_positive_int(
                os.environ.get("CV_PULL_MAX_WIDTH"), DEFAULT_PULL_MAX_WIDTH, "CV_PULL_MAX_WIDTH"
            ),
            pull_open_timeout_millis=_parse_positive_int(
                os.environ.get("CV_PULL_OPEN_TIMEOUT_MILLIS"),
                DEFAULT_PULL_OPEN_TIMEOUT_MILLIS,
                "CV_PULL_OPEN_TIMEOUT_MILLIS",
            ),
            pull_stall_timeout_millis=_parse_positive_int(
                os.environ.get("CV_PULL_STALL_TIMEOUT_MILLIS"),
                DEFAULT_PULL_STALL_TIMEOUT_MILLIS,
                "CV_PULL_STALL_TIMEOUT_MILLIS",
            ),
            pull_clock_mode=_parse_choice(
                os.environ.get("CV_PULL_CLOCK_MODE"),
                DEFAULT_PULL_CLOCK_MODE,
                _PULL_CLOCK_MODES,
                "CV_PULL_CLOCK_MODE",
            ),
            pull_clock_reanchor_threshold_millis=_parse_positive_float(
                os.environ.get("CV_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS"),
                DEFAULT_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS,
                "CV_PULL_CLOCK_REANCHOR_THRESHOLD_MILLIS",
            ),
            geo_data_dir=Path(os.environ.get("CV_GEO_DATA_DIR", str(_BASE_DIR / _GEO_DIRNAME))),
            geo_model_cache=Path(
                os.environ.get("CV_GEO_MODEL_CACHE", str(_BASE_DIR / _GEO_MODEL_CACHE_DIRNAME))
            ),
            geo_encoder=_parse_string(os.environ.get("CV_GEO_ENCODER"), DEFAULT_GEO_ENCODER),
            geo_device=_parse_device(os.environ.get("CV_GEO_DEVICE")),
            geo_rerank_k=_parse_positive_int(
                os.environ.get("CV_GEO_RERANK_K"), DEFAULT_GEO_RERANK_K, "CV_GEO_RERANK_K"
            ),
            geo_matcher=_parse_choice(
                os.environ.get("CV_GEO_MATCHER"), DEFAULT_GEO_MATCHER, _GEO_MATCHERS, "CV_GEO_MATCHER"
            ),
            geo_match_floor=_parse_positive_int(
                os.environ.get("CV_GEO_MATCH_FLOOR"), DEFAULT_GEO_MATCH_FLOOR, "CV_GEO_MATCH_FLOOR"
            ),
            geo_inlier_floor=_parse_positive_int(
                os.environ.get("CV_GEO_INLIER_FLOOR"), DEFAULT_GEO_INLIER_FLOOR, "CV_GEO_INLIER_FLOOR"
            ),
            geo_promotion_inlier_floor=_parse_positive_int(
                os.environ.get("CV_GEO_PROMOTION_INLIER_FLOOR"),
                DEFAULT_GEO_PROMOTION_INLIER_FLOOR,
                "CV_GEO_PROMOTION_INLIER_FLOOR",
            ),
            geo_min_inlier_ratio=_parse_unit_fraction(
                os.environ.get("CV_GEO_MIN_INLIER_RATIO"),
                DEFAULT_GEO_MIN_INLIER_RATIO,
                "CV_GEO_MIN_INLIER_RATIO",
            ),
            geo_max_reprojection_rms_px=_parse_positive_float(
                os.environ.get("CV_GEO_MAX_REPROJECTION_RMS_PX"),
                DEFAULT_GEO_MAX_REPROJECTION_RMS_PX,
                "CV_GEO_MAX_REPROJECTION_RMS_PX",
            ),
            geo_min_rerank_margin=_parse_unit_interval(
                os.environ.get("CV_GEO_MIN_RERANK_MARGIN"),
                DEFAULT_GEO_MIN_RERANK_MARGIN,
                "CV_GEO_MIN_RERANK_MARGIN",
            ),
            geo_rectify=_parse_bool(os.environ.get("CV_GEO_RECTIFY"), DEFAULT_GEO_RECTIFY, "CV_GEO_RECTIFY"),
            geo_rectify_min_pitch_deg=_parse_nonnegative_float(
                os.environ.get("CV_GEO_RECTIFY_MIN_PITCH_DEG"),
                DEFAULT_GEO_RECTIFY_MIN_PITCH_DEG,
                "CV_GEO_RECTIFY_MIN_PITCH_DEG",
            ),
            geo_sequence_enabled=_parse_bool(
                os.environ.get("CV_GEO_SEQUENCE"), DEFAULT_GEO_SEQUENCE, "CV_GEO_SEQUENCE"
            ),
            geo_sequence_particles=_parse_positive_int(
                os.environ.get("CV_GEO_SEQUENCE_PARTICLES"),
                DEFAULT_GEO_SEQUENCE_PARTICLES,
                "CV_GEO_SEQUENCE_PARTICLES",
            ),
            geo_sequence_temperature=_parse_positive_float(
                os.environ.get("CV_GEO_SEQUENCE_TEMPERATURE"),
                DEFAULT_GEO_SEQUENCE_TEMPERATURE,
                "CV_GEO_SEQUENCE_TEMPERATURE",
            ),
            geo_seq_min_supporting_frames=_parse_positive_int(
                os.environ.get("CV_GEO_SEQ_MIN_SUPPORTING_FRAMES"),
                DEFAULT_GEO_SEQ_MIN_SUPPORTING_FRAMES,
                "CV_GEO_SEQ_MIN_SUPPORTING_FRAMES",
            ),
            geo_seq_min_baseline_m=_parse_nonnegative_float(
                os.environ.get("CV_GEO_SEQ_MIN_BASELINE_M"),
                DEFAULT_GEO_SEQ_MIN_BASELINE_M,
                "CV_GEO_SEQ_MIN_BASELINE_M",
            ),
            geo_osm_weight=_parse_nonnegative_float(
                os.environ.get("CV_GEO_OSM_WEIGHT"), DEFAULT_GEO_OSM_WEIGHT, "CV_GEO_OSM_WEIGHT"
            ),
            geo_max_pack_bytes=_parse_positive_int(
                os.environ.get("CV_GEO_MAX_PACK_BYTES"), DEFAULT_GEO_MAX_PACK_BYTES, "CV_GEO_MAX_PACK_BYTES"
            ),
            geo_min_laplacian_variance=_parse_nonnegative_float(
                os.environ.get("CV_GEO_MIN_LAPLACIAN_VARIANCE"),
                DEFAULT_GEO_MIN_LAPLACIAN_VARIANCE,
                "CV_GEO_MIN_LAPLACIAN_VARIANCE",
            ),
            geo_min_entropy=_parse_nonnegative_float(
                os.environ.get("CV_GEO_MIN_ENTROPY"), DEFAULT_GEO_MIN_ENTROPY, "CV_GEO_MIN_ENTROPY"
            ),
            geo_target_fps=_parse_positive_float(
                os.environ.get("CV_GEO_TARGET_FPS"), DEFAULT_GEO_TARGET_FPS, "CV_GEO_TARGET_FPS"
            ),
        )

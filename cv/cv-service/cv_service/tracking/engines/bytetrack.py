"""`bytetrack` -- a RETIRED reference implementation, over ultralytics' `BYTETracker`.

**Unregistered since CV-ORCHESTRATION wave W4 (decision E16, 2026-09-12).**
This file stays on disk on purpose -- kept as a reference implementation, not
as dead weight to delete -- but `registry.py`'s `BUILTIN_ASSOCIATORS` no
longer names it, `TrackerRegistry.probe()` never constructs it, and nothing
else in `cv_service/` imports this module at runtime. A deployment or client
still naming `bytetrack` (`CV_TRACK_ASSOCIATE_ENGINE=bytetrack`, or a wire
`engine_id="bytetrack"` in ASSOCIATE mode) is not left stranded: `cv_service.
tracking.params.resolve()` aliases it to `cost` and logs the substitution
once (`RETIRED_ASSOCIATORS`) rather than letting `EngineSet`'s `FOLLOW ->
ASSOCIATE -> OFF` degradation ladder silently drop every track id on the
floor, which is what an id that simply stopped resolving would otherwise do.

**Why it was retired, not merely deprecated.** CV-ORCHESTRATION rebuilt the
per-frame duty cycle as one evidence graph shared by every associator
(`cv_service/orchestration/`, `docs/plans/active/CV-ORCHESTRATION-PLAN.md`
§4.1/§4.3) -- ego-motion, ROI rescue, the dormant gallery and per-object
provenance all hang off `cost`'s own seam, the fact that its candidates ARE
`TrackBook`'s live tracks so "what did the match leave unexplained" is
readable BEFORE anything is booked. `ByteTrackEngine` below holds its own
Kalman state entirely behind ultralytics' `BYTETracker`, with no such seam:
it cannot be ego-motion-warped, ORU-rescued, or handed a descriptor, and the
book only ever gets to RENAME the ids it mints, never to reason about the
match itself. Keeping it registered would have meant every future
contributor in that graph needing a bytetrack-shaped special case forever --
a visible DAG dead-end (decision E11's own measurement already favoured
`cost` at the deployed detection counts; E16 is the structural argument, not
a second performance one). `tests/tracking/test_capability_benchmark.py`
still reproduces E11's numbers directly against this file, since it remains
importable and constructible on any host with the `cv` extra -- only the
registry stopped offering it.

The remainder of this docstring is retained as-is: it documents the adapter
below, which still exists, still works, and is still worth understanding
even though nothing in this process builds one anymore.

`docs/plans/done/TRACKING-PLAN.md` §5.B (the measured roster) and §5.C (library, not
reimplementation). IoU + Kalman motion association in two stages
(high-confidence first, then low), ~0.75 ms for 10 detections on this box.

`BYTETracker` is already in the tree -- ultralytics is a dependency for YOLO
itself -- and its `update(results)` takes any object exposing `.conf`,
`.xywh`, `.cls`, `__len__` and boolean `__getitem__`, so the adapter below is
the whole integration. Writing our own IoU+Kalman associator would be ~200
lines reproducing something already installed, tested and tuned; the
insurance against ultralytics' AGPL licence is `TrackerRegistry`, which makes
this one engine behind a protocol, replaceable in one file.

**`lap` is a hidden runtime dependency** of `BYTETracker`'s
`linear_assignment`, which ultralytics AutoUpdates by pip-installing it from
the network *mid-process* if it is missing. That is an annoyance on a LAN box
and a guaranteed in-flight failure on an offline companion computer, which is
why `pyproject.toml` pins it into the `cv` extra (TRACKING-PLAN R2).

Two behaviours of the upstream library this adapter has to correct, both
verified against ultralytics 8.4.104 rather than assumed:

1. **`BYTETracker.__init__` resets a PROCESS-GLOBAL id counter**
   (`BaseTrack._count`, via `self.reset_id()`). Two concurrent streams each
   construct their own tracker, so without the guard below stream B's
   constructor renumbers stream A from 1 -- both streams get a "track 1" for
   different objects, and stream A's next new object re-uses an id it has
   already handed out. Measured: with the guard, A/B/A' get 1/2/3; without,
   1/1/2. The guard restores the counter after construction, making engine
   keys unique process-wide; `TrackBook` then maps them to per-stream ids.
2. **`parse_bboxes` prefers `xywhr`** (oriented boxes) when the results
   object has that attribute, so `_Results` deliberately does not define one.

Coordinates are passed through **normalized**, not scaled to pixels:
ByteTrack's association is IoU-based and its Kalman noise model is
proportional to box height, so it is scale-invariant. Verified by running the
same 20-frame occlusion sequence at scale 1.0 and 1000.0 -- identical id
assignment, including recovery of the same id after a 4-frame occlusion.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace
from typing import Any, Sequence

import numpy as np

from cv_service.tracking.engines.base import SOURCE_DETECTOR, Box, Observation

LOGGER = logging.getLogger("cv_service.tracking.engines.bytetrack")

ENGINE_ID = "bytetrack"

# ByteTrack's own algorithm parameters, copied from ultralytics'
# `cfg/trackers/bytetrack.yaml` defaults. These are NOT operator
# configuration -- the operator's knobs are `TrackingParams`, resolved in
# `params.py` from the wire and `CV_TRACK_*`. They live here as named
# constants because they belong to this engine's algorithm, and an engine is
# the one place TRACKING-ORCHESTRATION §2.1 says pixels and geometry may be
# decided. `track_buffer` is the one that would otherwise duplicate a
# `TrackingParams` field, so it is fed from `max_age_frames` at construction
# instead (see `ByteTrackEngine.__init__`).
_TRACK_HIGH_THRESH = 0.25
_TRACK_LOW_THRESH = 0.1
_NEW_TRACK_THRESH = 0.25
_MATCH_THRESH = 0.8
_FUSE_SCORE = True


class _Results:
    """The minimal `Results`-like view `BYTETracker.update` consumes.

    `.xywh` (centre-based, normalized), `.conf`, `.cls`, `__len__` and a
    boolean-mask `__getitem__` are the entire contract -- no ultralytics
    `Results` object, no torch tensor, no `xywhr` attribute (see the module
    docstring).
    """

    __slots__ = ("xywh", "conf", "cls")

    def __init__(self, xywh: np.ndarray, conf: np.ndarray, cls: np.ndarray) -> None:
        self.xywh = xywh
        self.conf = conf
        self.cls = cls

    def __len__(self) -> int:
        return int(self.conf.shape[0])

    def __getitem__(self, mask: Any) -> "_Results":
        return _Results(self.xywh[mask], self.conf[mask], self.cls[mask])


class ByteTrackEngine:
    """`Associator` over `ultralytics.trackers.BYTETracker`.

    One instance per stream (the registry hands out factories, never
    singletons -- TRACKING-PLAN §5.A): a tracker is cheap to build and
    inherently stateful, so sharing one would corrupt every stream at once.
    """

    engine_id = ENGINE_ID

    def __init__(self, *, max_age_frames: int) -> None:
        # Imported here, not at module scope, so `registry.py` stays pure
        # stdlib and an unusable engine degrades instead of breaking import.
        from ultralytics.trackers.basetrack import BaseTrack
        from ultralytics.trackers.byte_tracker import BYTETracker

        self._basetrack = BaseTrack
        self._args = SimpleNamespace(
            track_high_thresh=_TRACK_HIGH_THRESH,
            track_low_thresh=_TRACK_LOW_THRESH,
            new_track_thresh=_NEW_TRACK_THRESH,
            # The one parameter that IS operator configuration: ByteTrack's
            # own lost-track buffer is exactly TRACKING-PLAN §3.2's
            # `max_age_frames`, so it is fed from `TrackingParams` rather
            # than duplicated as a literal.
            track_buffer=max_age_frames,
            match_thresh=_MATCH_THRESH,
            fuse_score=_FUSE_SCORE,
        )
        self._byte_tracker_cls = BYTETracker
        self._tracker = self._new_tracker()

    def _new_tracker(self) -> Any:
        """Construct a `BYTETracker` without renumbering other streams.

        See the module docstring, defect (1). Wrapped defensively: if a
        future ultralytics drops `BaseTrack._count`, the guard silently does
        nothing rather than raising -- the engine still works, it just loses
        the process-wide uniqueness of its keys, which `TrackBook`'s
        per-stream mapping already contains.
        """
        previous = getattr(self._basetrack, "_count", None)
        tracker = self._byte_tracker_cls(self._args)
        if previous is not None:
            self._basetrack._count = previous
        return tracker

    def associate(self, detections: Sequence[Any], now: float) -> list[Observation]:
        """Assign identities to this frame's detections.

        Returns one `Observation` per detection ByteTrack actually tracked;
        detections it dropped (below its confidence thresholds, or
        unassociated on their first frame) are simply absent, and the
        session reports those boxes untracked.
        """
        if not detections:
            # Still step the tracker so its own Kalman ageing advances on an
            # empty frame -- skipping it would make an object's absence
            # invisible to the association state.
            self._tracker.update(_empty_results())
            return []

        results = _to_results(detections)
        tracked = self._tracker.update(results)
        if tracked is None or len(tracked) == 0:
            return []

        observations: list[Observation] = []
        for row in tracked:
            x1, y1, x2, y2, track_id, confidence = (
                float(row[0]),
                float(row[1]),
                float(row[2]),
                float(row[3]),
                int(row[4]),
                float(row[5]),
            )
            det_index = int(row[7])
            if not 0 <= det_index < len(detections):
                continue
            observations.append(
                Observation(
                    key=track_id,
                    box=Box(x1, y1, max(x2 - x1, 0.0), max(y2 - y1, 0.0)),
                    label=detections[det_index].label,
                    confidence=confidence,
                    source=SOURCE_DETECTOR,
                    det_index=det_index,
                )
            )
        return observations

    def reset(self) -> None:
        self._tracker = self._new_tracker()


def _to_results(detections: Sequence[Any]) -> _Results:
    count = len(detections)
    xywh = np.empty((count, 4), dtype=np.float32)
    conf = np.empty(count, dtype=np.float32)
    for index, detection in enumerate(detections):
        xywh[index] = (
            detection.x + detection.width / 2.0,
            detection.y + detection.height / 2.0,
            detection.width,
            detection.height,
        )
        conf[index] = detection.confidence
    # A single class for every detection, on purpose: ByteTrack's first-stage
    # association is IoU/motion-based and not class-gated anyway, and in
    # composite mode the same physical object can carry two different labels
    # from two models (`orion12l:tank` and `yolo11n:truck`). Feeding the
    # label as a class would invite two tracks for one object; feeding one
    # class yields one track whose label may flip between members, which is
    # the cosmetic outcome TRACKING-PLAN R9 predicted and accepts.
    #
    # R9 is overturned for the open-vocabulary path by `docs/plans/active/
    # TRACK-IDENTITY-PLAN.md` (wave L1, `track.py`'s label election) -- but
    # this engine feeds exactly one constant class regardless, so election
    # has nothing to vote over here and the flip this comment describes is
    # unaffected by that wave; see the plan's own preamble for why the
    # `cost` engine, not this one, is where election actually applies.
    cls = np.zeros(count, dtype=np.float32)
    return _Results(xywh, conf, cls)


def _empty_results() -> _Results:
    return _Results(
        np.empty((0, 4), dtype=np.float32),
        np.empty(0, dtype=np.float32),
        np.empty(0, dtype=np.float32),
    )


def create(*, max_age_frames: int) -> ByteTrackEngine:
    """Factory used by `TrackerRegistry`. One engine per stream."""
    return ByteTrackEngine(max_age_frames=max_age_frames)

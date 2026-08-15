"""End-to-end proof that `metrics.implausible_velocity_count` (TRACKING-V3-
PLAN §6b finding O3) actually fires against the REAL `StreamTrackingSession`
-- not merely against a hand-fed `ReplayResult` (`test_metrics.py` already
covers the counting/threshold logic in isolation, pure stdlib, no replay
needed).

A metric that has never been observed to fire is not evidence: this file
builds a single-object sequence whose ground truth teleports for exactly one
frame, runs it through the real `run_replay` -> real `Track._observe`
velocity blend, and asserts the resulting `Metrics.implausible_velocity_
count` is non-zero. Needs `numpy` (frame rendering) the same reason every
other `tools.trackeval` replay test does.
"""

from __future__ import annotations

import numpy as np
import pytest

pytest.importorskip("numpy")

from cv_service.config import Settings
from cv_service.tracking.engines.base import Box
from cv_service.tracking.params import MODE_ASSOCIATE

from tools.trackeval.metrics import MAX_PLAUSIBLE_VELOCITY_PER_SECOND, compute
from tools.trackeval.replay import run_replay
from tools.trackeval.sequences import GroundTruthObject, Sequence, SyntheticFrame

_WIDTH = 320
_HEIGHT = 240
_FPS = 10.0
_LABEL = "object"

# `track_min_hits`'s default (3) confirms the track by frame 2; the teleport
# lands well after that so it lands on an already-CONFIRMED track, not a
# below-min_hits one metrics.py's own docstring notes reports `track=None`.
_TELEPORT_FRAME_INDEX = 6
_TELEPORT_X = 50.0  # far outside [0, 1] -- see the module docstring


def _frame(index: int, x: float) -> SyntheticFrame:
    box = Box(x, 0.5, 0.1, 0.1)
    gt = GroundTruthObject(gt_id=1, label=_LABEL, box=box, visible=True)
    image = np.zeros((_HEIGHT, _WIDTH, 3), dtype=np.uint8)
    return SyntheticFrame(index=index, image=image, ground_truth=(gt,))


def _diverging_sequence() -> Sequence:
    """One object crawling at a plausible, constant velocity (0.01 frame-
    widths/frame = 0.1/sec, comfortably under `MAX_PLAUSIBLE_VELOCITY_PER_
    SECOND`), except for ONE frame where it teleports far outside the frame
    and back -- the "constant-velocity target plus an injected absurd
    velocity" this proof needs. Single-object by design: `cost`'s matcher
    force-matches a lone candidate to a lone track regardless of geometry
    (`AssignGates.min_iou=0.0`/`max_cost=inf` by default -- see `BASELINE.
    md`'s `nonlinear`/§3 writeup for the same structural fact), so the
    teleported detection is guaranteed to book onto the SAME track rather
    than risk a multi-object scenario's own matching ambiguity.
    """
    xs = [0.05 + 0.01 * i for i in range(8)]
    xs[_TELEPORT_FRAME_INDEX] = _TELEPORT_X
    frames = tuple(_frame(i, x) for i, x in enumerate(xs))
    return Sequence(name="velocity-plausibility-proof", fps=_FPS, width=_WIDTH, height=_HEIGHT, frames=frames)


def test_a_teleporting_detection_trips_the_velocity_plausibility_check() -> None:
    sequence = _diverging_sequence()
    # Motion compensation off: this proof is about `track.py`'s own velocity
    # blend, not about ego-motion warping interacting with a blank synthetic
    # frame -- the SAME isolation `nonlinear`'s ASSOCIATE row already relies
    # on (no `CameraPose` supplied to `run_replay` either).
    settings = Settings(track_motion_engine="off")
    result = run_replay(sequence, mode=MODE_ASSOCIATE, settings=settings)
    metrics = compute(result)

    assert metrics.implausible_velocity_count > 0, (
        "the teleported detection never tripped MAX_PLAUSIBLE_VELOCITY_PER_SECOND "
        f"({MAX_PLAUSIBLE_VELOCITY_PER_SECOND}/sec) -- either the teleport failed to book onto "
        "the track (check the single-object force-match assumption above) or the check itself "
        "regressed"
    )

    # Every OTHER frame -- the plausible 0.1/sec crawl -- must NOT trip it:
    # this is a detector, not a general "something happened" flag, and it
    # should not be over-broad either.
    assert metrics.implausible_velocity_count < len(sequence.frames)

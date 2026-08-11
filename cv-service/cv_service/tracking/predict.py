"""Constant-velocity prediction for a track between observations.

TRACKING-V2-PLAN §3.2, review finding C1. `Track` computes its own
`velocity_x`/`velocity_y` every frame and, before this module existed,
nothing ever read them: a lost tracker or an invalid box made the session
hold the box exactly where it was last seen, so during an occlusion the box
sat still while the object kept moving -- and the verify pass that followed
then measured IoU against that stale position and failed to re-anchor. The
freeze was what caused the very re-anchor failure it was trying to survive.

Pure stdlib -- `Box`/`Transform`/`IDENTITY` are the same vocabulary
`track.py`/`session.py` already share, so importing this module costs
nothing extra on a build with no `cv` extra at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from cv_service.tracking.engines.base import IDENTITY, Box, Transform

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cv_service.tracking.track import Track

# A gap longer than this is clamped before it is extrapolated: a track that
# has gone unconfirmed for a long time should not fling its box clear across
# the frame on the strength of one old velocity estimate. Sized to the duty
# cycle's own default verify cadence (CV_TRACK_VERIFY_MS=2000) -- long enough
# to bridge one missed verify pass, short enough that a genuinely stale track
# just holds near where it last was rather than running away with itself.
_MAX_EXTRAPOLATION_SECONDS = 2.0

# Prediction confidence reaches zero this many seconds after the track's last
# DETECTOR confirmation -- not merely its last processed frame, see
# `Track.last_confirmed`'s own docstring for why those two differ. This is
# the signal a future association gate (`assign.py`, wave C3) needs in order
# to stop trusting a long-dormant prediction; wave C1 does not yet consume
# it, but the decay has to exist for that gate to have something honest to
# read once it does.
_CONFIDENCE_DECAY_SECONDS = 6.0


@dataclass(frozen=True)
class Prediction:
    """Where a track's box is now believed to be, and how much to trust it."""

    box: Box
    confidence: float


def predict(track: "Track", now: float, transform: Transform = IDENTITY) -> Prediction:
    """Extrapolate `track`'s box to `now` at constant velocity, then warp it.

    Extrapolation runs from the track's own last position/velocity, clamped
    against `_MAX_EXTRAPOLATION_SECONDS` so a long gap cannot fling the box
    off-frame on one old estimate. `transform` then expresses whatever camera
    motion happened since -- `IDENTITY` (a no-op) until wave C2 ships a real
    motion compensator, at which point warping always runs last: the box is
    predicted in the track's own frame of reference first, then re-expressed
    in the current one.
    """
    elapsed = now - track.last_seen
    if elapsed < 0.0:
        elapsed = 0.0
    elif elapsed > _MAX_EXTRAPOLATION_SECONDS:
        elapsed = _MAX_EXTRAPOLATION_SECONDS

    box = track.box
    predicted = Box(
        box.x + track.velocity_x * elapsed,
        box.y + track.velocity_y * elapsed,
        box.width,
        box.height,
    )
    warped = transform.apply_box(predicted)

    since_confirmed = max(0.0, now - track.last_confirmed)
    confidence = max(0.0, 1.0 - since_confirmed / _CONFIDENCE_DECAY_SECONDS)
    return Prediction(box=warped, confidence=confidence)

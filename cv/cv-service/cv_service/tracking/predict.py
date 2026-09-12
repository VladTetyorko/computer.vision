"""Constant-velocity prediction for a track between observations.

TRACKING-V2-PLAN §3.2, review finding C1. `Track` computes its own
`velocity_x`/`velocity_y` every frame and, before this module existed,
nothing ever read them: a lost tracker or an invalid box made the session
hold the box exactly where it was last seen, so during an occlusion the box
sat still while the object kept moving -- and the verify pass that followed
then measured IoU against that stale position and failed to re-anchor. The
freeze was what caused the very re-anchor failure it was trying to survive.

**No `transform` argument here (TRACKING-V2-PLAN wave C2).** An earlier
version of this function took one and warped the extrapolated box by it,
which looked right in isolation but undercorrected badly in practice: a
track stalled for N frames only ever picked up the CURRENT frame's
single-frame delta at the moment something finally called `predict()`,
never the N frames of camera motion that accumulated while nothing read it
-- worst exactly when compensation matters most (a stalled tracker, nothing
but prediction left). The fix moved the warp to `TrackBook.warp()`, called
once per frame in `session.py`'s `process()` for EVERY live track, whether
or not anything reads it that frame, so N stalled frames accumulate N
single-frame warps instead of one applied once. By the time this function
runs, `track.box`/`velocity_*` are already expressed in the CURRENT frame's
coordinates -- there is nothing left for `predict()` itself to warp.

Pure stdlib -- `Box` is the same vocabulary `track.py`/`session.py` already
share, so importing this module costs nothing extra on a build with no `cv`
extra at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from cv_service.tracking.engines.base import Box

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
    #: How far `box` was actually extrapolated, in seconds -- AFTER the
    #: `_MAX_EXTRAPOLATION_SECONDS` clamp below, so it is the interval this
    #: prediction really used and not the gap it was asked to cover. Reported
    #: on the wire as `ObjectState.kinematics.horizon_ms` (CV-ORCHESTRATION
    #: wave W1): a predicted box means something very different at 40ms than
    #: at the 2s ceiling, and a consumer cannot tell the two apart from the
    #: box alone. Carried here rather than recomputed by the reader because
    #: the clamp is this module's rule and must not be spelled twice.
    horizon_seconds: float


def predict(track: "Track", now: float) -> Prediction:
    """Extrapolate `track`'s box to `now` at constant velocity.

    Runs from the track's own last position/velocity -- already warped for
    every frame of camera motion since (`TrackBook.warp()`, see the module
    docstring) -- clamped against `_MAX_EXTRAPOLATION_SECONDS` so a long gap
    cannot fling the box off-frame on one old estimate.
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

    since_confirmed = max(0.0, now - track.last_confirmed)
    confidence = max(0.0, 1.0 - since_confirmed / _CONFIDENCE_DECAY_SECONDS)
    return Prediction(box=predicted, confidence=confidence, horizon_seconds=elapsed)

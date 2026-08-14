"""ORU -- Observation-Centric Re-Update (TRACKING-V3-PLAN §4.2, wave V3).

When a track is re-anchored after a gap, `track.py`'s pre-V3 behaviour
accepted whatever the constant-velocity estimator (`predict.py`) had
drifted the box to, measured a "velocity" from THAT drifted position to the
new detection over just the last frame's `elapsed` (not the true gap), and
blended it in. Two things are wrong with that, not one: the "before"
position is the estimate's own accumulated error, not evidence, and the
time base is too short by exactly the length of the gap it is supposed to
be describing. A track whose real motion reversed while hidden (`nonlinear`)
or that coasted through a long occlusion (`long_occlusion`) inherits that
error as its NEW velocity, which then corrupts every prediction downstream.

ORU deletes the error instead of inheriting it, by rebuilding the gap from
the two REAL observations that bracket it and re-deriving velocity from
that virtual trajectory:

    z̃(t) = z(t1) + (t - t1)/(t2 - t1) * (z(t2) - z(t1))     for t1 < t < t2

`t1` is the last REAL observation before the gap (`ObservationRing.before`);
`t2` is the detection that just re-anchored it. Source: OC-SORT (arXiv:
2203.14360, CVPR'23) -- its own ablation is why this module does not reach
for anything richer than a straight line: "linear interpolation beat
Gaussian Process Regression" for gap reconstruction, because online data is
too sparse for a richer local model to pay for itself (decision E1, plan
§2). The interpolant is deliberately the dumbest thing that works.

## §4.1b, closed: which frame is z(t1) expressed in?

`TrackBook.warp()` moves every live track's `box` into the CURRENT frame
every frame; ring entries are not warped, and stay in whatever frame they
were captured in (`history.py`'s own module docstring). Interpolating
between `z(t1)` (an old frame) and `z(t2)` (now) without correcting for
that mixes coordinate systems, with error proportional to how much the
camera moved during the gap -- `docs/conclusions/CV-RATE-BUDGET.md` §2 puts
ego-motion at ~32 px/frame against ~4 px/frame of target motion on a
yawing drone, so naively implemented this reconstructs the gap in the
wrong space precisely when the gap matters most.

**Measured choice: compose the accumulated warp on read, via
`Track.history_transform` (`track.py`).** `TrackBook.warp()` composes each
frame's `Transform` into it (`track.history_transform = track.
history_transform.compose(transform)`) for every live track, every frame,
and `track.py`'s `_observe` resets it to `IDENTITY` exactly when the ring
actually admits a new entry -- so at ANY later moment it holds precisely
"the frame that entry was captured in -> now". That is O(1) extra work per
live track per frame (one `Transform.compose`, six float multiply-adds), not
O(ring capacity): the two other candidates the plan named were rejected on
measurement, not preference --

  * *Warp every ring entry every frame* was rejected without being built:
    `ObservationRing` is out of this wave's file scope (`history.py` is not
    listed), and even if it were not, this option's own cost -- capacity x
    live tracks, every frame, whether or not ORU ever runs that stream --
    is stated in the plan's own §4.1b table as the reason to prefer
    "compose on read".
  * *Accept the error, bound the gap* was measured directly against
    `pan_occlusion`'s own construction (world-static target, pure camera
    pan, `PAN_OCCLUSION_CAMERA_VELOCITY=0.02`/frame over a
    `PAN_OCCLUSION_GAP_FRAMES=25` gap): replaying that exact pan through
    `TrackBook.warp()` and then calling `reupdate()` with `history_
    transform` left at `IDENTITY` (simulating "accept the error") versus
    left to accumulate normally reproduces a **0.5-normalized (160 px on a
    320 px frame) reconstruction error with the coordinate mix left
    uncorrected, against 0.0 with it corrected** -- exactly the full pan
    displacement, because an uncorrected `z(t1)` is off by precisely how
    far the world moved under the camera during the gap. `nonlinear` (zero
    ego-motion by construction) is the control: both choices agree there,
    since `Transform.compose` on an all-`IDENTITY` sequence is `IDENTITY`
    regardless. Reproduced as `tests/tracking/test_reupdate.py::
    test_the_bracket_is_warped_by_history_transform_before_interpolating`
    and its `..._an_uncorrected_bracket_would_have_reported_the_cameras_
    own_motion` control. "Accept the error" is exactly wrong on the one
    scenario engineered to test it.

No `Transform.inverse()` is needed anywhere in this: `history_transform`
only ever accumulates FORWARD (the next frame's own delta, composed onto
what came before), so there is nothing to invert -- which also matters
because `engines/base.py` (where `Transform` lives) is outside this wave's
file scope.

## Why velocity, not "position, velocity and descriptor" verbatim

§4.2's sketch says ORU re-derives "the track's position, velocity and
descriptor" along the virtual trajectory. Position needs no separate work:
`track.py`'s `_observe` already sets `track.box = observation.box`
unconditionally right after this runs, which is `z̃(t2)` by construction
(the interpolant's value AT `t2` is exactly the real endpoint). Descriptor
likewise needs no separate mechanism -- `session.py`'s existing
`observe_descriptor` EMA-blends toward the new detection's descriptor the
same way it always has, and there is no FICTITIOUS descriptor evidence
partway through the gap to interpolate between (an appearance histogram has
no "halfway" between two crops the way a box coordinate does). Velocity is
the one quantity that is genuinely stale MID-air at the moment of re-anchor
and needs the caller to install `reupdate()`'s own answer instead of
`track.py`'s ordinary single-frame measurement -- which is why `Reupdate`
below carries `velocity_x`/`velocity_y`, a field the plan's literal
`@dataclass` sketch (§4.2) omitted despite describing exactly the value a
caller needs to apply the correction. Recorded here as the one place §4.2
turned out underspecified, not silently patched over.

Pure stdlib (`math`, `dataclasses`) -- this module carries the wave's whole
accuracy win and must run at capability level L1 (invariant P8), the same
ARMv6-companion constraint `history.py`/`predict.py` already meet.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional

from cv_service.tracking.engines.base import Observation

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cv_service.tracking.history import ObservationRing
    from cv_service.tracking.track import Track


@dataclass(frozen=True)
class Reupdate:
    """The result of one successful gap reconstruction -- diagnostics plus
    the one value a caller actually needs to apply.

    `steps` is `track.misses` at call time, floored at 1: the number of
    consecutive failed confirmations this gap represents (a frame count in
    ASSOCIATE, where the detector runs every received frame; a failed-
    verify-pass count in FOLLOW, where it runs on cadence -- `track.py`'s
    own module docstring draws the same distinction for `max_age_frames`).
    Diagnostic only -- nothing downstream keys behaviour off it.

    `drift_before` is `|track.box (the estimator's own, possibly drifted,
    current position) - z̃(t2)|` at re-anchor, normalized -- exactly the
    error ORU deletes, reported so the harness (and this module's own
    tests) can see how much it was. `drift_after` is always `0.0`: `z̃(t2)`
    IS the real detection `track.box` gets set to immediately after this
    runs, by construction, not by further computation here.
    """

    steps: int
    gap_millis: int
    drift_before: float
    drift_after: float
    velocity_x: float
    velocity_y: float


def reupdate(
    track: "Track",
    ring: "ObservationRing",
    observation: Observation,
    now: float,
    *,
    max_gap_millis: int,
) -> Optional[Reupdate]:
    """Rebuild the gap ending at `now` from the two real observations that
    bracket it, and return the corrected velocity -- or `None` when there is
    no bracketing pair, the gap is not positive, or it exceeds
    `max_gap_millis` (too long to reconstruct honestly; `memory.py`'s
    dormant-gallery recovery is what serves that case instead, and it does
    not consult this ring at all).

    `ring` is taken as an explicit parameter rather than read off `track`
    (even though every real caller passes `track.history`) so this stays
    testable against a bare `ObservationRing` with no `Track` to construct
    -- the same reason `history.py`'s own module docstring gives for
    keeping `ObservationRing` a separate structure from `Track` in the
    first place. **This is also the ONLY source of `t1`** -- never
    `track.box`/`track.velocity_*`, which are the estimator's own drifted
    state, the exact error this function exists to correct (TRACKING-V3-
    PLAN's own instruction, restated here because it is the invariant a
    future edit to this function must not quietly break).
    """
    if max_gap_millis <= 0:
        return None
    bracket = ring.before(now)
    if bracket is None:
        return None
    gap_seconds = now - bracket.timestamp
    if gap_seconds <= 0.0:
        return None
    gap_millis = int(round(gap_seconds * 1000.0))
    if gap_millis > max_gap_millis:
        return None

    # §4.1b: `bracket.observation.box` is expressed in ITS OWN capture
    # frame -- warp it into `now`'s frame (the same frame `observation.box`
    # and `track.box` are already in) via the track's own accumulated
    # ego-motion transform since that entry was recorded. See this module's
    # docstring for the measurement that settled this against the two other
    # candidates.
    t1_box = track.history_transform.apply_box(bracket.observation.box)
    t1_cx, t1_cy = t1_box.center
    t2_cx, t2_cy = observation.box.center

    drift_before = _distance(track.box.center, (t2_cx, t2_cy))
    velocity_x = (t2_cx - t1_cx) / gap_seconds
    velocity_y = (t2_cy - t1_cy) / gap_seconds

    return Reupdate(
        steps=max(1, track.misses),
        gap_millis=gap_millis,
        drift_before=drift_before,
        drift_after=0.0,
        velocity_x=velocity_x,
        velocity_y=velocity_y,
    )


def _distance(a: "tuple[float, float]", b: "tuple[float, float]") -> float:
    return math.hypot(a[0] - b[0], a[1] - b[1])


__all__ = ["Reupdate", "reupdate"]

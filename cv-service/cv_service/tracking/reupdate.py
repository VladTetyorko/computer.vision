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

## Wave V6: `late_correction` -- the SAME math, run per-detection

§4.5 asks for "ORU applied per-detection rather than only post-occlusion":
`session.py`'s own clock (pull mode's `capture_skew_millis`, `pull/clock.py`)
tells it a just-arrived detection describes a frame that is already
`detection_lag_millis` old by the time it lands -- an offboard detector is a
late detector by construction (§5.2, "L1 RELAY"), not an occasional gap.
Booking that box against `now` as though it were fresh is the exact same
lag-as-drift error `reupdate()` above deletes after a miss; the only
difference is WHEN it fires.

`late_correction` does not duplicate `reupdate()`'s interpolant -- it CALLS
it, at `now - lag_seconds` instead of `now`, to get an honest velocity from
the two REAL observations that bracket the detection's OWN capture instant,
then projects the detection's box forward by that SAME `lag_seconds` using
that velocity. `z̃(t2)` (`reupdate()`'s `t2`, here the capture instant) is
computed once and never re-derived; only the forward projection past it is
new arithmetic, and it is the same constant-velocity extrapolation
`predict.py` already performs, just inlined on a bare `Box` instead of a
`Track` (this module has no reason to import `predict.py` for six lines of
`x + v*t`).

Pure stdlib (`math`, `dataclasses`) -- this module carries the wave's whole
accuracy win and must run at capability level L1 (invariant P8), the same
ARMv6-companion constraint `history.py`/`predict.py` already meet.

## 2026-08-14 repair: the ring's two clocks, and a floor on this one

`late_correction`'s own reasoning above already says it plainly: `reupdate()`
needs `t1`'s timestamp and `t2`'s (`captured_at`, here) to be honest capture
instants, in the SAME time base, or the elapsed time between them is wrong.
It was: `ObservationRing.record()` (`history.py`) filed every entry under
its ARRIVAL time regardless of caller, which this function's own `t1`
(`ring.before(now)`) silently inherited -- correct only when `t1` was never
late, which stopped being true the moment `late_correction` started calling
this function with a genuinely lagged `t2`. `history.py`'s `TimedObservation`
docstring carries the full account and the fix (a `captured_at` parameter
threaded down from `session.py` through `TrackBook.apply()`); this module's
own share of the repair is `_MIN_RECONSTRUCTION_GAP_SECONDS` below -- a
floor on the denominator itself, since aligning the clocks removes this
defect's specific MECHANISM without removing the general risk any two
independently-supplied floats can be closer together than either caller
intended.

## 2026-08-14 repair, part 2: the bracket must be plausible, not merely timed

Everything above tests whether the two real observations bracketing a gap
are close enough in TIME to bridge. Nothing tested whether they are
plausibly the SAME OBJECT -- and `docs/conclusions/TRACKING-BENCHMARK-
RESULTS.md` §4 measured what that omission costs on real footage: across 21
MOT17 scene/detector pairs, turning ORU on made IDSW WORSE in 15 of them
(+470 net, worst case +277 on `MOT17-04-DPM`), and ORU's own implausible-
velocity count rose in 19 of 21 (`MOT17-04-DPM` 157 -> 441, an order of
magnitude). On the synthetic scenarios this module was built and measured
against (`nonlinear`, `long_occlusion`, `latency`), the two bracketing
observations are always the same object by construction, so this defect
never showed there -- it took real crowds and a real weak detector (DPM) to
surface it.

The fix is a second, independent gate on the SAME velocity this function
already computes: `max_velocity_per_second` (both `reupdate()` and
`late_correction()`, since the latter calls the former for its velocity
rather than re-deriving it). When the implied `|velocity_x|` or
`|velocity_y|` exceeds it, the function refuses the reconstruction --
returns `None`, the SAME "no honest answer" contract `max_gap_millis`
already established, never a clamp and never a partial application. A
bracket that implies an impossible velocity is not evidence of a fast
object; a target crossing the whole frame more than five times a second is
not a real object this platform's own worst-case documented motion (a 90
deg/s search yaw, 1.5 frame-widths/sec -- `docs/conclusions/CV-RATE-
BUDGET.md` §2) comes anywhere near -- it is evidence the bracket itself is
built from two different objects, and there is no salvageable velocity to
extract from a wrong bracket, only a less obviously wrong one. The bound's
own value and derivation live in `cv_service.config.
DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND` (invariant P4: this module
takes the resolved number as a plain argument and has no constant of its
own to keep in sync); `TrackingParams.reupdate_max_velocity_per_second` is
how it reaches both callers (`track.py`'s `_observe`, `session.py`'s
`_late_corrected_box`) via `params.resolve()`.

A refused reconstruction is a degradation, not an error (invariant P5): it
is logged once per process (`_warn_implausible_velocity_once`, the same
`global`-flag "log once" idiom `cv_service.training.trainer._warn_cpu_once`
already uses), never raised, and never spammed per frame -- a stream that
keeps producing implausible brackets keeps silently falling back to the
ordinary single-frame velocity measurement `track.py`'s `_observe` already
uses whenever `reupdate()` returns `None` for any other reason, which is
exactly what makes this change reversible: `max_velocity_per_second <= 0`
(the default both functions take when a caller passes nothing) disables the
guard outright, reproducing every byte of this module's pre-repair
behaviour (invariant P7) -- proven in `tests/tracking/test_reupdate.py`.

## 2026-08-15 density gate: a plausible velocity is not a trustworthy bracket

`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b re-ran the velocity
guard above across all 21 MOT17 ORU pairs and split the result by scene
density: at 10 or more detections/frame, ORU improved **0 of 7** scenes
(net **+320** IDSW); below that line it improved 5 of 14 (net +57). ORU has
never once helped a crowded scene. The velocity guard tests whether a
bracket's IMPLIED motion is physically possible; it has no opinion on
whether the two observations it interpolates between were ever the SAME
OBJECT to begin with, and bracket ambiguity rises with crowding by
construction -- more live targets means more candidates a weak detector's
next real observation could plausibly (if wrongly) continue.

The fix is a SECOND, independent gate, refusing the same way the velocity
guard does (`None`, never clamped, same call sites): when the scene is too
crowded for the bracket to be trusted, do not attempt it.

**What "crowded" reads, and why it is a proxy, not the measured
quantity.** §4b measured density as detections/frame. `reupdate()`/
`late_correction()` are pure functions of `track`, `ring` and a handful of
plain values -- neither carries a reference to `TrackBook` or to the raw
per-frame detection list, so neither can compute "detections this frame"
from what it is handed. Getting that number here would mean threading a
new argument through `TrackBook.apply()`'s callers in `session.py`: two
different ASSOCIATE branches (`cost` and `bytetrack`) plus FOLLOW's verify
and extras paths, a file whose own module docstring already records it
overran its ~80-line budget. Worse, some of those callers have no such
number to offer -- a tracker-only FOLLOW frame runs no detector pass at
all, so "detections this frame" is not merely hard to reach there, it does
not exist.

The number of LIVE TRACKS in the book at the moment of re-anchor is
reachable at BOTH real call sites with no threading at all: `track.py`'s
`_observe` is itself a `TrackBook` method (`self._tracks`), and `session.
py`'s `_late_corrected_box` already holds `self._book` for other reasons
(`self._book.tracks`). It rises and falls with scene crowding the same
direction detections/frame does -- more objects in view tends to mean both
more detections AND more live tracks -- but it is not the same number (a
missed detection shrinks one and not the other; a coasting/LOST track
still counts toward the book's `self._tracks` for a while after the
detector stops confirming it). Stated plainly: this is a PROXY for what
§4b measured, chosen because it is the one signal actually in reach at
both call sites, not because it is believed identical to detections/frame.

Callers pass it in as `live_track_count`; the ceiling itself is
`max_track_count` (`cv_service.config.DEFAULT_TRACK_REUPDATE_MAX_TRACK_
COUNT`, invariant P4 -- this module takes the resolved number as a plain
argument, same division of labour `max_velocity_per_second` already has).
`max_track_count <= 0` disables the gate entirely, reproducing this
module's pre-gate behaviour exactly (invariant P7) -- the SAME `<=0`-is-
disabled shape every other ceiling in this module already uses. It ships
DEFAULTED TO `0` (disabled): unlike the velocity bound, which was derived
from this platform's own documented worst-case motion, nobody has yet
swept a live-track ceiling against the real MOT17 matrix to know what
count actually separates "trustworthy" from "not" -- see `config.py`'s own
comment on the default constant for what sets it instead of a guess.

Checked as its own step, independent of and in addition to the velocity
guard -- a crowded frame is refused whether or not the implied velocity
would have passed, and a sparse frame's implausible velocity is still
refused whether or not the book happens to be small. Logged once per
process (`_warn_high_density_once`, the SAME `global`-flag idiom
`_warn_implausible_velocity_once` above uses), never raised, never spammed
per frame (invariant P5).
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional

from cv_service.tracking.engines.base import Box, Observation

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cv_service.tracking.history import ObservationRing
    from cv_service.tracking.track import Track

LOGGER = logging.getLogger("cv_service.tracking.reupdate")

# Log-once flag for the implausible-velocity guard below -- same module-
# level "warn once, never per-frame" idiom `cv_service.training.trainer.
# _warn_cpu_once`'s own `_cpu_warned` already uses for a degradation that is
# a property of the DEPLOYMENT (this host has no CUDA / this bracket keeps
# producing impossible velocities), not of any one call, so a per-`Track` or
# per-session flag would either miss repeats on other tracks or need state
# threaded in that this otherwise-pure function does not carry (P5: log once,
# never raise, never spam per frame).
_implausible_velocity_logged = False

# Log-once flag for the density gate below (2026-08-15) -- same idiom and
# same reasoning as `_implausible_velocity_logged` immediately above, kept
# as its own separate flag (not reused) so a deployment that trips both
# guards logs each once, rather than the second guard's first occurrence
# silently going unreported because the first already flipped a shared
# flag.
_high_density_logged = False

# The shortest elapsed time this module will ever divide by to reconstruct a
# velocity -- insurance against the SHAPE of the 2026-08-14 divergence
# defect (`history.py`'s own docstring on `TimedObservation`), not only its
# one known cause. Aligning `ObservationRing`'s two clocks (done, this same
# repair) removes the specific mechanism that produced a near-zero
# denominator; it does not remove the possibility of one -- `bracket.
# timestamp` and `now` are two independently-supplied floats this function
# does not control the provenance of, and a future caller (or a bug in one)
# handing it a genuinely tiny-but-positive gap would still divide by it
# without this floor.
#
# Sized against the fastest FRAME rate this platform's own measurement doc
# ever attributes to a real camera, not a round number: `docs/conclusions/
# CV-RATE-BUDGET.md`'s "Hold" row puts raw video at "25-30 Hz", the ceiling
# every other rate in that table (10 fps ASSOCIATE, 15 fps FOLLOW verify,
# the sampler's own 9.998 fps measured result) sits under. `ObservationRing`
# only ever holds `SOURCE_DETECTOR` entries (`history.py`'s own module
# docstring), which arrive no faster than the DETECTOR's cadence -- itself
# always looser than the raw 30 Hz ceiling in every configuration this
# service ships. So no two REAL bracketing entries this function is ever
# handed in production can be genuinely `1/30`s apart or closer; a gap
# smaller than that is not a fast frame, it is two clocks disagreeing about
# when something happened, and reconstructing a velocity from it would
# report false precision instead of refusing to answer.
_MIN_RECONSTRUCTION_GAP_SECONDS = 1.0 / 30.0


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
    max_velocity_per_second: float = 0.0,
    max_track_count: int = 0,
    live_track_count: int = 0,
) -> Optional[Reupdate]:
    """Rebuild the gap ending at `now` from the two real observations that
    bracket it, and return the corrected velocity -- or `None` when there is
    no bracketing pair, the gap is not positive, it exceeds `max_gap_millis`
    (too long to reconstruct honestly; `memory.py`'s dormant-gallery
    recovery is what serves that case instead, and it does not consult this
    ring at all), the implied velocity is not plausible, or the scene is too
    crowded to trust the bracket at all (see both guards below).

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

    `max_velocity_per_second` (2026-08-14 repair, part 2 --
    `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4/§8): the bracket
    above only tests that the two real observations are close enough in
    TIME to bridge; it never tests that they are plausibly the SAME OBJECT.
    On synthetic scenarios they always are, and ORU wins; on MOT17 -- real
    crowds, real weak detectors -- they frequently are not, and the
    reconstruction produces a velocity that is physically impossible (peak
    measured 12.9 normalized frame-widths/second under the `cost` engine),
    which then propagates forward as a prediction and costs an identity.
    Refusing outright (never clamping, never partially applying) is
    deliberate: an implausible reconstruction is evidence the bracket
    itself is wrong -- the two observations are probably two different
    objects -- and a wrong bracket has no salvageable answer, only a less
    obviously wrong one. `<= 0` (the default) disables this check entirely,
    reproducing the pre-repair behaviour exactly (invariant P7) -- the same
    "off switch" shape `max_gap_millis <= 0` already gives the bracket
    check above. `cv_service/tracking/params.py`'s
    `TrackingParams.reupdate_max_velocity_per_second` is the one place a
    deployment default for this lives (invariant P4); this function takes
    the resolved number as a plain argument and has no opinion on where it
    came from, same division of labour `max_gap_millis` already has.

    `max_track_count`/`live_track_count` (2026-08-15 density gate --
    `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b/§8): independent of
    the velocity guard above, and checked before any bracket arithmetic runs
    -- a bracket built in a crowded scene is refused whether or not its
    implied velocity would have passed. `live_track_count` is the CALLER's
    own count of live tracks in the book right now (this function has no
    book reference to compute it from); `max_track_count` is the deployment
    ceiling above which that count is too crowded to trust. `<= 0` (the
    default) disables this gate entirely, the same off-switch shape every
    other ceiling here uses (invariant P7). See this module's own docstring
    for why track count, not detections/frame (what §4b actually measured),
    is what reaches this function -- stated there as a PROXY, not assumed to
    be the same signal.
    """
    if max_gap_millis <= 0:
        return None
    if max_track_count > 0 and live_track_count > max_track_count:
        _warn_high_density_once(live_track_count, max_track_count)
        return None
    bracket = ring.before(now)
    if bracket is None:
        return None
    gap_seconds = now - bracket.timestamp
    # Subsumes the old `<= 0.0` check (the floor is positive): a gap this
    # small is either genuinely non-positive or too small to trust -- see
    # `_MIN_RECONSTRUCTION_GAP_SECONDS`'s own module-level comment for why
    # dividing by it would fabricate precision the evidence cannot support,
    # not merely round awkwardly.
    if gap_seconds < _MIN_RECONSTRUCTION_GAP_SECONDS:
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

    # The plausibility guard -- see this function's own docstring on
    # `max_velocity_per_second` for why refusing outright, not clamping, is
    # the correct response to a bracket whose implied motion is impossible.
    if max_velocity_per_second > 0.0 and (
        abs(velocity_x) > max_velocity_per_second or abs(velocity_y) > max_velocity_per_second
    ):
        _warn_implausible_velocity_once(velocity_x, velocity_y, max_velocity_per_second)
        return None

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


def _warn_implausible_velocity_once(velocity_x: float, velocity_y: float, bound: float) -> None:
    """Log the one-time "ORU refused a reconstruction" note, if applicable
    (P5: a degradation is logged once, never raised, never spammed
    per-frame -- same `global`-flag idiom `cv_service.training.trainer.
    _warn_cpu_once` already uses for its own one-shot degradation log)."""
    global _implausible_velocity_logged
    if _implausible_velocity_logged:
        return
    _implausible_velocity_logged = True
    LOGGER.warning(
        "tracking: ORU refused a reconstruction with an implausible velocity "
        "(x=%.3f, y=%.3f, bound=%.3f frame-widths/heights per second) -- the "
        "bracket is probably two different objects, not one (this warning "
        "logs once per process)",
        velocity_x,
        velocity_y,
        bound,
    )


def _warn_high_density_once(live_track_count: int, bound: int) -> None:
    """Log the one-time "ORU refused a reconstruction: too crowded" note, if
    applicable (P5: a degradation is logged once, never raised, never
    spammed per-frame -- same `global`-flag idiom `_warn_implausible_
    velocity_once` above already uses, kept as its own flag/message so this
    guard's first occurrence is reported even when the velocity guard
    already logged once for a different track)."""
    global _high_density_logged
    if _high_density_logged:
        return
    _high_density_logged = True
    LOGGER.warning(
        "tracking: ORU refused a reconstruction -- the book held %d live "
        "track(s), above the %d-track density gate (a crowded scene's "
        "bracket is not trustworthy, docs/conclusions/TRACKING-BENCHMARK-"
        "RESULTS.md §4b; this warning logs once per process)",
        live_track_count,
        bound,
    )


def late_correction(
    track: "Track",
    ring: "ObservationRing",
    box: Box,
    now: float,
    lag_seconds: float,
    *,
    max_gap_millis: int,
    max_velocity_per_second: float = 0.0,
    max_track_count: int = 0,
    live_track_count: int = 0,
) -> Optional[Box]:
    """`box` re-propagated to `now`, when this stream measured a positive
    `lag_seconds` for it (TRACKING-V3-PLAN §4.5, wave V6) -- `None` when
    there is nothing honest to correct with, in which case the caller keeps
    `box` as given.

    Binds `box` -- this frame's raw, just-arrived detection -- to its own
    capture instant (`now - lag_seconds`) rather than `now`, and asks
    `reupdate()` to re-derive velocity from the two REAL observations that
    bracket THAT instant: the ring's last entry strictly before it, and
    `box` itself. That velocity, not `track.velocity_x`/`velocity_y` (the
    estimator's own possibly-stale state -- `reupdate()`'s own docstring is
    explicit that this is the exact error the whole mechanism exists to
    correct), then projects `box` forward by the SAME `lag_seconds`,
    landing on this function's best estimate of where the object is at
    `now`.

    `None` -- exactly `reupdate()`'s own contract, reused rather than
    re-decided here -- when `lag_seconds` is not positive, `ring` has no
    real observation before the capture instant (a brand-new track has
    nothing to bracket against yet), the resulting gap exceeds
    `max_gap_millis`, the reconstructed velocity fails `reupdate()`'s own
    `max_velocity_per_second` plausibility guard (2026-08-14 repair, part 2
    -- this function CALLS `reupdate()` for its velocity, so a bracket that
    is probably two different objects is exactly as wrong here as it is
    post-occlusion, and gets exactly the same refusal, not a second
    decision), or (2026-08-15 density gate) `live_track_count` exceeds
    `max_track_count` -- same reused-not-duplicated reasoning: a scene too
    crowded to trust a bracket in is too crowded to trust one here either.
    All three ceilings are the SAME ones that bound post-occlusion ORU
    (`TrackingParams.reupdate_max_gap_millis` /
    `reupdate_max_velocity_per_second` / `reupdate_max_track_count`) --
    reused, not duplicated: a gap, a velocity or a density too implausible
    to trust for one purpose is too implausible for the other, and `<= 0`
    is therefore invariant P7's off switch for this correction too, with no
    second knob needed just to disable any of them. Callers must treat
    `None` as "keep the raw box" and never fabricate a correction from
    nothing (**P5**).

    **Known simplification, stated rather than hidden.** The bracket
    `reupdate()` reads is warped into `box`'s frame by `track.
    history_transform`, which accumulates ego-motion from the ring's last
    entry up to `now` (`TrackBook.warp()`), not up to the capture instant
    `lag_seconds` short of it -- `history.py` keeps one running total, not a
    per-frame log a caller could warp to an arbitrary earlier instant. On a
    stream with ego-motion DURING the lag window, this over-warps the
    bracket by that remainder. `latency`'s own harness scenario (the one
    this wave is measured against) has no ego-motion at all, so this never
    bites the case in front of it; a stream with both a moving camera and a
    lagged detector is a real refinement, not one this wave's file scope
    (`history.py`'s ring has no timestamped transform log to add one) reaches.
    """
    if lag_seconds <= 0.0:
        return None
    captured_at = now - lag_seconds
    # `reupdate()` only reads `.box` off this -- key/label/confidence carry
    # no meaning for gap reconstruction, so `track`'s own are harmless
    # stand-ins rather than plumbing the real detection's through for no
    # arithmetic reason.
    stand_in = Observation(key=track.key, box=box, label=track.label, confidence=track.confidence)
    reconstruction = reupdate(
        track,
        ring,
        stand_in,
        captured_at,
        max_gap_millis=max_gap_millis,
        max_velocity_per_second=max_velocity_per_second,
        max_track_count=max_track_count,
        live_track_count=live_track_count,
    )
    if reconstruction is None:
        return None
    center_x, center_y = box.center
    projected_x = center_x + reconstruction.velocity_x * lag_seconds
    projected_y = center_y + reconstruction.velocity_y * lag_seconds
    return Box(
        projected_x - box.width / 2.0,
        projected_y - box.height / 2.0,
        box.width,
        box.height,
    )


__all__ = ["Reupdate", "reupdate", "late_correction"]

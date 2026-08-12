"""`pose` -- ego-motion compensation from camera attitude, with no pixels at all.

`docs/plans/active/TRACKING-V2-PLAN.md` §3.1, `docs/conclusions/TRACKING-REVIEW.md` §4.5.

**The defect this closes.** Association is IoU-based, and IoU is computed in
image space. When the *camera* yaws, every box in the frame moves; nothing
told the tracker that, so the motion is charged to the objects as if they had
moved, and a target that shifts further than its own width between two
sampled frames has zero overlap with itself and is reborn under a new id. On
a drone that is the normal case, not an edge case: at 10 fps a 30 deg/s slew
moves the image by several box widths per sampled frame.

**Why this engine exists beside `flow`.** `flow` estimates the same transform
from pixels and works anywhere, including a client that sends no telemetry.
This one is the drone-native answer: the platform already has synchronised
attitude and video on the same box, so the camera's motion is *known* rather
than inferred, exactly and for free, with no features to lose and nothing to
be confused by a moving subject that fills the frame. It is also the only
compensator available to a build with no OpenCV, which is why this module is
deliberately on the pure-stdlib side of the package rule -- it is
trigonometry, not image processing.

**The projection, stated because a sign error here is invisible.** For a
pinhole camera the angle from boresight of a point at normalized-device
coordinate `u` in `[-1, 1]` is `atan(u * tan(fov / 2))`. Differentiating at
the centre gives the first-order image shift for a small attitude change:

    du = -d_yaw / tan(hfov / 2)          (camera yaws right -> scene moves left)
    dv = +d_pitch / tan(vfov / 2)        (camera pitches up  -> scene moves down,
                                          because normalized y runs downward)

and roll rotates the image about its centre by `-d_roll`, in a space scaled
by the frame aspect so the rotation stays a similarity rather than a shear.
The first-order (affine) form is used rather than the exact `tan` map because
`Transform` is affine by contract and because the error is second-order in
the attitude step -- negligible for the per-frame deltas this sees, and
self-correcting at the next detector pass regardless.

Pure stdlib.
"""

from __future__ import annotations

import logging
import math
from typing import Any, Optional

from cv_service.tracking.engines.base import IDENTITY, CameraPose, Transform

LOGGER = logging.getLogger("cv_service.tracking.engines.pose_gmc")

ENGINE_ID = "pose"

# Attitude steps larger than this are not a camera movement -- they are a
# telemetry glitch, a reconnect, or a wrapped angle that survived
# normalization. Compensating for one would fling every box off the frame, so
# the step is refused and the frame degrades to no compensation, which is
# exactly today's behavior.
_MAX_STEP_DEGREES = 45.0

# A pose whose timestamp is this far from the previous one is not a step in
# the same continuous motion; the reference is restarted instead. Applies
# only when the client actually stamps poses (0 = unstamped, see `_elapsed`).
_MAX_POSE_GAP_MILLIS = 2000

# Below this the attitude change is indistinguishable from telemetry noise,
# and returning IDENTITY exactly is what lets `Transform.identity` stay an
# exact comparison with no epsilon anywhere downstream.
_MIN_STEP_DEGREES = 0.01


class PoseMotionCompensator:
    """`MotionCompensator` over camera attitude deltas.

    One instance per stream, like every other engine: it holds the previous
    pose, which is inherently per-stream state.
    """

    engine_id = ENGINE_ID

    def __init__(self, *, aspect_ratio: float = 0.0) -> None:
        self._previous: Optional[CameraPose] = None
        # Only used for roll. Defaults to "derive from the two FOVs", which is
        # what a correctly-filled CameraPose allows; an explicit value is for
        # a caller that knows the frame shape and sends only `hfov`.
        self._aspect_ratio = aspect_ratio

    def available(self, pose: CameraPose) -> bool:
        """True only when an attitude delta can become a pixel shift.

        Checked before the frame is decoded, so a client that never sends a
        pose costs one attribute read per frame rather than a failed estimate
        per frame.
        """
        return pose is not None and pose.known

    def estimate(self, frame: Any, pose: CameraPose) -> Transform:
        """Previous frame -> current frame. `frame` is ignored, deliberately.

        Returns `IDENTITY` for every case where the answer is not known
        rather than guessing: no pose, first pose of a stream, a stale pose,
        an implausible step, or a step below telemetry noise.
        """
        if pose is None or not pose.known:
            self._previous = None
            return IDENTITY

        previous = self._previous
        self._previous = pose
        if previous is None:
            return IDENTITY
        if not _within_gap(previous, pose):
            return IDENTITY

        d_yaw = _delta_degrees(previous.yaw_degrees, pose.yaw_degrees)
        d_pitch = _delta_degrees(previous.pitch_degrees, pose.pitch_degrees)
        d_roll = _delta_degrees(previous.roll_degrees, pose.roll_degrees)

        if max(abs(d_yaw), abs(d_pitch), abs(d_roll)) > _MAX_STEP_DEGREES:
            LOGGER.debug(
                "pose: refusing an implausible attitude step (yaw=%.1f pitch=%.1f roll=%.1f)",
                d_yaw,
                d_pitch,
                d_roll,
            )
            return IDENTITY
        if max(abs(d_yaw), abs(d_pitch), abs(d_roll)) < _MIN_STEP_DEGREES:
            return IDENTITY

        hfov = pose.hfov_degrees
        vfov = pose.vfov_degrees if pose.vfov_degrees > 0.0 else hfov
        aspect = self._aspect(hfov, vfov)

        # Yaw/pitch are a pure translation to first order; roll is a rotation
        # about the frame centre. Composed rather than merged by hand so the
        # two derivations stay separately checkable.
        translation = Transform(
            c=-_normalized_shift(d_yaw, hfov),
            f=+_normalized_shift(d_pitch, vfov),
        )
        if abs(d_roll) < _MIN_STEP_DEGREES:
            return translation
        return translation.compose(_roll_transform(d_roll, aspect))

    def reset(self) -> None:
        self._previous = None

    def _aspect(self, hfov: float, vfov: float) -> float:
        if self._aspect_ratio > 0.0:
            return self._aspect_ratio
        half_v = math.tan(math.radians(vfov) / 2.0)
        if half_v <= 0.0:
            return 1.0
        return math.tan(math.radians(hfov) / 2.0) / half_v


def _normalized_shift(delta_degrees: float, fov_degrees: float) -> float:
    """First-order image shift, in normalized [0, 1] units, for one axis.

    The factor of two converts the normalized-device shift (on [-1, 1], where
    the projection is derived) to the [0, 1] box convention the whole wire
    uses.
    """
    half_fov = math.tan(math.radians(fov_degrees) / 2.0)
    if half_fov <= 0.0:
        return 0.0
    return math.radians(delta_degrees) / half_fov / 2.0


def _roll_transform(delta_degrees: float, aspect: float) -> Transform:
    """Rotation about the frame centre, corrected for the frame aspect.

    Without the aspect correction a roll on a 16:9 frame is a shear, not a
    rotation, and boxes would deform instead of turning.
    """
    angle = math.radians(-delta_degrees)
    cos_a = math.cos(angle)
    sin_a = math.sin(angle)
    safe_aspect = aspect if aspect > 0.0 else 1.0
    a = cos_a
    b = -sin_a / safe_aspect
    d = safe_aspect * sin_a
    e = cos_a
    return Transform(
        a=a,
        b=b,
        c=0.5 - 0.5 * a - 0.5 * b,
        d=d,
        e=e,
        f=0.5 - 0.5 * d - 0.5 * e,
    )


def _delta_degrees(previous: float, current: float) -> float:
    """Signed shortest angular difference, so 359 -> 1 is +2 and never -358."""
    return (current - previous + 180.0) % 360.0 - 180.0


def _within_gap(previous: CameraPose, current: CameraPose) -> bool:
    if previous.timestamp_millis <= 0 or current.timestamp_millis <= 0:
        # Unstamped poses are the normal case for a client that fills only
        # attitude; there is nothing to judge staleness against, so the step
        # is trusted and the implausibility guard above is the only defence.
        return True
    gap = current.timestamp_millis - previous.timestamp_millis
    return 0 <= gap <= _MAX_POSE_GAP_MILLIS


def create(*, aspect_ratio: float = 0.0) -> PoseMotionCompensator:
    """Factory used by `TrackerRegistry`. One compensator per stream."""
    return PoseMotionCompensator(aspect_ratio=aspect_ratio)

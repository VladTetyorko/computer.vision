"""`flow` -- ego-motion compensation from pixels, the standard BoT-SORT GMC approach.

`docs/plans/active/TRACKING-V2-PLAN.md` §3.1, `docs/conclusions/TRACKING-REVIEW.md` §4.5.

**The defect this closes**, same as `pose_gmc.py`'s: association is IoU-based
in image space, so a camera pan charges its own motion to every object in
frame and a target that shifts further than its own width between two
sampled frames is reborn under a new id. Where `pose_gmc.py` reads the
answer off telemetry, this engine MEASURES it: sparse features tracked from
the previous grey frame to the current one, fit to one global affine by
RANSAC. It works on any client -- including one that never learns to send a
`CameraPose` -- which is why it is the deployment default (`CV_TRACK_MOTION_
ENGINE`).

**Background, not the target.** Corners are taken over the WHOLE frame,
unmasked -- the tracked object's own box is deliberately not excluded. That
sounds backwards until the guard below is read: a target that is small
relative to the frame contributes a small, usually outvoted minority of the
point cloud, and RANSAC's inlier count is exactly the signal that tells the
two cases apart without ever being told which pixels are "the object".

**The direction, stated because a sign error here is invisible in
isolation** (same reasoning `pose_gmc.py`'s docstring gives): `Transform`
maps a point in the PREVIOUS frame to where it appears in the CURRENT one.
`cv2.estimateAffinePartial2D(src, dst)` already fits exactly that mapping
when `src` are the previous frame's corners and `dst` are where
`calcOpticalFlowPyrLK` found them in the current frame -- so the pixel
matrix this engine gets back from OpenCV is already oriented correctly; the
only translation left to get right is pixel space to the wire's normalized
`[0, 1]` space.

**The pixel -> normalized conversion.** `Transform` is affine in normalized
coordinates; OpenCV's fit is affine in pixel coordinates. Substituting
`px = x * width`, `py = y * height` into the pixel equations and dividing
back through by `width`/`height` gives:

    a = m00                    d = m10 * (width / height)
    b = m01 / (width / height) e = m11
    c = m02 / width             f = m12 / height

A translation term picks up a straight `/ width` or `/ height`; a linear
(rotation/scale) term additionally picks up the frame's aspect ratio,
because `[0, 1]` x and y are not the same physical distance unless the
frame is square -- the same correction `pose_gmc._roll_transform` applies
for the same reason.

Needs `cv2` -- one of the two engines this package's own invariant (P3)
names as the exception to "no cv2/numpy outside engines/".
"""

from __future__ import annotations

import logging
import math
from typing import Optional

import cv2
import numpy as np

from cv_service.tracking.engines.base import IDENTITY, CameraPose, Transform

LOGGER = logging.getLogger("cv_service.tracking.engines.flow_gmc")

ENGINE_ID = "flow"

# Corners are drawn from the WHOLE frame (no mask) -- see the module
# docstring on why the object is deliberately not excluded. More corners
# than `lk.py`'s per-target `_MAX_CORNERS` because this fit is over the
# entire frame, not one small box, and a global affine needs points spread
# across it to be well-conditioned.
_MAX_CORNERS = 200
_CORNER_QUALITY = 0.01
_CORNER_MIN_DISTANCE = 8

_LK_WINDOW = (21, 21)
_LK_PYRAMID_LEVELS = 3

# Below this many successfully-flowed correspondences a global affine fit is
# noise, not a measurement -- refuse rather than fit a plausible-looking
# answer off a handful of points.
_MIN_TRACKED_POINTS = 12

# RANSAC reprojection tolerance for `estimateAffinePartial2D`, in pixels.
_RANSAC_REPROJ_THRESHOLD_PX = 3.0

# Below this fraction of tracked points agreeing with the fitted affine (the
# RANSAC inlier mask), the fit is not trusted. This is THE guard against the
# moving-subject-fills-the-frame case the plan calls out: a foreground
# object large enough to contribute a comparable share of the point cloud
# splits the vote between two genuinely different motions, and a confident
# answer fit to either is worse than none -- it would warp a track's
# predicted box AWAY from where the object actually is instead of leaving it
# where the un-compensated system already puts it.
_MIN_INLIER_RATIO = 0.6

# A fitted affine's linear part should be a similarity (rotation + uniform
# scale), whose determinant is ~1 for the per-frame deltas a camera pan/tilt/
# roll actually produces. One far from 1 is not camera motion -- it is a
# degenerate fit (near-parallel correspondences, a near-singular estimate)
# that would badly distort box AREAS rather than translate/rotate them, so
# it is refused as a degenerate result rather than trusted.
_MAX_DETERMINANT_DEVIATION = 0.5


class FlowMotionCompensator:
    """`MotionCompensator` over sparse optical flow across the whole frame.

    One instance per stream, like every other engine: it holds the previous
    greyscale frame, which is inherently per-stream state.
    """

    engine_id = ENGINE_ID

    def __init__(self) -> None:
        self._previous_gray: Optional["np.ndarray"] = None

    def available(self, pose: CameraPose) -> bool:
        """Always true -- this engine needs pixels, never telemetry."""
        return True

    def estimate(self, frame: "Optional[np.ndarray]", pose: CameraPose) -> Transform:
        """Previous frame -> current frame, measured from pixels. `pose` is
        ignored, deliberately -- see `pose_gmc.py` for the telemetry twin.

        Returns `IDENTITY` for every case where the answer is not known
        rather than guessing: no frame, a first frame, too few tracked
        points, a failed fit, or a fit RANSAC could not agree on.
        """
        if frame is None:
            self._previous_gray = None
            return IDENTITY

        gray = _to_gray(frame)
        previous = self._previous_gray
        self._previous_gray = gray
        if previous is None or previous.shape != gray.shape:
            return IDENTITY

        previous_points = cv2.goodFeaturesToTrack(
            previous,
            maxCorners=_MAX_CORNERS,
            qualityLevel=_CORNER_QUALITY,
            minDistance=_CORNER_MIN_DISTANCE,
        )
        if previous_points is None or len(previous_points) < _MIN_TRACKED_POINTS:
            return IDENTITY

        moved, status, _err = cv2.calcOpticalFlowPyrLK(
            previous, gray, previous_points, None, winSize=_LK_WINDOW, maxLevel=_LK_PYRAMID_LEVELS
        )
        if moved is None or status is None:
            return IDENTITY
        matched = status.reshape(-1) == 1
        source = previous_points[matched]
        destination = moved[matched]
        if len(source) < _MIN_TRACKED_POINTS:
            return IDENTITY

        matrix, inliers = cv2.estimateAffinePartial2D(
            source,
            destination,
            method=cv2.RANSAC,
            ransacReprojThreshold=_RANSAC_REPROJ_THRESHOLD_PX,
        )
        if matrix is None or inliers is None or len(inliers) == 0:
            return IDENTITY
        inlier_ratio = float(inliers.sum()) / float(len(inliers))
        if inlier_ratio < _MIN_INLIER_RATIO:
            LOGGER.debug(
                "flow: inlier ratio %.2f below %.2f (a moving subject may be filling the "
                "frame); refusing this frame's estimate",
                inlier_ratio,
                _MIN_INLIER_RATIO,
            )
            return IDENTITY

        height, width = gray.shape[:2]
        transform = _normalized_transform(matrix, width, height)
        return transform if transform is not None else IDENTITY

    def reset(self) -> None:
        self._previous_gray = None


def _to_gray(frame: "np.ndarray") -> "np.ndarray":
    if frame.ndim == 2:
        return frame
    return cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)


def _normalized_transform(matrix: "np.ndarray", width: int, height: int) -> Optional[Transform]:
    """Pixel-space affine -> normalized `Transform`, or `None` if degenerate.

    See the module docstring for the derivation -- a translation term is
    divided by the matching dimension, a linear term additionally picks up
    the frame's aspect ratio.
    """
    if width <= 0 or height <= 0:
        return None
    m00, m01, m02 = (float(v) for v in matrix[0])
    m10, m11, m12 = (float(v) for v in matrix[1])

    determinant = m00 * m11 - m01 * m10
    if not math.isfinite(determinant) or abs(determinant - 1.0) > _MAX_DETERMINANT_DEVIATION:
        return None

    aspect = width / height
    transform = Transform(
        a=m00,
        b=m01 / aspect,
        c=m02 / width,
        d=m10 * aspect,
        e=m11,
        f=m12 / height,
    )
    if not all(math.isfinite(value) for value in (transform.a, transform.b, transform.c, transform.d, transform.e, transform.f)):
        return None
    return transform


def create() -> FlowMotionCompensator:
    """Factory used by `TrackerRegistry`. One compensator per stream."""
    return FlowMotionCompensator()

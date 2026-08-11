"""`lk` -- the FOLLOW default: Lucas-Kanade sparse optical flow.

`docs/plans/done/TRACKING-PLAN.md` §5.B. Tracks <=40 `goodFeaturesToTrack` corners
inside the locked box with `cv2.calcOpticalFlowPyrLK`, then moves the box by
the median corner translation and rescales it by the median radial expansion
about the corner centroid. Core OpenCV `video` module only -- no ONNX
assets, no contrib package, nothing x86- or CUDA-specific (invariant P1).

Why this and not KCF/CSRT/MOSSE: **they do not exist here.** OpenCV 5 removed
them and `cv2.legacy` is absent; the only `Tracker*` symbols this box has are
`TrackerMIL` (24.4 ms/frame -- 65x the budget, rejected) and
`TrackerNano`/`TrackerVit`/`TrackerDaSiamRPN`, each of which raises on
construction because opencv-python ships no ONNX weights for them
(TRACKING-PLAN R1, measured). `lk` is the better outcome anyway: measured
0.49 ms/frame here including the full-frame greyscale conversion.

The numeric constants below are this engine's own algorithm parameters, not
operator configuration: the operator's knobs are `TrackingParams`, resolved
in `params.py`. They are named module constants rather than literals buried
in call sites so that distinction stays visible.
"""

from __future__ import annotations

import logging
from typing import Optional

import cv2
import numpy as np

from cv_service.tracking.engines.base import Box, TrackerUpdate

LOGGER = logging.getLogger("cv_service.tracking.engines.lk")

ENGINE_ID = "lk"

_MAX_CORNERS = 40
_CORNER_QUALITY = 0.01
_CORNER_MIN_DISTANCE = 5
_LK_WINDOW = (21, 21)
_LK_PYRAMID_LEVELS = 3
# Below this many surviving corners the flow field is noise, not a target.
_MIN_TRACKED_CORNERS = 4
# Per-frame scale change is clamped: a genuine target cannot double or halve
# between two consecutive frames, but a few outlier corners landing on the
# background can make the median radius say it did.
_MIN_SCALE_STEP = 0.8
_MAX_SCALE_STEP = 1.25


class LkFlowEngine:
    """`SingleObjectTracker` over `cv2.calcOpticalFlowPyrLK`.

    One instance per stream, built by `TrackerRegistry`'s factory. Holds the
    previous greyscale frame and the surviving corner set -- inherently
    stateful, which is exactly why engines are never shared.
    """

    engine_id = ENGINE_ID

    def __init__(self) -> None:
        self._previous_gray: Optional[np.ndarray] = None
        self._points: Optional[np.ndarray] = None
        self._box: Optional[Box] = None
        self._initial_corners = 0

    def init(self, frame: np.ndarray, box: Box) -> bool:
        gray = _to_gray(frame)
        height, width = gray.shape[:2]
        x0, y0, x1, y1 = _pixel_bounds(box, width, height)
        if x1 - x0 < 2 or y1 - y0 < 2:
            return False
        mask = np.zeros(gray.shape, dtype=np.uint8)
        mask[y0:y1, x0:x1] = 255
        points = cv2.goodFeaturesToTrack(
            gray,
            maxCorners=_MAX_CORNERS,
            qualityLevel=_CORNER_QUALITY,
            minDistance=_CORNER_MIN_DISTANCE,
            mask=mask,
        )
        if points is None or len(points) < _MIN_TRACKED_CORNERS:
            # A featureless target (a plain wall, a blurred speck) is not a
            # tracker failure -- it is a target this engine cannot hold.
            # Reporting False lets the session fall back rather than raise.
            return False
        self._previous_gray = gray
        self._points = np.asarray(points, dtype=np.float32)
        self._box = box
        self._initial_corners = len(self._points)
        return True

    def update(self, frame: np.ndarray) -> Optional[TrackerUpdate]:
        if self._previous_gray is None or self._points is None or self._box is None:
            return None
        gray = _to_gray(frame)
        moved, status, _err = cv2.calcOpticalFlowPyrLK(
            self._previous_gray,
            gray,
            self._points,
            None,
            winSize=_LK_WINDOW,
            maxLevel=_LK_PYRAMID_LEVELS,
        )
        if moved is None or status is None:
            return None
        kept = status.reshape(-1) == 1
        new_points = moved[kept]
        old_points = self._points[kept]
        if len(new_points) < _MIN_TRACKED_CORNERS:
            return None

        height, width = gray.shape[:2]
        deltas = (new_points - old_points).reshape(-1, 2)
        dx = float(np.median(deltas[:, 0])) / width
        dy = float(np.median(deltas[:, 1])) / height
        scale = _median_scale(old_points.reshape(-1, 2), new_points.reshape(-1, 2))

        box = self._box
        new_width = box.width * scale
        new_height = box.height * scale
        # Scale about the box centre so a growing target does not drift.
        moved_box = Box(
            box.x + dx - (new_width - box.width) / 2.0,
            box.y + dy - (new_height - box.height) / 2.0,
            new_width,
            new_height,
        )
        self._box = moved_box
        self._previous_gray = gray
        self._points = new_points.reshape(-1, 1, 2).astype(np.float32)
        # Confidence is the fraction of the original corner set still being
        # followed: a target sliding behind an obstruction loses corners
        # before it loses its box, so this is the earliest honest signal
        # that the scheduler should spend a verify pass (trigger (b)).
        confidence = len(new_points) / float(self._initial_corners or 1)
        return TrackerUpdate(box=moved_box, confidence=min(confidence, 1.0))

    def reset(self) -> None:
        self._previous_gray = None
        self._points = None
        self._box = None
        self._initial_corners = 0


def _to_gray(frame: np.ndarray) -> np.ndarray:
    if frame.ndim == 2:
        return frame
    return cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)


def _pixel_bounds(box: Box, width: int, height: int) -> tuple[int, int, int, int]:
    x0 = max(0, min(width - 1, int(round(box.x * width))))
    y0 = max(0, min(height - 1, int(round(box.y * height))))
    x1 = max(x0, min(width, int(round((box.x + box.width) * width))))
    y1 = max(y0, min(height, int(round((box.y + box.height) * height))))
    return x0, y0, x1, y1


def _median_scale(old_points: np.ndarray, new_points: np.ndarray) -> float:
    old_centre = old_points.mean(axis=0)
    new_centre = new_points.mean(axis=0)
    old_radii = np.linalg.norm(old_points - old_centre, axis=1)
    new_radii = np.linalg.norm(new_points - new_centre, axis=1)
    usable = old_radii > 1e-3
    if not usable.any():
        return 1.0
    scale = float(np.median(new_radii[usable] / old_radii[usable]))
    if not np.isfinite(scale) or scale <= 0.0:
        return 1.0
    return min(max(scale, _MIN_SCALE_STEP), _MAX_SCALE_STEP)


def create() -> LkFlowEngine:
    """Factory used by `TrackerRegistry`. One engine per stream."""
    return LkFlowEngine()

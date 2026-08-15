"""`ncc` -- the FOLLOW alternative: normalized cross-correlation template match.

`docs/plans/done/TRACKING-PLAN.md` §5.B. Keeps the locked box's greyscale patch as a
template and finds it each frame with `cv2.matchTemplate(TM_CCOEFF_NORMED)`
inside a search window twice the template's size. Core OpenCV only.

Chosen alongside `lk` rather than instead of it because the two fail in
different places, which is the point of having a roster at all: `lk` needs
corners and gives up on a feature-poor target (a plain-sided vehicle, a
distant speck), while `ncc` needs only that the patch keep looking like
itself and so holds those targets -- but drifts when the target rotates or
changes scale, which `lk` handles. Measured 0.23 ms/frame here.

The template is deliberately **not** re-learned every frame. Re-learning is
what makes a template tracker slide off its target and onto the background
over a few hundred frames, and the duty cycle already provides the correct
re-learning trigger: the detector's verify pass re-anchors the box, at which
point `init()` takes a fresh template from ground truth.

As in `lk`, the constants below are this engine's algorithm parameters, not
operator configuration.
"""

from __future__ import annotations

import logging
from typing import Optional

import cv2
import numpy as np

from cv_service.tracking.engines.base import Box, TrackerUpdate

LOGGER = logging.getLogger("cv_service.tracking.engines.ncc")

ENGINE_ID = "ncc"

# Search window half-margin, as a fraction of the template's own size: the
# window is 2x the template, so the target may move half its own width
# between frames and still be found.
_SEARCH_MARGIN = 0.5
# Below this correlation the best match is not the target any more.
_MIN_CORRELATION = 0.3
_MIN_TEMPLATE_PIXELS = 2


class NccEngine:
    """`SingleObjectTracker` over `cv2.matchTemplate`. One instance per stream."""

    engine_id = ENGINE_ID

    def __init__(self) -> None:
        self._template: Optional[np.ndarray] = None
        self._box: Optional[Box] = None

    def init(self, frame: np.ndarray, box: Box) -> bool:
        gray = _to_gray(frame)
        height, width = gray.shape[:2]
        x0, y0, x1, y1 = _pixel_bounds(box, width, height)
        if x1 - x0 < _MIN_TEMPLATE_PIXELS or y1 - y0 < _MIN_TEMPLATE_PIXELS:
            return False
        self._template = gray[y0:y1, x0:x1].copy()
        self._box = box
        return True

    def update(self, frame: np.ndarray) -> Optional[TrackerUpdate]:
        if self._template is None or self._box is None:
            return None
        gray = _to_gray(frame)
        height, width = gray.shape[:2]
        template_height, template_width = self._template.shape[:2]
        x0, y0, _x1, _y1 = _pixel_bounds(self._box, width, height)

        margin_x = int(template_width * _SEARCH_MARGIN)
        margin_y = int(template_height * _SEARCH_MARGIN)
        sx0 = max(0, x0 - margin_x)
        sy0 = max(0, y0 - margin_y)
        sx1 = min(width, x0 + template_width + margin_x)
        sy1 = min(height, y0 + template_height + margin_y)
        window = gray[sy0:sy1, sx0:sx1]
        if window.shape[0] < template_height or window.shape[1] < template_width:
            return None

        scores = cv2.matchTemplate(window, self._template, cv2.TM_CCOEFF_NORMED)
        _min_value, max_value, _min_loc, max_loc = cv2.minMaxLoc(scores)
        if max_value < _MIN_CORRELATION:
            return None

        moved = Box(
            (sx0 + max_loc[0]) / width,
            (sy0 + max_loc[1]) / height,
            template_width / width,
            template_height / height,
        )
        self._box = moved
        # `TM_CCOEFF_NORMED` is on [-1, 1]; the negative half means
        # anti-correlated, which is no match at all, so clamping at 0 keeps
        # `TrackerUpdate.confidence` on the [0, 1] the protocol documents.
        return TrackerUpdate(box=moved, confidence=max(0.0, min(1.0, float(max_value))))

    def reset(self) -> None:
        self._template = None
        self._box = None


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


def create() -> NccEngine:
    """Factory used by `TrackerRegistry`. One engine per stream."""
    return NccEngine()

"""`histogram` -- colour-histogram appearance descriptor, the first `AppearanceExtractor`.

`docs/plans/active/TRACKING-V2-PLAN.md` §3.1/§3 wave C3 (D7), `docs/conclusions/TRACKING-REVIEW.md`
§3 group B (finding B1) / §4.4.

**The defect this closes.** Association was IoU-only, so two objects whose
paths cross are a symmetric ambiguity an IoU matcher cannot break -- the
classic id-swap (`crossing | ASSOCIATE`'s baseline `IDSW=4`, produced by
detector jitter no bigger than a box's own edge noise). This engine gives
`assign.py`'s `CostAssociator` a second, independent signal: what the object
LOOKS like, not only where its box is.

**HSV, not BGR (D7's "near-free, no model asset" choice, made concrete).**
Hue/saturation stays far more stable than raw BGR under the illumination
swings a moving camera or a moving subject produces. Value (brightness) is
dropped entirely -- it is the channel most sensitive to exposure and shadow,
and dropping a channel is cheaper than weighting it down.

**Hellinger, not cosine.** `engines/base.py`'s `Descriptor` already
implements `METRIC_HELLINGER` for a non-negative, sums-to-one vector -- an
L1-normalized histogram is exactly that -- and its `blend` renormalizes back
onto the simplex, so `Track.descriptor`'s EMA (`track.py`) never drifts off
it.

**`None`, not a degenerate descriptor, for anything this engine cannot
describe.** Off-frame, sub-pixel and empty-region boxes all return `None`
positionally -- `assign.py`'s `_appearance_distance` already treats a missing
descriptor as neutral, never as a rejection, so withholding one here costs
nothing downstream and inventing a value would be dishonest evidence.

Needs `cv2`/`numpy` -- the third and last named exception to `tracking/`'s
pure-stdlib-outside-`engines/` rule (P3), alongside `bytetrack.py`/`lk.py`/
`flow_gmc.py`.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Optional, Sequence

import cv2

from cv_service.tracking.engines.base import METRIC_HELLINGER, Box, Descriptor

if TYPE_CHECKING:  # pragma: no cover - typing only
    import numpy as np

ENGINE_ID = "histogram"

# A coarse joint H x S histogram: enough to separate "red car" from "blue
# car" without spending more than a fraction of a millisecond per object on
# a duty-cycled detector pass. Hue gets more bins than saturation because it
# is the axis that actually distinguishes objects; saturation mainly guards
# against matching a vivid colour to a washed-out one.
_HUE_BINS = 16
_SATURATION_BINS = 8
_HUE_RANGE = (0, 180)  # OpenCV's own 8-bit HSV hue range
_SATURATION_RANGE = (0, 256)

# A box smaller than this many pixels (after clipping to the frame) has too
# few samples for a histogram to mean anything. Returned as `None` -- "no
# appearance evidence" -- never as a degenerate near-uniform descriptor.
_MIN_DESCRIBABLE_PIXELS = 64


class HistogramAppearanceExtractor:
    """`AppearanceExtractor` over a per-box HSV colour histogram.

    Stateless across frames, unlike the motion/SOT engines: a histogram
    needs nothing but the current frame and box. One instance per stream
    exists only to match the registry's per-engine-per-stream convention,
    not because there is anything to hold.
    """

    engine_id = ENGINE_ID

    def describe(
        self, frame: "np.ndarray", boxes: Sequence[Box]
    ) -> "list[Optional[Descriptor]]":
        if frame is None:
            return [None for _ in boxes]
        height, width = frame.shape[0], frame.shape[1]
        if height <= 0 or width <= 0:
            return [None for _ in boxes]
        hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
        return [self._describe_one(hsv, box, width, height) for box in boxes]

    def _describe_one(
        self, hsv: "np.ndarray", box: Box, width: int, height: int
    ) -> Optional[Descriptor]:
        left = max(0, int(round(box.x * width)))
        top = max(0, int(round(box.y * height)))
        right = min(width, int(round((box.x + box.width) * width)))
        bottom = min(height, int(round((box.y + box.height) * height)))
        if right <= left or bottom <= top:
            return None
        if (right - left) * (bottom - top) < _MIN_DESCRIBABLE_PIXELS:
            return None

        roi = hsv[top:bottom, left:right]
        histogram = cv2.calcHist(
            [roi],
            [0, 1],
            None,
            [_HUE_BINS, _SATURATION_BINS],
            [*_HUE_RANGE, *_SATURATION_RANGE],
        )
        total = float(histogram.sum())
        if total <= 0.0:
            return None
        normalized = (histogram / total).flatten()
        return Descriptor(ENGINE_ID, tuple(float(value) for value in normalized), METRIC_HELLINGER)

    def reset(self) -> None:
        """No inter-frame state to drop -- a histogram is a pure function of
        one frame and one box, unlike the motion/SOT engines beside it."""


def create() -> HistogramAppearanceExtractor:
    """Factory used by `TrackerRegistry`. One extractor per stream."""
    return HistogramAppearanceExtractor()

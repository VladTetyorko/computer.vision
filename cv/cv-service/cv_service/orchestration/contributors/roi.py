"""`detect.roi` -- one bounded second look at what the match left unmatched.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2 / O1 Q13. Moved out of
`_run_cost_associate` with its policy intact; what the move BUYS is that the
second detector pass now has a ledger entry of its own, beside `detect.full`
rather than folded into it. The duty ratio a flow strip computes counts
`detect.full` only, which is exactly why the two must not share a row.

It also settles the old "THE ONLY GATE ACQUISITION" claim, which O1
contradiction 3 showed was already false: ROI rescue ships ON
(`config.py`'s `DEFAULT_TRACK_ROI_ENABLED`), so TWO detector passes per frame
is the DEFAULT for `cost`, not an exception. Both go through the same
`DetectorClient`, and now both say so.

Reachable only from the `cost` path: its candidates ARE `TrackBook`'s own
tracks, already known confirmed/unmatched before anything is booked, which is
the seam this needs. `bytetrack` has no such seam -- the same reason it never
gets ego-motion compensation either.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from cv_service.orchestration.budget import DETECT_ROI
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext, skipped
from cv_service.orchestration.contributors.appearance import describe
from cv_service.orchestration.contributors.associate import CostAssignment
from cv_service.orchestration.detector import DetectorClient, served_by_summary
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.orchestration.state import StreamState
from cv_service.tracking.levels import LEVEL_L3
from cv_service.tracking.assign import Target
from cv_service.tracking.engines.base import Box, Descriptor, Observation


@dataclass(frozen=True)
class Rescue:
    """One rescued object: what to book, what it looks like, what it came from."""

    observation: Observation
    descriptor: "Optional[Descriptor]"
    #: The raw ROI detection, appended to the response directly -- it has no
    #: full-frame detection index to map through.
    detection: Any


class RoiRescue:
    """At most one bounded, gated second look per frame.

    **Priority, stated explicitly.** Eligible candidates are the unmatched
    ones that are also CONFIRMED -- a candidate that never earned an id is not
    "something the system believes in", so it is not worth a second detector
    pass. Among those, the one with the MOST consecutive misses (closest to
    ageing out into LOST) gets the pass: recovering it now avoids the far more
    expensive failure losing it altogether would cost. Ties break on the
    lowest `track_id`, for a deterministic, greppable choice.

    **The bound.** At most ONE `detect(roi)` call per frame, full stop.

    **The gate on the MATCH, not just the pass.** A degenerate crop is a
    genuine no-op -- no detector call, nothing logged, because nothing went
    wrong. Otherwise whatever the crop returns is judged by the SAME
    already-retuned `engine.assign`, restricted to this ONE candidate, and
    then held to a STRICTER overlap than an ordinary match.
    """

    id = DETECT_ROI
    family = DETECT_ROI
    reads = frozenset({Key.FRAME, Key.ASSIGNMENT})
    writes = frozenset({Key.DETECTIONS_ROI})
    min_level = LEVEL_L3
    phase = PHASE_TRACK

    def __init__(self, engines: EngineSet, client: DetectorClient, state: StreamState) -> None:
        self._engines = engines
        self._client = client
        self._state = state

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        found: Optional[CostAssignment] = ctx.get(Key.ASSIGNMENT)
        if found is None:
            return skipped("no assignment to rescue from")

        params = self._engines.params
        candidates = found.candidates
        tracks = found.tracks
        eligible = [
            index
            for index in found.assignment.unmatched_candidates
            if candidates[index].confirmed
        ]
        if not eligible:
            return skipped("nothing confirmed went unmatched")
        chosen_index = max(
            eligible, key=lambda index: (tracks[index].misses, -tracks[index].track_id)
        )
        candidate = candidates[chosen_index]
        chosen = tracks[chosen_index]

        roi = roi_box(candidate.box, params)
        if roi is None:
            return skipped("crop collapsed")

        self._state.roi_ran = True
        result = self._client.detect(roi)
        self._state.roi_millis += result.inference_millis
        self._state.inference_millis += result.inference_millis
        roi_detections = result.detections
        summary = {
            "track": str(chosen.track_id),
            "misses": str(chosen.misses),
            "roi_detections": str(len(roi_detections or [])),
            **served_by_summary(result),
        }
        if not roi_detections:
            return Contribution(
                reason="crop was empty",
                summary=summary,
                cost_ms=float(result.inference_millis),
            )

        roi_boxes = [Box(d.x, d.y, d.width, d.height) for d in roi_detections]
        roi_descriptors = describe(
            self._engines, self._engines.appearance_extractor, ctx.get(Key.FRAME), roi_boxes
        )
        roi_targets = [
            Target(
                box=roi_boxes[index],
                label=detection.label,
                confidence=detection.confidence,
                descriptor=roi_descriptors[index],
                det_index=index,
            )
            for index, detection in enumerate(roi_detections)
        ]
        rescue_assignment = self._engines.engine.assign([candidate], roi_targets)
        if not rescue_assignment.matches:
            return Contribution(
                reason="crop matched nothing",
                summary=summary,
                cost_ms=float(result.inference_millis),
            )
        # A rescue is held to a STRICTER overlap than an ordinary match, and
        # this is the one place the two deliberately differ. The crop exists
        # because the object was predicted here, so a genuine rescue sits on
        # the prediction; a neighbour the deliberately-wide crop happens to
        # contain does not. Reusing the primary gate -- which C3 left
        # permissive on purpose, since a full-frame match has the whole scene
        # competing to explain each box -- let a crowd's neighbour win the
        # slot, and cost `clutter` six id swaps. Measured, not supposed.
        rescued_box = roi_targets[rescue_assignment.matches[0][1]].box
        overlap = candidate.box.iou(rescued_box)
        if overlap < params.roi_min_iou:
            return Contribution(
                reason="below the rescue overlap gate",
                summary={**summary, "iou": repr(overlap)},
                cost_ms=float(result.inference_millis),
            )
        _, target_index = rescue_assignment.matches[0]
        target = roi_targets[target_index]
        observation = Observation(
            key=candidate.key,
            box=target.box,
            label=target.label,
            confidence=target.confidence,
            # -1: no FULL-FRAME detection index behind this observation --
            # `propose.cost` appends this box to the response directly instead
            # of through the `by_index`/`enumerate(detections)` mapping.
            det_index=-1,
        )
        return Contribution(
            outputs={
                Key.DETECTIONS_ROI: Rescue(
                    observation=observation,
                    descriptor=target.descriptor,
                    detection=roi_detections[target_index],
                )
            },
            summary={**summary, "iou": repr(overlap), "rescued": "1"},
            evidence={chosen.track_id: {"roi_iou": repr(overlap), "roi_label": target.label}},
            cost_ms=float(result.inference_millis),
        )


def roi_box(box: Box, params: Any) -> Optional[Box]:
    """A square crop centered on `box`, `roi_crop_factor` times its own larger
    dimension, clamped to the unit frame.

    `None` -- a genuine no-op, P5 -- when `box` has already collapsed or the
    clamped crop has nothing of the frame left in it; the detector never sees
    a degenerate region.
    """
    side = params.roi_crop_factor * max(box.width, box.height)
    if side <= 0.0:
        return None
    cx, cy = box.center
    half = side / 2.0
    x0 = _clamp01(cx - half)
    x1 = _clamp01(cx + half)
    y0 = _clamp01(cy - half)
    y1 = _clamp01(cy + half)
    width = x1 - x0
    height = y1 - y0
    if width <= 0.0 or height <= 0.0:
        return None
    return Box(x0, y0, width, height)


def _clamp01(value: float) -> float:
    """`roi_box`'s own clamp-to-frame, a plain function rather than an import
    of the identically-named helper in the inference package: that module is
    `cv2`/`numpy`-gated (P3), and this one call site does not need the rest
    of it.
    """
    return max(0.0, min(1.0, value))

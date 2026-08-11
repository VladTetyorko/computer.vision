"""Lock arbitration: `lock_seq` monotonicity and target selection.

`docs/TRACKING-PLAN.md` §4.A (`TargetLock`), `docs/TRACKING-ORCHESTRATION.md`
§2.1, §3.3.

A lock is **declarative and restated on every frame**, never an imperative
one-shot: `LatestOnlyMailbox` silently drops a frame when the sender gets
ahead of the consumer (cv-service/MODULE.md), so a one-shot control message
could be lost with no error and no retry. Restating is self-healing -- the
next frame carries the same desired state again.

What makes restating safe is this file's one hard rule:

> **A lock is applied only when its `lock_seq` is STRICTLY GREATER than the
> last one applied for this stream.**

so the 15th restatement of lock #4 is a no-op, and a stale lock replayed
after a newer one can never take effect. `lock_seq` is allocated server-side
by the Java application layer (`DefaultStreamService`, an `AtomicLong`);
clients never send it, so a client cannot replay a stale lock at all.

Pure stdlib.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Callable, Optional, Sequence

from cv_service.tracking.engines.base import Box
from cv_service.tracking.params import LockRequest

LOGGER = logging.getLogger("cv_service.tracking.lock")


@dataclass(frozen=True)
class LockTarget:
    """What the operator asked FOLLOW to hold, before it is bound to a track.

    Exactly one of the three forms is populated, in the precedence the wire
    documents: an explicit `box` wins, then `track_id`, then a click `point`.
    """

    track_id: int = 0
    point: Optional[tuple[float, float]] = None
    box: Optional[Box] = None


class LockArbiter:
    """Per-stream lock state: which target, which sequence, bound to which track.

    Two levels, deliberately kept apart:

    * the **target** is what the operator asked for (a track id, a click
      point, a box) -- known the moment a lock is applied;
    * the **bound track id** is what cv-service actually managed to hold --
      known only once a detector pass has resolved the target to a real
      track.

    `DetectionResponse.locked_track_id` reports the second, never the first.
    That is the honesty rule of TRACKING-ORCHESTRATION §3.3: a lock is a
    request until a response confirms it, so a target that could not be
    honoured shows as *not locked* rather than as a lying chip in the UI.
    """

    def __init__(self) -> None:
        self._applied_seq = 0
        self._target: Optional[LockTarget] = None
        self._bound_track_id = 0
        self._generation = 0

    @property
    def applied_seq(self) -> int:
        return self._applied_seq

    @property
    def target(self) -> Optional[LockTarget]:
        return self._target

    @property
    def bound_track_id(self) -> int:
        """The track actually held; 0 when none is."""
        return self._bound_track_id

    @property
    def generation(self) -> int:
        """Increments on every applied lock.

        The session uses it to mint a fresh `TrackBook` key per lock, so
        re-acquiring after a release yields a NEW track id rather than
        silently resurrecting the previous one.
        """
        return self._generation

    @property
    def has_target(self) -> bool:
        return self._target is not None

    def apply(self, request: Optional[LockRequest]) -> bool:
        """Apply `request` if its `lock_seq` is strictly greater than the last.

        Returns True when this call changed the lock state. An absent
        request, a stale or equal `lock_seq`, and a `lock_seq` of 0 ("no lock
        has ever been issued") are all no-ops.
        """
        if request is None or request.lock_seq <= 0:
            return False
        if request.lock_seq <= self._applied_seq:
            return False
        self._applied_seq = request.lock_seq
        if request.release:
            self.drop()
            LOGGER.info("tracking: lock released (lock_seq=%d)", request.lock_seq)
            return True
        self._target = _target_from(request)
        self._bound_track_id = 0
        self._generation += 1
        LOGGER.info("tracking: lock applied (lock_seq=%d, %s)", request.lock_seq, self._target)
        return True

    def bind(self, track_id: int) -> None:
        """Record which track the target actually resolved to."""
        self._bound_track_id = track_id

    def unbind(self) -> None:
        """The held track is gone, but the operator's target request stands.

        Keeps `target` so the next detector pass re-acquires (scheduler
        trigger (c) is `has_lock`, which this makes False again).
        """
        self._bound_track_id = 0

    def drop(self) -> None:
        """Release the lock entirely: no target, no bound track."""
        self._target = None
        self._bound_track_id = 0


def _target_from(request: LockRequest) -> LockTarget:
    if request.box is not None:
        return LockTarget(track_id=request.track_id, box=Box(*request.box))
    if request.track_id > 0:
        return LockTarget(track_id=request.track_id)
    return LockTarget(point=(request.point_x, request.point_y))


# -- target selection ------------------------------------------------------
#
# Both helpers are pure geometry over duck-typed detections (anything with
# `x`/`y`/`width`/`height`), so they need neither `cv2` nor the detector
# module, and they are the reason `session.py` stays composition-only:
# picking WHICH box to hold is target selection, which is this file's
# charter.


def select_by_point(boxes: Sequence[Box], x: float, y: float) -> int:
    """Index of the box a click at `(x, y)` selects, or -1.

    Prefers the smallest box containing the point (clicking inside a car
    parked in front of a building should select the car, not the building);
    falls back to the box whose centre is nearest when the click landed
    outside every box, which is what makes a slightly-off click still do
    something useful instead of nothing.
    """
    best_index = -1
    best_area = 0.0
    for index, box in enumerate(boxes):
        if not box.contains(x, y):
            continue
        area = box.width * box.height
        if best_index < 0 or area < best_area:
            best_index, best_area = index, area
    if best_index >= 0:
        return best_index

    best_distance = 0.0
    for index, box in enumerate(boxes):
        cx, cy = box.center
        distance = (cx - x) ** 2 + (cy - y) ** 2
        if best_index < 0 or distance < best_distance:
            best_index, best_distance = index, distance
    return best_index


def select_target(
    boxes: Sequence[Box],
    *,
    held_box: Optional[Box] = None,
    target: Optional[LockTarget] = None,
    min_iou: float = 0.0,
    box_of_track: Optional[Callable[[int], Optional[Box]]] = None,
) -> int:
    """Index of the box FOLLOW should hold on this verify pass, or -1.

    Re-anchoring an already-held target and acquiring a new one are the same
    question asked twice, so both live here rather than in the session:
    an existing target re-anchors on the best-IoU box at or above the
    threshold, and a new one resolves from whichever form the operator sent.

    `box_of_track` looks a track id up in the caller's book -- passed in
    rather than imported so this module keeps knowing nothing about the
    lifecycle machine.
    """
    if not boxes:
        return -1
    if held_box is not None:
        return best_iou_match(held_box, boxes, min_iou)
    if target is None:
        return -1
    if target.box is not None:
        return best_iou_match(target.box, boxes, min_iou)
    if target.point is not None:
        return select_by_point(boxes, target.point[0], target.point[1])
    if target.track_id > 0 and box_of_track is not None:
        known = box_of_track(target.track_id)
        if known is not None:
            return best_iou_match(known, boxes, min_iou)
    return -1


def best_iou_match(box: Box, candidates: Sequence[Box], min_iou: float) -> int:
    """Index of the candidate with the highest IoU >= `min_iou`, or -1.

    This is TRACKING-PLAN §3.1's re-anchor test: on a FOLLOW verify pass the
    detector's boxes are matched to the tracked box by IoU, and a match at
    or above the threshold re-anchors the tracker while anything below
    leaves it coasting.
    """
    best_index = -1
    best_iou = min_iou
    for index, candidate in enumerate(candidates):
        overlap = box.iou(candidate)
        if overlap >= best_iou:
            best_index, best_iou = index, overlap
    return best_index

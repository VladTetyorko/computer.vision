"""`appearance.<engine>` -- this frame's descriptors, one per detection.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. Only `cost` has anywhere
to put a descriptor, so this contributor is only ever registered on that
path -- `bytetrack` has no descriptor input to feed and a stream using it
never decodes a frame for appearance purposes.

`describe` stays a free function as well as a contributor, because the ROI
rescue needs the SAME behaviour for its crop's own boxes and reimplementing
the "never raises, degrades once per engine id" half there would be two
owners of one rule.
"""

from __future__ import annotations

import logging
from typing import Any, Optional, Sequence

from cv_service.orchestration.budget import APPEARANCE
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.tracking.levels import LEVEL_L2
from cv_service.tracking.engines.base import Box, Descriptor

LOGGER = logging.getLogger("cv_service.tracking.session")


class Appearance:
    """Describe every detection this frame, positionally."""

    family = APPEARANCE
    reads = frozenset({Key.FRAME, Key.DETECTIONS})
    writes = frozenset({Key.DESCRIPTORS})
    #: L3 is where `TrackerRegistry` first offers an appearance engine.
    min_level = LEVEL_L2
    phase = PHASE_TRACK

    def __init__(self, engines: EngineSet) -> None:
        self._engines = engines
        self.id = f"appearance.{engines.params.appearance_engine_id}"

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        detections = ctx.get(Key.DETECTIONS) or []
        # Resolved HERE, and the resolution is the contributor's real work:
        # `assoc.cost` reads `engines.appearance_extractor` to decide whether
        # appearance counts toward the match cost at all, and asking that
        # question must not itself build one.
        extractor = self._engines.resolve_appearance_extractor()
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        descriptors = describe(self._engines, extractor, ctx.get(Key.FRAME), boxes)
        described = sum(1 for descriptor in descriptors if descriptor is not None)
        return Contribution(
            outputs={Key.DESCRIPTORS: descriptors},
            reason="" if extractor is not None else "nothing constructible",
            summary={"described": str(described), "boxes": str(len(boxes))},
        )


def describe(
    engines: EngineSet, extractor: Any, frame: Any, boxes: "Sequence[Box]"
) -> "list[Optional[Descriptor]]":
    """This frame's appearance descriptors, one per box, positionally.

    `extractor` is already resolved by the caller, which also needs to know
    WHETHER one resolved to decide the cost weight, not just what it returns
    -- `None` for every box when it is `None`, the common case until an
    operator opts BOTH `cost` and an appearance engine in. Never decodes a
    frame unless an extractor actually resolved, and never raises: a
    describing failure costs this frame's appearance evidence, never the
    stream (P5), the same posture ego-motion takes for a raising compensator.
    """
    if not boxes or extractor is None:
        return [None] * len(boxes)
    try:
        described = extractor.describe(frame(), boxes)
    except Exception as exc:  # noqa: BLE001 - a bad extractor costs accuracy, never the stream
        if engines.appearance_engine_id not in engines.degraded_appearance_ids:
            engines.degraded_appearance_ids.add(engines.appearance_engine_id)
            LOGGER.warning(
                "appearance extractor %r raised (%s); this frame runs without appearance evidence",
                engines.appearance_engine_id,
                exc,
            )
        engines.forget_appearance_extractor()
        return [None] * len(boxes)
    return list(described)

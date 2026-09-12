"""`predict.cv` -- where every live track is, now, by constant velocity.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1. `predict()` itself is
untouched (`tracking/predict.py`); this contributor only decides WHEN the
whole book is predicted and publishes the answer, so the associator stops
being the thing that happens to call it.

**It also owns the snapshot.** `TRACKS_PREV` is captured here, once, and
`PREDICTIONS[i]` is `TRACKS_PREV[i]`'s `Prediction` by construction -- the
"captured once, read twice" invariant `_run_cost_associate` used to state in
a comment and defend by nothing. A second `book.tracks` read is only correct
by coincidence, and nothing should have to promise that to stay correct.

`PREDICTIONS` carries the whole `Prediction`, not just its box (CV-
ORCHESTRATION wave W1): `Prediction.horizon_seconds` is how far the clamp
actually let the extrapolation reach, and the only moment that is knowable is
HERE -- by the time the aggregator has folded, every touched track's
`last_seen` has already advanced to now and the interval is gone.

**Why this runs after ego-motion and not before.** `TrackBook.warp` has
already carried every stored box into THIS frame's coordinates, so `predict`
needs no transform of its own. Ordering is `PHASE_PRE` before `PHASE_TRACK`,
which the orchestrator guarantees structurally.

FOLLOW does not register this node: it predicts one held target and each
extra individually, inside the follow contributor, and a whole-book
prediction nobody reads would be new per-frame work. Recorded in
`cv/cv-service/MODULE.md` as a deviation from the plan's mapping table.
"""

from __future__ import annotations

from typing import Any

from cv_service.orchestration.budget import PREDICT_CV
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext
from cv_service.orchestration.keys import Key
from cv_service.tracking.predict import predict
from cv_service.tracking.track import TrackBook


class PredictConstantVelocity:
    """Predict every live track to this frame's instant."""

    id = PREDICT_CV
    family = PREDICT_CV
    reads: "frozenset[Key]" = frozenset()
    writes = frozenset({Key.TRACKS_PREV, Key.PREDICTIONS})
    min_level = 0
    phase = PHASE_TRACK

    def __init__(self, book: TrackBook) -> None:
        self._book = book

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        tracks = self._book.tracks
        predictions = [predict(track, ctx.now) for track in tracks]
        return Contribution(
            outputs={Key.TRACKS_PREV: tracks, Key.PREDICTIONS: predictions},
            summary={"tracks": str(len(tracks))},
            evidence={
                track.track_id: {
                    "predicted": _box_text(prediction.box),
                    "held": _box_text(track.box),
                    "velocity": f"{track.velocity_x!r},{track.velocity_y!r}",
                }
                for track, prediction in zip(tracks, predictions)
            },
        )


def _box_text(box: Any) -> str:
    return f"{box.x!r},{box.y!r},{box.width!r},{box.height!r}"

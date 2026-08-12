"""Wave C3 acceptance: two crossing objects with distinct appearance keep
their ids -- the defect in the OPERATOR's terms, not a unit test of the cost
function (already covered, brute-force-verified, by `test_assign.py`).

Uses the REAL harness (`tools/trackeval`) and its `crossing` scenario, built
specifically to isolate REVIEW finding B1 ("no appearance model exists
anywhere to disambiguate them") -- not a hand-rolled fake. `run_replay` is
called directly with an explicit `settings=` (not through the CLI, which has
its own documented `CV_TRACK_*`-blindness gap, `cv-service/MODULE.md`'s own
Gotcha) so this test genuinely exercises `CV_TRACK_APPEARANCE_ENGINE`.

Needs `cv2`/`numpy` -- `histogram.py` (HSV extraction) and the harness's own
frame rendering (`tools/trackeval/sequences.py`) both need them; skips
rather than fails when the `cv` extra is absent, same contract every other
`cv2`-needing test file in this package uses.
"""

from __future__ import annotations

import dataclasses

import pytest

pytest.importorskip("cv2")
pytest.importorskip("numpy")

from cv_service.config import Settings
from cv_service.tracking.params import MODE_ASSOCIATE

from tools.trackeval import metrics as metrics_module
from tools.trackeval import replay as replay_module
from tools.trackeval.__main__ import DEFAULT_NOISE_BY_SCENARIO
from tools.trackeval.sequences import DEFAULT_SEED, SCENARIOS


def _run(engine_id: str, *, appearance_engine: str) -> metrics_module.Metrics:
    sequence = SCENARIOS["crossing"](DEFAULT_SEED)
    settings = dataclasses.replace(Settings(), track_appearance_engine=appearance_engine)
    result = replay_module.run_replay(
        sequence,
        mode=MODE_ASSOCIATE,
        engine_id=engine_id,
        detector_config=DEFAULT_NOISE_BY_SCENARIO["crossing"],
        settings=settings,
    )
    return metrics_module.compute(result)


def test_bytetrack_still_swaps_ids_on_the_baseline_crossing_scenario():
    # The pre-wave baseline, reproduced (`tools/trackeval/BASELINE.md`'s own
    # `crossing | ASSOCIATE: IDSW=4`) -- confirms this test is actually
    # measuring the defect, not a scenario that never swapped ids to begin
    # with, and confirms `bytetrack` stays the untouched, selectable, no-
    # appearance baseline TRACKING-V2-PLAN §3.3 requires it to remain.
    metrics = _run("bytetrack", appearance_engine="off")

    assert metrics.idsw > 0


def test_cost_with_histogram_appearance_keeps_both_crossing_objects_ids():
    # THE acceptance test: `crossing`'s two objects are clearly different
    # colours (RED and GREEN, `sequences.py`'s own palette) -- with `cost` +
    # `histogram` doing the matching, appearance breaks the geometric
    # ambiguity an IoU-only matcher cannot, and neither object's id should
    # ever swap onto the other.
    metrics = _run("cost", appearance_engine="histogram")

    assert metrics.idsw == 0

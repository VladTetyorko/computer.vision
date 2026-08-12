"""The end-to-end claim for object memory, asserted in both directions.

`tests/tracking/test_memory.py` proves each of the gallery's four gates
rejects what it should. That is necessary and not sufficient: a gallery can
be perfectly gated and still never be consulted, or be consulted and never
populated -- and `clutter`, the crowd scenario the plan leaned on, turned out
to be one frame short of ever expiring a track, so its clean numbers said
nothing about memory at all.

So these tests assert the operator-visible outcome (does the object come back
under its own number) AND assert that it fails without the gallery. A test
that only checks the feature-on arm cannot tell "this works" from "this
scenario never needed it".
"""

from __future__ import annotations

import dataclasses

import pytest

from cv_service.config import Settings
from cv_service.tracking.params import MODE_ASSOCIATE
from tools.trackeval import metrics, replay
from tools.trackeval.sequences import crowd_recall, long_occlusion

# A gallery TTL of zero is the documented "no memory at all" setting, and it
# is what makes these tests a comparison rather than an assertion of faith.
MEMORY_OFF_TTL = 0


def score(sequence, *, memory: bool):
    settings = Settings() if memory else dataclasses.replace(
        Settings(), track_memory_ttl_millis=MEMORY_OFF_TTL
    )
    return metrics.compute(
        replay.run_replay(sequence, mode=MODE_ASSOCIATE, settings=settings)
    )


@pytest.fixture(scope="module")
def solo():
    sequence = long_occlusion()
    return score(sequence, memory=False), score(sequence, memory=True)


@pytest.fixture(scope="module")
def crowd():
    sequence = crowd_recall()
    return score(sequence, memory=False), score(sequence, memory=True)


def test_without_memory_a_long_absence_costs_the_object_its_identity(solo):
    without, _ = solo
    # The operator's original complaint, as a number: the track is deleted
    # while the object is hidden, and what comes back is a new object.
    assert without.idsw == 1
    assert without.recovery_rate == 0.0


def test_with_memory_the_object_returns_under_its_own_id(solo):
    _, with_memory = solo
    assert with_memory.idsw == 0
    assert with_memory.recovery_rate == pytest.approx(1.0)


def test_without_memory_a_crowd_loses_every_identity(crowd):
    without, _ = crowd
    assert without.idsw == without.gt_object_count
    assert without.recovery_rate == 0.0


def test_memory_does_not_confuse_objects_that_look_identical(crowd):
    # The failure this scenario exists to provoke, and the one that matters
    # most: three PAIRS sharing a colour, so appearance alone is a coin flip
    # between twins. A gallery that leaned on appearance would swap them and
    # hand the operator a confident wrong number -- worse than handing back
    # nothing. Zero swaps means the motion gate is carrying real weight.
    _, with_memory = crowd
    assert with_memory.idsw == 0
    assert with_memory.recovery_rate == pytest.approx(1.0)
    assert with_memory.gt_object_count == 6

"""`cv_service.tracking.history` -- the bounded observation ring (TRACKING-V3-PLAN wave V2).

Pure stdlib: plain `Observation`s and a fake clock, no frames and no OpenCV.
"""

from __future__ import annotations

import subprocess
import sys
import textwrap

from cv_service.tracking.engines.base import SOURCE_DETECTOR, SOURCE_TRACKER, Box, Observation
from cv_service.tracking.history import ObservationRing, TimedObservation


def seen(x=0.1, source=SOURCE_DETECTOR, **kwargs) -> Observation:
    return Observation(
        key="k",
        box=Box(x, 0.1, 0.1, 0.1),
        label="car",
        confidence=0.9,
        source=source,
        **kwargs,
    )


# -- record(): only REAL observations survive --------------------------------


def test_a_detector_observation_is_recorded():
    ring = ObservationRing()

    ring.record(seen(), 1.0)

    assert len(ring) == 1
    assert ring.latest() == TimedObservation(1.0, seen())


def test_a_predicted_observation_is_never_recorded():
    # The load-bearing rule: a coasted/extrapolated box is not evidence.
    ring = ObservationRing()

    ring.record(seen(source=SOURCE_TRACKER, predicted=True), 1.0)

    assert len(ring) == 0
    assert ring.latest() is None


def test_coasting_a_track_for_many_frames_never_grows_the_ring():
    # The acceptance scenario stated directly: predicted boxes are provably
    # never recorded, even across a long coast.
    ring = ObservationRing()
    ring.record(seen(), 0.0)

    for frame in range(1, 50):
        ring.record(seen(source=SOURCE_TRACKER, predicted=True), float(frame))

    assert len(ring) == 1
    assert ring.latest().timestamp == 0.0


def test_a_tracker_produced_observation_is_never_recorded_even_when_not_predicted():
    # The subtler half of the rule: `SOURCE_TRACKER` with `predicted=False`
    # is LK/NCC's own successful per-frame match, not a `predicted=True`
    # coast -- and it is STILL not detector evidence, so it must still be
    # excluded. `not observation.predicted` alone would wrongly admit this.
    ring = ObservationRing()

    ring.record(seen(source=SOURCE_TRACKER, predicted=False), 1.0)

    assert len(ring) == 0


def test_an_authoritative_detector_observation_is_recorded():
    # FOLLOW's operator-chosen lock re-anchor: `authoritative=True` decorates
    # a `SOURCE_DETECTOR` observation (see `observation_for`'s own default in
    # `track.py`) -- it is real evidence, not a different kind of value, and
    # needs no special case in the ring.
    ring = ObservationRing()

    ring.record(seen(authoritative=True), 1.0)

    assert len(ring) == 1


# -- bounded by construction ---------------------------------------------------


def test_the_ring_never_grows_past_its_capacity():
    ring = ObservationRing(capacity=4)

    for frame in range(100):
        ring.record(seen(x=float(frame)), float(frame))

    assert len(ring) == 4


def test_the_ring_keeps_the_newest_entries_when_it_overflows():
    ring = ObservationRing(capacity=3)

    for frame in range(10):
        ring.record(seen(x=float(frame)), float(frame))

    assert ring.latest().timestamp == 9.0
    # Walk `before()` backward from just past the end to recover every
    # surviving entry and confirm it is exactly the last 3 recorded, oldest
    # discarded first.
    third = ring.before(10.0)
    second = ring.before(third.timestamp)
    first = ring.before(second.timestamp)
    assert (third.timestamp, second.timestamp, first.timestamp) == (9.0, 8.0, 7.0)
    assert first is not None
    assert ring.before(first.timestamp) is None


def test_capacity_is_clamped_to_at_least_one():
    ring = ObservationRing(capacity=0)
    ring.record(seen(x=1.0), 1.0)
    ring.record(seen(x=2.0), 2.0)

    assert ring.capacity == 1
    assert len(ring) == 1
    assert ring.latest().timestamp == 2.0


def test_default_capacity_is_a_named_constant_greater_than_the_ocm_default_span():
    # Sized against something, not a round number (P4) -- see the module's
    # own comment on `_DEFAULT_CAPACITY` for the rationale. This test only
    # pins the property that matters to callers: comfortably more than the
    # OCM default span (3, TRACKING-V3-PLAN §3's `momentum_span_frames`).
    ring = ObservationRing()

    assert ring.capacity > 3


# -- latest() ------------------------------------------------------------------


def test_latest_is_none_for_an_empty_ring():
    assert ObservationRing().latest() is None


def test_latest_is_the_most_recently_recorded_entry():
    ring = ObservationRing()
    ring.record(seen(x=0.1), 1.0)
    ring.record(seen(x=0.2), 2.0)

    assert ring.latest().timestamp == 2.0
    assert ring.latest().observation.box.x == 0.2


# -- before(): ORU's t1 ----------------------------------------------------


def test_before_is_none_for_an_empty_ring():
    assert ObservationRing().before(5.0) is None


def test_before_is_none_when_the_ring_has_a_single_entry_recorded_after_the_query():
    ring = ObservationRing()
    ring.record(seen(), 5.0)

    assert ring.before(1.0) is None


def test_before_returns_the_single_entry_when_it_predates_the_query():
    ring = ObservationRing()
    ring.record(seen(), 5.0)

    found = ring.before(10.0)

    assert found.timestamp == 5.0


def test_before_is_none_when_the_query_predates_everything_retained():
    # A gap older than the ring's own retained history -- `reupdate.py`
    # treats this the same as "no bracketing pair" (TRACKING-V3-PLAN §4.2).
    ring = ObservationRing()
    ring.record(seen(), 100.0)
    ring.record(seen(), 101.0)

    assert ring.before(50.0) is None


def test_before_returns_the_closest_entry_strictly_before_the_timestamp():
    ring = ObservationRing()
    for timestamp in (1.0, 2.0, 3.0, 4.0):
        ring.record(seen(x=timestamp), timestamp)

    found = ring.before(3.5)

    assert found.timestamp == 3.0


def test_before_is_strict_and_does_not_match_an_exact_timestamp():
    ring = ObservationRing()
    ring.record(seen(), 1.0)
    ring.record(seen(), 2.0)

    found = ring.before(2.0)

    assert found.timestamp == 1.0


# -- span(): OCM's Δt pair ---------------------------------------------------


def test_span_is_none_for_an_empty_ring():
    assert ObservationRing().span(1) is None


def test_span_is_none_for_a_single_entry():
    ring = ObservationRing()
    ring.record(seen(), 1.0)

    assert ring.span(1) is None


def test_span_is_none_for_zero_or_negative_frames():
    ring = ObservationRing()
    ring.record(seen(), 1.0)
    ring.record(seen(), 2.0)

    assert ring.span(0) is None
    assert ring.span(-1) is None


def test_span_wider_than_what_the_ring_currently_holds_is_none():
    ring = ObservationRing(capacity=10)
    ring.record(seen(), 1.0)
    ring.record(seen(), 2.0)

    # Only 2 entries recorded -- a 5-apart span cannot be honoured, even
    # though capacity would allow it eventually.
    assert ring.span(5) is None


def test_span_wider_than_the_rings_own_capacity_is_always_none():
    ring = ObservationRing(capacity=3)
    for frame in range(20):
        ring.record(seen(x=float(frame)), float(frame))

    # Never satisfiable, no matter how long the track lives.
    assert ring.span(5) is None


def test_span_returns_the_latest_and_the_entry_frames_before_it_oldest_first():
    ring = ObservationRing(capacity=10)
    for timestamp in (1.0, 2.0, 3.0, 4.0):
        ring.record(seen(x=timestamp), timestamp)

    older, newer = ring.span(2)

    assert older.timestamp == 2.0
    assert newer.timestamp == 4.0


def test_span_of_one_pairs_the_two_most_recent_entries():
    ring = ObservationRing()
    ring.record(seen(), 1.0)
    ring.record(seen(), 2.0)
    ring.record(seen(), 3.0)

    older, newer = ring.span(1)

    assert (older.timestamp, newer.timestamp) == (2.0, 3.0)


# -- purity: importable with no cv2/numpy/ultralytics ------------------------

_IMPORT_SCRIPT = textwrap.dedent(
    """
    import sys

    import cv_service.tracking.history  # noqa: F401

    leaked = sorted({"cv2", "numpy", "ultralytics", "torch", "lap"} & set(sys.modules))
    assert not leaked, "history.py imported: %r" % (leaked,)
    print("HISTORY_PURE_OK")
    """
)


def test_history_imports_nothing_heavy_in_a_clean_interpreter():
    # In-process is worthless here -- `tests/conftest.py` already imports
    # `numpy` for its own fixtures at collection time, so by the time any
    # test function in this file runs, `numpy` is already in `sys.modules`
    # regardless of what `history.py` itself did. A genuinely clean `python
    # -c` subprocess is the same pattern `tests/tracking/test_levels.py`
    # uses for the identical reason (see that file's own module docstring).
    result = subprocess.run(
        [sys.executable, "-c", _IMPORT_SCRIPT],
        capture_output=True,
        text=True,
        timeout=30,
    )

    assert result.returncode == 0, f"stdout={result.stdout!r} stderr={result.stderr!r}"
    assert "HISTORY_PURE_OK" in result.stdout

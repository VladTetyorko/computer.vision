"""`cv_service.tracking.reupdate` -- ORU, the Observation-Centric Re-Update.

TRACKING-V3-PLAN wave V3. Pure stdlib: a plain `Track`/`ObservationRing`,
no engines, no frames, no gRPC.
"""

from __future__ import annotations

import subprocess
import sys
import textwrap

import pytest

from cv_service.tracking.engines.base import (
    SOURCE_DETECTOR,
    SOURCE_TRACKER,
    Box,
    Observation,
    Transform,
)
from cv_service.tracking.reupdate import reupdate
from cv_service.tracking.track import Track


def real(x: float, y: float, *, key: object = "k") -> Observation:
    return Observation(key=key, box=Box(x, y, 0.1, 0.1), label="car", confidence=0.9, source=SOURCE_DETECTOR)


def track(**overrides) -> Track:
    base = dict(
        track_id=1,
        key="k",
        box=Box(0.5, 0.5, 0.1, 0.1),
        label="car",
        confidence=0.9,
        first_seen=0.0,
        last_seen=0.0,
        last_confirmed=0.0,
        misses=1,
    )
    base.update(overrides)
    return Track(**base)


# -- the bracket must exist ---------------------------------------------------


def test_none_when_the_ring_is_empty():
    subject = track()

    assert reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=10_000) is None


def test_none_when_the_gap_is_not_positive():
    subject = track()
    subject.history.record(real(0.1, 0.1), 1.0)

    # `now` at or before the bracket's own timestamp is not a gap to bridge.
    assert reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=10_000) is None
    assert reupdate(subject, subject.history, real(0.5, 0.5), now=0.5, max_gap_millis=10_000) is None


def test_none_when_the_gap_exceeds_the_ceiling():
    subject = track()
    subject.history.record(real(0.1, 0.1), 0.0)

    assert reupdate(subject, subject.history, real(0.5, 0.5), now=10.001, max_gap_millis=10_000) is None


def test_a_gap_exactly_at_the_ceiling_is_still_reconstructed():
    subject = track()
    subject.history.record(real(0.1, 0.1), 0.0)

    result = reupdate(subject, subject.history, real(0.5, 0.5), now=10.0, max_gap_millis=10_000)

    assert result is not None


def test_a_non_positive_ceiling_is_a_genuine_off_switch():
    # invariant P7: `reupdate_max_gap_millis <= 0` must disable ORU outright,
    # regardless of how good the bracket is -- every real gap is positive,
    # so this can never be satisfied once the ceiling is not.
    subject = track()
    subject.history.record(real(0.1, 0.1), 0.0)

    assert reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=0) is None
    assert reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=-1) is None


# -- the math ------------------------------------------------------------------


def test_velocity_is_the_average_over_the_true_gap_not_one_frame():
    # The defect ORU exists to correct: a track's OWN state (`track.box`,
    # `last_seen`) may have drifted or gone stale during the gap, but the
    # ring's real bracket and the true elapsed time are what must drive the
    # reconstructed velocity, not whatever `track.box`/`last_seen` say.
    subject = track(box=Box(0.9, 0.9, 0.1, 0.1), last_seen=9.9)  # badly drifted/stale
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.5, 0.0), now=5.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.1)  # (0.5 - 0.0) / 5.0
    assert result.velocity_y == pytest.approx(0.0)


def test_gap_millis_is_the_true_elapsed_time_in_milliseconds():
    subject = track()
    subject.history.record(real(0.0, 0.0), 1.0)

    result = reupdate(subject, subject.history, real(0.2, 0.2), now=3.5, max_gap_millis=10_000)

    assert result is not None
    assert result.gap_millis == 2500


def test_steps_is_track_misses_floored_at_one():
    subject = track(misses=0)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.1, 0.1), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.steps == 1  # floored, even though misses is 0

    subject2 = track(misses=7)
    subject2.history.record(real(0.0, 0.0), 0.0)
    result2 = reupdate(subject2, subject2.history, real(0.1, 0.1), now=1.0, max_gap_millis=10_000)
    assert result2.steps == 7


def test_drift_before_is_the_estimators_own_error_and_drift_after_is_zero():
    # `track.box` is the DRIFTED/estimated position at the moment of
    # re-anchor; the new observation (z(t2)) is the truth ORU reconstructs
    # toward -- drift_before is exactly the gap between the two.
    subject = track(box=Box(0.9, 0.0, 0.1, 0.1))  # drifted far from the real re-anchor
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.0, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.drift_before == pytest.approx(0.9)  # box centers: (0.95,0.05) vs (0.05,0.05)
    assert result.drift_after == 0.0


def test_a_perfectly_predicted_reanchor_reports_zero_drift():
    subject = track(box=Box(0.5, 0.5, 0.1, 0.1))
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.drift_before == pytest.approx(0.0)


# -- §4.1b: the coordinate-frame fix --------------------------------------


def test_the_bracket_is_warped_by_history_transform_before_interpolating():
    # A pure translation, as `pose_gmc`/`flow_gmc` produce for a panning
    # camera (`Transform.apply_box`'s own docstring: previous frame -> this
    # frame). Without warping the bracket, the reconstructed velocity would
    # be measuring the CAMERA's motion, not the object's.
    pan = Transform(c=0.2)  # the world shifted 0.2 units since t1 was recorded
    subject = track(history_transform=pan)
    subject.history.record(real(0.0, 0.0), 0.0)

    # The object is WORLD-static: its truth at t2, expressed in t2's frame,
    # is exactly where the pan carried its t1 position to.
    result = reupdate(subject, subject.history, real(0.2, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.0, abs=1e-9)
    assert result.velocity_y == pytest.approx(0.0, abs=1e-9)


def test_an_uncorrected_bracket_would_have_reported_the_cameras_own_motion():
    # The control: WITHOUT the fix (history_transform left at IDENTITY, the
    # "accept the error" candidate §4.1b rejected), the same world-static
    # re-anchor is misread as the object moving at the camera's own rate.
    subject = track()  # history_transform defaults to IDENTITY
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.2, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.2)  # wrong: this is the PAN's rate, not the object's


def test_history_transform_never_needs_inverting_only_composing_forward():
    # No `Transform.inverse()` exists (deliberately -- see reupdate.py's own
    # module docstring): confirms a multi-step accumulated transform
    # (as `TrackBook.warp()` builds it, one `.compose()` per frame) still
    # warps the bracket correctly with pure forward composition.
    accumulated = Transform().compose(Transform(c=0.1)).compose(Transform(c=0.1)).compose(Transform(c=0.1))
    subject = track(history_transform=accumulated)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.3, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.0, abs=1e-9)


# -- ObservationRing is the only source of t1 (never track.box) --------------


def test_a_predicted_or_tracker_sourced_entry_is_never_the_bracket():
    # The ring itself already refuses to record these (`history.py`), but
    # this confirms `reupdate()` reads NOTHING else: an empty ring (because
    # only a coasted/tracker observation was ever "seen") means no bracket,
    # full stop -- never falling back to `track.box`.
    subject = track()
    subject.history.record(
        Observation(key="k", box=Box(0.3, 0.3, 0.1, 0.1), label="car", confidence=0.9,
                    source=SOURCE_TRACKER, predicted=True),
        0.0,
    )

    assert reupdate(subject, subject.history, real(0.5, 0.5), now=1.0, max_gap_millis=10_000) is None


def test_the_most_recent_real_observation_is_used_not_an_older_one():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)
    subject.history.record(real(0.4, 0.0), 4.0)  # closer bracket

    result = reupdate(subject, subject.history, real(1.0, 0.0), now=6.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.3)  # (1.0 - 0.4) / (6.0 - 4.0), not from t=0.0


# -- P8: pure stdlib, importable at capability level L1 -----------------------
#
# TRACKING-V3-PLAN invariant P8: "Level 1 must import no cv2, no numpy, no
# ultralytics, and load no model asset." `reupdate.py` carries this wave's
# whole accuracy win and must run on an ARMv6 companion with none of those
# installed. Same "clean subprocess" method `tests/tracking/test_levels.py`
# uses for the same reason: `tests/conftest.py` imports `numpy` for its own
# fixtures, so an in-process check would pass trivially regardless of
# whether this module ever touched it.

_PURITY_SCRIPT = textwrap.dedent(
    """
    import sys

    from cv_service.tracking.reupdate import Reupdate, reupdate
    from cv_service.tracking.history import ObservationRing
    from cv_service.tracking.engines.base import Box, Observation, SOURCE_DETECTOR
    from cv_service.tracking.track import Track

    ring = ObservationRing()
    ring.record(Observation(key="k", box=Box(0.0, 0.0, 0.1, 0.1), label="x",
                             confidence=0.9, source=SOURCE_DETECTOR), 0.0)
    track = Track(track_id=1, key="k", box=Box(0.5, 0.5, 0.1, 0.1), label="x",
                   confidence=0.9, first_seen=0.0, last_seen=0.0,
                   last_confirmed=0.0, misses=1)
    track.history.record(Observation(key="k", box=Box(0.0, 0.0, 0.1, 0.1), label="x",
                                      confidence=0.9, source=SOURCE_DETECTOR), 0.0)
    result = reupdate(
        track, track.history,
        Observation(key="k", box=Box(0.5, 0.0, 0.1, 0.1), label="x",
                    confidence=0.9, source=SOURCE_DETECTOR),
        now=1.0, max_gap_millis=10_000,
    )
    assert result is not None, "reupdate() must succeed on a genuine bracket"

    leaked = sorted({"cv2", "numpy", "ultralytics", "torch", "lap"} & set(sys.modules))
    assert not leaked, "reupdate.py imported: %r" % (leaked,)
    print("P8_OK")
    """
)


def test_reupdate_imports_no_cv_stack_in_a_clean_interpreter():
    result = subprocess.run(
        [sys.executable, "-c", _PURITY_SCRIPT], capture_output=True, text=True, timeout=60
    )

    assert result.returncode == 0, f"stdout={result.stdout!r} stderr={result.stderr!r}"
    assert "P8_OK" in result.stdout

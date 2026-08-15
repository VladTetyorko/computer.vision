"""`cv_service.tracking.reupdate` -- ORU, the Observation-Centric Re-Update.

TRACKING-V3-PLAN wave V3. Pure stdlib: a plain `Track`/`ObservationRing`,
no engines, no frames, no gRPC.
"""

from __future__ import annotations

import logging
import math
import subprocess
import sys
import textwrap

import pytest

import cv_service.tracking.reupdate as reupdate_module
from cv_service.tracking.engines.base import (
    SOURCE_DETECTOR,
    SOURCE_TRACKER,
    Box,
    Observation,
    Transform,
)
from cv_service.tracking.reupdate import late_correction, reupdate
from cv_service.tracking.track import Track


def real(x: float, y: float, *, key: object = "k") -> Observation:
    return Observation(key=key, box=Box(x, y, 0.1, 0.1), label="car", confidence=0.9, source=SOURCE_DETECTOR)


def sized(x: float, y: float, w: float, h: float, *, key: object = "k") -> Observation:
    """Like `real()` above, but with an explicit box size -- the shape check
    section below needs boxes that are not the fixed 0.1x0.1 `real()` gives."""
    return Observation(key=key, box=Box(x, y, w, h), label="car", confidence=0.9, source=SOURCE_DETECTOR)


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


# -- 2026-08-14 repair, part 2: the plausibility guard -----------------------
#
# `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4: ORU made MOT17 IDSW
# worse in 15 of 21 scene/detector pairs because it had no test that the
# bracket's two real observations are plausibly the same object. These tests
# are that guard's own acceptance criteria: it fires on an absurd implied
# velocity (#1), `<= 0` reproduces the pre-repair reconstruction exactly
# (#2, invariant P7), and `late_correction` -- which calls `reupdate()` for
# its own velocity rather than re-deriving it -- is genuinely covered too,
# not merely assumed to be.


def test_none_when_the_implied_velocity_exceeds_the_bound():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    # (10.0 - 0.0) / 1.0 = 10.0/s, far over the 5.0/s bound given here.
    result = reupdate(
        subject,
        subject.history,
        real(10.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is None


def test_the_y_axis_is_checked_independently_of_x():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(0.0, 10.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is None


def test_a_velocity_within_the_bound_is_not_refused():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(4.9, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is not None
    assert result.velocity_x == pytest.approx(4.9)


def test_a_velocity_exactly_at_the_bound_is_not_refused():
    # The check is strictly-greater-than: a reconstruction that lands
    # exactly on the bound is not itself evidence of a wrong bracket.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(5.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is not None


def test_a_refused_reconstruction_leaves_no_partial_or_clamped_answer():
    # The fix refuses outright rather than clamping -- `result` must be
    # `None`, never a `Reupdate` with the velocity capped at the bound.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(10.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is None


def test_a_non_positive_velocity_bound_is_a_genuine_off_switch():
    # invariant P7, the SAME shape `max_gap_millis`'s own test above proves:
    # `max_velocity_per_second <= 0` must reproduce the reconstruction
    # exactly, however absurd the implied velocity.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    for bound in (0.0, -1.0):
        result = reupdate(
            subject,
            subject.history,
            real(10.0, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_velocity_per_second=bound,
        )
        assert result is not None
        assert result.velocity_x == pytest.approx(10.0)


def test_the_default_velocity_bound_is_disabled_reproducing_pre_repair_behaviour():
    # No `max_velocity_per_second` argument at all -- every call site this
    # module had before this repair -- must behave identically to an
    # explicit non-positive bound, i.e. today's exact behaviour (P7).
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(10.0, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(10.0)


def test_late_correction_is_none_when_the_reconstructed_velocity_is_implausible():
    # `late_correction` does not re-derive velocity -- it CALLS `reupdate()`
    # for it -- so this confirms the guard genuinely covers that path too,
    # rather than assuming a shared call automatically inherits it.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    # captured_at = 1.0 - 0.5 = 0.5s; (10.0 - 0.0) / 0.5 = 20.0/s, over the
    # 5.0/s bound given here.
    result = late_correction(
        subject,
        subject.history,
        Box(10.0, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
    )

    assert result is None


def test_late_correction_still_succeeds_when_the_bound_is_disabled():
    # invariant P7 for `late_correction` specifically: the same absurd
    # bracket that the guard refuses above must still be applied when the
    # bound is left at its disabled default, exactly as before this repair.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(10.0, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
    )

    assert result is not None


def test_the_guard_is_logged_once_per_process_never_per_frame(monkeypatch, caplog):
    # P5: a refused reconstruction is a degradation, never raised, and
    # logged at most once -- not once per frame, however many times the
    # same stream keeps producing an implausible bracket.
    monkeypatch.setattr(reupdate_module, "_implausible_velocity_logged", False)
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        for velocity_x in (10.0, 20.0, 30.0):
            result = reupdate(
                subject,
                subject.history,
                real(velocity_x, 0.0),
                now=1.0,
                max_gap_millis=10_000,
                max_velocity_per_second=5.0,
            )
            assert result is None

    assert sum("implausible" in record.getMessage() for record in caplog.records) == 1


def test_a_plausible_reconstruction_never_logs_anything(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_implausible_velocity_logged", False)
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        result = reupdate(
            subject,
            subject.history,
            real(0.1, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_velocity_per_second=5.0,
        )

    assert result is not None
    assert caplog.records == []


# -- 2026-08-15 density gate: crowding, not merely velocity ------------------
#
# `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b: split by scene
# density, ORU improved 0 of 7 crowded scenes (net +320 IDSW) against 5 of 14
# sparse scenes (net +57). These tests are that gate's own acceptance
# criteria: it fires on a bracket whose implied velocity is perfectly
# plausible, purely because the book is crowded (#1), `<= 0` reproduces the
# pre-gate reconstruction exactly however crowded the book (#2, invariant
# P7), and `late_correction` -- which calls `reupdate()` for its own velocity
# rather than re-deriving it -- is genuinely covered too, not merely assumed
# to be.


def test_none_when_live_track_count_exceeds_the_density_gate():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    # A small, entirely plausible velocity (0.5/s) -- only density refuses
    # this reconstruction; a bracket that would reconstruct fine at low
    # density returns `None` once the book is above the threshold.
    result = reupdate(
        subject,
        subject.history,
        real(0.5, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=4,
    )

    assert result is None


def test_a_bracket_below_the_density_gate_is_not_refused():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(0.5, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=2,
    )

    assert result is not None
    assert result.velocity_x == pytest.approx(0.5)


def test_a_live_track_count_exactly_at_the_gate_is_not_refused():
    # Same strictly-greater-than convention `max_velocity_per_second`'s own
    # test proves: a book exactly at the ceiling is not itself evidence of
    # an untrustworthy bracket.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(0.5, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=3,
    )

    assert result is not None


def test_a_refused_high_density_reconstruction_leaves_no_partial_or_clamped_answer():
    # The gate refuses outright rather than clamping -- `result` must be
    # `None`, never a `Reupdate` computed regardless of the book's size.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(0.5, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=10,
    )

    assert result is None


def test_a_non_positive_track_count_ceiling_is_a_genuine_off_switch():
    # invariant P7, the SAME shape `max_velocity_per_second`'s own test
    # above proves: `max_track_count <= 0` must reproduce the reconstruction
    # exactly, however crowded the book.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    for bound in (0, -1):
        result = reupdate(
            subject,
            subject.history,
            real(0.5, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_track_count=bound,
            live_track_count=1_000,
        )
        assert result is not None
        assert result.velocity_x == pytest.approx(0.5)


def test_the_default_density_gate_is_disabled_reproducing_pre_gate_behaviour():
    # No `max_track_count`/`live_track_count` argument at all -- every call
    # site this module had before this gate -- must behave identically to an
    # explicit non-positive bound, i.e. today's exact behaviour (P7,
    # acceptance #2/#3: the default leaves everything byte-identical).
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.5, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None
    assert result.velocity_x == pytest.approx(0.5)


def test_late_correction_is_none_when_live_track_count_exceeds_the_density_gate():
    # `late_correction` does not re-derive velocity -- it CALLS `reupdate()`
    # for it -- so this confirms the density gate genuinely covers that path
    # too, rather than assuming a shared call automatically inherits it.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.2, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=4,
    )

    assert result is None


def test_late_correction_still_succeeds_when_the_density_gate_is_disabled():
    # invariant P7 for `late_correction` specifically: a crowded book that
    # the gate would otherwise refuse must still be applied when the ceiling
    # is left at its disabled default, exactly as before this gate.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.2, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
        live_track_count=1_000,
    )

    assert result is not None


def test_the_density_gate_is_logged_once_per_process_never_per_frame(monkeypatch, caplog):
    # P5: a gated-out reconstruction is a degradation, never raised, and
    # logged at most once -- not once per frame, however many times the
    # same stream keeps producing a too-crowded bracket.
    monkeypatch.setattr(reupdate_module, "_high_density_logged", False)
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        for live_track_count in (4, 5, 6):
            result = reupdate(
                subject,
                subject.history,
                real(0.5, 0.0),
                now=1.0,
                max_gap_millis=10_000,
                max_track_count=3,
                live_track_count=live_track_count,
            )
            assert result is None

    assert sum("density gate" in record.getMessage() for record in caplog.records) == 1


def test_a_sparse_book_never_logs_the_density_gate(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_high_density_logged", False)
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        result = reupdate(
            subject,
            subject.history,
            real(0.5, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_track_count=3,
            live_track_count=1,
        )

    assert result is not None
    assert caplog.records == []


def test_the_density_gate_and_velocity_guard_are_independent():
    # A book too crowded to trust AND an implausible velocity: the density
    # gate must still refuse even though the velocity guard is disabled
    # here, and vice versa -- neither guard's absence hides the other's
    # presence.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    density_only = reupdate(
        subject,
        subject.history,
        real(10.0, 0.0),  # implausible if the velocity guard were active
        now=1.0,
        max_gap_millis=10_000,
        max_track_count=3,
        live_track_count=4,  # over the density ceiling
        # max_velocity_per_second left at its disabled default (0.0)
    )
    assert density_only is None

    velocity_only = reupdate(
        subject,
        subject.history,
        real(10.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,
        live_track_count=1,  # well under a ceiling that is disabled anyway
        # max_track_count left at its disabled default (0)
    )
    assert velocity_only is None


# -- 2026-08-15 bracket-identity check: Check A, shape consistency -----------
#
# `docs/conclusions/TRACKING-RECOVERY-RESEARCH.md` §2.1: neither guard above
# ever tests whether the bracket's two observations ARE the same object.
# Check A refuses a reconstruction whose box changed shape too abruptly
# across the gap (`|ln(w2/w1)|` or `|ln(h2/h1)|` over `max_shape_log_ratio`).


def test_none_when_the_shape_changed_too_abruptly():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    # width 0.1 -> 0.5 is a 5x growth; |ln(5)| ~= 1.609, over the 0.5 bound.
    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 0.5, 0.1),
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
    )

    assert result is None


def test_the_height_axis_is_checked_independently_of_width():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    # width unchanged; height 0.1 -> 0.5 alone must still refuse.
    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 0.1, 0.5),
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
    )

    assert result is None


def test_a_modest_shape_change_within_the_bound_is_not_refused():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    # |ln(0.11/0.1)| ~= 0.095, well under the 0.5 bound given here.
    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 0.11, 0.11),
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
    )

    assert result is not None


def test_a_shape_change_exactly_at_the_bound_is_not_refused():
    # Same strictly-greater-than convention every other guard's own boundary
    # test proves: a bracket landing exactly on the bound is not itself
    # evidence of a wrong bracket.
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)
    bound = math.log(2.0)  # exactly a 2x growth

    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 0.2, 0.1),
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=bound,
    )

    assert result is not None


def test_a_refused_shape_reconstruction_leaves_no_partial_or_clamped_answer():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 5.0, 5.0),
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
    )

    assert result is None


def test_a_non_positive_shape_bound_is_a_genuine_off_switch():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    for bound in (0.0, -1.0):
        result = reupdate(
            subject,
            subject.history,
            sized(0.0, 0.0, 10.0, 10.0),  # absurd growth
            now=1.0,
            max_gap_millis=10_000,
            max_shape_log_ratio=bound,
        )
        assert result is not None


def test_the_default_shape_bound_is_disabled_reproducing_pre_check_behaviour():
    # No `max_shape_log_ratio` argument at all must behave identically to an
    # explicit non-positive bound (P7).
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    result = reupdate(subject, subject.history, sized(0.0, 0.0, 10.0, 10.0), now=1.0, max_gap_millis=10_000)

    assert result is not None


def test_a_degenerate_box_does_not_crash_the_shape_check():
    # A non-positive dimension makes that AXIS inconclusive, never a crash --
    # `math.log` of a non-positive number is undefined, and this function's
    # contract throughout is "never raise", not "validate the caller's box".
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.0, 0.1), 0.0)  # degenerate width at t1

    result = reupdate(
        subject,
        subject.history,
        sized(0.0, 0.0, 0.1, 0.1),  # height ratio 1.0 -- well within bound
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.01,
    )

    assert result is not None


def test_the_shape_guard_is_logged_once_per_process_never_per_frame(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_implausible_shape_logged", False)
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        for width in (0.5, 0.6, 0.7):
            result = reupdate(
                subject,
                subject.history,
                sized(0.0, 0.0, width, 0.1),
                now=1.0,
                max_gap_millis=10_000,
                max_shape_log_ratio=0.5,
            )
            assert result is None

    assert sum("shape" in record.getMessage() for record in caplog.records) == 1


def test_a_plausible_shape_never_logs_anything(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_implausible_shape_logged", False)
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        result = reupdate(
            subject,
            subject.history,
            sized(0.0, 0.0, 0.11, 0.11),
            now=1.0,
            max_gap_millis=10_000,
            max_shape_log_ratio=0.5,
        )

    assert result is not None
    assert caplog.records == []


def test_late_correction_is_none_when_the_shape_changed_too_abruptly():
    # `late_correction` does not re-derive the shape check -- it CALLS
    # `reupdate()` -- so this confirms the check genuinely covers that path
    # too, rather than assuming a shared call automatically inherits it.
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.0, 0.0, 0.5, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
    )

    assert result is None


def test_late_correction_still_succeeds_when_the_shape_bound_is_disabled():
    subject = track()
    subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.0, 0.0, 0.5, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
    )

    assert result is not None


# -- 2026-08-15 bracket-identity check: Check B, motion plausibility ---------
#
# Forward-predicts the bracket's earlier observation to the later one's own
# timestamp using the TRACK's own PRE-GAP velocity (`track.velocity_x`/
# `velocity_y` as they stand BEFORE this call's own reconstruction below
# overwrites them), and refuses when that forecast lands more than
# `max_motion_center_distance` box-diagonals from the real later
# observation's centre.


def test_none_when_the_motion_forecast_lands_far_from_the_real_observation():
    subject = track(velocity_x=0.0, velocity_y=0.0)  # a stationary pre-gap estimate
    subject.history.record(real(0.0, 0.0), 0.0)

    # v=0 forecasts the centre stays at (0.05, 0.05); the real t2 lands at
    # (0.95, 0.05) -- 0.9 away, against a ~0.1414 box-diagonal scale, well
    # over the 2.0-diagonal bound given here.
    result = reupdate(
        subject,
        subject.history,
        real(0.9, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
    )

    assert result is None


def test_a_motion_forecast_that_matches_exactly_is_not_refused():
    subject = track(velocity_x=0.1, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    # v=0.1/s forecasts the centre moves from 0.05 to 0.15 over the 1s gap;
    # the real t2 lands exactly there.
    result = reupdate(
        subject,
        subject.history,
        real(0.1, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
    )

    assert result is not None


def test_a_moderate_forecast_mismatch_within_the_bound_is_not_refused():
    subject = track(velocity_x=0.1, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    # Forecast centre: 0.15. Real t2 centre: 0.20 -- 0.05 off, well within
    # 2.0 box-diagonals (~0.283) of slack, proving this is not a
    # match-exactly-or-nothing test.
    result = reupdate(
        subject,
        subject.history,
        real(0.15, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
    )

    assert result is not None


def test_a_refused_motion_reconstruction_leaves_no_partial_or_clamped_answer():
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(0.9, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
    )

    assert result is None


def test_a_non_positive_motion_bound_is_a_genuine_off_switch():
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    for bound in (0.0, -1.0):
        result = reupdate(
            subject,
            subject.history,
            real(0.9, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_motion_center_distance=bound,
        )
        assert result is not None


def test_the_default_motion_bound_is_disabled_reproducing_pre_check_behaviour():
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(subject, subject.history, real(0.9, 0.0), now=1.0, max_gap_millis=10_000)

    assert result is not None


def test_a_degenerate_box_does_not_crash_the_motion_check():
    # A non-positive diagonal on BOTH boxes makes the scale inconclusive,
    # never a division by zero.
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(sized(0.0, 0.0, 0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        sized(0.9, 0.0, 0.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=0.01,  # would otherwise refuse
    )

    assert result is not None


def test_the_motion_guard_is_logged_once_per_process_never_per_frame(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_implausible_motion_logged", False)
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        for x in (0.9, 0.8, 0.7):
            result = reupdate(
                subject,
                subject.history,
                real(x, 0.0),
                now=1.0,
                max_gap_millis=10_000,
                max_motion_center_distance=2.0,
            )
            assert result is None

    assert sum("forecast" in record.getMessage() for record in caplog.records) == 1


def test_a_plausible_motion_forecast_never_logs_anything(monkeypatch, caplog):
    monkeypatch.setattr(reupdate_module, "_implausible_motion_logged", False)
    subject = track(velocity_x=0.1, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.reupdate"):
        result = reupdate(
            subject,
            subject.history,
            real(0.1, 0.0),
            now=1.0,
            max_gap_millis=10_000,
            max_motion_center_distance=2.0,
        )

    assert result is not None
    assert caplog.records == []


def test_late_correction_is_none_when_the_motion_forecast_is_implausible():
    # `late_correction` does not re-derive the motion check -- it CALLS
    # `reupdate()` -- so this confirms the check genuinely covers that path
    # too, rather than assuming a shared call automatically inherits it.
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.9, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
    )

    assert result is None


def test_late_correction_still_succeeds_when_the_motion_bound_is_disabled():
    subject = track(velocity_x=0.0, velocity_y=0.0)
    subject.history.record(real(0.0, 0.0), 0.0)

    result = late_correction(
        subject,
        subject.history,
        Box(0.9, 0.0, 0.1, 0.1),
        now=1.0,
        lag_seconds=0.5,
        max_gap_millis=10_000,
    )

    assert result is not None


# -- 2026-08-15 bracket-identity check: A and B are independent off switches -


def test_the_shape_and_motion_checks_are_independent():
    # Acceptance #2 (P7): each of A/B is individually an off switch, and
    # neither's presence or absence hides the other's. A bracket built to
    # fail BOTH checks is refused with only A enabled, refused with only B
    # enabled, and reconstructs when both are left at their disabled
    # default -- all three proven in one place because they share the SAME
    # bad bracket.
    def fresh() -> Track:
        subject = track(velocity_x=0.0, velocity_y=0.0)
        subject.history.record(sized(0.0, 0.0, 0.1, 0.1), 0.0)
        return subject

    # Fails shape (5x growth, |ln(5)| ~= 1.609 > 0.5) AND motion (forecast
    # centre 0.05 vs real centre ~1.15, far over a 2.0-diagonal bound).
    bad = sized(0.9, 0.0, 0.5, 0.5)

    subject_a = fresh()
    shape_only = reupdate(
        subject_a,
        subject_a.history,
        bad,
        now=1.0,
        max_gap_millis=10_000,
        max_shape_log_ratio=0.5,
        # max_motion_center_distance left at its disabled default
    )
    assert shape_only is None

    subject_b = fresh()
    motion_only = reupdate(
        subject_b,
        subject_b.history,
        bad,
        now=1.0,
        max_gap_millis=10_000,
        max_motion_center_distance=2.0,
        # max_shape_log_ratio left at its disabled default
    )
    assert motion_only is None

    subject_neither = fresh()
    neither = reupdate(subject_neither, subject_neither.history, bad, now=1.0, max_gap_millis=10_000)
    assert neither is not None


def test_the_new_checks_do_not_interfere_with_the_existing_velocity_guard():
    # A bracket that passes BOTH new checks (unchanged shape, and a
    # pre-gap velocity that forecasts t2 exactly) can still be refused by
    # the pre-existing, unrelated velocity guard -- the new checks are
    # ADDED gates, not a replacement.
    subject = track(velocity_x=10.0, velocity_y=0.0)  # pre-gap velocity already matches
    subject.history.record(real(0.0, 0.0), 0.0)

    result = reupdate(
        subject,
        subject.history,
        real(10.0, 0.0),
        now=1.0,
        max_gap_millis=10_000,
        max_velocity_per_second=5.0,  # the reconstructed 10.0/s trips this
        max_shape_log_ratio=0.5,  # unchanged 0.1x0.1 -> passes
        max_motion_center_distance=1.0,  # forecast matches exactly -> passes
    )

    assert result is None  # the untouched velocity guard alone refuses it


# -- late_correction (TRACKING-V3-PLAN wave V6, §4.5) ------------------------


def test_late_correction_is_none_for_a_non_positive_lag():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    assert late_correction(subject, subject.history, Box(0.1, 0.0, 0.1, 0.1), now=1.0, lag_seconds=0.0, max_gap_millis=10_000) is None
    assert late_correction(subject, subject.history, Box(0.1, 0.0, 0.1, 0.1), now=1.0, lag_seconds=-0.5, max_gap_millis=10_000) is None


def test_late_correction_is_none_with_no_bracket_to_reconstruct_from():
    subject = track()  # empty ring -- a brand-new track has nothing to bracket

    assert late_correction(subject, subject.history, Box(0.1, 0.0, 0.1, 0.1), now=1.0, lag_seconds=0.5, max_gap_millis=10_000) is None


def test_late_correction_projects_the_box_forward_by_the_reconstructed_velocity():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)  # the last REAL observation

    # `box` is this frame's raw, just-arrived detection: `lag_seconds=0.5`
    # says it describes `now(1.0) - 0.5 = 0.5`, so `reupdate()` reconstructs
    # velocity from the t=0.0 -> t=0.5 bracket: (0.2-0.0)/0.5 = 0.4/s.
    raw_box = Box(0.2, 0.0, 0.1, 0.1)

    corrected = late_correction(subject, subject.history, raw_box, now=1.0, lag_seconds=0.5, max_gap_millis=10_000)

    assert corrected is not None
    # Projected forward the SAME 0.5s, at the SAME reconstructed rate: +0.2.
    assert corrected.x == pytest.approx(0.4)
    assert corrected.y == pytest.approx(0.0)
    assert corrected.width == pytest.approx(0.1)
    assert corrected.height == pytest.approx(0.1)


def test_late_correction_is_none_when_the_reconstructed_gap_exceeds_the_ceiling():
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    # captured_at = 1.0 - 0.5 = 0.5s; the bracket at t=0.0 is a 500ms gap,
    # over the 100ms ceiling given here.
    assert (
        late_correction(subject, subject.history, Box(0.2, 0.0, 0.1, 0.1), now=1.0, lag_seconds=0.5, max_gap_millis=100)
        is None
    )


def test_a_non_positive_ceiling_disables_late_correction_too():
    # invariant P7: the SAME `reupdate_max_gap_millis` sentinel that
    # switches post-occlusion ORU off must switch this correction off too --
    # reused, not duplicated, so there is only one knob to reason about.
    subject = track()
    subject.history.record(real(0.0, 0.0), 0.0)

    assert late_correction(subject, subject.history, Box(0.2, 0.0, 0.1, 0.1), now=1.0, lag_seconds=0.5, max_gap_millis=0) is None


def test_late_correction_reuses_history_transform_for_the_bracket():
    # A world-static object, correctly reported at the panned position:
    # `history_transform` warps the t=0.0 bracket to read (0.2, 0.0) too, so
    # the reconstructed velocity is genuinely zero and the box is returned
    # unmoved -- the SAME coordinate-frame fix `reupdate()` itself proves
    # (see the tests above), reached through this function instead.
    pan = Transform(c=0.2)
    subject = track(history_transform=pan)
    subject.history.record(real(0.0, 0.0), 0.0)

    raw_box = Box(0.2, 0.0, 0.1, 0.1)
    corrected = late_correction(subject, subject.history, raw_box, now=1.0, lag_seconds=0.5, max_gap_millis=10_000)

    assert corrected is not None
    assert corrected.x == pytest.approx(0.2, abs=1e-9)
    assert corrected.y == pytest.approx(0.0, abs=1e-9)


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

    from cv_service.tracking.reupdate import Reupdate, late_correction, reupdate
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

    # TRACKING-V3-PLAN wave V6 -- the per-detection extension, same P8 bar.
    corrected = late_correction(
        track, track.history, Box(0.5, 0.0, 0.1, 0.1), now=1.0, lag_seconds=0.5,
        max_gap_millis=10_000,
    )
    assert corrected is not None, "late_correction() must succeed on a genuine bracket"

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

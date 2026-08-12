"""`cv_service.tracking.predict` -- constant-velocity extrapolation.

Pure stdlib: a plain `Track`, no engines, no frames. Review finding C1.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import Box
from cv_service.tracking.predict import predict
from cv_service.tracking.track import Track


def track(**overrides) -> Track:
    base = dict(
        track_id=1,
        key="k",
        box=Box(0.1, 0.1, 0.1, 0.1),
        label="car",
        confidence=0.9,
        first_seen=0.0,
        last_seen=0.0,
        last_confirmed=0.0,
        velocity_x=0.0,
        velocity_y=0.0,
    )
    base.update(overrides)
    return Track(**base)


def test_a_coasting_box_moves_under_constant_velocity():
    # THE fix for review finding C1: a lost/coasting track's box must not
    # freeze -- it has to keep moving at the velocity the track itself last
    # measured.
    subject = track(velocity_x=0.05, velocity_y=-0.02, last_seen=0.0)

    prediction = predict(subject, now=1.0)

    assert prediction.box.x == pytest.approx(0.15)
    assert prediction.box.y == pytest.approx(0.08)
    assert prediction.box.width == subject.box.width
    assert prediction.box.height == subject.box.height


def test_a_stationary_track_does_not_move():
    subject = track(velocity_x=0.0, velocity_y=0.0)

    prediction = predict(subject, now=5.0)

    assert prediction.box == subject.box


def test_a_long_gap_is_clamped_rather_than_extrapolated_forever():
    # An absurd velocity held over an absurd gap must not fling the box
    # arbitrarily far across the frame -- the clamp caps how much elapsed
    # time is ever extrapolated, not how far a short gap may travel.
    subject = track(velocity_x=1.0, velocity_y=0.0, last_seen=0.0)

    at_the_clamp = predict(subject, now=2.0).box
    far_beyond_it = predict(subject, now=1000.0).box

    assert far_beyond_it.x == pytest.approx(at_the_clamp.x)


def test_negative_elapsed_is_clamped_to_zero_not_extrapolated_backwards():
    subject = track(velocity_x=1.0, last_seen=10.0)

    prediction = predict(subject, now=0.0)  # now BEFORE last_seen

    assert prediction.box == subject.box


def test_confidence_decays_with_time_since_the_last_detector_confirmation():
    subject = track(last_confirmed=0.0)

    fresh = predict(subject, now=0.0).confidence
    partway = predict(subject, now=3.0).confidence
    long_dormant = predict(subject, now=100.0).confidence

    assert fresh == pytest.approx(1.0)
    assert 0.0 < partway < 1.0
    assert long_dormant == pytest.approx(0.0)


def test_confidence_decay_reads_last_confirmed_not_last_seen():
    # This is the whole reason `last_confirmed` exists rather than reusing
    # `last_seen`: a track that has been coasting (its `last_seen` bumped to
    # ~now every frame) but never re-confirmed by the detector must still
    # read as stale.
    subject = track(last_seen=99.9, last_confirmed=0.0)

    assert predict(subject, now=100.0).confidence == pytest.approx(0.0)


def test_predict_takes_no_transform_argument():
    # TRACKING-V2-PLAN wave C2: warping moved to `TrackBook.warp()`, called
    # once per frame for EVERY live track before anything reads one, so a
    # track stalled N frames accumulates N single-frame warps instead of
    # picking up only the current frame's delta at read time (an up-to-N-x
    # undercorrection the old `predict(track, now, transform)` signature
    # had). By the time `predict()` runs, `track.box` is already expressed
    # in the current frame's coordinates -- see `tests/tracking/test_track.
    # py`'s `TrackBook.warp()` tests for the accumulation proof itself.
    import inspect

    assert "transform" not in inspect.signature(predict).parameters

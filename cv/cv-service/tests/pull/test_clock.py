"""Unit tests for `cv_service.pull.clock.CaptureClock` (MEDIA-SOT-PLAN §6):
`capturedAt = anchorWallclock + (pts - anchorPts)`, re-anchored when measured
skew exceeds a threshold; `arrival` mode as the always-selectable baseline.
"""

from __future__ import annotations

import pytest

from cv_service.pull.clock import ARRIVAL, CaptureClock

T0 = 1_700_000_000_000.0  # an arbitrary wallclock millis epoch


def test_rejects_unknown_mode():
    with pytest.raises(ValueError):
        CaptureClock(mode="ffmpeg_wallclock")  # dropped candidate, CV-PULL-SPIKE.md §4/§6


def test_arrival_mode_reports_wallclock_and_zero_skew():
    clock = CaptureClock(mode=ARRIVAL)
    captured_at, skew = clock.capture_time(pts_millis=12345.0, now_wall_millis=T0)
    assert captured_at == round(T0)
    assert skew == 0

    # A second frame: still just echoes wallclock, PTS is irrelevant.
    captured_at2, skew2 = clock.capture_time(pts_millis=99999.0, now_wall_millis=T0 + 33.0)
    assert captured_at2 == round(T0 + 33.0)
    assert skew2 == 0


def test_anchor_mode_first_frame_anchors_with_zero_skew():
    clock = CaptureClock(mode="anchor")
    captured_at, skew = clock.capture_time(pts_millis=0.0, now_wall_millis=T0)
    assert captured_at == round(T0)
    assert skew == 0


def test_anchor_mode_tracks_pts_delta_from_the_anchor():
    clock = CaptureClock(mode="anchor")
    clock.capture_time(pts_millis=0.0, now_wall_millis=T0)

    # PTS advances by 40ms, wallclock by 40ms too -- no jitter, skew stays 0.
    captured_at, skew = clock.capture_time(pts_millis=40.0, now_wall_millis=T0 + 40.0)
    assert captured_at == round(T0 + 40.0)
    assert skew == 0


def test_anchor_mode_reports_skew_as_transit_latency_drift_since_anchor():
    """Skew is (arrival - captured_at_estimate) -- the CHANGE in transit
    latency since the anchor frame, not raw absolute latency (see clock.py's
    module docstring for the derivation)."""
    clock = CaptureClock(mode="anchor", reanchor_threshold_millis=1000.0)  # high enough not to trigger
    clock.capture_time(pts_millis=0.0, now_wall_millis=T0)

    # PTS advances 100ms, wallclock advances 130ms -- 30ms more latency than
    # at the anchor frame.
    captured_at, skew = clock.capture_time(pts_millis=100.0, now_wall_millis=T0 + 130.0)
    assert captured_at == round(T0 + 100.0)
    assert skew == 30


def test_anchor_mode_reanchors_once_skew_exceeds_the_threshold():
    clock = CaptureClock(mode="anchor", reanchor_threshold_millis=100.0)
    clock.capture_time(pts_millis=0.0, now_wall_millis=T0)

    # Skew grows past 100ms -- must re-anchor: captured_at snaps to THIS
    # frame's own wallclock, skew resets to 0 rather than reporting >100ms.
    captured_at, skew = clock.capture_time(pts_millis=50.0, now_wall_millis=T0 + 200.0)
    assert captured_at == round(T0 + 200.0)
    assert skew == 0

    # The NEW anchor is this frame -- a small subsequent delta tracks from
    # here, not from the original anchor.
    captured_at2, skew2 = clock.capture_time(pts_millis=60.0, now_wall_millis=T0 + 210.0)
    assert captured_at2 == round(T0 + 210.0)
    assert skew2 == 0


def test_anchor_mode_stays_flat_when_skew_never_exceeds_the_threshold():
    """M0's own measurement shape (CV-PULL-SPIKE.md §4): stable transit
    latency over many frames should never trip a re-anchor."""
    clock = CaptureClock(mode="anchor", reanchor_threshold_millis=100.0)
    clock.capture_time(pts_millis=0.0, now_wall_millis=T0)

    skews = []
    for i in range(1, 50):
        pts = i * 33.0
        wall = T0 + i * 33.0 + 15.0  # a constant ~15ms latency offset from the anchor
        _captured_at, skew = clock.capture_time(pts_millis=pts, now_wall_millis=wall)
        skews.append(skew)

    assert all(abs(s - 15) <= 1 for s in skews)


def test_capture_time_reads_the_wallclock_argument_exactly_once_per_call():
    """One-clock-read discipline extends to this boundary too: `capture_time`
    must not call `time.time()`/`time.monotonic()` itself -- the caller
    reads the wallclock once and passes it in. `clock.py` imports no `time`
    at all, which is the structural guarantee: there is nothing to call."""
    import cv_service.pull.clock as clock_module

    assert "time" not in vars(clock_module)

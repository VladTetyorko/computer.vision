"""Unit tests for `cv_service.pull.loop.PullDecodeLoop` -- the reader
thread + deadline sampler pairing that decides which pulled frames actually
get served for inference (MEDIA-SOT-PLAN §7, §8 M3).

Uses a `FakePullSource` (no cv2/RTSP needed) so these run fast and
deterministically; the D8 test below is a real pytest of the exact scenario
`spikes/pull/rate_stall_bench.py` measured manually
(docs/conclusions/CV-PULL-SPIKE.md §3), compressed to run in under a second.
"""

from __future__ import annotations

import threading
import time

import pytest

from cv_service.pull.clock import CaptureClock
from cv_service.pull.loop import PullDecodeLoop, PullStalledError
from cv_service.pull.source import PulledFrame


class FakePullSource:
    """Produces frames on a fixed cadence (or on demand) from a background-
    free, synchronous `.read()` -- `PullDecodeLoop`'s own reader thread is
    what makes this concurrent with the consumer, this fake stays dumb."""

    def __init__(self, *, interval_seconds: float = 0.0, frame_count: "int | None" = None, stall_after: bool = False):
        self._interval_seconds = interval_seconds
        self._frame_count = frame_count
        self._stall_after = stall_after
        self.n = 0
        self.closed = False

    def read(self):
        if self._frame_count is not None and self.n >= self._frame_count:
            if self._stall_after:
                time.sleep(10.0)  # effectively "never" for these tests' short budgets
            return None
        if self._interval_seconds:
            time.sleep(self._interval_seconds)
        self.n += 1
        return PulledFrame(
            image=None, width=4, height=3, pts_millis=self.n * self._interval_seconds * 1000.0, decode_millis=1.5
        )

    def close(self):
        self.closed = True


def _drain(loop: PullDecodeLoop, *, max_items: int, should_continue=lambda: True):
    items = []
    for item in loop.frames(should_continue=should_continue):
        items.append(item)
        if len(items) >= max_items:
            break
    return items


def test_serves_frames_at_roughly_the_target_rate():
    source = FakePullSource(interval_seconds=1.0 / 100.0)  # 100fps source
    loop = PullDecodeLoop(source, target_fps=20.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=2000)
    try:
        t0 = time.monotonic()
        items = _drain(loop, max_items=10)
        elapsed = time.monotonic() - t0
    finally:
        loop.close()

    assert len(items) == 10
    # 10 deadlines at 20fps (50ms interval) should take roughly 0.45-0.5s;
    # generous bounds to keep this robust on a loaded CI box.
    assert 0.3 < elapsed < 1.2


def test_d8_dropped_frames_climb_while_served_frames_stay_fresh_under_a_stalled_consumer():
    """Proves D8: a fast source + a consumer that stalls between deadlines
    (standing in for a slow detector) must show `dropped_frames` climbing
    monotonically, while the served frame's own recency (arrival-mode
    capture time, i.e. essentially "now" at consumption) never degrades --
    the decode loop is never working from a growing backlog.

    The very first served frame legitimately reports `dropped_frames == 0`
    here (docs/conclusions/MEDIA-SOT-RESULTS.md §6's fix): nothing has been
    handed to a caller yet at that point, so every overwrite up to the first
    serve is ordinary downsampling of the 200fps source into the 20fps
    target, not a discard. The count only starts climbing once a served
    frame is actually out with the (simulated, `time.sleep`-stalled) caller
    -- see `test_dropped_frames_stays_zero_when_only_downsampling_happens`
    below for the companion half of this same fix.
    """
    source = FakePullSource(interval_seconds=1.0 / 200.0)  # a fast, 200fps source
    loop = PullDecodeLoop(
        source, target_fps=20.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=5000
    )
    try:
        dropped_over_time = []
        served_ages_ms = []
        count = 0
        for frame, captured_at_millis, diagnostics in loop.frames():
            count += 1
            consumption_wall_millis = time.time() * 1000.0
            served_ages_ms.append(consumption_wall_millis - captured_at_millis)
            dropped_over_time.append(diagnostics.dropped_frames)
            # Simulated stalled detector: much slower than the 50ms sample
            # interval, so the source backs up a real queue the reader must
            # discard from rather than block on.
            time.sleep(0.15)
            if count >= 6:
                break
    finally:
        loop.close()

    # Drops must climb (not just appear once) -- proves the reader kept
    # overwriting the mailbox's single slot throughout the stall, never
    # blocking on the slow consumer.
    assert dropped_over_time == sorted(dropped_over_time)
    assert dropped_over_time[0] == 0
    assert dropped_over_time[-1] > 0
    assert dropped_over_time[-1] > dropped_over_time[1]

    # Every served frame was fresh (a few tens of ms old) at the moment it
    # was actually consumed -- NOT growing with the backlog, which is
    # exactly D8's "latest-wins, not oldest-queued" guarantee.
    assert all(age_ms < 200 for age_ms in served_ages_ms)


def test_dropped_frames_stays_zero_when_only_downsampling_happens():
    """Pins the other half of docs/conclusions/MEDIA-SOT-RESULTS.md §6's fix
    (also the defect M9 found live: pull mode reporting `droppedInFlight:
    1027` -- really just a 30fps source downsampled to a 10fps target --
    against a healthy `dropRatio: 1.0`).

    A source running faster than `target_fps`, drained promptly (no
    artificial stall standing in for a busy detector -- the loop always has
    a served frame ready and is never caught still holding one when the
    next arrives), must report `dropped_frames == 0` throughout: every
    frame the reader overwrites between deadlines was never selected by the
    sampler in the first place, which is downsampling working as designed,
    not a discard. Paired with the stalled-consumer test above, this is the
    whole fix expressed as a test: non-selection must never be counted, and
    genuine backlog must still be counted.
    """
    source = FakePullSource(interval_seconds=1.0 / 100.0)  # a 100fps source
    loop = PullDecodeLoop(
        source, target_fps=10.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=5000
    )
    try:
        # Enough served deadlines to span several seconds' worth of the
        # 10:1 source:target ratio -- if any non-selected frame were
        # miscounted as dropped, it would show up well within this many.
        items = _drain(loop, max_items=25)
    finally:
        loop.close()

    assert len(items) == 25
    assert all(diagnostics.dropped_frames == 0 for _frame, _captured_at, diagnostics in items)


def test_source_stall_raises_pull_stalled_error():
    source = FakePullSource(interval_seconds=0.0, frame_count=2, stall_after=True)
    loop = PullDecodeLoop(source, target_fps=20.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=200)
    try:
        with pytest.raises(PullStalledError):
            _drain(loop, max_items=100)
    finally:
        loop.close()


def test_source_ending_cleanly_also_raises_pull_stalled_error():
    """A source that simply runs out of frames (EOF, `.read()` -> None) is
    indistinguishable from a stall at this layer -- both mean "no more
    frames are coming", and MEDIA-SOT-PLAN §5.1 treats source-unopenable-
    or-stalled uniformly as UNAVAILABLE."""
    source = FakePullSource(interval_seconds=0.0, frame_count=3, stall_after=False)
    loop = PullDecodeLoop(source, target_fps=50.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=200)
    try:
        with pytest.raises(PullStalledError):
            _drain(loop, max_items=100)
    finally:
        loop.close()


def test_stop_ends_frames_iteration_cleanly_without_raising():
    source = FakePullSource(interval_seconds=1.0 / 100.0)
    loop = PullDecodeLoop(source, target_fps=20.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=2000)

    def stopper():
        time.sleep(0.2)
        loop.stop()

    threading.Thread(target=stopper, daemon=True).start()
    served = 0
    for _frame, _captured_at, _diagnostics in loop.frames():
        served += 1
    loop.close()

    assert served >= 1  # got at least one frame before stopping
    assert source.closed is True


def test_should_continue_predicate_also_ends_iteration():
    source = FakePullSource(interval_seconds=1.0 / 100.0)
    loop = PullDecodeLoop(source, target_fps=50.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=2000)
    stop_flag = {"stop": False}

    def stop_after_delay():
        time.sleep(0.15)
        stop_flag["stop"] = True

    threading.Thread(target=stop_after_delay, daemon=True).start()
    served = 0
    for _item in loop.frames(should_continue=lambda: not stop_flag["stop"]):
        served += 1
    loop.close()

    assert served >= 1


def test_set_target_fps_changes_the_serving_rate():
    source = FakePullSource(interval_seconds=1.0 / 200.0)
    loop = PullDecodeLoop(source, target_fps=5.0, clock=CaptureClock(mode="arrival"), stall_timeout_millis=2000)
    try:
        # Serve one frame at the slow (5fps) rate, then speed up.
        first_iter = loop.frames()
        next(first_iter)
        loop.set_target_fps(50.0)

        t0 = time.monotonic()
        for _ in range(5):
            next(first_iter)
        elapsed = time.monotonic() - t0
        # 5 deadlines at 50fps (20ms interval) => ~0.1s, nowhere near the
        # ~1s five deadlines at 5fps would have taken.
        assert elapsed < 0.5
    finally:
        loop.close()

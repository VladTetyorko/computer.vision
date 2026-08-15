"""Unit tests for `cv_service.pull.loop.DeadlineSampler` -- the deadline
scheduler ported from `spikes/pull/sampler.py` (itself a port of
`StreamPipeline.sampleDue`/`armScheduleAt`, feat/cv-rate-control).

Covers the two properties MEDIA-SOT-PLAN §8 M3 calls out as the ones a
careless port most easily loses: one-clock-read-per-decision (enforced here
structurally -- `sample_due` takes `now_ns` as a parameter and never imports
`time` at all) and the `max(now, ...)` debt clamp (a schedule more than one
interval late restarts from `now + interval`, counting -- not firing -- the
skipped deadlines).
"""

from __future__ import annotations

import inspect

import pytest

from cv_service.pull.loop import NANOS_PER_SECOND, DeadlineSampler


def test_sample_due_never_reads_the_clock_itself():
    """Structural guarantee of the one-clock-read rule: `sample_due` must
    take `now_ns` as an argument and never call `time.*` internally -- the
    caller (`PullDecodeLoop.frames`) is the only place a clock is read, once
    per iteration, for both the due-check AND the re-arm."""
    source = inspect.getsource(DeadlineSampler.sample_due)
    assert "time." not in source and "time(" not in source
    assert "now_ns" in inspect.signature(DeadlineSampler.sample_due).parameters


def test_rejects_nonpositive_target_fps():
    with pytest.raises(ValueError):
        DeadlineSampler(0.0)
    with pytest.raises(ValueError):
        DeadlineSampler(-5.0)


def test_first_call_is_always_due_and_arms_the_next_deadline():
    sampler = DeadlineSampler(10.0)
    interval_ns = NANOS_PER_SECOND // 10

    assert sampler.sample_due(1_000) is True
    # Immediately after arming, a `now` inside the interval is NOT due.
    assert sampler.sample_due(1_000 + interval_ns // 2) is False
    assert sampler.missed_deadlines == 0


def test_due_exactly_on_the_armed_deadline():
    sampler = DeadlineSampler(10.0)
    interval_ns = NANOS_PER_SECOND // 10
    sampler.sample_due(0)

    assert sampler.sample_due(interval_ns) is True
    assert sampler.missed_deadlines == 0


def test_every_deadline_is_served_by_exactly_one_frame_for_a_faster_source():
    """A source arriving much faster than the target must not be served on
    every arrival -- exactly one arrival per deadline, matching the target."""
    sampler = DeadlineSampler(10.0)  # 100ms interval
    served = 0
    # Simulate 100 arrivals at 1ms spacing (a 1000fps source) over 1 second.
    for i in range(1000):
        now_ns = i * (NANOS_PER_SECOND // 1000)
        if sampler.sample_due(now_ns):
            served += 1
    # ~10 deadlines/sec over ~1s of simulated arrivals, +/- boundary effects.
    assert 9 <= served <= 11


def test_degenerate_clock_fails_open():
    """A frozen/stepped-backwards clock must not silently stop sampling."""
    sampler = DeadlineSampler(10.0)
    sampler.sample_due(10_000)
    # Clock stepped backwards (e.g. NTP correction) -- fails OPEN, not closed.
    assert sampler.sample_due(5_000) is True
    assert sampler.missed_deadlines == 0


def test_debt_clamp_counts_missed_deadlines_instead_of_a_catch_up_burst():
    """A schedule more than one interval late restarts from `now + interval`
    -- it must NOT fire a burst of catch-up detections, only count what it
    skipped (the load-bearing `max(now, ...)` clamp)."""
    sampler = DeadlineSampler(10.0)  # 100ms interval
    interval_ns = NANOS_PER_SECOND // 10
    assert sampler.sample_due(0) is True  # arms next at +100ms

    # Consumer "stalls" for 850ms -- 8 whole deadlines (100..800ms) elapse
    # with no check at all.
    late_now_ns = 850 * 1_000_000
    assert sampler.sample_due(late_now_ns) is True
    # (850 - 100) // 100 = 7 deadlines were skipped over, not fired as a burst.
    assert sampler.missed_deadlines == 7

    # The NEXT deadline is armed from `now + interval`, not from where the
    # schedule left off -- i.e. exactly late_now_ns + interval_ns, not
    # 100ms + 8*interval_ns (which would be earlier and let a burst fire).
    just_before_next = late_now_ns + interval_ns - 1
    assert sampler.sample_due(just_before_next) is False
    assert sampler.sample_due(late_now_ns + interval_ns) is True
    assert sampler.missed_deadlines == 7  # unchanged -- this one was served, not skipped


def test_retarget_changes_the_interval_used_by_future_arms_only():
    sampler = DeadlineSampler(10.0)  # 100ms
    interval_100ms = NANOS_PER_SECOND // 10
    sampler.sample_due(0)  # arms next at +100ms, unaffected by a later retarget

    sampler.retarget(4.0)  # 250ms from now on
    interval_250ms = NANOS_PER_SECOND // 4

    # The deadline armed BEFORE the retarget still fires at its own,
    # previously-computed instant (100ms), not the new one.
    assert sampler.sample_due(interval_100ms) is True

    # The deadline armed just now (by the line above) uses the NEW interval.
    assert sampler.sample_due(interval_100ms + interval_250ms - 1) is False
    assert sampler.sample_due(interval_100ms + interval_250ms) is True


def test_retarget_rejects_nonpositive_fps():
    sampler = DeadlineSampler(10.0)
    with pytest.raises(ValueError):
        sampler.retarget(0.0)

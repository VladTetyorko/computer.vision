"""Unit tests for `cv_service.inference.concurrency`: `LatestOnlyMailbox` and
`InferenceGate`.

Pure stdlib (`threading`) -- no `cv`/gRPC extras needed to run these. The
`CV_MAX_CONCURRENT_INFERENCES` env var parsing this module used to also
cover now lives in `cv_service.config` -- see `tests/test_config.py`.
"""

from __future__ import annotations

import threading
import time

import pytest

from cv_service.inference.concurrency import GateFull, InferenceGate, LatestOnlyMailbox

# --- LatestOnlyMailbox -------------------------------------------------------


def test_mailbox_put_then_get_roundtrips_a_single_value():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    mailbox.put("frame-0")

    assert mailbox.get() == "frame-0"
    assert mailbox.dropped == 0


def test_mailbox_second_put_before_get_replaces_and_counts_drop():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    mailbox.put("frame-0")
    mailbox.put("frame-1")  # frame-0 was never taken -> dropped

    assert mailbox.get() == "frame-1"
    assert mailbox.dropped == 1


def test_mailbox_three_puts_before_any_get_only_drops_the_middle_one():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    mailbox.put("frame-0")
    mailbox.put("frame-1")
    mailbox.put("frame-2")

    assert mailbox.get() == "frame-2"
    assert mailbox.dropped == 2


def test_mailbox_get_blocks_until_a_producer_thread_puts():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    result = {}

    def consumer():
        result["value"] = mailbox.get()

    thread = threading.Thread(target=consumer)
    thread.start()
    time.sleep(0.05)  # give the consumer a chance to start blocking in get()
    assert "value" not in result  # still waiting

    mailbox.put("frame-0")
    thread.join(timeout=2)

    assert result["value"] == "frame-0"


def test_mailbox_close_unblocks_a_waiting_get_with_none():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    result = {}

    def consumer():
        result["value"] = mailbox.get()

    thread = threading.Thread(target=consumer)
    thread.start()
    time.sleep(0.05)
    mailbox.close()
    thread.join(timeout=2)

    assert result["value"] is None


def test_mailbox_close_does_not_discard_an_already_pending_value():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    mailbox.put("frame-0")
    mailbox.close()

    # A value that arrived before close() is still delivered once;
    # only the *next* get() sees the "closed and empty" None.
    assert mailbox.get() == "frame-0"
    assert mailbox.get() is None


def test_mailbox_put_after_close_is_a_noop():
    mailbox: LatestOnlyMailbox[str] = LatestOnlyMailbox()
    mailbox.close()
    mailbox.put("frame-0")

    assert mailbox.get() is None
    assert mailbox.dropped == 0


# --- InferenceGate ------------------------------------------------------------


def test_inference_gate_rejects_non_positive_size():
    with pytest.raises(ValueError):
        InferenceGate(0)


def test_inference_gate_allows_up_to_its_size_concurrently_and_no_more():
    gate = InferenceGate(2)
    current = 0
    max_seen = 0
    lock = threading.Lock()

    def worker():
        nonlocal current, max_seen
        with gate.acquire():
            with lock:
                current += 1
                max_seen = max(max_seen, current)
            time.sleep(0.05)
            with lock:
                current -= 1

    threads = [threading.Thread(target=worker) for _ in range(5)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=5)

    assert max_seen == 2


def test_inference_gate_releases_on_exception_inside_the_block():
    gate = InferenceGate(1)

    with pytest.raises(RuntimeError):
        with gate.acquire():
            raise RuntimeError("boom")

    # If acquire() leaked (didn't release on exception), this would hang.
    acquired = gate._semaphore.acquire(timeout=1)
    assert acquired, "gate did not release its slot after an exception"


# --- InferenceGate.admit / GateFull (CV-ORCHESTRATION wave W4, §4.9) ---------
#
# `admit()` is the `Detector` RPC's admission door: unlike `acquire()`, it
# refuses immediately (`GateFull`) once the queue is already at its bound,
# instead of making a caller that has somewhere else to go wait behind work
# it could have routed around. It shares the exact same semaphore as
# `acquire()` -- see `test_admit_and_acquire_share_the_same_permit_pool`,
# the fact `cv_service.grpc.detector_servicer.DetectorServicer.Detect()`'s
# own deadlock-avoidance decision rests on (its permit is released BEFORE
# any real detection call re-acquires the gate).


def test_admit_grants_a_permit_immediately_when_the_queue_is_not_full():
    gate = InferenceGate(1)
    with gate.admit(max_queue=1):
        assert gate.occupancy == 1
        assert gate.queue_depth == 0
    assert gate.occupancy == 0


def test_admit_releases_its_permit_after_the_block_like_acquire_does():
    gate = InferenceGate(1)
    with gate.admit(max_queue=1):
        pass
    # If admit() leaked its permit, this would hang.
    acquired = gate._semaphore.acquire(timeout=1)
    assert acquired, "admit() did not release its slot after the block"


def test_admit_and_acquire_share_the_same_permit_pool():
    gate = InferenceGate(1)
    with gate.admit(max_queue=5):
        # The one permit is held by admit() -- a concurrent acquire() call
        # must wait for it, proving both doors spend the SAME semaphore.
        acquired_immediately = gate._semaphore.acquire(timeout=0.1)
        assert not acquired_immediately, "admit() and acquire() must share one permit pool"


def test_admit_raises_gate_full_once_the_queue_reaches_its_bound():
    gate = InferenceGate(1)
    # Hold the gate's one permit externally so every admit() call below has
    # to queue rather than proceed straight through.
    holder_ready = threading.Event()
    release_holder = threading.Event()

    def hold():
        with gate.acquire():
            holder_ready.set()
            release_holder.wait(timeout=5)

    holder = threading.Thread(target=hold)
    holder.start()
    assert holder_ready.wait(timeout=2)

    # One admit() call queues (waiting=1) and then blocks on the semaphore --
    # `max_queue=1` means THIS call is still admitted (0 waiting when it
    # checks), but it never gets its permit until `release_holder` fires.
    queued_entered = threading.Event()

    def queued():
        queued_entered.set()
        with gate.admit(max_queue=1):
            pass

    queued_thread = threading.Thread(target=queued)
    queued_thread.start()
    assert queued_entered.wait(timeout=2)
    deadline = time.monotonic() + 2
    while gate.queue_depth < 1 and time.monotonic() < deadline:
        time.sleep(0.01)
    assert gate.queue_depth == 1, "the queued admit() call never reached the semaphore wait"

    # A THIRD caller now finds the queue already at its bound (1) and is
    # refused immediately -- it never touches the semaphore at all.
    with pytest.raises(GateFull):
        with gate.admit(max_queue=1):
            pass

    release_holder.set()
    holder.join(timeout=2)
    queued_thread.join(timeout=2)
    assert not holder.is_alive() and not queued_thread.is_alive()

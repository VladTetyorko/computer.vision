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

from cv_service.inference.concurrency import InferenceGate, LatestOnlyMailbox

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

"""Integration tests for V-d: DetectStream's per-stream/cross-stream concurrency.

Covers the four scenarios called out by docs/MVP2-PLAN.md's V-d row:
* receive/infer overlap within one stream (a slow-arriving iterator paired
  with a slow detector finishes faster than the naive receive-then-infer
  bound would allow);
* the process-wide `InferenceGate` actually caps how many `detect()` calls
  run at once, across streams, rather than letting every stream's own
  thread pile in unbounded;
* latest-wins dropping: a frame that arrives while the previous one is
  still being inferred is dropped, never inferred, never yields a response,
  and per-stream response ordering is otherwise preserved;
* graceful teardown on client cancel: the per-stream `_StreamReader`
  background thread does not leak.

Requires the generated stubs (`scripts/gen_proto.sh`), same as
`test_server.py`, but not the `cv` optional dependency group -- every
detector here is a fake/test double.
"""

from __future__ import annotations

import threading
import time
from concurrent import futures

import grpc

from cv_service.concurrency import InferenceGate
from cv_service.server import InferenceServicer, cv_pb2, cv_pb2_grpc


def _make_request(**overrides):
    fields = dict(
        stream_id="stream-1",
        sequence=42,
        timestamp_millis=1234,
        width=4,
        height=3,
        encoding=cv_pb2.IMAGE_ENCODING_BGR24,
        data=b"\x00" * (4 * 3 * 3),
        model_id="",
        model_version="",
        confidence_threshold=0.0,
    )
    fields.update(overrides)
    return cv_pb2.FrameRequest(**fields)


class _DelayedIterator:
    """Yields `items` one at a time, sleeping `delay` seconds before each --
    stands in for a real network's non-instantaneous frame arrival."""

    def __init__(self, items, delay: float) -> None:
        self._items = iter(items)
        self._delay = delay

    def __iter__(self):
        return self

    def __next__(self):
        time.sleep(self._delay)
        return next(self._items)


class _SlowDetector:
    def __init__(self, delay: float) -> None:
        self.model_name = "slow-model"
        self._delay = delay
        self.calls = 0

    def detect(self, **_kwargs):
        self.calls += 1
        time.sleep(self._delay)
        return [], int(self._delay * 1000)


# --- (1) receive/infer overlap within one stream ------------------------------


def test_receive_and_infer_overlap_within_one_stream():
    transit = 0.05
    infer = 0.05
    frame_count = 5

    detector = _SlowDetector(infer)
    servicer = InferenceServicer(detector=detector, inference_gate=InferenceGate(4))
    requests = [_make_request(sequence=i) for i in range(frame_count)]

    start = time.monotonic()
    responses = list(
        servicer.DetectStream(_DelayedIterator(requests, transit), context=None)
    )
    elapsed = time.monotonic() - start

    assert [r.sequence for r in responses] == list(range(frame_count))

    naive_serial = frame_count * (transit + infer)
    # If receive and infer never overlapped (the pre-V-d "for request in
    # iterator: infer; yield" loop), this would take ~naive_serial. A
    # generous 75% margin keeps this robust on a loaded CI box while still
    # meaningfully proving the two overlap rather than merely not-regressing.
    assert elapsed < naive_serial * 0.75, (
        f"expected receive/infer overlap, took {elapsed:.3f}s "
        f"(naive receive-then-infer bound {naive_serial:.3f}s)"
    )


# --- (2) cross-stream InferenceGate bound --------------------------------------


def test_two_streams_make_concurrent_progress_under_a_shared_gate():
    # One frame per stream, deliberately -- a single-item iterator is always
    # safely delivered (no drop-race is possible with nothing to race
    # against), so this isolates exactly what's under test: do two
    # *different* streams' detect() calls actually run side by side under a
    # gate sized to allow it, rather than being serialized. (Drop behavior
    # under a busy multi-frame stream is covered separately, see
    # test_latest_wins_drops_stale_frame_received_while_busy_inferring.)
    delay = 0.15

    detector = _SlowDetector(delay)
    gate = InferenceGate(2)
    servicer = InferenceServicer(detector=detector, inference_gate=gate)

    results = {}

    def run_stream(stream_id: str) -> None:
        requests = [_make_request(stream_id=stream_id, sequence=0)]
        results[stream_id] = list(servicer.DetectStream(iter(requests), context=None))

    threads = [threading.Thread(target=run_stream, args=(sid,)) for sid in ("a", "b")]
    start = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)
    elapsed = time.monotonic() - start

    assert all(len(r) == 1 for r in results.values())

    serial_sum = 2 * delay
    # Gate size 2 means both streams' single detect() call can run side by
    # side -- total wall time should look like one detect() call's own
    # delay, not the sum of both.
    assert elapsed < serial_sum * 0.75, (
        f"expected concurrent progress across streams, took {elapsed:.3f}s "
        f"(serial sum would be {serial_sum:.3f}s)"
    )


def test_inference_gate_caps_concurrent_detects_across_four_streams():
    current = 0
    max_seen = 0
    lock = threading.Lock()

    class _TrackingDetector:
        model_name = "tracking-model"

        def detect(self, **_kwargs):
            nonlocal current, max_seen
            with lock:
                current += 1
                max_seen = max(max_seen, current)
            time.sleep(0.05)
            with lock:
                current -= 1
            return [], 1

    gate = InferenceGate(2)
    servicer = InferenceServicer(detector=_TrackingDetector(), inference_gate=gate)

    def run_stream(stream_id: str) -> None:
        requests = [_make_request(stream_id=stream_id, sequence=i) for i in range(3)]
        list(servicer.DetectStream(iter(requests), context=None))

    threads = [threading.Thread(target=run_stream, args=(sid,)) for sid in ("a", "b", "c", "d")]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=10)

    assert max_seen <= 2, f"gate of size 2 let {max_seen} concurrent detect() calls through"
    assert max_seen >= 2, "expected the 4 contending streams to actually reach the gate's cap"


# --- (3) latest-wins drop -------------------------------------------------------


def test_latest_wins_drops_stale_frame_received_while_busy_inferring():
    release_first = threading.Event()
    call_count = {"n": 0}

    class _GatedDetector:
        model_name = "gated-model"

        def detect(self, **_kwargs):
            call_count["n"] += 1
            if call_count["n"] == 1:
                assert release_first.wait(timeout=5), "test setup: release_first never set"
            return [], 1

    servicer = InferenceServicer(detector=_GatedDetector(), inference_gate=InferenceGate(4))
    requests = [_make_request(sequence=i) for i in range(3)]

    result = {}

    def run() -> None:
        result["responses"] = list(servicer.DetectStream(iter(requests), context=None))

    thread = threading.Thread(target=run)
    thread.start()
    # Frame 0's detect() is blocked; give the reader thread time to race
    # through the remaining (immediately available) frames 1 and 2 into the
    # 1-slot mailbox -- by LatestOnlyMailbox's contract only frame 2 (the
    # newest) survives, frame 1 is silently dropped before ever reaching
    # detect().
    time.sleep(0.2)
    release_first.set()
    thread.join(timeout=5)

    assert [r.sequence for r in result["responses"]] == [0, 2]
    assert call_count["n"] == 2  # frame 1 never reached detect() at all


def test_ordering_is_preserved_per_stream_when_nothing_is_dropped():
    detector = _SlowDetector(0.01)
    servicer = InferenceServicer(detector=detector, inference_gate=InferenceGate(4))
    frame_count = 8
    # Paced arrival (comfortably slower than the 0.01s detect()) keeps the
    # consumer always caught up, so nothing is expected to be dropped here
    # -- this test is about ordering, not about the drop behavior itself.
    requests = [_make_request(sequence=i) for i in range(frame_count)]

    responses = list(
        servicer.DetectStream(_DelayedIterator(requests, 0.03), context=None)
    )

    assert [r.sequence for r in responses] == list(range(frame_count))
    assert detector.calls == frame_count


# --- (4) graceful teardown on client cancel -----------------------------------


def test_reader_thread_does_not_leak_on_client_cancel():
    """A client cancelling mid-stream must not leave `_StreamReader`'s
    background thread running forever.

    Uses a real gRPC server+channel (not a fake iterator) because the
    guarantee under test -- that gRPC itself unblocks a thread parked in
    `next(request_iterator)` once the call is cancelled, regardless of
    which thread is blocked on it -- is a real property of gRPC's
    cancellation machinery, verified empirically for this grpc version, not
    something a fake iterator could stand in for.
    """
    detector = _SlowDetector(0.0)
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=4))
    cv_pb2_grpc.add_InferenceServicer_to_server(InferenceServicer(detector=detector), server)
    port = server.add_insecure_port("localhost:0")
    server.start()

    release = threading.Event()
    channel = grpc.insecure_channel(f"localhost:{port}")
    try:
        stub = cv_pb2_grpc.InferenceStub(channel)

        def request_gen():
            yield _make_request(sequence=0)
            # Simulate an otherwise-idle live stream: no more frames until
            # released, so the server's reader thread would sit blocked on
            # the network read exactly like a real quiet camera feed.
            release.wait(timeout=5)

        call = stub.DetectStream(request_gen())
        response = next(call)
        assert response.sequence == 0

        call.cancel()

        deadline = time.monotonic() + 5.0
        leaked = []
        while time.monotonic() < deadline:
            leaked = [t for t in threading.enumerate() if t.name == "cv-detectstream-reader"]
            if not leaked:
                break
            time.sleep(0.02)

        assert not leaked, f"reader thread(s) leaked after client cancel: {leaked}"
    finally:
        release.set()
        channel.close()
        server.stop(grace=0).wait(timeout=5)


def test_reader_thread_does_not_leak_on_normal_stream_completion():
    detector = _SlowDetector(0.0)
    servicer = InferenceServicer(detector=detector, inference_gate=InferenceGate(4))
    requests = [_make_request(sequence=i) for i in range(3)]

    baseline = {t.name for t in threading.enumerate()}
    list(servicer.DetectStream(iter(requests), context=None))

    deadline = time.monotonic() + 2.0
    leaked = []
    while time.monotonic() < deadline:
        leaked = [
            t
            for t in threading.enumerate()
            if t.name == "cv-detectstream-reader" and t.name not in baseline
        ]
        if not leaked:
            break
        time.sleep(0.02)

    assert not leaked, f"reader thread(s) leaked after normal stream completion: {leaked}"

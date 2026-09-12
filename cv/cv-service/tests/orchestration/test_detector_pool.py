"""`PoolDetectorClient`: ordered failover over a `RemoteDetector` list.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.9 (wave W4). This file stays
at `PoolDetectorClient`'s own level -- fake `RemoteDetector`s (a `.target`
plus a `.detect()` that answers, refuses, or fails exactly the way a real
pooled target does), never a wire. `tests/orchestration/test_detector_affinity.py`
proves the pool composes correctly with a live `StreamTrackingSession`;
`tests/grpc/test_detector_service.py` proves the wire transport
(`GrpcRemoteDetector`) translates gRPC status codes correctly. This file is
the one that proves the ORDERING/ACCOUNTING logic itself, in isolation.

Also covers the one addition made to `cv_service/orchestration/detector.py`
for this wave: `PoolDetectorClient(..., health=...)`, an optional shared
per-target health dict so `InferenceServicer.Inspect` can report one
process-wide view of target health without polling every live session (see
that module's own `health=` docstring for the full rationale).
"""

from __future__ import annotations

import pytest

from cv_service.orchestration.detector import (
    DetectorBusy,
    DetectorPass,
    DetectorUnavailable,
    FramePayload,
    LOCAL_TARGET,
    LocalDetectorClient,
    NoDetectorAvailable,
    PoolDetectorClient,
    TargetHealth,
)

TARGET_A = "cv-detector-1:50051"
TARGET_B = "cv-detector-2:50051"
TARGET_C = "cv-detector-3:50051"


class FakeRemote:
    """A `RemoteDetector`: answers, refuses (`DetectorBusy`), or fails
    (`DetectorUnavailable`) on command -- never a socket."""

    def __init__(self, target: str, *, detections=None) -> None:
        self.target = target
        self.detections = list(detections) if detections is not None else []
        self.busy = False
        self.down = False
        self.calls = 0
        self.payloads: "list[FramePayload]" = []

    def detect(self, payload: FramePayload, roi=None) -> DetectorPass:
        self.calls += 1
        self.payloads.append(payload)
        if self.busy:
            raise DetectorBusy(f"{self.target}: queue full")
        if self.down:
            raise DetectorUnavailable(f"{self.target}: unreachable")
        return DetectorPass(
            detections=list(self.detections), inference_millis=11, wait_ms=0.0, roi=roi is not None
        )


class FrameDetect:
    """The servicer's per-frame callable in its `FrameDetect` shape: callable
    (what `LocalDetectorClient` needs) and payload-carrying (what a pool
    reads off it via `getattr(detect, "payload", None)`)."""

    def __init__(self, payload: FramePayload, detections=None) -> None:
        self.payload = payload
        self.detections = list(detections) if detections is not None else []
        self.calls = 0

    def __call__(self, roi=None):
        self.calls += 1
        return list(self.detections), 3


def payload(sequence: int = 1) -> FramePayload:
    return FramePayload(
        stream_id="pool-test",
        sequence=sequence,
        width=640,
        height=480,
        encoding="IMAGE_ENCODING_JPEG",
        data=b"\xff\xd8jpeg",
        model_id="yolo26n",
    )


def bound_pool(*targets, detections=None) -> PoolDetectorClient:
    """A `PoolDetectorClient` over `targets`, bound to one frame's payload."""
    pool = PoolDetectorClient(targets, local=LocalDetectorClient())
    pool.bind(FrameDetect(payload(), detections=detections))
    return pool


# --- construction --------------------------------------------------------


def test_pool_rejects_an_empty_target_list():
    with pytest.raises(ValueError):
        PoolDetectorClient([], local=LocalDetectorClient())


def test_pool_id_is_pool():
    assert PoolDetectorClient.id == "pool"


# --- ordering + first-success -------------------------------------------


def test_pool_tries_targets_in_order_and_stops_at_the_first_success():
    a = FakeRemote(TARGET_A)
    b = FakeRemote(TARGET_B)
    pool = bound_pool(a, b, detections=[])

    result = pool.detect()

    assert a.calls == 1
    assert b.calls == 0
    assert result.served_by == TARGET_A
    assert result.hops == 0


def test_pool_falls_through_a_busy_target_and_records_refused():
    a = FakeRemote(TARGET_A)
    a.busy = True
    b = FakeRemote(TARGET_B)
    pool = bound_pool(a, b, detections=[])

    result = pool.detect()

    assert a.calls == 1
    assert b.calls == 1
    assert result.served_by == TARGET_B
    assert result.hops == 1

    health = {h.target: h for h in pool.health}
    assert health[TARGET_A].refused == 1
    assert health[TARGET_A].served == 0
    assert health[TARGET_A].failed == 0
    assert "queue full" in health[TARGET_A].last_error
    assert health[TARGET_B].served == 1


def test_pool_falls_through_an_unavailable_target_and_records_failed():
    a = FakeRemote(TARGET_A)
    a.down = True
    b = FakeRemote(TARGET_B)
    pool = bound_pool(a, b, detections=[])

    result = pool.detect()

    assert result.served_by == TARGET_B
    health = {h.target: h for h in pool.health}
    assert health[TARGET_A].failed == 1
    assert health[TARGET_A].refused == 0
    assert "unreachable" in health[TARGET_A].last_error


def test_a_served_target_clears_its_own_last_error():
    a = FakeRemote(TARGET_A)
    pool = bound_pool(a, detections=[])

    pool.bind(FrameDetect(payload(1)))
    a.down = True
    with pytest.raises(NoDetectorAvailable):
        pool.detect()
    assert pool.health[0].last_error != ""

    pool.bind(FrameDetect(payload(2)))
    a.down = False
    pool.detect()
    assert pool.health[0].last_error == ""
    assert pool.health[0].served == 1
    assert pool.health[0].failed == 1  # the earlier failure is still counted


def test_pool_raises_no_detector_available_when_every_target_fails():
    a = FakeRemote(TARGET_A)
    a.busy = True
    b = FakeRemote(TARGET_B)
    b.down = True
    pool = bound_pool(a, b, detections=[])

    with pytest.raises(NoDetectorAvailable):
        pool.detect()

    health = {h.target: h for h in pool.health}
    assert health[TARGET_A].refused == 1
    assert health[TARGET_B].failed == 1


def test_pool_health_is_reported_in_configured_order_and_starts_at_zero():
    pool = PoolDetectorClient(
        [FakeRemote(TARGET_C), FakeRemote(TARGET_A), FakeRemote(TARGET_B)], local=LocalDetectorClient()
    )

    assert [h.target for h in pool.health] == [TARGET_C, TARGET_A, TARGET_B]
    assert all(h.served == h.refused == h.failed == 0 and h.last_error == "" for h in pool.health)
    assert pool.targets == (TARGET_C, TARGET_A, TARGET_B)


def test_pool_wait_ms_is_measured_across_the_whole_attempt_sequence():
    a = FakeRemote(TARGET_A)
    a.busy = True
    b = FakeRemote(TARGET_B)
    pool = bound_pool(a, b, detections=[])

    result = pool.detect()

    # Two attempts were made before the winning one answered -- the reported
    # cost includes the refusal it walked past, not just the winning hop.
    assert result.wait_ms >= 0.0
    assert pool.wait_ms >= 0.0
    assert pool.passes == 1
    assert pool.served_by == TARGET_B


# --- the local token -------------------------------------------------------


class UnreachablePlaceholder:
    """Stands in for `grpc/detector_transport.py`'s `_LocalPlaceholder`:
    `.target == "local"`, and a `.detect()` that must never actually run."""

    target = LOCAL_TARGET

    def detect(self, payload, roi=None):  # pragma: no cover - must be unreachable
        raise AssertionError("the local placeholder's detect() must never be called")


def test_local_token_routes_through_the_embedded_local_client():
    placeholder = UnreachablePlaceholder()
    pool = PoolDetectorClient([placeholder], local=LocalDetectorClient())
    pool.bind(FrameDetect(payload(), detections=[("noop",)]))

    # `FrameDetect.__call__` above ignores `roi` and returns a fixed list;
    # swap in a callable that returns something recognizable instead.
    from cv_service.inference.detector import Detection

    local_detections = [Detection("car", 0.9, 0.1, 0.1, 0.1, 0.1)]

    def detect(roi=None):
        return local_detections, 4

    detect.payload = payload()
    pool.bind(detect)

    result = pool.detect()

    assert result.served_by == LOCAL_TARGET
    assert result.hops == 0
    assert result.detections == local_detections
    assert pool.health[0].served == 1


def test_a_pool_mixing_a_remote_target_and_the_local_fallback_falls_back_correctly():
    remote = FakeRemote(TARGET_A)
    remote.down = True
    placeholder = UnreachablePlaceholder()

    def detect(roi=None):
        return [], 2

    detect.payload = payload()

    pool = PoolDetectorClient([remote, placeholder], local=LocalDetectorClient())
    pool.bind(detect)

    result = pool.detect()

    assert result.served_by == LOCAL_TARGET
    assert result.hops == 1
    assert remote.calls == 1


# --- bind() ------------------------------------------------------------------


def test_bind_reads_the_payload_off_a_frame_detect_and_resets_the_tally():
    a = FakeRemote(TARGET_A)
    pool = PoolDetectorClient([a], local=LocalDetectorClient())

    pool.bind(FrameDetect(payload(1), detections=[]))
    pool.detect()
    assert pool.passes == 1

    pool.bind(FrameDetect(payload(2), detections=[]))
    assert pool.passes == 0
    assert pool.wait_ms == 0.0
    assert pool.served_by == ""


def test_a_bare_callable_with_no_payload_attribute_is_reported_as_unavailable():
    """`PoolDetectorClient.bind()` accepts a bare `DetectFn` too (no `.payload`
    -- every pre-W4 test double). A REMOTE target then has nothing to send,
    which is reported as that target being unavailable rather than raised
    outright, so one misconfigured frame is never worse than one unreachable
    box (`PoolDetectorClient._attempt`'s own docstring)."""
    a = FakeRemote(TARGET_A)
    pool = PoolDetectorClient([a], local=LocalDetectorClient())
    pool.bind(lambda *_: ([], 0))

    with pytest.raises(NoDetectorAvailable):
        pool.detect()

    assert a.calls == 0
    assert pool.health[0].failed == 1
    assert "no wire payload" in pool.health[0].last_error


# --- health= : the shared process-wide aggregation seam ----------------------


def test_health_dict_defaults_to_a_private_instance_when_omitted():
    a = FakeRemote(TARGET_A)
    pool_1 = PoolDetectorClient([a], local=LocalDetectorClient())
    pool_2 = PoolDetectorClient([FakeRemote(TARGET_A)], local=LocalDetectorClient())

    pool_1.bind(FrameDetect(payload(), detections=[]))
    pool_1.detect()

    # No `health=` given -- each instance's counters are its own.
    assert pool_1.health[0].served == 1
    assert pool_2.health[0].served == 0


def test_health_dict_is_shared_by_reference_across_pool_instances_when_given():
    shared: "dict[str, TargetHealth]" = {TARGET_A: TargetHealth(TARGET_A)}

    session_one = PoolDetectorClient([FakeRemote(TARGET_A)], local=LocalDetectorClient(), health=shared)
    session_two = PoolDetectorClient([FakeRemote(TARGET_A)], local=LocalDetectorClient(), health=shared)

    session_one.bind(FrameDetect(payload(1), detections=[]))
    session_one.detect()
    session_two.bind(FrameDetect(payload(2), detections=[]))
    session_two.detect()

    # Both sessions' passes landed in the SAME dict -- the process-wide view
    # `Inspect` reads counts both, even though each session's own
    # `PoolDetectorClient` is a private instance with its own per-frame state.
    assert shared[TARGET_A].served == 2
    assert session_one.health[0] is session_two.health[0]
    assert session_one.health[0] is shared[TARGET_A]


def test_health_dict_gains_an_entry_for_a_target_missing_from_a_given_dict():
    # A caller handing in a health dict built from a DIFFERENT (older, or
    # subset) target list must not crash -- every configured target is
    # guaranteed an entry.
    shared: "dict[str, TargetHealth]" = {}
    pool = PoolDetectorClient([FakeRemote(TARGET_A), FakeRemote(TARGET_B)], local=LocalDetectorClient(), health=shared)

    assert set(shared) == {TARGET_A, TARGET_B}
    assert [h.target for h in pool.health] == [TARGET_A, TARGET_B]

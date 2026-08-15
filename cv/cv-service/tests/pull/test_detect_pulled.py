"""Integration tests for `InferenceServicer.DetectPulled` (MEDIA-SOT-PLAN
wave M3, §5.1's frozen call semantics).

Drives the real servicer method end to end against a `FakePullSource`
(injected via `pull_source_open=`, the same "explicit injection beats a real
backend" seam `detector=`/`registry=` already are for `DetectStream`) -- no
real RTSP/mediamtx/cv2.VideoCapture involved, so these run fast and
deterministically. Needs the `cv` extra (`numpy`, for the fake frame pixels
and the real downscale path), same requirement as
`tests/tracking/test_flow_gmc.py` and friends.

Every "happy path" test below ends the call itself via `stop=true` (never by
letting the fake source run dry) -- `list()` on a generator discards
whatever it already collected if a LATER item raises, so a test that wants
to assert on the RESPONSES a call produced must end that call cleanly, not
by racing a stall/abort. Tests that want the abort/stall behavior itself
(`test_stalled_source_aborts_unavailable`) drive that on purpose instead.
"""

from __future__ import annotations

import time
from typing import Optional

import grpc
import pytest

np = pytest.importorskip("numpy")

from cv_service.config import Settings
from cv_service.grpc.servicers import InferenceServicer, cv_pb2
from cv_service.inference.concurrency import InferenceGate
from cv_service.pull.source import PulledFrame, PullSourceError


class _AbortError(Exception):
    """Raised by `FakeContext.abort` -- mirrors real `grpc.ServicerContext.
    abort()`'s "never returns" behavior so tests can assert on the code/
    details exactly like a real client would observe them."""

    def __init__(self, code, details):
        super().__init__(f"{code}: {details}")
        self.code = code
        self.details = details


class FakeContext:
    def __init__(self) -> None:
        self._active = True
        self.aborted: Optional[tuple] = None

    def abort(self, code, details):
        self.aborted = (code, details)
        self._active = False
        raise _AbortError(code, details)

    def is_active(self) -> bool:
        return self._active

    def cancel(self) -> None:
        self._active = False


class FakePullSource:
    """A `PullSource` producing small, real numpy BGR24 frames on a fixed
    cadence, indefinitely -- real pixels (not `None`) because
    `_build_pull_frame_request` reads `.shape` off them for the downscale
    step. Tests end a call via `stop=true`, never by exhausting this."""

    def __init__(self, *, width: int = 8, height: int = 6, interval_seconds: float = 0.005) -> None:
        self._width = width
        self._height = height
        self._interval = interval_seconds
        self.n = 0
        self.closed = False

    def read(self) -> Optional[PulledFrame]:
        if self._interval:
            time.sleep(self._interval)
        self.n += 1
        image = np.full((self._height, self._width, 3), self.n % 255, dtype=np.uint8)
        return PulledFrame(
            image=image,
            width=self._width,
            height=self._height,
            pts_millis=self.n * self._interval * 1000.0,
            decode_millis=2.0,
        )

    def close(self) -> None:
        self.closed = True


class _NeverProducesAgain:
    """A `PullSource` that yields exactly one frame, then goes quiet forever
    -- for the source-stall test."""

    def __init__(self) -> None:
        self._served = False
        self.closed = False

    def read(self) -> Optional[PulledFrame]:
        if not self._served:
            self._served = True
            return PulledFrame(
                image=np.zeros((6, 8, 3), dtype=np.uint8), width=8, height=6, pts_millis=0.0, decode_millis=1.0
            )
        time.sleep(5.0)  # far longer than any test's stall_timeout_millis
        return None

    def close(self) -> None:
        self.closed = True


class FakeDetector:
    def __init__(self, model_name="fake-model", detections=None, inference_millis=7):
        self.model_name = model_name
        self._detections = detections if detections is not None else []
        self._inference_millis = inference_millis
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        return self._detections, self._inference_millis


class FakeRegistry:
    """Same minimal stand-in `tests/grpc/test_inference_servicer.py` uses for
    `cv_service.inference.registry.ModelRegistry`: only the
    `.resolve(model_id) -> list[(id, detector)]` surface `InferenceServicer`
    actually calls."""

    def __init__(self, resolved_by_model_id=None, *, default=None):
        self._resolved_by_model_id = resolved_by_model_id or {}
        self._default = default if default is not None else []

    def resolve(self, model_id: str):
        return self._resolved_by_model_id.get(model_id, self._default)


def _settings(**overrides) -> Settings:
    return Settings(**overrides)


def _first_message(**overrides) -> "cv_pb2.PullControl":
    fields = dict(
        stream_id="pulled-1",
        source_url="rtsp://example/pulled-1",
        rtsp_transport="tcp",
        target_fps=100.0,  # fast, so tests spend little real wall time waiting on deadlines
    )
    fields.update(overrides)
    return cv_pb2.PullControl(**fields)


def _stop_message(**overrides) -> "cv_pb2.PullControl":
    fields = dict(stream_id="pulled-1", stop=True)
    fields.update(overrides)
    return cv_pb2.PullControl(**fields)


def _drive(servicer: InferenceServicer, control_iterable, context: FakeContext) -> list:
    return list(servicer.DetectPulled(iter(control_iterable), context))


def _drive_until_stopped(servicer: InferenceServicer, context: FakeContext, *, run_seconds: float, first, extra=()) -> list:
    """Runs a call for `run_seconds`, then sends `stop=true` -- the standard
    shape for a "happy path" test that wants to inspect the responses a call
    produced without racing the fake source's own stall behavior."""

    def control_gen():
        yield first
        for message, delay in extra:
            time.sleep(delay)
            yield message
        time.sleep(run_seconds)
        yield _stop_message()

    return _drive(servicer, control_gen(), context)


# --- source_url / open failures --------------------------------------------


def test_missing_source_url_on_first_message_aborts_invalid_argument():
    servicer = InferenceServicer(detector=FakeDetector(), inference_gate=InferenceGate(4))
    context = FakeContext()
    first = cv_pb2.PullControl(stream_id="s1", target_fps=10.0)  # no source_url

    with pytest.raises(_AbortError) as exc_info:
        _drive(servicer, [first], context)

    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT
    assert "source_url" in exc_info.value.details


def test_unopenable_source_aborts_unavailable():
    def _raising_open(url, **kwargs):
        raise PullSourceError(f"could not open {url!r}")

    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(4), pull_source_open=_raising_open
    )
    context = FakeContext()

    with pytest.raises(_AbortError) as exc_info:
        _drive(servicer, [_first_message()], context)

    assert exc_info.value.code == grpc.StatusCode.UNAVAILABLE
    assert "pulled-1" in exc_info.value.details


def test_stalled_source_aborts_unavailable():
    source = _NeverProducesAgain()
    servicer = InferenceServicer(
        detector=FakeDetector(),
        inference_gate=InferenceGate(4),
        settings=_settings(pull_stall_timeout_millis=100),
        pull_source_open=lambda url, **kwargs: source,
    )
    context = FakeContext()

    def control_gen():
        yield _first_message(target_fps=20.0)
        # Keep the CONTROL stream open well past the source's stall budget --
        # otherwise an immediately-ending control iterator (a single-item
        # list) is itself indistinguishable from a clean half-close, and
        # THAT would end the call before the source-level stall ever gets a
        # chance to fire. This test wants the SOURCE stall to be what ends
        # the call, not an incidental control-stream close racing it.
        time.sleep(1.0)

    with pytest.raises(_AbortError) as exc_info:
        _drive(servicer, control_gen(), context)

    assert exc_info.value.code == grpc.StatusCode.UNAVAILABLE
    assert source.closed is True


# --- stream_id identity ------------------------------------------------------


def test_stream_id_mismatch_mid_call_aborts_invalid_argument():
    first = _first_message(stream_id="s1", target_fps=200.0)
    mismatched = cv_pb2.PullControl(stream_id="s2", target_fps=200.0)

    def control_gen():
        yield first
        time.sleep(0.1)
        yield mismatched

    source = FakePullSource(interval_seconds=0.002)
    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    with pytest.raises(_AbortError) as exc_info:
        _drive(servicer, control_gen(), context)

    assert exc_info.value.code == grpc.StatusCode.INVALID_ARGUMENT
    assert "stream_id" in exc_info.value.details


# --- clean teardown ----------------------------------------------------------


def test_stop_true_drains_cleanly():
    source = FakePullSource(interval_seconds=0.002)
    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    responses = _drive_until_stopped(servicer, context, run_seconds=0.1, first=_first_message(target_fps=200.0))

    assert len(responses) >= 1
    assert context.aborted is None
    assert source.closed is True


def test_client_half_close_also_drains_cleanly():
    """The control stream simply ending (no explicit stop=true) must also
    end the call cleanly, not hang or abort (§5.1 'Teardown')."""
    first = _first_message(target_fps=200.0)

    def control_gen():
        yield first
        time.sleep(0.1)  # let some real streaming happen before the send side closes
        # generator just ends here -- half-close, no stop=true

    source = FakePullSource(interval_seconds=0.002)
    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    responses = _drive(servicer, control_gen(), context)

    assert len(responses) >= 1
    assert context.aborted is None


# --- diagnostic fields (16-21) ------------------------------------------------


def test_all_six_diagnostic_fields_are_populated():
    source = FakePullSource(interval_seconds=0.005)
    servicer = InferenceServicer(
        detector=FakeDetector(inference_millis=3), inference_gate=InferenceGate(4),
        # `arrival` here so `capture_skew_millis` is deterministically 0 by
        # construction (see `clock.py`) -- `test_clock.py` already covers
        # `anchor`'s own skew arithmetic in isolation; this test is only
        # about "all six fields round-trip onto the wire".
        settings=_settings(pull_clock_mode="arrival"),
        pull_source_open=lambda url, **kwargs: source,
    )
    context = FakeContext()

    responses = _drive_until_stopped(servicer, context, run_seconds=0.15, first=_first_message(target_fps=50.0))

    assert context.aborted is None
    assert len(responses) >= 2
    last = responses[-1]
    assert last.decode_millis >= 0
    assert last.source_fps > 0
    # `achieved_fps` may legitimately be 0.0 on the very first served frame
    # (nothing to measure an interval against yet) but must round-trip as a
    # field either way -- this asserts presence/non-negativity, not >0.
    assert last.achieved_fps >= 0
    assert last.dropped_frames >= 0
    assert last.missed_deadlines >= 0
    assert last.capture_skew_millis == 0  # arrival mode's own by-construction guarantee


def test_timestamp_millis_is_the_captured_at_of_the_analysed_frame():
    """Same field, same meaning, same units as push mode (§5.1) -- must be a
    plausible wallclock millis value close to when the frame was produced,
    not zero and not some unrelated sequence-derived number."""
    source = FakePullSource(interval_seconds=0.005)
    servicer = InferenceServicer(
        detector=FakeDetector(),
        inference_gate=InferenceGate(4),
        settings=_settings(pull_clock_mode="arrival"),
        pull_source_open=lambda url, **kwargs: source,
    )
    context = FakeContext()
    before_millis = time.time() * 1000.0

    responses = _drive_until_stopped(servicer, context, run_seconds=0.1, first=_first_message(target_fps=50.0))

    after_millis = time.time() * 1000.0
    assert len(responses) >= 1
    for response in responses:
        assert before_millis - 50 <= response.timestamp_millis <= after_millis + 50


# --- sequencing ---------------------------------------------------------------


def test_sequence_is_worker_minted_monotonic_starting_at_one():
    source = FakePullSource(interval_seconds=0.003)
    servicer = InferenceServicer(
        detector=FakeDetector(), inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    responses = _drive_until_stopped(servicer, context, run_seconds=0.1, first=_first_message(target_fps=100.0))

    sequences = [r.sequence for r in responses]
    assert sequences == list(range(1, len(sequences) + 1))


# --- mid-call model swap -------------------------------------------------------


def test_mid_call_model_swap_takes_effect_on_a_later_frame():
    detector_a = FakeDetector(model_name="model-a")
    detector_b = FakeDetector(model_name="model-b")
    registry = FakeRegistry(
        resolved_by_model_id={
            "model-a": [("model-a", detector_a)],
            "model-b": [("model-b", detector_b)],
        }
    )
    source = FakePullSource(interval_seconds=0.01)
    servicer = InferenceServicer(
        registry=registry, inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    first = _first_message(model_id="model-a", target_fps=100.0)
    swap = cv_pb2.PullControl(stream_id="pulled-1", model_id="model-b", target_fps=100.0)

    responses = _drive_until_stopped(
        servicer, context, run_seconds=0.2, first=first, extra=[(swap, 0.15)]
    )

    assert len(detector_a.calls) >= 1, "model-a must have served at least one frame before the swap"
    assert len(detector_b.calls) >= 1, "model-b must have served at least one frame after the swap"
    model_ids_served = [r.model_id for r in responses]
    last_a_index = max(i for i, m in enumerate(model_ids_served) if m == "model-a")
    first_b_index = min(i for i, m in enumerate(model_ids_served) if m == "model-b")
    assert last_a_index < first_b_index


# --- echo mode (no model loaded at all) ---------------------------------------


def test_echoes_when_no_model_is_loaded_at_all():
    source = FakePullSource(interval_seconds=0.005)
    servicer = InferenceServicer(
        detector=None, registry=None, inference_gate=InferenceGate(4),
        pull_source_open=lambda url, **kwargs: source,
    )
    context = FakeContext()

    responses = _drive_until_stopped(servicer, context, run_seconds=0.1, first=_first_message(target_fps=50.0))

    assert len(responses) >= 1
    for response in responses:
        assert list(response.detections) == []
        assert response.inference_millis == 0
        # diagnostics are still populated even in echo mode.
        assert response.dropped_frames >= 0


# --- detect_width downscaling --------------------------------------------------


def test_detect_width_downscales_wide_frames_before_detection():
    source = FakePullSource(width=100, height=50, interval_seconds=0.01)
    detector = FakeDetector()
    servicer = InferenceServicer(
        detector=detector, inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    _drive_until_stopped(
        servicer, context, run_seconds=0.05, first=_first_message(target_fps=100.0, detect_width=40)
    )

    assert detector.calls, "the fake detector must have been invoked"
    assert detector.calls[0]["width"] == 40
    assert detector.calls[0]["height"] == 20  # round(50 * 40 / 100), aspect preserved


def test_detect_width_leaves_a_narrow_frame_untouched():
    source = FakePullSource(width=20, height=10, interval_seconds=0.01)
    detector = FakeDetector()
    servicer = InferenceServicer(
        detector=detector, inference_gate=InferenceGate(4), pull_source_open=lambda url, **kwargs: source
    )
    context = FakeContext()

    _drive_until_stopped(
        servicer, context, run_seconds=0.05, first=_first_message(target_fps=100.0, detect_width=40)
    )

    assert detector.calls[0]["width"] == 20
    assert detector.calls[0]["height"] == 10

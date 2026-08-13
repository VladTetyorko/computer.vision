"""Unit tests for `cv_service.pull.source` -- the `PullSource` protocol and
the `opencv` backend M0 chose (docs/conclusions/CV-PULL-SPIKE.md §2/§7).

Needs the `cv` extra (`cv2`/`numpy`), same requirement as
`tests/tracking/test_flow_gmc.py` and friends -- no RTSP/network dependency:
`cv2.VideoCapture` itself is monkeypatched with a fake so these tests never
touch a real socket.
"""

from __future__ import annotations

import os

import pytest

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from cv_service.pull.source import OpenCvPullSource, PullSourceError, open_source


class _FakeCapture:
    """Stands in for `cv2.VideoCapture`: records `.set()`/`.open()` calls,
    and serves a small fixed sequence of frames from `.read()`."""

    def __init__(self, *, opens_ok: bool = True, frame_count: int = 3):
        self.opens_ok = opens_ok
        self.frame_count = frame_count
        self.set_calls: list[tuple[int, float]] = []
        self.opened_with: "tuple[str, int] | None" = None
        self._reads = 0
        self._released = False

    def set(self, prop_id, value):
        self.set_calls.append((prop_id, value))
        return True

    def open(self, url, api_preference):
        self.opened_with = (url, api_preference)
        return self.opens_ok

    def isOpened(self):
        return self.opens_ok

    def read(self):
        if self._reads >= self.frame_count:
            return False, None
        self._reads += 1
        frame = np.full((3, 4, 3), self._reads, dtype=np.uint8)
        return True, frame

    def get(self, prop_id):
        assert prop_id == cv2.CAP_PROP_POS_MSEC
        return float(self._reads * 33)

    def release(self):
        self._released = True


def test_open_failure_raises_pull_source_error(monkeypatch):
    monkeypatch.setattr(cv2, "VideoCapture", lambda: _FakeCapture(opens_ok=False))
    with pytest.raises(PullSourceError):
        OpenCvPullSource("rtsp://example/stream")


def test_open_success_sets_timeouts_before_open(monkeypatch):
    fake = _FakeCapture()
    monkeypatch.setattr(cv2, "VideoCapture", lambda: fake)

    source = OpenCvPullSource(
        "rtsp://example/stream", rtsp_transport="udp", open_timeout_millis=1234, read_timeout_millis=5678
    )

    assert (cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 1234) in fake.set_calls
    assert (cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5678) in fake.set_calls
    assert fake.opened_with == ("rtsp://example/stream", cv2.CAP_FFMPEG)
    source.close()
    assert fake._released is True


def test_read_returns_decoded_frames_then_none_at_end(monkeypatch):
    fake = _FakeCapture(frame_count=2)
    monkeypatch.setattr(cv2, "VideoCapture", lambda: fake)
    source = OpenCvPullSource("rtsp://example/stream")

    first = source.read()
    assert first is not None
    assert first.width == 4 and first.height == 3
    assert first.pts_millis == 33.0
    assert first.decode_millis >= 0.0

    second = source.read()
    assert second is not None

    third = source.read()
    assert third is None  # end of the fake's fixed sequence
    source.close()


def test_rtsp_transport_env_var_is_set_during_open_and_restored_after(monkeypatch):
    """`OPENCV_FFMPEG_CAPTURE_OPTIONS` is the only lever cv2's FFmpeg backend
    exposes for `rtsp_transport` -- it must be set for the `open()` call and
    restored to whatever it was before, never leaked into unrelated code."""
    fake = _FakeCapture()
    captured_env = {}

    def fake_open(url, api_preference):
        captured_env["value"] = os.environ.get("OPENCV_FFMPEG_CAPTURE_OPTIONS")
        return True

    fake.open = fake_open
    monkeypatch.setattr(cv2, "VideoCapture", lambda: fake)
    monkeypatch.delenv("OPENCV_FFMPEG_CAPTURE_OPTIONS", raising=False)

    OpenCvPullSource("rtsp://example/stream", rtsp_transport="udp")

    assert captured_env["value"] == "rtsp_transport;udp"
    assert "OPENCV_FFMPEG_CAPTURE_OPTIONS" not in os.environ


def test_rtsp_transport_env_var_restores_a_previous_value():
    fake = _FakeCapture()
    captured_env = {}

    def fake_open(url, api_preference):
        captured_env["value"] = os.environ.get("OPENCV_FFMPEG_CAPTURE_OPTIONS")
        return True

    fake.open = fake_open
    original = cv2.VideoCapture
    cv2.VideoCapture = lambda: fake
    os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = "some_other_option;1"
    try:
        OpenCvPullSource("rtsp://example/stream", rtsp_transport="tcp")
        assert captured_env["value"] == "rtsp_transport;tcp"
        assert os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] == "some_other_option;1"
    finally:
        cv2.VideoCapture = original
        os.environ.pop("OPENCV_FFMPEG_CAPTURE_OPTIONS", None)


def test_open_source_falls_back_to_opencv_for_an_unsupported_backend(monkeypatch, caplog):
    fake = _FakeCapture()
    monkeypatch.setattr(cv2, "VideoCapture", lambda: fake)

    with caplog.at_level("WARNING"):
        source = open_source("rtsp://example/stream", backend="pyav")

    assert isinstance(source, OpenCvPullSource)
    assert any("CV_PULL_DECODER" in record.message for record in caplog.records)
    source.close()


def test_open_source_opencv_backend_does_not_warn(monkeypatch, caplog):
    fake = _FakeCapture()
    monkeypatch.setattr(cv2, "VideoCapture", lambda: fake)

    with caplog.at_level("WARNING"):
        source = open_source("rtsp://example/stream", backend="opencv")

    assert not caplog.records
    source.close()

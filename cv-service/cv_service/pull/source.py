"""``PullSource``: the seam between the decode loop and whatever library
actually opens/decodes a pulled RTSP stream (D7, MEDIA-SOT-PLAN §4/§8 M3).

M0 measured ``cv2.VideoCapture(url, cv2.CAP_FFMPEG)`` as the fastest decoder
on both the laptop and the GB4005 box (2.3-4.1 ms / 1.9-4.3 ms p50, decode
only), with zero new dependency -- ``opencv-python`` is already pinned in
the ``cv`` extra (docs/conclusions/CV-PULL-SPIKE.md §2/§7).
``OpenCvPullSource`` below is that backend, and ``CV_PULL_DECODER=opencv``
(the default, ``cv_service/config.py``) is its selector.

The point of a ``Protocol`` here -- rather than hardwiring ``cv2`` into
``loop.py`` -- is that a Pi's ``v4l2m2m`` hardware decoder or the GB4005's
VAAPI path is a LATER SWAP behind this same interface (D7), not a rewrite of
the decode loop. PyAV was M0's measured (and rejected, ~33 ms p50) fallback
candidate; it stays a *named* option only -- implementing it is not this
wave's scope (no product code chose it), so :func:`open_source` logs and
falls back to ``opencv`` for any other backend name rather than pretending
to support one that was never wired up.

Importable without the ``cv`` extra at MODULE scope (no ``cv2``/``numpy``
import here) -- ``cv2`` is imported lazily inside ``OpenCvPullSource``,
matching every other cv-service module's "no `cv` extra required to import
me" discipline (``inference/detector.py``, ``grpc/servicers.py``'s
``_frame_loader``).
"""

from __future__ import annotations

import logging
import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Optional, Protocol

LOGGER = logging.getLogger("cv_service.pull.source")

OPENCV = "opencv"
_SUPPORTED_BACKENDS = (OPENCV,)


class PullSourceError(RuntimeError):
    """The source could not be opened, or failed unrecoverably."""


@dataclass(frozen=True)
class PulledFrame:
    """One decoded frame plus everything the clock/rate diagnostics need.

    ``image`` is BGR24, shape ``(height, width, 3)`` -- ``decode_frame()``'s
    / ``detect()``'s native shape, so nothing between here and inference
    re-encodes or re-decodes it. ``pts_millis`` is
    ``cv2.CAP_PROP_POS_MSEC`` -- stream-RELATIVE elapsed time since this
    ``PullSource`` opened, NOT an absolute wallclock (see ``clock.py`` for
    why, and how ``anchor`` mode turns it into one). ``decode_millis`` is the
    wall-clock cost of the single ``read()`` call that produced this frame,
    timed by the source itself (it is the only thing that knows where decode
    ends and hand-off begins).
    """

    image: Any
    width: int
    height: int
    pts_millis: float
    decode_millis: float


class PullSource(Protocol):
    """One open connection to a pulled stream. Not thread-safe by itself --
    ``loop.py`` owns exactly one reader thread per instance, and calls
    :meth:`read` from that thread only.
    """

    def read(self) -> Optional[PulledFrame]:
        """Block for the next frame; ``None`` on end-of-stream, an
        unrecoverable read failure, or (when the backend supports a read
        timeout) no data within that budget -- ``loop.py`` treats any of
        these identically: the source has stopped producing frames."""
        ...

    def close(self) -> None: ...


# Serializes construction only (see `_apply_rtsp_transport`'s docstring for
# why) -- never held during `read()`, so two pulls with different
# `rtsp_transport` values never contend on their steady-state hot path, only
# on the brief window each spends opening.
_OPEN_LOCK = threading.Lock()


class OpenCvPullSource:
    """``cv2.VideoCapture(url, cv2.CAP_FFMPEG)`` -- ``CV_PULL_DECODER=opencv``,
    M0's chosen backend (docs/conclusions/CV-PULL-SPIKE.md §7).
    """

    def __init__(
        self,
        url: str,
        *,
        rtsp_transport: str = "tcp",
        open_timeout_millis: int = 5000,
        read_timeout_millis: int = 5000,
    ) -> None:
        import cv2

        self._cv2 = cv2
        capture = cv2.VideoCapture()
        # Set BEFORE open() -- these are the only two generic timeout knobs
        # cv2's FFmpeg backend exposes (CAP_PROP_OPEN_TIMEOUT_MSEC since
        # OpenCV 4.5.3). CAP_PROP_READ_TIMEOUT_MSEC is what makes a genuinely
        # stalled source (camera stops sending, TCP connection stays up)
        # surface as a `read()` failure within budget instead of blocking
        # forever -- loop.py's own stall-timeout watchdog is a second,
        # independent line of defense (in case a build of cv2 silently
        # ignores this property), not a substitute for it.
        capture.set(cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, open_timeout_millis)
        capture.set(cv2.CAP_PROP_READ_TIMEOUT_MSEC, read_timeout_millis)
        opened = _open_with_rtsp_transport(capture, url, rtsp_transport, cv2.CAP_FFMPEG)
        if not opened or not capture.isOpened():
            capture.release()
            raise PullSourceError(
                f"could not open pulled source {url!r} (rtsp_transport={rtsp_transport!r}, "
                f"open_timeout_millis={open_timeout_millis})"
            )
        self._capture = capture

    def read(self) -> Optional[PulledFrame]:
        t0 = time.perf_counter()
        ok, frame = self._capture.read()
        decode_millis = (time.perf_counter() - t0) * 1000.0
        if not ok or frame is None:
            return None
        height, width = frame.shape[0], frame.shape[1]
        pts_millis = self._capture.get(self._cv2.CAP_PROP_POS_MSEC)
        return PulledFrame(
            image=frame, width=width, height=height, pts_millis=pts_millis, decode_millis=decode_millis
        )

    def close(self) -> None:
        self._capture.release()


def _open_with_rtsp_transport(capture: Any, url: str, rtsp_transport: str, api_preference: int) -> bool:
    """``cv2.VideoCapture`` has no per-instance way to force the FFmpeg RTSP
    demuxer's ``rtsp_transport`` option -- the only lever it exposes is the
    process-wide ``OPENCV_FFMPEG_CAPTURE_OPTIONS`` environment variable, read
    once, at ``open()`` time. That is inherently a global, so this function
    serializes every ``OpenCvPullSource`` construction on ``_OPEN_LOCK`` and
    restores whatever value (if any) was there before -- two pulls opening
    concurrently with DIFFERENT transports would otherwise race on which
    value FFmpeg actually reads. The window is just the ``open()`` call
    itself (milliseconds), never the steady-state read loop, so this never
    contends with an already-open pull's hot path.
    """
    transport = rtsp_transport or "tcp"
    options = f"rtsp_transport;{transport}"
    with _OPEN_LOCK:
        previous = os.environ.get("OPENCV_FFMPEG_CAPTURE_OPTIONS")
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = options
        try:
            return bool(capture.open(url, api_preference))
        finally:
            if previous is None:
                os.environ.pop("OPENCV_FFMPEG_CAPTURE_OPTIONS", None)
            else:
                os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = previous


def open_source(
    url: str,
    *,
    backend: str = OPENCV,
    rtsp_transport: str = "tcp",
    open_timeout_millis: int = 5000,
    read_timeout_millis: int = 5000,
) -> PullSource:
    """Build the configured :class:`PullSource` backend.

    ``backend`` values other than ``"opencv"`` (e.g. an operator setting
    ``CV_PULL_DECODER=pyav`` ahead of that fallback actually being
    implemented) log a warning and fall back to ``opencv`` rather than
    raising -- the same "never crash-loop, degrade and say why" posture
    ``ModelRegistry``/``TrackerRegistry`` already take for an unrecognized
    id.
    """
    if backend not in _SUPPORTED_BACKENDS:
        LOGGER.warning(
            "CV_PULL_DECODER=%r is not implemented (only %r is); falling back to %r -- "
            "M0's measured choice, docs/conclusions/CV-PULL-SPIKE.md §2/§7",
            backend,
            _SUPPORTED_BACKENDS,
            OPENCV,
        )
    return OpenCvPullSource(
        url,
        rtsp_transport=rtsp_transport,
        open_timeout_millis=open_timeout_millis,
        read_timeout_millis=read_timeout_millis,
    )

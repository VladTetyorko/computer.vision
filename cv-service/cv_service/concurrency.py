"""Concurrency primitives for `cv_service.server`'s `DetectStream` handling.

Two independent knobs live here (see `MODULE.md` "V-d: parallel per-stream
inference" for the full design writeup and what was verified):

* **Per-stream** (`LatestOnlyMailbox`): decouples *receiving* frames over the
  network from *inferring* on them, so the next frame's transit can overlap
  with the current frame's inference instead of the two being strictly
  serial. It never queues more than one waiting frame -- if a second frame
  arrives before the consumer has taken the first, the first is silently
  replaced (dropped, never inferred, never yields a response). This module
  is deliberately generic (no `cv_pb2` import) -- `server.py`'s
  `_StreamReader` is what wires it to `FrameRequest`, mirroring
  `inference.py`'s own "no protobuf here" convention.
* **Across streams** (`InferenceGate`): bounds how many `detect()` calls may
  run at once, process-wide, protecting the CPU from N streams all
  launching their own (already internally-parallel) inference call
  simultaneously.
"""

from __future__ import annotations

import logging
import os
import threading
from contextlib import contextmanager
from typing import Generic, Iterator, Optional, TypeVar

LOGGER = logging.getLogger("cv_service.concurrency")

T = TypeVar("T")

# Process-wide bound on concurrent `detect()` calls, overridable via the
# CV_MAX_CONCURRENT_INFERENCES env var (same forgiving-parse idiom as
# inference.py's CV_IMGSZ -- a bad/missing value is a performance knob, not
# something that should crash the service).
_ENV_MAX_CONCURRENT_INFERENCES = "CV_MAX_CONCURRENT_INFERENCES"


class LatestOnlyMailbox(Generic[T]):
    """A single-slot, latest-wins handoff between one producer thread and one
    consumer thread.

    `put()` never blocks and never queues: if a value is already waiting
    (the consumer hasn't taken the previous one yet), it is silently
    replaced -- `dropped` counts how many times this happened. The point is
    that a slow consumer never builds a backlog of stale frames; it always
    works on the newest one available once it's ready. `close()` makes every
    current/future blocked `get()` return `None` once any already-pending
    value has been drained -- signals "no more values are coming."

    Not meant for values where `None` is itself a meaningful payload -- `get`
    uses `None` as the "closed and empty" sentinel. `FrameRequest` messages
    are never `None`, so this is a non-issue for this module's actual use.
    """

    def __init__(self) -> None:
        self._not_empty = threading.Condition(threading.Lock())
        self._value: Optional[T] = None
        self._has_value = False
        self._closed = False
        self.dropped = 0

    def put(self, value: T) -> None:
        with self._not_empty:
            if self._closed:
                return
            if self._has_value:
                self.dropped += 1
            self._value = value
            self._has_value = True
            self._not_empty.notify()

    def get(self) -> Optional[T]:
        """Block until a value is available, or `None` once closed and drained."""
        with self._not_empty:
            while not self._has_value and not self._closed:
                self._not_empty.wait()
            if self._has_value:
                value = self._value
                self._value = None
                self._has_value = False
                return value
            return None

    def close(self) -> None:
        with self._not_empty:
            self._closed = True
            self._not_empty.notify_all()


def _default_max_concurrent_inferences() -> int:
    """`min(2, cpu_count // 2)`, floored at 1.

    Two, not `cpu_count`, is deliberate: a single Ultralytics/PyTorch
    `detect()` call already spreads itself across several intra-op threads
    on its own -- verified in this task's dev environment,
    `torch.get_num_threads()` reported `6` on a 12-logical-core box, entirely
    PyTorch's own default heuristic, nothing this module configures. So a
    handful of *concurrent* `detect()` calls can already contend for every
    core; `cpu_count // 2` keeps total demand (concurrent calls x each
    call's own intra-op threads) in the right ballpark relative to the
    machine instead of naively scaling the gate with core count, and it's
    capped at 2 because the realistic demo load (1-3 simultaneous streams)
    never needs more anyway (see MODULE.md for the honest OpenVINO caveat:
    its own CPU-plugin thread auto-configuration was not independently
    re-verified the same way, since OpenVINO only runs inside the Docker
    image, not this dev environment). Floored at 1 so a 1-2 logical-core box
    still runs (serially, which is the correct degradation) instead of
    constructing an invalid `Semaphore(0)`.
    """
    cpu = os.cpu_count() or 4
    return max(1, min(2, cpu // 2))


def _resolve_max_concurrent_inferences(raw: Optional[str]) -> int:
    default = _default_max_concurrent_inferences()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning(
            "%s=%r is not a valid integer; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    if value <= 0:
        LOGGER.warning(
            "%s=%r must be positive; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    return value


class InferenceGate:
    """Process-wide bound on how many `detect()` calls may run concurrently.

    A plain counting `threading.Semaphore`, not a thread pool -- inference
    already runs on each stream's own consumer thread (see `server.py`'s
    `DetectStream`/`_StreamReader`); this only ever gates *how many of those
    threads* may be inside `detect()` at once, it never moves work onto new
    threads of its own.
    """

    def __init__(self, max_concurrent: int) -> None:
        if max_concurrent < 1:
            raise ValueError(f"max_concurrent must be >= 1, got {max_concurrent}")
        self.max_concurrent = max_concurrent
        self._semaphore = threading.Semaphore(max_concurrent)

    @contextmanager
    def acquire(self) -> Iterator[None]:
        self._semaphore.acquire()
        try:
            yield
        finally:
            self._semaphore.release()


# Resolved once at import time (env var is a startup-time deployment knob,
# not something expected to change while the process is running).
DEFAULT_MAX_CONCURRENT_INFERENCES = _resolve_max_concurrent_inferences(
    os.environ.get(_ENV_MAX_CONCURRENT_INFERENCES)
)

_PROCESS_GATE = InferenceGate(DEFAULT_MAX_CONCURRENT_INFERENCES)


def process_gate() -> InferenceGate:
    """The shared, process-wide `InferenceGate` every `InferenceServicer`
    uses by default (tests inject their own differently-sized gate instead --
    see `InferenceServicer.__init__`'s `inference_gate` parameter)."""
    return _PROCESS_GATE

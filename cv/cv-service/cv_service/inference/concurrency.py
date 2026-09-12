"""Concurrency primitives for ``cv_service.grpc.servicers``' `DetectStream` handling.

Two independent knobs live here (see `MODULE.md` "V-d: parallel per-stream
inference" for the full design writeup and what was verified):

* **Per-stream** (`LatestOnlyMailbox`): decouples *receiving* frames over the
  network from *inferring* on them, so the next frame's transit can overlap
  with the current frame's inference instead of the two being strictly
  serial. It never queues more than one waiting frame -- if a second frame
  arrives before the consumer has taken the first, the first is silently
  replaced (dropped, never inferred, never yields a response). This module
  is deliberately generic (no `cv_pb2` import) -- `cv_service.grpc.servicers`'
  `_StreamReader` is what wires it to `FrameRequest`, mirroring
  `inference/detector.py`'s own "no protobuf here" convention.
* **Across streams** (`InferenceGate`): bounds how many `detect()` calls may
  run at once, process-wide, protecting the CPU from N streams all
  launching their own (already internally-parallel) inference call
  simultaneously.

Pure stdlib (`threading` only -- no `cv2`/`numpy`/`ultralytics`), so this
module is importable without the `cv` extra installed.
"""

from __future__ import annotations

import logging
import threading
from contextlib import contextmanager
from typing import Generic, Iterator, Optional, TypeVar

LOGGER = logging.getLogger("cv_service.inference.concurrency")

T = TypeVar("T")


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


class GateFull(RuntimeError):
    """Admission refused: this instance's queue is already at its bound.

    Raised only by `InferenceGate.admit`, never by `acquire` --
    CV-ORCHESTRATION §4.9 puts admission at the detector, and a refusal is a
    *reported* outcome (`RESOURCE_EXHAUSTED` on the `Detector` wire, then the
    caller's next target) rather than an error anybody has to handle as one.
    """


class InferenceGate:
    """Process-wide bound on how many `detect()` calls may run concurrently.

    A plain counting `threading.Semaphore`, not a thread pool -- inference
    already runs on each stream's own consumer thread (see
    `cv_service.grpc.servicers`' `DetectStream`/`_StreamReader`); this only
    ever gates *how many of those threads* may be inside `detect()` at once,
    it never moves work onto new threads of its own.

    Since CV-ORCHESTRATION wave W4 it also COUNTS, because a detector instance
    that cannot say how busy it is cannot be pooled: the fleet budget is the
    sum of what instances report (decision E10), not a shared token. The two
    counters cost one uncontended lock acquisition on each side of a call that
    already costs 135-230ms, so they are unconditional rather than a knob.
    """

    def __init__(self, max_concurrent: int) -> None:
        if max_concurrent < 1:
            raise ValueError(f"max_concurrent must be >= 1, got {max_concurrent}")
        self.max_concurrent = max_concurrent
        self._semaphore = threading.Semaphore(max_concurrent)
        # One lock for BOTH counters: `admit` has to test-and-increment
        # `_waiting` atomically or two callers can both squeak past a full
        # queue, and splitting the counters would make that impossible to
        # state in one place.
        self._counts = threading.Lock()
        self._occupancy = 0
        self._waiting = 0

    @property
    def occupancy(self) -> int:
        """Calls inside the gate right now (0..`max_concurrent`)."""
        return self._occupancy

    @property
    def queue_depth(self) -> int:
        """Calls waiting for a permit -- the number admission bounds."""
        return self._waiting

    @contextmanager
    def acquire(self) -> Iterator[None]:
        """Wait for a permit, however long it takes. Unchanged by W4.

        Every in-process caller uses this: an all-in-one deployment has
        nowhere else to send the work, so refusing it would drop a frame that
        nothing else was going to detect.
        """
        self._enter_queue()
        yield from self._hold()

    @contextmanager
    def admit(self, max_queue: int) -> Iterator[None]:
        """Wait for a permit, or raise `GateFull` if the queue is already full.

        The `Detector` service's door (§4.9): a pooled caller HAS somewhere
        else to go, so telling it "no" immediately is strictly better than
        making it wait behind work it could have routed around.
        """
        with self._counts:
            if self._waiting >= max_queue:
                raise GateFull(
                    f"inference queue full: {self._waiting} waiting, bound {max_queue}"
                )
            self._waiting += 1
        yield from self._hold()

    def _enter_queue(self) -> None:
        with self._counts:
            self._waiting += 1

    def _hold(self) -> Iterator[None]:
        """Block for a permit already queued for, then hold it for the body."""
        self._semaphore.acquire()
        with self._counts:
            self._waiting -= 1
            self._occupancy += 1
        try:
            yield
        finally:
            with self._counts:
                self._occupancy -= 1
            self._semaphore.release()


_process_gate_lock = threading.Lock()
_process_gate: Optional[InferenceGate] = None


def process_gate() -> InferenceGate:
    """The shared, process-wide `InferenceGate` a bare `InferenceServicer()`
    falls back to when no `inference_gate=` is given (the production
    composition root, `cv_service.grpc.server.serve()`, always builds and
    passes one explicitly from its already-resolved `Settings` instead, so
    this lazy singleton is only ever reached via direct/test construction).

    Lazily built (and cached for the rest of the process) from
    `cv_service.config.Settings.from_env()` on first call -- deliberately
    *not* resolved at import time (unlike this gate's pre-`config.py`
    incarnation): nothing here needs the value before it's actually needed,
    and building it lazily means a plain `import cv_service.inference.concurrency`
    never touches `os.environ` on its own.
    """
    global _process_gate
    if _process_gate is None:
        with _process_gate_lock:
            if _process_gate is None:
                from cv_service.config import Settings

                _process_gate = InferenceGate(Settings.from_env().max_concurrent_inferences)
    return _process_gate

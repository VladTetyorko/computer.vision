"""The pull worker's decode loop: one background reader thread pulling frames
from a :class:`~cv_service.pull.source.PullSource` as fast as they arrive,
paired with the deadline sampler that decides which of those frames actually
get served for inference (MEDIA-SOT-PLAN §7, §8 M3;
docs/conclusions/CV-PULL-SPIKE.md §3).

Ported from ``cv-service/spikes/pull/sampler.py`` -- which itself is a Python
port of ``StreamPipeline.sampleDue``/``armScheduleAt``
(feat/cv-rate-control, ``vision-application/.../pipeline/StreamPipeline.java``
~L908-943). Two properties are load-bearing and are exactly what a careless
port loses -- both are preserved here on purpose, carried forward from the
spike unchanged:

1. **One clock read per frame.** :meth:`DeadlineSampler.sample_due` NEVER
   reads the clock itself -- :meth:`PullDecodeLoop.frames`, its one caller,
   reads ``time.monotonic_ns()`` exactly once per loop iteration and passes
   it in. Two reads for one decision (one to check "is it due", a second
   inside to arm the next deadline) would let the schedule silently skew
   from whatever ``now`` classified the iteration as due in the first place.
2. **The ``max(now, ...)`` debt clamp.** When more than a whole interval has
   elapsed since the last-armed deadline, the schedule restarts from
   ``now + interval`` instead of advancing by one interval from where it
   left off. Without this, a stalled/slow consumer builds up deadline debt
   that fires as a catch-up burst the moment it recovers -- spending the
   scarcest resource (inference) on frames whose moment has already passed.
   The skipped deadlines are counted (``missed_deadlines``) instead of
   served.

**D8 (MEDIA-SOT-PLAN decision, CLAUDE.md rule 9).** The reader thread always
overwrites the mailbox's single slot, never queues: a not-yet-consumed frame
is replaced, so the decode loop is always working with the NEWEST frame the
source has produced, and a stalled consumer (inference) never makes it fall
behind on wall-clock freshness -- it only ever loses frames it would have
discarded as stale anyway.

**What ``dropped_frames`` counts, precisely (fixed post-MEDIA-SOT-RESULTS.md
&sect;6).** Not every overwrite is a discard. Most of the time the source
simply runs faster than ``target_fps`` -- the reader overwrites the mailbox
several times between one served deadline and the next, and none of those
intermediate frames was ever going to be selected; that is downsampling
working as designed, visible instead as the gap between ``source_fps`` and
``achieved_fps``. A frame only counts as genuinely **dropped** when it is
overwritten while :meth:`PullDecodeLoop.frames` has already handed a served
frame to its caller and is waiting for control back -- i.e. inference is
busy and a fresher frame arrives before the caller returns for the next one.
:attr:`PullDecodeLoop._awaiting_consumer` is exactly that window. Every such
discard is counted (``dropped_frames``) and travels on the wire
(``DetectionResponse.dropped_frames``, field 19) -- never silent (the
standing CV-RATE-BUDGET.md lesson: an uncounted drop is how ``effectiveFps``
sat at 7.58 against a configured 10 for a whole release) -- but a discard
the sampler never wanted in the first place is not miscounted as one either.

Pure stdlib (``threading``, ``time``) -- no ``cv_pb2`` import (that
translation belongs to ``grpc/servicers.py`` alone) and no ``cv2``/``numpy``
import (this module never looks inside a frame's pixels, only at its
metadata) -- so it is importable without the ``cv`` extra, same discipline
``cv_service.inference.concurrency`` keeps.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from typing import Callable, Iterator, Optional

from cv_service.pull.clock import CaptureClock
from cv_service.pull.source import PulledFrame, PullSource, PullSourceError

LOGGER = logging.getLogger("cv_service.pull.loop")

NANOS_PER_SECOND = 1_000_000_000


class PullStalledError(PullSourceError):
    """No frame arrived from the source for longer than the configured stall
    budget (``CV_PULL_STALL_TIMEOUT_MILLIS``) -- MEDIA-SOT-PLAN §5.1's call
    semantics: this ends the ``DetectPulled`` call with ``UNAVAILABLE``.
    Distinct from D8's decode-side drop (a slow DETECTOR never raises this;
    it only grows ``dropped_frames`` -- see this module's own docstring).
    """


class DeadlineSampler:
    """Decides, once per loop iteration, whether this instant serves a sample
    deadline. Ported verbatim from ``spikes/pull/sampler.py`` (see that
    module and this file's own docstring for the two load-bearing
    properties), plus :meth:`retarget` -- new here, because
    ``PullControl.target_fps`` is HOT (MEDIA-SOT-PLAN §5.1): it may change on
    any message of a call, unlike the spike's fixed-at-construction target.

    Every deadline is served by exactly one frame (not ``sequence % N``), so
    the achieved rate equals the target for any source faster than it -- the
    same guarantee the Java version documents.
    """

    def __init__(self, target_fps: float) -> None:
        self._interval_ns = _interval_ns(target_fps)
        self._armed = False
        self._last_sample_at_ns = 0
        self._next_sample_at_ns = 0
        self.missed_deadlines = 0

    def retarget(self, target_fps: float) -> None:
        """Change the sampling interval used by every FUTURE arm decision.

        Deliberately does not touch ``_armed``/``_last_sample_at_ns``/
        ``_next_sample_at_ns`` -- the already-armed next deadline still fires
        at its own previously-computed instant; only the interval computed
        FROM that point on changes. Self-correcting within one interval of
        the change, with no special-cased reset -- exactly like the Java
        rate controller's own output changing ``targetFps`` between frames.
        """
        self._interval_ns = _interval_ns(target_fps)

    def sample_due(self, now_ns: int) -> bool:
        """``now_ns`` must be read by the caller exactly once for this
        iteration (see module docstring, property 1)."""
        if not self._armed:
            self._armed = True
            self._arm(now_ns, now_ns + self._interval_ns)
            return True

        if now_ns > self._last_sample_at_ns and now_ns < self._next_sample_at_ns:
            return False

        if now_ns <= self._last_sample_at_ns:
            # Degenerate clock (frozen or stepped backwards): fail OPEN,
            # matching the Java rationale -- a time-based schedule that fails
            # closed on clock skew silently stops all detection, the loudest
            # failure for the quietest cause.
            self._arm(now_ns, now_ns + self._interval_ns)
            return True

        advanced = self._next_sample_at_ns + self._interval_ns
        if advanced <= now_ns:
            self.missed_deadlines += (now_ns - self._next_sample_at_ns) // self._interval_ns
            advanced = now_ns + self._interval_ns
        self._arm(now_ns, advanced)
        return True

    def _arm(self, now_ns: int, next_at_ns: int) -> None:
        self._last_sample_at_ns = now_ns
        self._next_sample_at_ns = next_at_ns


def _interval_ns(target_fps: float) -> int:
    if target_fps <= 0:
        raise ValueError(f"target_fps must be > 0, got {target_fps}")
    return max(1, round(NANOS_PER_SECOND / target_fps))


class _LatestOnlyMailbox:
    """Single-slot, latest-wins, NON-BLOCKING (put/take) handoff between the
    reader thread and the consumer -- ported from ``spikes/pull/sampler.py``.

    Deliberately distinct from ``cv_service.inference.concurrency.
    LatestOnlyMailbox``: that one's ``get()`` BLOCKS until a value or
    ``close()`` arrives, the right shape for ``DetectStream``'s
    ``_StreamReader`` (which has nothing else to do while waiting for the
    next network frame). This loop's consumer instead runs the deadline
    sampler on its own schedule and only wants to LOOK when a deadline is
    due, so it needs a non-blocking peek/take, not a blocking wait.

    Purely mechanical -- it does not decide what counts as a genuine drop
    (see the module docstring's D8 section and :class:`PullDecodeLoop`);
    it only reports whether a ``put`` overwrote an unconsumed frame, and
    lets the caller decide whether that overwrite matters.
    """

    def __init__(self) -> None:
        self._slot: Optional[PulledFrame] = None
        self._lock = threading.Lock()

    def put(self, frame: PulledFrame) -> bool:
        """Returns True when this overwrote a frame nobody had taken yet."""
        with self._lock:
            overwritten = self._slot is not None
            self._slot = frame
            return overwritten

    def take(self) -> Optional[PulledFrame]:
        with self._lock:
            frame = self._slot
            self._slot = None
            return frame


class _RateMeter:
    """Seed-then-blend EWMA of inter-event rate -- mirrors
    ``StreamPipeline#recordArrival``'s own measurement
    (vision-application/.../pipeline/StreamPipeline.java) so
    ``source_fps``/``achieved_fps`` here mean the same thing push mode's
    ``DetectionRate`` already does: seeded directly from the first observed
    interval (not blended in slowly from an arbitrary starting value), then
    EWMA-blended thereafter.
    """

    def __init__(self, *, alpha: float = 0.2) -> None:
        self._alpha = alpha
        self._last_event_ns: Optional[int] = None
        self.fps: float = 0.0

    def record(self, now_ns: int) -> None:
        if self._last_event_ns is not None:
            delta_ns = now_ns - self._last_event_ns
            if delta_ns > 0:
                instantaneous = NANOS_PER_SECOND / delta_ns
                self.fps = instantaneous if self.fps <= 0 else (
                    self._alpha * instantaneous + (1 - self._alpha) * self.fps
                )
        self._last_event_ns = now_ns


@dataclass(frozen=True)
class PullDiagnostics:
    """``DetectionResponse`` fields 17-21's worth of per-response accounting
    for a served frame (``decode_millis``, field 16, is set by
    ``servicers.py`` directly from the frame's own ``PulledFrame.decode_millis``
    -- that one is per-frame source metadata, not loop-level accounting, so
    it does not need to round-trip through here)."""

    source_fps: float
    achieved_fps: float
    dropped_frames: int
    missed_deadlines: int
    capture_skew_millis: int


class PullDecodeLoop:
    """Owns the reader thread for one pulled stream. :meth:`frames` yields
    one ``(frame, captured_at_millis, diagnostics)`` per SERVED sample
    deadline, until the source ends/stalls (raises :class:`PullStalledError`)
    or the caller's ``should_continue`` predicate turns false.

    ``_awaiting_consumer`` marks the one window that separates a genuine
    drop from ordinary downsampling (module docstring, D8 section): it is
    True from the moment a served frame is handed to the caller (``yield``
    in :meth:`frames`) until the caller comes back for the next one --
    exactly the span the real caller (``InferenceServicer.DetectPulled``)
    spends running inference synchronously before asking for another frame.
    Any mailbox overwrite that lands inside that window is a frame the loop
    genuinely could not keep up with; any overwrite outside it is just the
    source running faster than ``target_fps``, which the sampler was never
    going to select anyway. Set/cleared only by the consumer thread; read
    (never written) by the reader thread -- the same lock-free, single-
    writer cross-thread pattern ``_last_frame_monotonic``/``_error`` already
    use in this class.
    """

    def __init__(
        self,
        source: PullSource,
        *,
        target_fps: float,
        clock: CaptureClock,
        stall_timeout_millis: float,
        poll_interval_seconds: float = 0.005,
    ) -> None:
        self._source = source
        self._sampler = DeadlineSampler(target_fps)
        self._clock = clock
        self._stall_timeout_millis = stall_timeout_millis
        self._poll_interval_seconds = poll_interval_seconds
        self._mailbox = _LatestOnlyMailbox()
        self._source_rate = _RateMeter()
        self._achieved_rate = _RateMeter()
        self._stop_event = threading.Event()
        self._error: Optional[BaseException] = None
        self._last_frame_monotonic = time.monotonic()
        self._dropped_frames = 0
        self._awaiting_consumer = False
        self._reader = threading.Thread(target=self._read_loop, name="cv-pull-reader", daemon=True)
        self._reader.start()

    def set_target_fps(self, target_fps: float) -> None:
        """Hot: ``PullControl.target_fps`` may change on any message."""
        self._sampler.retarget(target_fps)

    def stop(self) -> None:
        """Ask :meth:`frames` to return once its current wait ends -- used
        for ``PullControl.stop=true`` and client half-close."""
        self._stop_event.set()

    def close(self) -> None:
        """Stop the reader thread and release the source. Always safe to
        call more than once."""
        self.stop()
        self._source.close()
        self._reader.join(timeout=2.0)

    def _read_loop(self) -> None:
        try:
            while not self._stop_event.is_set():
                frame = self._source.read()
                if frame is None:
                    self._error = PullStalledError("pulled source ended or stopped producing frames")
                    return
                self._last_frame_monotonic = time.monotonic()
                self._source_rate.record(time.monotonic_ns())
                overwritten = self._mailbox.put(frame)
                # Only a discard if it happened while a served frame was out
                # for inference (see class docstring) -- an overwrite that
                # lands while frames() is merely waiting for its next
                # scheduled deadline is downsampling, not a drop.
                if overwritten and self._awaiting_consumer:
                    self._dropped_frames += 1
        except Exception as exc:  # noqa: BLE001 - surfaced to frames() below, never crashes the thread silently
            self._error = exc

    def frames(
        self, *, should_continue: Callable[[], bool] = lambda: True
    ) -> Iterator[tuple[PulledFrame, int, PullDiagnostics]]:
        """Yields one served sample per deadline. Raises :class:`PullStalledError`
        (subclass of ``PullSourceError``) when the source itself has stopped
        producing frames -- caller is expected to translate that into
        ``UNAVAILABLE`` (MEDIA-SOT-PLAN §5.1)."""
        while not self._stop_event.is_set() and should_continue():
            if self._error is not None:
                raise self._error

            stalled_for_millis = (time.monotonic() - self._last_frame_monotonic) * 1000.0
            if stalled_for_millis > self._stall_timeout_millis:
                raise PullStalledError(
                    f"no frame received for {stalled_for_millis:.0f}ms "
                    f"(budget {self._stall_timeout_millis}ms)"
                )

            now_ns = time.monotonic_ns()  # ONE clock read for this iteration's due-check AND arm
            if not self._sampler.sample_due(now_ns):
                time.sleep(self._poll_interval_seconds)
                continue

            frame = self._mailbox.take()
            if frame is None:
                # Armed but nothing has arrived yet -- only possible right at
                # call start, before the reader thread's first frame. The
                # deadline this represents was already recorded by `_arm`;
                # nothing more to count here, just wait for the next one.
                time.sleep(self._poll_interval_seconds)
                continue

            now_wall_millis = time.time() * 1000.0  # the OTHER clock this frame reads, once (clock.py)
            captured_at_millis, capture_skew_millis = self._clock.capture_time(
                pts_millis=frame.pts_millis, now_wall_millis=now_wall_millis
            )
            self._achieved_rate.record(now_ns)
            diagnostics = PullDiagnostics(
                source_fps=round(self._source_rate.fps, 3),
                achieved_fps=round(self._achieved_rate.fps, 3),
                dropped_frames=self._dropped_frames,
                missed_deadlines=self._sampler.missed_deadlines,
                capture_skew_millis=capture_skew_millis,
            )
            # Open the "genuinely could not keep up" window (class docstring)
            # for exactly as long as the caller has this frame -- the real
            # caller (servicers.py) runs inference synchronously before
            # coming back for the next one, so this span IS the inference
            # cost. Overwrites the reader makes while we're sitting in
            # sample_due()'s own poll-sleep, below, are never inside it.
            self._awaiting_consumer = True
            try:
                yield frame, captured_at_millis, diagnostics
            finally:
                self._awaiting_consumer = False

        if self._error is not None:
            raise self._error

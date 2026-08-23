"""Python port of the deadline sampler from `StreamPipeline.sampleDue`/`armScheduleAt`
(feat/cv-rate-control, vision-application/src/main/java/com/drones/vision/application/
pipeline/StreamPipeline.java, ~L908-943), for M0's rate-discipline measurement
(docs/plans/done/MEDIA-SOT-PLAN.md §8 M0, §7 "deadline sampler ... ported to Python").

Two properties are load-bearing and are exactly what a careless port loses -- both are
preserved here on purpose, not incidentally:

1. **One clock read per frame.** `sample_due(now_ns)` NEVER reads the clock itself --
   the caller reads it exactly once per incoming frame and passes it in. Two clock reads
   in the same frame's decision (one to decide "is it due", a second inside to arm the
   next deadline) would let the schedule silently skew from whatever `now` classified
   the frame as due in the first place -- a subtle bug the Java code avoids by threading
   a single `long now` through the whole per-frame call chain.

2. **The `max(now, ...)` debt clamp.** When a frame arrives more than a whole interval
   late (`advanced <= now`), the schedule restarts from `now + interval` instead of
   advancing by one interval from where it left off. Without this, a stalled/slow source
   builds up deadline debt that fires as a catch-up burst the moment it recovers --
   spending the scarcest resource (inference) on frames whose moment has already passed.
   The skipped deadlines are counted (`missed_deadlines`) instead of served.
"""
from __future__ import annotations

NANOS_PER_SECOND = 1_000_000_000


class DeadlineSampler:
    """Decides, once per incoming frame, whether that frame serves a sample deadline.

    Every deadline is served by exactly one frame (not `sequence % stride`), so the
    achieved rate equals the target for any source faster than it -- the same guarantee
    the Java version documents.
    """

    def __init__(self, target_fps: float) -> None:
        if target_fps <= 0:
            raise ValueError(f"target_fps must be > 0, got {target_fps}")
        self._interval_ns = max(1, round(NANOS_PER_SECOND / target_fps))
        self._armed = False
        self._last_sample_at_ns = 0
        self._next_sample_at_ns = 0
        self.missed_deadlines = 0

    def sample_due(self, now_ns: int) -> bool:
        """`now_ns` must be read by the caller exactly once for this frame."""
        if not self._armed:
            self._armed = True
            self._arm(now_ns, now_ns + self._interval_ns)
            return True

        if now_ns > self._last_sample_at_ns and now_ns < self._next_sample_at_ns:
            return False

        if now_ns <= self._last_sample_at_ns:
            # Degenerate clock (frozen or stepped backwards): fail OPEN, matching the
            # Java rationale -- a time-based schedule that fails closed on clock skew
            # silently stops all detection, the loudest failure for the quietest cause.
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


class LatestOnlyMailbox:
    """Single-slot, latest-wins handoff between a reader loop and a consumer loop (D8).

    `put` never blocks and never queues: a not-yet-consumed item is overwritten and
    counted as dropped. This is the decode-side equivalent of the inference-side
    `InferenceGate`/in-flight-bound discipline the Java pipeline already has -- the
    newest frame always wins, and the loss is counted, never silent.
    """

    def __init__(self) -> None:
        self._slot = None
        self.dropped_frames = 0

    def put(self, item) -> None:
        if self._slot is not None:
            self.dropped_frames += 1
        self._slot = item

    def take(self):
        item = self._slot
        self._slot = None
        return item

    def peek_available(self) -> bool:
        return self._slot is not None

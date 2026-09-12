"""`SessionRegistry`: `StreamTrackingSession` pooled by `stream_id`, not by RPC call.

`docs/plans/done/TRACKING-V2-PLAN.md` wave C5b,
`docs/conclusions/TRACKING-REVIEW.md` finding B5 ("Session state lives in the
RPC call ... an RF link blip, a reconnect, a worker restart resets every id
in the scene to 1. On a drone link that is not an edge case.").

Before this module, `InferenceServicer.DetectStream` built a fresh
`StreamTrackingSession` on every call -- so the identity core wave C4 spent a
whole wave building (a dormant gallery that survives a NINE-SECOND occlusion)
was worthless against a TWO-SECOND reconnect, because the book that gallery
lives inside never survived the reconnect long enough to be asked.

**The fix, and its one deliberate subtlety.** Keying by `stream_id` with a
disconnect grace window lets a reconnecting stream resume the SAME
`StreamTrackingSession` object -- same `TrackBook` (ids), same `ObjectMemory`
(the dormant gallery), same `LockArbiter` (the operator's FOLLOW target). But
`lk`/`flow` each hold a previous DECODED FRAME, and after a reconnect frame
continuity is broken -- the next frame that arrives is not adjacent to
whatever the engine last saw. Feeding it in anyway computes optical flow (or
a "camera motion" transform) across a discontinuity: a large, bogus estimate
applied to every live track at exactly the moment the stream is most
fragile. So a resumed session's per-frame ENGINES (the SOT, the motion
compensator, the appearance extractor) are reset on release -- forcing a
clean rebuild on the resumed stream's first active frame -- while the
identity core (book/gallery/lock) is left completely untouched. See
`StreamTrackingSession.reset_for_reconnect` for the mechanism this delegates
to; this module only decides WHEN a disconnect happened, never WHAT one
means to a session's own internals.

**Thread safety.** `InferenceServicer` is shared across every concurrent
`DetectStream` call on the gRPC `ThreadPoolExecutor`, so this registry's own
bookkeeping -- the `{stream_id -> _Entry}` map and each entry's `in_use`/
`released_at_millis` fields -- is guarded by one `threading.Lock` for the
full duration of every `acquire()`/`release()` call. What this lock does NOT
protect: a `StreamTrackingSession`'s OWN per-frame state. That is safe by
construction, not by locking, because `acquire()` only ever hands out an
entry with `in_use=False`, immediately flips it to `in_use=True` before
releasing the registry's own lock, and every `DetectStream` call processes
its frames strictly sequentially on one thread -- so at most one thread ever
touches one `StreamTrackingSession`'s frame-processing methods at a time.
The one race this registry chooses NOT to serialize -- two concurrent
`DetectStream` calls for the SAME `stream_id` (a client bug: a stream_id is
meant to identify one call at a time) -- is handled by minting the second
caller an INDEPENDENT, never-pooled session rather than blocking the caller
or handing out a session two threads would mutate at once (see `acquire()`).

**Bounded.** A stream that disconnects and never reconnects must not leak: a
released entry is evicted once `grace_millis` has elapsed since release,
swept opportunistically on every `acquire()`/`release()` call (no background
thread, no timer). `capacity` caps the total number of entries this registry
will hold; when exceeded, the OLDEST-released disconnected entries are
evicted first -- an `in_use` entry is NEVER evicted (that would corrupt a
live stream out from under it), so a fleet with more concurrent LIVE streams
than `capacity` is bounded elsewhere, by the gRPC server's own thread pool
(`CV_GRPC_WORKERS`), not by this cap.

Pure stdlib (`threading`, `time`) -- this module knows nothing about
`cv2`/`numpy`/gRPC, the same invariant every module under `tracking/` outside
`engines/` carries (P3).
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from typing import Callable, Optional

from cv_service.tracking.session import StreamTrackingSession

LOGGER = logging.getLogger("cv_service.tracking.sessions")


@dataclass
class _Entry:
    """One pooled session plus this registry's own bookkeeping about it.

    `released_at_millis` is `None` while `in_use` -- there is nothing to
    measure a grace window FROM until the stream has actually disconnected.
    """

    session: StreamTrackingSession
    in_use: bool
    released_at_millis: Optional[float] = None


class SessionRegistry:
    """`StreamTrackingSession` pool keyed by `stream_id`, one instance per servicer."""

    def __init__(
        self,
        *,
        grace_millis: int,
        capacity: int,
        session_factory: Callable[[], StreamTrackingSession],
    ) -> None:
        self._grace_millis = max(0, grace_millis)
        self._capacity = max(1, capacity)
        self._session_factory = session_factory
        self._lock = threading.Lock()
        self._entries: "dict[str, _Entry]" = {}

    def acquire(self, stream_id: str) -> StreamTrackingSession:
        """The session for `stream_id`: resumed if one is waiting inside its
        grace window, otherwise freshly built.

        A blank `stream_id` never pools -- an un-keyable stream has no way
        to be correlated with a future reconnect, and pooling it under the
        empty string would risk sharing state between two genuinely
        different streams that both happened to omit one.
        """
        if not stream_id:
            return self._mint("")
        now = _now_millis()
        with self._lock:
            self._evict_expired(now)
            entry = self._entries.get(stream_id)
            if entry is None:
                session = self._mint(stream_id)
                self._entries[stream_id] = _Entry(session=session, in_use=True)
                self._enforce_capacity()
                return session
            if entry.in_use:
                # A second live call for a stream_id already in use -- a
                # client bug (a stream_id is meant to identify one call at a
                # time), not a reconnect. Never hand out, or otherwise touch,
                # the entry another thread owns; mint an independent session
                # instead so this call still works, just unpooled.
                LOGGER.warning(
                    "tracking: stream_id=%r already has a live session; "
                    "serving this call an independent, unpooled one",
                    stream_id,
                )
                return self._mint(stream_id)
            entry.in_use = True
            entry.released_at_millis = None
            LOGGER.info(
                "tracking: stream_id=%r resumed its session (reconnect within the grace window)",
                stream_id,
            )
            return entry.session

    def _mint(self, stream_id: str) -> StreamTrackingSession:
        """A fresh session, stamped with the id it serves.

        The registry is the only thing that knows a session's `stream_id` --
        the factory takes no arguments and the session never learns one on
        its own -- so this is where the ledger gets its name (CV-ORCHESTRATION
        §4.4). Stamped even on the unpooled fallback paths, so a ledger read
        through `Inspect` is never anonymous just because pooling was skipped.
        """
        session = self._session_factory()
        session.stream_id = stream_id
        return session

    def release(self, stream_id: str, session: StreamTrackingSession) -> None:
        """Return `session` to the pool, disconnected as of now.

        Resets its per-frame ENGINES (never its book/gallery/lock -- see
        `StreamTrackingSession.reset_for_reconnect`) immediately, so an entry
        sitting inside its grace window never holds a stale decoded frame,
        and a reconnect that DOES arrive resumes into an already-clean
        state.

        A no-op if `session` is not the CURRENT entry for `stream_id` -- an
        unpooled session (see `acquire()`'s concurrent-call branch above), or
        one a newer call has already replaced, is simply dropped, never
        double-released and never allowed to clobber a different call's
        entry.
        """
        if not stream_id:
            return
        now = _now_millis()
        with self._lock:
            entry = self._entries.get(stream_id)
            if entry is None or entry.session is not session:
                return
            session.reset_for_reconnect()
            entry.in_use = False
            entry.released_at_millis = now
            self._evict_expired(now)

    def size(self) -> int:
        """Diagnostics/tests only, never the hot path."""
        with self._lock:
            return len(self._entries)

    def _evict_expired(self, now_millis: float) -> None:
        expired = [
            stream_id
            for stream_id, entry in self._entries.items()
            if not entry.in_use
            and entry.released_at_millis is not None
            and now_millis - entry.released_at_millis > self._grace_millis
        ]
        for stream_id in expired:
            del self._entries[stream_id]

    def _enforce_capacity(self) -> None:
        if len(self._entries) <= self._capacity:
            return
        disconnected = sorted(
            (
                (stream_id, entry)
                for stream_id, entry in self._entries.items()
                if not entry.in_use
            ),
            key=lambda item: item[1].released_at_millis or 0.0,
        )
        overflow = len(self._entries) - self._capacity
        for stream_id, _entry in disconnected[:overflow]:
            del self._entries[stream_id]


def _now_millis() -> float:
    return time.monotonic() * 1000.0


__all__ = ["SessionRegistry"]

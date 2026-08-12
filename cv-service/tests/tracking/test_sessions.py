"""`cv_service.tracking.sessions` -- the `stream_id`-keyed session pool.

Pure stdlib: a fake clock (monkeypatching the SAME `time.monotonic` this
module calls), a trivial session factory. No frames, no cv2, no gRPC --
`StreamTrackingSession` itself is constructed with `registry_provider=lambda:
None`, so no engine is ever built here either.
"""

from __future__ import annotations

import threading

import pytest

from cv_service.config import Settings
from cv_service.tracking import sessions as sessions_module
from cv_service.tracking.sessions import SessionRegistry
from cv_service.tracking.session import StreamTrackingSession


class FakeClock:
    def __init__(self) -> None:
        self.seconds = 0.0

    def __call__(self) -> float:
        return self.seconds


@pytest.fixture
def clock(monkeypatch):
    fake = FakeClock()
    monkeypatch.setattr(sessions_module.time, "monotonic", fake)
    return fake


def new_session() -> StreamTrackingSession:
    return StreamTrackingSession(settings=Settings(), registry_provider=lambda: None)


def pool(*, grace_millis=1000, capacity=8, factory=new_session) -> SessionRegistry:
    return SessionRegistry(grace_millis=grace_millis, capacity=capacity, session_factory=factory)


# -- acquire: new streams -----------------------------------------------------


def test_acquire_for_a_new_stream_id_builds_a_fresh_session(clock):
    registry = pool()

    session = registry.acquire("stream-1")

    assert isinstance(session, StreamTrackingSession)
    assert registry.size() == 1


def test_two_distinct_stream_ids_get_two_distinct_sessions(clock):
    registry = pool()

    left = registry.acquire("left")
    right = registry.acquire("right")

    assert left is not right
    assert registry.size() == 2


def test_a_blank_stream_id_never_pools(clock):
    registry = pool()

    first = registry.acquire("")
    second = registry.acquire("")

    assert first is not second
    assert registry.size() == 0


# -- release / reconnect ------------------------------------------------------


def test_release_then_acquire_within_grace_resumes_the_same_session(clock):
    registry = pool(grace_millis=1000)
    first = registry.acquire("stream-1")

    registry.release("stream-1", first)
    clock.seconds = 0.5  # 500ms, well inside the 1000ms grace window
    second = registry.acquire("stream-1")

    assert second is first
    assert registry.size() == 1


def test_release_then_acquire_after_grace_builds_a_fresh_session(clock):
    registry = pool(grace_millis=1000)
    first = registry.acquire("stream-1")

    registry.release("stream-1", first)
    clock.seconds = 2.0  # 2000ms, past the 1000ms grace window
    second = registry.acquire("stream-1")

    assert second is not first


def test_release_resets_the_sessions_engines_but_not_its_own_identity(clock):
    """Cheap proxy for `StreamTrackingSession.reset_for_reconnect` actually
    being called on release -- the thorough per-field proof lives in
    `tests/tracking/test_session.py`, this only pins that THIS registry
    calls it, once, at release time."""
    calls = []

    class TrackingSession(StreamTrackingSession):
        def reset_for_reconnect(self) -> None:
            calls.append(1)
            super().reset_for_reconnect()

    session = TrackingSession(settings=Settings(), registry_provider=lambda: None)
    registry = pool(factory=lambda: session)
    acquired = registry.acquire("stream-1")

    assert calls == []  # not reset while still connected
    registry.release("stream-1", acquired)
    assert calls == [1]


def test_a_second_concurrent_acquire_for_the_same_stream_id_is_independent(clock):
    registry = pool()
    first = registry.acquire("stream-1")

    # `first` is still `in_use` -- never released -- so this is a second
    # LIVE call for the same stream_id, not a reconnect.
    second = registry.acquire("stream-1")

    assert second is not first
    assert registry.size() == 1  # the concurrent caller was never pooled


def test_release_is_a_noop_for_a_session_that_is_not_the_current_entry(clock):
    registry = pool()
    live = registry.acquire("stream-1")
    stranger = new_session()

    # A stale/foreign session releasing under someone else's stream_id must
    # never clobber the live entry.
    registry.release("stream-1", stranger)

    # Proven by releasing the REAL entry afterward and confirming it still
    # resumes as the SAME object -- if the stranger's release had touched
    # it, this would either raise or hand back a fresh session instead.
    registry.release("stream-1", live)
    clock.seconds = 0.1
    assert registry.acquire("stream-1") is live


def test_releasing_an_already_evicted_stream_id_is_a_noop(clock):
    registry = pool(grace_millis=0)
    session = registry.acquire("stream-1")
    registry.release("stream-1", session)
    clock.seconds = 1.0  # evicts it (grace_millis=0)
    registry.acquire("other")  # sweeps the expired entry via _evict_expired

    registry.release("stream-1", session)  # must not raise or resurrect it

    assert registry.size() == 1  # only "other"


# -- bounded: grace eviction + capacity ---------------------------------------


def test_a_disconnected_stream_that_never_reconnects_is_evicted(clock):
    registry = pool(grace_millis=1000)
    session = registry.acquire("stream-1")
    registry.release("stream-1", session)

    clock.seconds = 2.0
    registry.acquire("stream-2")  # any call sweeps expired entries

    assert registry.size() == 1  # only "stream-2" remains


def test_capacity_evicts_the_oldest_released_entry_first(clock):
    registry = pool(grace_millis=10_000, capacity=2)
    a = registry.acquire("a")
    registry.release("a", a)
    clock.seconds = 1.0
    b = registry.acquire("b")
    registry.release("b", b)
    clock.seconds = 2.0

    registry.acquire("c")  # a third entry: over capacity, "a" is oldest-released

    assert registry.size() == 2
    fresh_a = registry.acquire("a")
    assert fresh_a is not a  # "a" was evicted, not resumed


def test_capacity_never_evicts_an_in_use_entry(clock):
    registry = pool(grace_millis=10_000, capacity=1)
    live = registry.acquire("live")  # in_use throughout the churn below

    # Two more DISCONNECTED entries pile up past capacity while "live" stays
    # connected; "live" must survive every capacity sweep regardless.
    for stream_id in ("a", "b"):
        session = registry.acquire(stream_id)
        registry.release(stream_id, session)

    # Proven by releasing "live" only now and confirming it still resumes as
    # the SAME object -- if capacity enforcement had evicted it while it was
    # in_use, this would hand back a freshly-built session instead.
    registry.release("live", live)
    clock.seconds = 0.1
    assert registry.acquire("live") is live


# -- thread safety -------------------------------------------------------------


def test_concurrent_acquire_release_across_many_stream_ids_never_corrupts_the_pool(clock):
    """Not a proof of absence of races, but real concurrent pressure across
    many threads and many distinct stream_ids -- the registry's own
    docstring claims this is safe by construction; this is the check that
    it does not simply crash or lose bookkeeping under real contention."""
    registry = pool(grace_millis=10_000, capacity=1000)
    errors: list[BaseException] = []

    def worker(stream_id: str) -> None:
        try:
            for _ in range(50):
                session = registry.acquire(stream_id)
                registry.release(stream_id, session)
        except BaseException as exc:  # noqa: BLE001 - capture, don't crash the thread silently
            errors.append(exc)

    threads = [
        threading.Thread(target=worker, args=(f"stream-{i}",)) for i in range(16)
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert registry.size() == 16


# -- what a resumed session is actually FOR ---------------------------------
#
# Pooling the session object is the mechanism, not the outcome. These run the
# real ASSOCIATE path end to end, because the defect they guard against was
# invisible to every unit test: the session was pooled and resumed perfectly
# and the operator still lost every number, because the reconnect bumped the
# book's key epoch and orphaned the very tracks the pooling had preserved.


def _real_session() -> StreamTrackingSession:
    from cv_service.tracking.registry import build_default_registry

    settings = Settings()
    registry = build_default_registry(settings, probe=True)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    session.apply_config(_associate_request(), wire_token=object())
    return session


def _associate_request():
    from cv_service.tracking.params import MODE_ASSOCIATE, TrackingRequest

    return TrackingRequest(mode=MODE_ASSOCIATE)


class _Detection:
    label = "car"
    confidence = 0.9

    def __init__(self, x: float) -> None:
        self.x = x
        self.y = 0.5
        self.width = 0.1
        self.height = 0.1


def _advance(session: StreamTrackingSession, start: int, count: int, xs) -> list[int]:
    import numpy as np

    frame = np.zeros((240, 320, 3), dtype=np.uint8)
    outcome = None
    for index in range(start, start + count):
        outcome = session.process(
            now_millis=index * 100.0,
            detect=lambda roi=None: ([_Detection(x) for x in xs], 0),
            frame=lambda: frame,
        )
    return sorted(box.track.track_id for box in (outcome.boxes or []) if box.track)


def test_a_reconnect_returns_the_same_objects_under_the_same_ids():
    session = _real_session()
    assert _advance(session, 0, 6, [0.20, 0.60]) == [1, 2]

    session.reset_for_reconnect()

    # Same two objects, a hair further along -- they must come back as
    # themselves, not as #3 and #4. "The counter did not restart at 1" is a
    # weaker property that this used to satisfy while still losing the
    # operator's numbers.
    assert _advance(session, 6, 6, [0.21, 0.61]) == [1, 2]


def test_a_reconnect_does_not_freeze_the_counter_for_genuinely_new_objects():
    # The other half: preserving ids must not be achieved by refusing to
    # issue any, or a scene that gains an object after a blip would silently
    # hand it someone else's number.
    session = _real_session()
    assert _advance(session, 0, 6, [0.20, 0.60]) == [1, 2]

    session.reset_for_reconnect()

    assert _advance(session, 6, 6, [0.21, 0.61, 0.90]) == [1, 2, 3]

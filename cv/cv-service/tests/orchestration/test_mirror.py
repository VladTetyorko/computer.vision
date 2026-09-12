"""`orchestration/mirror.py` -- the rules the builder itself obeys.

The wire-level behaviour is asserted in `tests/grpc/test_object_state_wire.py`
against real serialized responses. What CANNOT be seen from there is what
this file covers: that describing a frame never changes it, and that an
absent group and a zero field stay different answers.

Pure stdlib -- a hand-built `TrackBook`, a hand-built `FrameLedger`, no
engines, no gRPC, no proto stubs.
"""

from __future__ import annotations

import copy
import dataclasses

from cv_service.orchestration.contract import FrameContext
from cv_service.orchestration.keys import Key
from cv_service.orchestration.ledger import FrameLedger, ObjectEvidence
from cv_service.orchestration.mirror import object_states
from cv_service.tracking.engines.base import SOURCE_DETECTOR, SOURCE_TRACKER, Box
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.memory import MemoryParams, ObjectMemory
from cv_service.tracking.objectstate import (
    EVIDENCE_SOURCE_PREDICTED,
    OBJECT_LIFECYCLE_CONFIRMED,
    OBJECT_LIFECYCLE_DORMANT,
)
from cv_service.tracking.outcome import TrackedBox
from cv_service.tracking.track import STATE_COASTING, STATE_CONFIRMED, Track

from tests.orchestration.params import params

NOW = 100.0


class FakeBook:
    """Just the one thing the mirror reads -- and ordered by id, as
    `TrackBook.tracks` is, so the mirror never has to sort."""

    def __init__(self, *tracks: Track) -> None:
        self.tracks = sorted(tracks, key=lambda track: track.track_id)


def track(track_id: int = 1, *, last_seen: float = NOW, **overrides) -> Track:
    fields = dict(
        track_id=track_id,
        key=f"k{track_id}",
        box=Box(0.4, 0.5, 0.1, 0.1),
        label="car",
        confidence=0.8,
        first_seen=NOW - 5.0,
        last_seen=last_seen,
        last_confirmed=last_seen,
        state=STATE_CONFIRMED,
        source=SOURCE_DETECTOR,
        hits=6,
        misses=0,
        age_frames=6,
        elected_label="car",
    )
    fields.update(overrides)
    return Track(**fields)


def context(**seeded) -> FrameContext:
    ctx = FrameContext(
        stream_id="stream-1",
        sequence=7,
        now_millis=NOW * 1000.0,
        level_served=3,
        params=params("associate"),
        lag_millis=0,
    )
    for key, value in seeded.items():
        ctx.seed(Key[key], value)
    return ctx


def ledger() -> FrameLedger:
    return FrameLedger(stream_id="stream-1", sequence=7, captured_at_millis=NOW * 1000.0)


def build(book, *, memory=None, lock=None, ctx=None, book_ledger=None, boxes=()):
    return object_states(
        book=book,
        memory=memory,
        lock=lock if lock is not None else LockArbiter(),
        ctx=ctx if ctx is not None else context(),
        ledger=book_ledger if book_ledger is not None else ledger(),
        boxes=boxes,
    )


# --- describing a frame must not change it -----------------------------------


def test_the_mirror_mutates_nothing_it_reads():
    # The invariant the whole design rests on: a describe-step that changed
    # what it describes would make the ledger a record of itself. Compared by
    # deep copy rather than by spot-checking fields, so a future field nobody
    # thought to assert on is covered the day it is added.
    subject = track()
    book = FakeBook(subject)
    memory = ObjectMemory(MemoryParams())
    memory.remember(
        track_id=9,
        label="person",
        box=Box(0.1, 0.1, 0.05, 0.05),
        velocity=(0.0, 0.0),
        descriptor=None,
        now_millis=(NOW - 2.0) * 1000.0,
    )
    entries = ledger()
    entries.claim(1, ObjectEvidence("assoc.cost", {"matched_det": "0"}))

    before_track = _snapshot(subject)
    before_ledger = copy.deepcopy(entries)
    before_gallery = copy.deepcopy(memory.identities())

    build(book, memory=memory, book_ledger=entries)

    assert _snapshot(subject) == before_track
    assert entries == before_ledger
    assert memory.identities() == before_gallery


def _snapshot(subject: Track) -> "dict[str, object]":
    """Every `Track` field except `history`.

    `ObservationRing` is a plain object with no `__eq__`, so two deep copies
    of one ring never compare equal; every other field is comparable, and
    `history` has its own mutation tests in `tests/tracking/test_history.py`.
    Enumerated from `dataclasses.fields` rather than listed, so a field added
    to `Track` tomorrow is covered without editing this test.
    """
    return {
        field.name: copy.deepcopy(getattr(subject, field.name))
        for field in dataclasses.fields(subject)
        if field.name != "history"
    }


def test_the_same_frame_described_twice_gives_the_same_answer():
    book = FakeBook(track())
    entries = ledger()

    assert build(book, book_ledger=entries) == build(book, book_ledger=entries)


# --- absent group vs. zero field ---------------------------------------------


def test_no_gallery_means_an_absent_memory_group_not_a_zeroed_one():
    # "Nobody could be asked" and "asked, and did not recover" are different
    # facts, and only an absent GROUP can express the first.
    without = build(FakeBook(track()))[0]
    assert without.memory is None

    with_gallery = build(FakeBook(track()), memory=ObjectMemory(MemoryParams()))[0]
    assert with_gallery.memory is not None
    assert with_gallery.memory.recovered is False
    assert with_gallery.memory.match_distance == 1.0


def test_no_lock_ever_applied_means_an_absent_lock_group():
    assert build(FakeBook(track()))[0].lock is None


def test_a_lock_applied_elsewhere_still_reports_this_object_as_unlocked():
    # Once a lock EXISTS on the stream, `locked=False` is a real answer about
    # this object rather than an absence -- so the group must be present.
    lock = LockArbiter()

    class _Request:
        lock_seq = 4
        track_id = 99
        point_x = 0.0
        point_y = 0.0
        release = False
        box = None

    lock.apply(_Request())

    state = build(FakeBook(track()), lock=lock)[0]
    assert state.lock is not None
    assert state.lock.locked is False
    assert state.lock.lock_seq_applied == 4


# --- this-frame facts never carry over ---------------------------------------


def test_an_untouched_track_reports_no_displacement_and_no_reupdate():
    # `Track.reupdated`/`displacement_*` describe the frame that last BOOKED
    # the track. `detections[]` never shows a stale one because an untouched
    # track emits no detection at all; the mirror shows every live track, so
    # it has to zero them itself.
    stale = track(last_seen=NOW - 1.0, state=STATE_COASTING, misses=2)
    stale.reupdated = True
    stale.displacement_x = 0.25
    stale.displacement_y = -0.25

    state = build(FakeBook(stale))[0]

    assert state.kinematics.displacement_x == 0.0
    assert state.kinematics.displacement_y == 0.0
    assert state.provenance.reupdated is False
    assert state.provenance.source == EVIDENCE_SOURCE_PREDICTED


def test_a_touched_track_reports_this_frames_displacement():
    fresh = track()
    fresh.displacement_x = 0.02
    fresh.displacement_y = 0.01

    state = build(FakeBook(fresh))[0]

    assert state.kinematics.displacement_x == 0.02
    assert state.lifecycle == OBJECT_LIFECYCLE_CONFIRMED


def test_the_tracker_box_is_reported_only_on_a_frame_the_tracker_produced():
    # The SOT engine's box IS the settled box on a frame it observed, and
    # nothing at all on a frame it did not.
    followed = track(source=SOURCE_TRACKER)
    assert build(FakeBook(followed))[0].kinematics.tracker_box == followed.box

    detected = track(source=SOURCE_DETECTOR)
    assert build(FakeBook(detected))[0].kinematics.tracker_box is None


# --- ordering and provenance --------------------------------------------------


def test_live_tracks_come_first_then_dormant_identities_each_by_id():
    # Deterministic order frame to frame: a consumer diffing two frames must
    # never see a reordering it has to undo.
    memory = ObjectMemory(MemoryParams())
    for track_id in (9, 4):
        memory.remember(
            track_id=track_id,
            label="person",
            box=Box(0.1, 0.1, 0.05, 0.05),
            velocity=(0.0, 0.0),
            descriptor=None,
            now_millis=(NOW - 2.0) * 1000.0,
        )

    states = build(FakeBook(track(3), track(1)), memory=memory)

    assert [(state.id, state.lifecycle) for state in states] == [
        (1, OBJECT_LIFECYCLE_CONFIRMED),
        (3, OBJECT_LIFECYCLE_CONFIRMED),
        (4, OBJECT_LIFECYCLE_DORMANT),
        (9, OBJECT_LIFECYCLE_DORMANT),
    ]


def test_contributors_are_deduplicated_but_keep_their_run_order():
    entries = ledger()
    entries.claim(1, ObjectEvidence("predict.cv", {"predicted": "0.1,0.2"}))
    entries.claim(1, ObjectEvidence("assoc.cost", {"matched_det": "0"}))
    entries.claim(1, ObjectEvidence("assoc.cost", {"matched_det": "0"}))

    state = build(FakeBook(track()), book_ledger=entries)[0]

    assert state.provenance.contributors == ("predict.cv", "assoc.cost")


def test_confidence_raw_comes_from_this_frames_box_not_from_the_track():
    # `Track.confidence` survives from whichever frame last touched the
    # track; a track with no box this frame observed nothing, and `0.0` says
    # so where a carried-over number would not.
    subject = track(confidence=0.8)
    observed = build(
        FakeBook(subject), boxes=(TrackedBox("car", 0.55, subject.box, subject),)
    )[0]
    assert observed.belief.confidence_raw == 0.55

    assert build(FakeBook(subject))[0].belief.confidence_raw == 0.0

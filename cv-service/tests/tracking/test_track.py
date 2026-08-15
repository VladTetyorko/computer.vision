"""`cv_service.tracking.track` -- the lifecycle machine.

Pure stdlib: a fake clock, plain `Observation`s, no frames and no OpenCV.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.assign import AssignGates, AssignWeights
from cv_service.tracking.engines.base import (
    METRIC_HELLINGER,
    SOURCE_DETECTOR,
    SOURCE_TRACKER,
    Box,
    Descriptor,
    Observation,
    Transform,
)
from cv_service.tracking.history import _DEFAULT_CAPACITY
from cv_service.tracking.memory import MemoryParams, ObjectMemory
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingParams
from cv_service.tracking.track import (
    STATE_COASTING,
    STATE_CONFIRMED,
    STATE_LOST,
    STATE_TENTATIVE,
    RecoveredIdentity,
    TrackBook,
    observe_descriptor,
)


def params(**overrides) -> TrackingParams:
    base = dict(
        mode=MODE_ASSOCIATE,
        engine_id="e",
        verify_every_millis=2000,
        reacquire_every_millis=250,
        redetect_iou_threshold=0.3,
        max_age_frames=3,
        min_hits=3,
        # Effectively disabled by default -- this file's fake clock reuses
        # the frame index as "seconds elapsed" purely for test convenience
        # (up to ~30 "seconds" in some tests below), which is not meant to
        # exercise the wall-clock LOST rule. Tests that DO exercise it
        # override this explicitly with a small value.
        track_max_age_millis=1_000_000,
        min_tracker_confidence=0.5,
        motion_engine_id="",
        # TRACKING-V2-PLAN wave C3 -- inert here, `TrackBook` reads none of
        # these (`assign.py`'s own `CostAssociator` does); included only
        # because `TrackingParams` requires them.
        appearance_engine_id="",
        cost_weights=AssignWeights(),
        cost_gates=AssignGates(),
        # TRACKING-V2-PLAN wave C4 -- also inert here: `TrackBook` never
        # reads `TrackingParams.memory_params` itself (only `session.py`'s
        # `_resolve_memory` does, to build the `ObjectMemory` this file
        # wires in directly via `TrackBook(memory=...)`/`set_memory`).
        memory_params=MemoryParams(),
        # TRACKING-V2-PLAN wave C5b -- also inert here: only `session.py`
        # reads `follow_top_k`; included only because `TrackingParams`
        # requires it.
        follow_top_k=1,
        # TRACKING-V2-PLAN wave C5c -- also inert here: only `session.py`'s
        # `_roi_rescue` reads either; included only because `TrackingParams`
        # requires them.
        roi_enabled=False,
        roi_crop_factor=4.0,
        roi_min_iou=0.2,
        # TRACKING-V3-PLAN wave V1 -- also inert here: only `session.py`'s
        # `_resolve_capability_level` reads it; included only because
        # `TrackingParams` requires it. `0` = auto-probe, the sentinel's own
        # legitimate resolved value (see that field's own docstring).
        capability_level=0,
        # TRACKING-V3-PLAN wave V3 -- ORU's gap ceiling. A generous default
        # here (not `0`/disabled) so ORU tests below opt IN by building a
        # real gap, rather than every test in this file having to remember
        # to set it; `test_track.py`'s own ORU section overrides this
        # explicitly where the exact ceiling matters.
        reupdate_max_gap_millis=60_000,
        # TRACKING-V3-PLAN wave V6 -- also inert here: `TrackBook`/`_observe`
        # never read it (only `session.py`'s `_late_corrected_box` does,
        # entirely outside this module); included only because
        # `TrackingParams` requires it.
        detection_lag_correction_enabled=True,
        # 2026-08-14 repair, part 2 -- the ORU plausibility guard's bound.
        # `0.0` (disabled) by default here, same "opt in explicitly" shape
        # `reupdate_max_gap_millis`'s own comment above describes for THAT
        # field's non-default choice: unlike the gap ceiling, a disabled
        # velocity guard is the SAFER default for this file's tests (it
        # never turns a legitimate reconstruction this file already asserts
        # on into an unexpected `None`), so this file's own ORU-plausibility
        # tests override it explicitly where the bound itself matters.
        reupdate_max_velocity_per_second=0.0,
    )
    base.update(overrides)
    return TrackingParams(**base)


def seen(key, x=0.1, y=0.1, source=SOURCE_DETECTOR, **kwargs) -> Observation:
    return Observation(
        key=key,
        box=Box(x, y, 0.1, 0.1),
        label="car",
        confidence=0.9,
        source=source,
        **kwargs,
    )


def test_ids_start_at_one_and_are_allocated_per_book():
    book = TrackBook(params())

    first = book.apply([seen("a"), seen("b", x=0.5)], 0.0, detector_ran=True)

    assert [track.track_id for track in first] == [1, 2]


def test_two_books_never_collide_or_leak_ids():
    left, right = TrackBook(params()), TrackBook(params())

    left_tracks = left.apply([seen("engine-key-1")], 0.0, detector_ran=True)
    right_tracks = right.apply([seen("engine-key-1")], 0.0, detector_ran=True)

    # Same engine key, two streams: each book allocates from its own counter
    # and neither can see the other's track.
    assert left_tracks[0].track_id == right_tracks[0].track_id == 1
    assert left.get(1) is not right.get(1)
    assert [t.track_id for t in left.tracks] == [1]
    assert [t.track_id for t in right.tracks] == [1]


def test_a_one_frame_flicker_never_becomes_a_confirmed_id():
    book = TrackBook(params(min_hits=3))

    born = book.apply([seen("flicker")], 0.0, detector_ran=True)[0]
    assert born.state == STATE_TENTATIVE

    # It never comes back.
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    assert all(track.state != STATE_CONFIRMED for track in book.tracks)


def test_tentative_becomes_confirmed_only_at_min_hits():
    book = TrackBook(params(min_hits=3))

    states = [
        book.apply([seen("car")], float(frame), detector_ran=True)[0].state for frame in range(4)
    ]

    assert states == [STATE_TENTATIVE, STATE_TENTATIVE, STATE_CONFIRMED, STATE_CONFIRMED]


def test_an_authoritative_observation_skips_the_min_hits_gate():
    book = TrackBook(params(min_hits=3))

    born = book.apply([seen("locked", authoritative=True)], 0.0, detector_ran=True)[0]

    assert born.state == STATE_CONFIRMED


def test_a_short_occlusion_recovers_the_same_id():
    book = TrackBook(params(min_hits=1, max_age_frames=5))

    born = book.apply([seen("car")], 0.0, detector_ran=True)[0]
    assert born.state == STATE_CONFIRMED

    for frame in range(1, 5):  # occluded for fewer than max_age_frames
        book.apply([], float(frame), detector_ran=True)
        assert book.get(born.track_id).state == STATE_COASTING

    recovered = book.apply([seen("car")], 5.0, detector_ran=True)[0]

    assert recovered.track_id == born.track_id
    assert recovered.state == STATE_CONFIRMED


def test_a_long_occlusion_goes_lost_then_retires_the_id():
    book = TrackBook(params(min_hits=1, max_age_frames=2))

    born = book.apply([seen("car")], 0.0, detector_ran=True)[0]

    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)
    assert book.get(born.track_id).state == STATE_LOST

    for frame in range(4, 10):
        book.apply([], float(frame), detector_ran=True)
    assert book.get(born.track_id) is None

    # The retired id is never re-issued.
    reborn = book.apply([seen("car")], 10.0, detector_ran=True)[0]
    assert reborn.track_id != born.track_id


# -- wall-clock ageing (review finding B7) -----------------------------------


def test_wall_clock_declares_lost_even_while_misses_stays_at_zero():
    # FOLLOW's own bug: `misses` only advances on a verify pass that ran and
    # failed, so a long real-world gap with NO verify pass at all (nothing
    # but tracker-only touches) could coast forever under the frame-based
    # rule alone. `track_max_age_millis` is the wall-clock backstop.
    book = TrackBook(params(min_hits=1, max_age_frames=1000, track_max_age_millis=500))
    book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)

    still_fresh = book.apply([seen("car", source=SOURCE_TRACKER)], 0.1, detector_ran=False)[0]
    assert still_fresh.state == STATE_COASTING
    assert still_fresh.misses == 0

    stale = book.apply([seen("car", source=SOURCE_TRACKER)], 1.0, detector_ran=False)[0]

    assert stale.state == STATE_LOST
    assert stale.misses == 0  # the frame-based rule never had a chance to fire


def test_the_frame_based_rule_still_fires_independently_of_the_wall_clock():
    # The converse: a tight cadence can exhaust `max_age_frames` in well
    # under `track_max_age_millis` -- ByteTrack's own `track_buffer`
    # contract is unaffected by the new wall-clock rule.
    book = TrackBook(params(min_hits=1, max_age_frames=2, track_max_age_millis=1_000_000))
    book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)

    for frame in range(1, 4):
        book.apply([], float(frame) * 0.01, detector_ran=True)

    assert book.get(1).state == STATE_LOST


def test_a_tracker_only_frame_never_counts_as_a_miss():
    # This is what makes "FOLLOW runs the detector at the configured cadence
    # and no more" true: a duty-cycled frame did not ask the detector
    # anything, so the detector cannot have failed to find the target.
    book = TrackBook(params(min_hits=1, max_age_frames=2))
    born = book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)[0]

    for frame in range(1, 30):
        track = book.apply(
            [seen("car", source=SOURCE_TRACKER)], float(frame), detector_ran=False
        )[0]

    assert track.track_id == born.track_id
    assert track.misses == 0
    assert track.state == STATE_COASTING


def test_a_verify_pass_that_does_not_re_anchor_counts_as_a_miss():
    book = TrackBook(params(min_hits=1, max_age_frames=2))
    book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)

    track = book.apply([seen("car", source=SOURCE_TRACKER)], 1.0, detector_ran=True)[0]

    assert track.misses == 1
    assert track.state == STATE_COASTING


def test_age_frames_starts_at_zero_and_advances_every_frame():
    book = TrackBook(params(min_hits=1))

    ages = [book.apply([seen("car")], float(f), detector_ran=True)[0].age_frames for f in range(4)]

    assert ages == [0, 1, 2, 3]


def test_velocity_is_normalized_units_per_second():
    book = TrackBook(params(min_hits=1))
    book.apply([seen("car", x=0.10, y=0.20)], 0.0, detector_ran=True)

    track = book.apply([seen("car", x=0.30, y=0.10)], 2.0, detector_ran=True)[0]

    assert track.velocity_x == pytest.approx(0.1)
    assert track.velocity_y == pytest.approx(-0.05)


def test_forget_keys_retires_tracks_without_re_issuing_ids():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("engine-1")], 0.0, detector_ran=True)[0]

    book.forget_keys()
    reborn = book.apply([seen("engine-1")], 1.0, detector_ran=True)[0]

    assert book.get(born.track_id) is None
    assert reborn.track_id > born.track_id


# -- key epoch (review findings D1/D2) ---------------------------------------


def test_bump_epoch_retires_no_track():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("engine-1"), seen("engine-2", x=0.5)], 0.0, detector_ran=True)

    book.bump_epoch()

    # Unlike `forget_keys`, nothing was retired -- both tracks are exactly
    # as they were, same ids, still live.
    assert [t.track_id for t in book.tracks] == [t.track_id for t in born]
    assert book.get(born[0].track_id) is not None
    assert book.get(born[1].track_id) is not None


def test_bump_epoch_stops_a_re_issued_engine_key_from_resurrecting_the_old_track():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("engine-1")], 0.0, detector_ran=True)[0]

    book.bump_epoch()
    # The SAME raw engine key, re-issued by a just-restarted engine (its own
    # numbering starts over from the beginning) -- must NOT attach to the
    # old track.
    reborn = book.apply([seen("engine-1")], 1.0, detector_ran=True)[0]

    assert reborn.track_id != born.track_id
    assert book.get(born.track_id) is not None  # the old one still lives too


# -- ego-motion warp (TRACKING-V2-PLAN wave C2) ------------------------------


def test_warp_moves_every_live_tracks_box():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a", x=0.10), seen("b", x=0.50)], 0.0, detector_ran=True)
    shift = Transform(c=0.01)  # +0.01 to x, everything else identity

    book.warp(shift)

    assert book.get(born[0].track_id).box.x == pytest.approx(0.11)
    assert book.get(born[1].track_id).box.x == pytest.approx(0.51)


def test_n_consecutive_identical_warps_move_a_track_by_n_steps_not_one():
    # THE accumulation invariant the coordinator's fix exists for: a track
    # nobody reads for N frames must still pick up N single-frame warps
    # (matching N frames of real camera motion), not the ONE frame's worth
    # a call site would see if warping only happened at read time. This is
    # the direct, book-level proof; `tests/tracking/test_session.py` proves
    # the same thing end to end through a stalled FOLLOW session.
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a", x=0.10)], 0.0, detector_ran=True)[0]
    step = Transform(c=0.01)

    for _ in range(30):
        book.warp(step)

    assert book.get(born.track_id).box.x == pytest.approx(0.10 + 30 * 0.01)


def test_warp_is_an_exact_no_op_for_identity():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a", x=0.10, y=0.20)], 0.0, detector_ran=True)[0]
    original_box = born.box

    book.warp(Transform())  # IDENTITY

    assert book.get(born.track_id).box == original_box


def test_warp_moves_velocity_by_the_linear_part_only():
    # A rate has no position to translate -- `c`/`f` must NOT leak into it,
    # only the rotation/scale part (`a`, `b`, `d`, `e`).
    book = TrackBook(params(min_hits=1))
    book.apply([seen("a", x=0.10, y=0.10)], 0.0, detector_ran=True)
    moved = book.apply([seen("a", x=0.20, y=0.10)], 1.0, detector_ran=True)[0]
    assert moved.velocity_x == pytest.approx(0.1)
    assert moved.velocity_y == pytest.approx(0.0)

    # A pure translation: velocity is unaffected (it already excludes c/f).
    book.warp(Transform(c=0.5, f=0.5))
    assert book.get(moved.track_id).velocity_x == pytest.approx(0.1)
    assert book.get(moved.track_id).velocity_y == pytest.approx(0.0)

    # A pure 2x scale (a=e=2, b=d=0, no translation): the rate scales too.
    book.warp(Transform(a=2.0, e=2.0))
    assert book.get(moved.track_id).velocity_x == pytest.approx(0.2)
    assert book.get(moved.track_id).velocity_y == pytest.approx(0.0)


def test_warp_never_touches_a_track_born_after_it():
    # Ordering sanity: `warp()` only ever sees the tracks that exist AT THE
    # TIME it is called -- a track born later in the same frame (e.g. a
    # fresh re-anchor) is not retroactively shifted by a warp that already
    # ran before it existed.
    book = TrackBook(params(min_hits=1))

    book.warp(Transform(c=0.5))  # nothing alive yet -- must not raise

    born = book.apply([seen("a", x=0.10)], 0.0, detector_ran=True)[0]
    assert born.box.x == pytest.approx(0.10)


# -- descriptor (TRACKING-V2-PLAN wave C3) -----------------------------------

RED = Descriptor("hist", (1.0, 0.0, 0.0), METRIC_HELLINGER)
BLUE = Descriptor("hist", (0.0, 0.0, 1.0), METRIC_HELLINGER)


def test_a_fresh_track_has_no_descriptor():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a")], 0.0, detector_ran=True)[0]

    assert born.descriptor is None


def test_observe_descriptor_sets_the_first_signature_outright():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a")], 0.0, detector_ran=True)[0]

    observe_descriptor(born, RED)

    assert born.descriptor == RED


def test_observe_descriptor_is_a_no_op_for_none():
    # A box the extractor could not describe must not erase an already
    # accumulated signature on the strength of one missing frame.
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a")], 0.0, detector_ran=True)[0]
    observe_descriptor(born, RED)

    observe_descriptor(born, None)

    assert born.descriptor == RED


def test_observe_descriptor_blends_rather_than_replaces():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a")], 0.0, detector_ran=True)[0]
    observe_descriptor(born, RED)

    observe_descriptor(born, BLUE, alpha=0.5)

    # Halfway between RED and BLUE, renormalized (Descriptor.blend's own
    # contract) -- not a straight replacement.
    assert born.descriptor.values == pytest.approx((0.5, 0.0, 0.5))


# -- object memory wiring (TRACKING-V2-PLAN wave C4) -------------------------


def test_a_track_is_never_remembered_when_no_memory_is_configured():
    # The default -- `TrackBook(params())` with no `memory=` -- must stay a
    # genuine no-op all the way through expiry (P5): no gallery exists, so
    # nothing is even attempted.
    book = TrackBook(params(min_hits=1, max_age_frames=1))
    book.apply([seen("car")], 0.0, detector_ran=True)

    for frame in range(1, 5):
        book.apply([], float(frame), detector_ran=True)  # raises nothing

    assert book.tracks == []


def test_a_track_is_remembered_the_moment_it_is_actually_expired():
    # `occlusion`'s own retention already recovers a SHORT gap without any
    # gallery involved -- this proves the OTHER half: a track that survives
    # long enough to hit `_expire()` is handed to memory first, not simply
    # dropped. `max_age_frames=1` -> the book retires past 2 consecutive
    # misses (`_LOST_RETENTION_MULTIPLIER`).
    memory = ObjectMemory(MemoryParams())
    book = TrackBook(params(min_hits=1, max_age_frames=1), memory=memory)
    born = book.apply([seen("car", x=0.3, y=0.4)], 0.0, detector_ran=True)[0]

    assert memory.size() == 0  # not yet -- still live/coasting/LOST

    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    assert book.get(born.track_id) is None  # gone from the live book...
    assert memory.size() == 1  # ...but remembered, not simply discarded
    remembered = memory.identities()[0]
    assert remembered.track_id == born.track_id
    assert remembered.box.x == pytest.approx(0.3)
    assert remembered.label == "car"


def test_set_memory_swaps_the_gallery_a_book_hands_lost_tracks_to():
    first, second = ObjectMemory(MemoryParams()), ObjectMemory(MemoryParams())
    book = TrackBook(params(min_hits=1, max_age_frames=1), memory=first)
    book.set_memory(second)
    born = book.apply([seen("car")], 0.0, detector_ran=True)[0]

    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    assert first.size() == 0
    assert second.size() == 1
    assert second.identities()[0].track_id == born.track_id


def test_apply_books_a_recovery_under_the_remembered_id_and_skips_min_hits():
    # The counterpart to `test_an_authoritative_observation_skips_the_min_
    # hits_gate` above -- a recovery earns the same carve-out, for the same
    # reason: it is not a fresh, ambiguous detection.
    book = TrackBook(params(min_hits=5))
    recovery = RecoveredIdentity(
        track_id=42, first_seen=-10.0, descriptor=RED, velocity=(0.1, 0.2)
    )

    recovered = book.apply(
        [seen("x")], 3.0, detector_ran=True, recoveries={"x": recovery}
    )[0]

    assert recovered.track_id == 42
    assert recovered.state == STATE_CONFIRMED
    assert recovered.first_seen == pytest.approx(-10.0)
    assert recovered.descriptor == RED
    assert recovered.velocity_x == pytest.approx(0.1)
    assert recovered.velocity_y == pytest.approx(0.2)


def test_a_recovered_id_is_never_handed_out_again_by_the_counter():
    book = TrackBook(params(min_hits=1))
    recovery = RecoveredIdentity(track_id=100, first_seen=0.0)

    book.apply([seen("x")], 0.0, detector_ran=True, recoveries={"x": recovery})
    fresh = book.apply([seen("y")], 1.0, detector_ran=True)[0]

    assert fresh.track_id > 100


def test_recoveries_are_ignored_for_a_key_that_is_already_a_live_track():
    # `recoveries` only ever applies to a genuinely NEW birth -- an update to
    # an existing track must never be redirected onto a different id.
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("x")], 0.0, detector_ran=True)[0]

    still_born = book.apply(
        [seen("x")],
        1.0,
        detector_ran=True,
        recoveries={"x": RecoveredIdentity(track_id=999, first_seen=0.0)},
    )[0]

    assert still_born.track_id == born.track_id


# -- observation history (TRACKING-V3-PLAN wave V2) --------------------------


def test_a_birth_observation_is_recorded_into_history():
    book = TrackBook(params(min_hits=1))

    born = book.apply([seen("car")], 3.0, detector_ran=True)[0]

    latest = born.history.latest()
    assert latest is not None
    assert latest.timestamp == pytest.approx(3.0)
    assert latest.observation.box == born.box


def test_every_detector_confirmation_grows_history():
    book = TrackBook(params(min_hits=1))
    book.apply([seen("car")], 0.0, detector_ran=True)

    track = book.apply([seen("car", x=0.2)], 1.0, detector_ran=True)[0]

    assert len(track.history) == 2


def test_coasting_never_grows_history():
    # The direct proof this wave's own acceptance criteria call for: a track
    # coasted across many tracker-only frames records nothing new, because
    # none of those observations are `SOURCE_DETECTOR`.
    book = TrackBook(params(min_hits=1, max_age_frames=1000))
    born = book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)[0]
    assert len(born.history) == 1

    for frame in range(1, 50):
        book.apply([seen("car", source=SOURCE_TRACKER)], float(frame), detector_ran=False)

    assert len(book.get(born.track_id).history) == 1


def test_a_verify_pass_that_fails_to_re_anchor_does_not_grow_history():
    # A `SOURCE_TRACKER` observation on a verify frame that ran but did not
    # confirm the track (COASTING, `misses` advances) is still not evidence.
    book = TrackBook(params(min_hits=1, max_age_frames=5))
    born = book.apply([seen("car", authoritative=True)], 0.0, detector_ran=True)[0]

    track = book.apply([seen("car", source=SOURCE_TRACKER)], 1.0, detector_ran=True)[0]

    assert track.state == STATE_COASTING
    assert len(track.history) == 1
    assert track.history.latest().timestamp == pytest.approx(0.0)


def test_history_is_bounded_even_across_many_confirmations():
    book = TrackBook(params(min_hits=1))
    for frame in range(_DEFAULT_CAPACITY + 20):
        track = book.apply([seen("car")], float(frame), detector_ran=True)[0]

    assert len(track.history) == _DEFAULT_CAPACITY
    # ...and it kept the newest, not an arbitrary/oldest subset.
    assert track.history.latest().timestamp == pytest.approx(float(_DEFAULT_CAPACITY + 19))


def test_each_track_gets_its_own_history_ring_not_a_shared_one():
    # Aliasing guard: a `default_factory` gives every `Track` a FRESH ring --
    # if it were a shared mutable default, recording into one track's history
    # would silently leak into every other track's.
    book = TrackBook(params(min_hits=1))
    a, b = book.apply([seen("a", x=0.1), seen("b", x=0.5)], 0.0, detector_ran=True)

    assert a.history is not b.history
    assert len(a.history) == 1
    assert len(b.history) == 1


def test_a_recovered_track_starts_with_only_the_recovery_observation():
    # `RecoveredIdentity` carries no observation history of its own (`Object
    # Memory` never kept one) -- a recovered track's ring starts fresh with
    # this one entry, not backfilled from before the object went dormant.
    book = TrackBook(params(min_hits=5))
    recovery = RecoveredIdentity(track_id=42, first_seen=-10.0)

    recovered = book.apply(
        [seen("x")], 3.0, detector_ran=True, recoveries={"x": recovery}
    )[0]

    assert len(recovered.history) == 1
    assert recovered.history.latest().timestamp == pytest.approx(3.0)


# -- ORU (TRACKING-V3-PLAN wave V3) ------------------------------------------


def test_history_transform_accumulates_across_warps_and_resets_on_a_real_observation():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a", x=0.1)], 0.0, detector_ran=True)[0]
    step = Transform(c=0.01)

    book.warp(step)
    book.warp(step)
    book.warp(step)
    tracked = book.get(born.track_id)
    # Three single-frame warps compose to one 0.03 translation -- the SAME
    # accumulation invariant `warp()`'s own box/velocity tests already prove,
    # extended to `history_transform`.
    assert tracked.history_transform.apply_point(0.0, 0.0) == pytest.approx((0.03, 0.0))

    # A genuinely NEW real observation resets it: the freshest ring entry was
    # just captured in THIS frame, so there is no accumulated motion between
    # it and "now" yet.
    book.apply([seen("a", x=0.5)], 4.0, detector_ran=True)
    assert book.get(born.track_id).history_transform.identity


def test_history_transform_does_not_reset_on_a_coasted_touch():
    # Only a REAL (`SOURCE_DETECTOR`) observation moves the ring's own
    # "latest" -- a coasted/tracker touch must not silently zero out
    # accumulated ego-motion that `reupdate()` still needs to warp the OLD
    # bracket by.
    book = TrackBook(params(min_hits=1, max_age_frames=5))
    born = book.apply([seen("a", x=0.1, authoritative=True)], 0.0, detector_ran=True)[0]
    book.warp(Transform(c=0.01))

    book.apply([seen("a", x=0.11, source=SOURCE_TRACKER)], 1.0, detector_ran=True)

    assert not book.get(born.track_id).history_transform.identity


def test_a_track_born_or_adopted_starts_with_an_identity_transform():
    book = TrackBook(params(min_hits=1))
    born = book.apply([seen("a")], 0.0, detector_ran=True)[0]

    assert born.history_transform.identity

    recovery = RecoveredIdentity(track_id=99, first_seen=-5.0)
    recovered = book.apply([seen("x")], 3.0, detector_ran=True, recoveries={"x": recovery})[0]
    assert recovered.history_transform.identity


def test_a_real_reanchor_after_a_gap_replaces_velocity_with_orus_reconstruction():
    # The end-to-end proof, at the book level: a track re-anchors after
    # several failed verify passes (misses > 0), and the velocity ORU
    # reconstructs -- NOT the estimator's own (here, deliberately absurd)
    # `track.box`-based measurement -- is what survives.
    book = TrackBook(params(min_hits=1, max_age_frames=30))
    book.apply([seen("a", x=0.0, y=0.0, authoritative=True)], 0.0, detector_ran=True)
    # Several failed verify passes: a coasted (SOURCE_TRACKER, not booked as
    # a miss unless detector_ran) touch, then explicit misses via untouched
    # `apply()` calls (the SAME mechanism ASSOCIATE's own gap accrual uses).
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)  # nothing touches "a" -> misses += 1

    reanchored = book.apply([seen("a", x=0.5, y=0.0, authoritative=True)], 5.0, detector_ran=True)[0]

    assert reanchored.reupdated is True
    # (0.5 - 0.0) / (5.0 - 0.0) = 0.1, the TRUE average velocity over the
    # real gap -- not a one-frame measurement against a stale/frozen box.
    assert reanchored.velocity_x == pytest.approx(0.1)


def test_reupdated_is_reset_every_frame_never_stale_from_a_prior_touch():
    book = TrackBook(params(min_hits=1, max_age_frames=30))
    book.apply([seen("a", x=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)
    reanchored = book.apply([seen("a", x=0.5, authoritative=True)], 5.0, detector_ran=True)[0]
    assert reanchored.reupdated is True

    # A normal, ungapped confirmation right afterward must NOT still report
    # last frame's reupdate.
    settled = book.apply([seen("a", x=0.51, authoritative=True)], 5.1, detector_ran=True)[0]
    assert settled.reupdated is False


def test_reupdate_never_fires_for_a_track_that_has_not_actually_gapped():
    # `misses == 0` at the moment of confirmation (continuously matched,
    # nothing to reconstruct) must take the ordinary measured-and-blended
    # path, not ORU's -- ORU firing unconditionally would silently discard
    # the EMA smoothing every other confirmation relies on.
    book = TrackBook(params(min_hits=1))
    book.apply([seen("a", x=0.0)], 0.0, detector_ran=True)

    touched = book.apply([seen("a", x=0.1)], 1.0, detector_ran=True)[0]

    assert touched.reupdated is False


def test_a_gap_older_than_the_ceiling_falls_back_to_the_ordinary_measurement():
    book = TrackBook(params(min_hits=1, max_age_frames=30, reupdate_max_gap_millis=2_000))
    book.apply([seen("a", x=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    # The gap (5s) exceeds the 2s ceiling -- ORU must not fire, even though
    # `misses > 0`.
    reanchored = book.apply([seen("a", x=0.5, authoritative=True)], 5.0, detector_ran=True)[0]

    assert reanchored.reupdated is False


def test_an_implausible_reconstructed_velocity_falls_back_to_the_ordinary_measurement():
    # The book-level proof of the 2026-08-14 repair, part 2 guard
    # (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4): a real bracket
    # exists and the gap is well inside the ceiling, but the implied
    # velocity (15.0/s) is absurd against the 2.0/s bound given here -- the
    # SAME "fall back to the ordinary measured-and-blended path" outcome
    # `test_a_gap_older_than_the_ceiling_falls_back_to_the_ordinary_
    # measurement` above proves for a too-long gap, now proven for a
    # too-fast one: `reupdated` stays `False`, exactly as an un-reupdated
    # track (acceptance #1).
    book = TrackBook(
        params(min_hits=1, max_age_frames=30, reupdate_max_velocity_per_second=2.0)
    )
    book.apply([seen("a", x=0.0, y=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    # (15.0 - 0.0) / (5.0 - 0.0) = 3.0/s, over the 2.0/s bound.
    reanchored = book.apply([seen("a", x=15.0, y=0.0, authoritative=True)], 5.0, detector_ran=True)[0]

    assert reanchored.reupdated is False


def test_a_disabled_velocity_bound_reproduces_the_reconstruction_exactly():
    # invariant P7 at the book level: the SAME absurd bracket the test above
    # refuses must still be reconstructed by ORU when the bound is left at
    # its disabled default (`0.0`, this file's own `params()` default) --
    # reproducing today's behaviour exactly.
    book = TrackBook(params(min_hits=1, max_age_frames=30))
    book.apply([seen("a", x=0.0, y=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    reanchored = book.apply([seen("a", x=15.0, y=0.0, authoritative=True)], 5.0, detector_ran=True)[0]

    assert reanchored.reupdated is True
    assert reanchored.velocity_x == pytest.approx(3.0)  # (15.0 - 0.0) / (5.0 - 0.0)


def test_reupdate_stats_reset_and_accumulate_across_one_apply_call():
    book = TrackBook(params(min_hits=1, max_age_frames=30))
    book.apply([seen("a", x=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)

    book.reset_reupdate_stats()
    assert book.last_reupdate_millis == 0
    assert book.last_reupdated_tracks == 0

    book.apply([seen("a", x=0.5, authoritative=True)], 5.0, detector_ran=True)

    assert book.last_reupdated_tracks == 1
    assert book.last_reupdate_millis >= 0  # a real, if tiny, perf_counter measurement


def test_reset_reupdate_stats_is_what_a_frame_with_no_apply_call_must_use():
    # `session.py`'s own contract (`reset_reupdate_stats`'s docstring): a
    # frame that never calls `apply()` at all must not report a STALE
    # nonzero value from the last frame that did.
    book = TrackBook(params(min_hits=1, max_age_frames=30))
    book.apply([seen("a", x=0.0, authoritative=True)], 0.0, detector_ran=True)
    for frame in range(1, 4):
        book.apply([], float(frame), detector_ran=True)
    book.apply([seen("a", x=0.5, authoritative=True)], 5.0, detector_ran=True)
    assert book.last_reupdated_tracks == 1  # sanity: a reupdate really happened

    book.reset_reupdate_stats()

    assert book.last_reupdate_millis == 0
    assert book.last_reupdated_tracks == 0

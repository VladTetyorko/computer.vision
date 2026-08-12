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
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingParams
from cv_service.tracking.track import (
    STATE_COASTING,
    STATE_CONFIRMED,
    STATE_LOST,
    STATE_TENTATIVE,
    TrackBook,
    observe_descriptor,
)


def params(**overrides) -> TrackingParams:
    base = dict(
        mode=MODE_ASSOCIATE,
        engine_id="e",
        verify_every_millis=2000,
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

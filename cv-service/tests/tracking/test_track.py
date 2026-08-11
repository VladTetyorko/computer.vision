"""`cv_service.tracking.track` -- the lifecycle machine.

Pure stdlib: a fake clock, plain `Observation`s, no frames and no OpenCV.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import SOURCE_DETECTOR, SOURCE_TRACKER, Box, Observation
from cv_service.tracking.params import MODE_ASSOCIATE, TrackingParams
from cv_service.tracking.track import (
    STATE_COASTING,
    STATE_CONFIRMED,
    STATE_LOST,
    STATE_TENTATIVE,
    TrackBook,
)


def params(**overrides) -> TrackingParams:
    base = dict(
        mode=MODE_ASSOCIATE,
        engine_id="e",
        verify_every_millis=2000,
        redetect_iou_threshold=0.3,
        max_age_frames=3,
        min_hits=3,
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

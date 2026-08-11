"""`cv_service.tracking.lock` -- `lock_seq` monotonicity and target selection."""

from __future__ import annotations

from cv_service.tracking.engines.base import Box
from cv_service.tracking.lock import LockArbiter, best_iou_match, select_by_point
from cv_service.tracking.params import LockRequest


def test_no_lock_is_held_initially():
    arbiter = LockArbiter()

    assert arbiter.has_target is False
    assert arbiter.bound_track_id == 0
    assert arbiter.applied_seq == 0


def test_a_lock_applies_once_and_restating_it_is_a_no_op():
    arbiter = LockArbiter()
    request = LockRequest(lock_seq=4, track_id=7)

    assert arbiter.apply(request) is True
    generation = arbiter.generation

    for _ in range(10):  # the config is restated on EVERY frame
        assert arbiter.apply(request) is False

    assert arbiter.generation == generation
    assert arbiter.target.track_id == 7


def test_a_stale_or_equal_lock_seq_is_rejected():
    arbiter = LockArbiter()
    arbiter.apply(LockRequest(lock_seq=5, track_id=1))

    assert arbiter.apply(LockRequest(lock_seq=5, track_id=2)) is False
    assert arbiter.apply(LockRequest(lock_seq=4, track_id=3)) is False
    assert arbiter.target.track_id == 1

    assert arbiter.apply(LockRequest(lock_seq=6, track_id=9)) is True
    assert arbiter.target.track_id == 9


def test_lock_seq_zero_means_no_lock_has_ever_been_issued():
    arbiter = LockArbiter()

    assert arbiter.apply(LockRequest(lock_seq=0, track_id=3)) is False
    assert arbiter.apply(None) is False
    assert arbiter.has_target is False


def test_release_drops_the_target_and_the_binding():
    arbiter = LockArbiter()
    arbiter.apply(LockRequest(lock_seq=1, track_id=7))
    arbiter.bind(7)

    assert arbiter.apply(LockRequest(lock_seq=2, release=True)) is True

    assert arbiter.has_target is False
    assert arbiter.bound_track_id == 0


def test_the_generation_increments_per_lock_so_a_re_acquire_gets_a_new_id():
    arbiter = LockArbiter()
    arbiter.apply(LockRequest(lock_seq=1, track_id=1))
    first = arbiter.generation
    arbiter.apply(LockRequest(lock_seq=2, release=True))
    arbiter.apply(LockRequest(lock_seq=3, track_id=1))

    assert arbiter.generation > first


def test_unbind_keeps_the_target_so_the_next_pass_re_acquires():
    arbiter = LockArbiter()
    arbiter.apply(LockRequest(lock_seq=1, point_x=0.5, point_y=0.5))
    arbiter.bind(3)

    arbiter.unbind()

    assert arbiter.bound_track_id == 0
    assert arbiter.has_target is True


def test_target_form_precedence_is_box_then_track_id_then_point():
    arbiter = LockArbiter()

    arbiter.apply(LockRequest(lock_seq=1, track_id=2, box=(0.1, 0.2, 0.3, 0.4)))
    assert arbiter.target.box == Box(0.1, 0.2, 0.3, 0.4)

    arbiter.apply(LockRequest(lock_seq=2, track_id=2, point_x=0.9, point_y=0.9))
    assert arbiter.target.track_id == 2 and arbiter.target.point is None

    arbiter.apply(LockRequest(lock_seq=3, point_x=0.9, point_y=0.8))
    assert arbiter.target.point == (0.9, 0.8)


def test_a_click_selects_the_smallest_containing_box():
    building = Box(0.0, 0.0, 1.0, 1.0)
    car = Box(0.4, 0.4, 0.1, 0.1)

    assert select_by_point([building, car], 0.45, 0.45) == 1


def test_a_click_outside_every_box_selects_the_nearest():
    left = Box(0.0, 0.0, 0.1, 0.1)
    right = Box(0.8, 0.8, 0.1, 0.1)

    assert select_by_point([left, right], 0.9, 0.7) == 1


def test_select_by_point_on_an_empty_frame_selects_nothing():
    assert select_by_point([], 0.5, 0.5) == -1


def test_best_iou_match_respects_the_threshold():
    held = Box(0.10, 0.10, 0.10, 0.10)
    near = Box(0.11, 0.11, 0.10, 0.10)
    far = Box(0.80, 0.80, 0.10, 0.10)

    assert best_iou_match(held, [far, near], 0.3) == 1
    assert best_iou_match(held, [far], 0.3) == -1
    assert best_iou_match(held, [], 0.3) == -1


def test_box_validity_rejects_a_collapsed_or_departed_box():
    assert Box(0.1, 0.1, 0.2, 0.2).valid is True
    assert Box(0.1, 0.1, 0.0, 0.2).valid is False
    assert Box(0.1, 0.1, -0.2, 0.2).valid is False
    assert Box(1.4, 0.1, 0.2, 0.2).valid is False
    assert Box(-0.5, 0.1, 0.2, 0.2).valid is False
    # Half out of frame is still trackable, and still worth showing.
    assert Box(-0.05, 0.1, 0.2, 0.2).valid is True

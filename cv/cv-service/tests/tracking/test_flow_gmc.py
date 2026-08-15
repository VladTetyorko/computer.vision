"""`flow` ego-motion compensation: the pixel<->normalized conversion, the
DIRECTION, and every refusal path.

Same reasoning `test_pose_gmc.py`'s module docstring gives for its own
engine: a transform that compensates backwards looks exactly as plausible
as one that compensates forwards in isolation, and it would make
association strictly worse than no compensation at all while every unit
still looked healthy. `test_a_pixel_pan_warps_a_previous_frame_point_to_its_
new_pixel_position` is that test for this engine.

Needs `cv2`/`numpy` -- one of the two engines this package's own invariant
(P3) names as the exception to "no cv2/numpy outside engines/"; skips
rather than fails when the `cv` extra is absent, same contract
`test_engines.py` uses.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import CameraPose

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

from cv_service.tracking.engines.flow_gmc import ENGINE_ID, FlowMotionCompensator, create  # noqa: E402

WIDTH, HEIGHT = 240, 180
CANVAS_SIZE = 400


def _canvas(seed: int = 7) -> "np.ndarray":
    """A large, richly-textured background -- `goodFeaturesToTrack` needs
    real corners, and windowing a SINGLE canvas (rather than re-rolling a
    fresh random frame per call, the way `tests/tracking/test_engines.py`'s
    `scene()` does for an unrelated purpose) is what keeps the background
    content IDENTICAL between two "frames", so any measured motion can only
    be the window sliding, never independently-random pixels."""
    rng = np.random.default_rng(seed)
    return rng.integers(0, 255, (CANVAS_SIZE, CANVAS_SIZE, 3), dtype=np.uint8)


def _window(canvas: "np.ndarray", x0: int, y0: int) -> "np.ndarray":
    return canvas[y0 : y0 + HEIGHT, x0 : x0 + WIDTH]


def _panned(dx: int = 8, dy: int = 5, seed: int = 7):
    """Two frames of the SAME canvas, the second's crop window `(dx, dy)`
    pixels further in -- i.e. every background point shifts by `(-dx, -dy)`
    pixels from frame 1 to frame 2, simulating a camera pan."""
    canvas = _canvas(seed)
    return _window(canvas, 100, 100), _window(canvas, 100 + dx, 100 + dy)


def test_engine_id_matches_the_registry_name():
    assert create().engine_id == ENGINE_ID == "flow"


def test_available_is_always_true_regardless_of_pose():
    engine = create()
    assert engine.available(CameraPose()) is True
    assert engine.available(CameraPose(hfov_degrees=60.0)) is True


def test_the_first_frame_compensates_nothing():
    frame1, _frame2 = _panned()
    assert create().estimate(frame1, CameraPose()).identity


def test_a_missing_frame_compensates_nothing():
    engine = create()
    assert engine.estimate(None, CameraPose()).identity


def test_a_static_scene_is_close_to_identity():
    frame1, _ = _panned()
    engine = create()
    engine.estimate(frame1, CameraPose())

    transform = engine.estimate(frame1, CameraPose())  # the SAME frame again

    x, y = transform.apply_point(0.5, 0.5)
    assert x == pytest.approx(0.5, abs=0.01)
    assert y == pytest.approx(0.5, abs=0.01)


def test_a_pixel_pan_warps_a_previous_frame_point_to_its_new_pixel_position():
    # THE direction + pixel->normalized conversion test: a background point
    # that was at pixel (60, 60) in frame 1 sits at pixel (60 - dx, 60 - dy)
    # in frame 2 (the crop window slid (dx, dy) further into the SAME
    # canvas) -- `Transform` must map the frame-1 point to exactly that
    # frame-2 position, in NORMALIZED [0, 1] units.
    dx, dy = 8, 5
    frame1, frame2 = _panned(dx=dx, dy=dy)
    engine = create()
    engine.estimate(frame1, CameraPose())

    transform = engine.estimate(frame2, CameraPose())

    assert not transform.identity
    px, py = 60.0, 60.0
    expected_x = (px - dx) / WIDTH
    expected_y = (py - dy) / HEIGHT
    warped_x, warped_y = transform.apply_point(px / WIDTH, py / HEIGHT)
    assert warped_x == pytest.approx(expected_x, abs=0.01)
    assert warped_y == pytest.approx(expected_y, abs=0.01)


def test_the_translation_term_matches_pixels_over_width_and_height():
    # The conversion stated in the module docstring, pinned as a number:
    # `c = m02 / width`, `f = m12 / height`. Read directly off `Transform`'s
    # own fields rather than through `apply_point`, so this test would fail
    # even if `apply_point`'s own arithmetic somehow masked a conversion bug.
    dx, dy = 8, 5
    frame1, frame2 = _panned(dx=dx, dy=dy)
    engine = create()
    engine.estimate(frame1, CameraPose())

    transform = engine.estimate(frame2, CameraPose())

    assert transform.c == pytest.approx(-dx / WIDTH, abs=0.01)
    assert transform.f == pytest.approx(-dy / HEIGHT, abs=0.01)
    assert transform.a == pytest.approx(1.0, abs=0.01)
    assert transform.e == pytest.approx(1.0, abs=0.01)


def test_a_larger_pan_produces_a_proportionally_larger_shift():
    small_dx = 4
    large_dx = 16
    canvas = _canvas()
    reference = _window(canvas, 100, 100)

    small_engine = create()
    small_engine.estimate(reference, CameraPose())
    small_transform = small_engine.estimate(_window(canvas, 100 + small_dx, 100), CameraPose())

    large_engine = create()
    large_engine.estimate(reference, CameraPose())
    large_transform = large_engine.estimate(_window(canvas, 100 + large_dx, 100), CameraPose())

    assert abs(large_transform.c) > abs(small_transform.c)


def test_too_few_features_returns_identity():
    # A featureless (flat) frame: `goodFeaturesToTrack` finds nothing to
    # correspond, so there is nothing to fit -- IDENTITY, not a guess.
    flat1 = np.full((HEIGHT, WIDTH, 3), 100, dtype=np.uint8)
    flat2 = np.full((HEIGHT, WIDTH, 3), 100, dtype=np.uint8)
    engine = create()
    engine.estimate(flat1, CameraPose())

    assert engine.estimate(flat2, CameraPose()).identity


_BG_SEED = 11
_FG_SEED = 12


def _split_scene(fg_fraction: float, *, bg_dx: int, fg_dx: int):
    """Two frames: a background panning by `bg_dx` px, and an UNRELATED
    foreground block -- `fg_fraction` of the frame's width, independently
    textured -- panning by a different `fg_dx` px. Isolates exactly one
    variable (how much of the point cloud the foreground claims) so the two
    tests below can bracket the guard's threshold from both sides."""
    bg_canvas = np.random.default_rng(_BG_SEED).integers(0, 255, (CANVAS_SIZE, CANVAS_SIZE, 3), dtype=np.uint8)
    fg_canvas = np.random.default_rng(_FG_SEED).integers(0, 255, (CANVAS_SIZE, CANVAS_SIZE, 3), dtype=np.uint8)
    fg_width = int(WIDTH * fg_fraction)

    def compose(bg_x0, bg_y0, fg_x0, fg_y0):
        frame = _window(bg_canvas, bg_x0, bg_y0).copy()
        frame[0:HEIGHT, 0:fg_width] = fg_canvas[fg_y0 : fg_y0 + HEIGHT, fg_x0 : fg_x0 + fg_width]
        return frame

    frame1 = compose(100, 100, 50, 50)
    frame2 = compose(100 + bg_dx, 100, 50 + fg_dx, 50)
    return frame1, frame2


def test_a_dominant_moving_subject_is_refused_rather_than_trusted():
    # The plan's explicit guard: a foreground object large enough to
    # contribute a comparable share of the point cloud (HALF the frame
    # here), moving DIFFERENTLY from the background, splits RANSAC's inlier
    # vote below the threshold -- IDENTITY is preferred over a confident
    # wrong answer that would warp a track's box AWAY from the real target.
    frame1, frame2 = _split_scene(0.5, bg_dx=4, fg_dx=20)
    engine = create()
    engine.estimate(frame1, CameraPose())

    assert engine.estimate(frame2, CameraPose()).identity


def test_a_minority_moving_subject_does_not_block_compensation():
    # The other side of the same guard: a small foreground object (a FIFTH
    # of the frame) cannot outvote the background, so the background's own
    # motion is still measured correctly despite the object moving
    # differently right next to it.
    bg_dx = 4
    frame1, frame2 = _split_scene(0.2, bg_dx=bg_dx, fg_dx=40)
    engine = create()
    engine.estimate(frame1, CameraPose())

    transform = engine.estimate(frame2, CameraPose())

    assert not transform.identity
    assert transform.c == pytest.approx(-bg_dx / WIDTH, abs=0.01)


def test_reset_drops_the_reference_frame():
    frame1, frame2 = _panned()
    engine = create()
    engine.estimate(frame1, CameraPose())

    engine.reset()

    # With no reference frame left, the very next call is a "first frame"
    # again -- IDENTITY, not a transform measured against stale state.
    assert engine.estimate(frame2, CameraPose()).identity


def test_a_frame_shape_change_is_treated_as_a_first_frame():
    frame1, _ = _panned()
    engine = create()
    engine.estimate(frame1, CameraPose())

    differently_sized = np.zeros((HEIGHT + 10, WIDTH + 10, 3), dtype=np.uint8)

    assert engine.estimate(differently_sized, CameraPose()).identity


def test_create_returns_a_new_instance_each_time():
    assert create() is not create()


def test_pose_is_ignored_entirely():
    # `pose` is accepted (the `MotionCompensator` protocol is shared with
    # `pose_gmc`) but never consulted -- flow measures pixels, not telemetry.
    dx, dy = 8, 5
    frame1, frame2 = _panned(dx=dx, dy=dy)
    with_pose = create()
    with_pose.estimate(frame1, CameraPose())
    without_pose = create()
    without_pose.estimate(frame1, CameraPose(hfov_degrees=60.0, yaw_degrees=30.0))

    a = with_pose.estimate(frame2, CameraPose())
    b = without_pose.estimate(frame2, CameraPose(hfov_degrees=60.0, yaw_degrees=45.0))

    assert a.c == pytest.approx(b.c, abs=1e-6)
    assert a.f == pytest.approx(b.f, abs=1e-6)


def test_the_module_needs_the_cv_extra():
    # The flip side of `test_pose_gmc.py`'s own "needs no cv extra" test:
    # this engine legitimately imports cv2/numpy at module scope, which is
    # exactly why it -- unlike `pose_gmc` -- is one of `engines/`'s two
    # named exceptions to the pure-stdlib package rule (P3).
    import cv_service.tracking.engines.flow_gmc as module

    assert hasattr(module, "cv2")
    assert hasattr(module, "np")

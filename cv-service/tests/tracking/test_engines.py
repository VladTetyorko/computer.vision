"""The three shipped engines, against real `cv2`/`ultralytics`.

Needs the `cv` extra (`cv2` for `lk`/`ncc`, `ultralytics` + `lap` for
`bytetrack`); each group skips rather than fails when its dependency is
absent, the same contract `tests/inference/test_real_model.py` established.
"""

from __future__ import annotations

import pytest

from cv_service.tracking.engines.base import Box

cv2 = pytest.importorskip("cv2")
np = pytest.importorskip("numpy")

WIDTH, HEIGHT = 320, 240
TARGET = Box(60 / WIDTH, 80 / HEIGHT, 50 / WIDTH, 40 / HEIGHT)


def scene(dx: int = 0, dy: int = 0) -> "np.ndarray":
    """A textured patch on a textured background, shifted by (dx, dy) pixels."""
    rng = np.random.default_rng(11)
    frame = rng.integers(0, 60, (HEIGHT, WIDTH, 3), dtype=np.uint8)
    patch = rng.integers(90, 250, (40, 50, 3), dtype=np.uint8)
    y0, x0 = 80 + dy, 60 + dx
    frame[y0 : y0 + 40, x0 : x0 + 50] = patch
    return frame


# --- the single-object trackers ----------------------------------------------


def follow_engines():
    from cv_service.tracking.engines.lk import LkFlowEngine
    from cv_service.tracking.engines.ncc import NccEngine

    return [LkFlowEngine, NccEngine]


@pytest.mark.parametrize("engine_cls", follow_engines())
def test_a_follow_engine_tracks_a_moving_target(engine_cls):
    engine = engine_cls()

    assert engine.init(scene(), TARGET) is True

    for step in range(1, 16):
        update = engine.update(scene(dx=step * 3, dy=step * 2))
        assert update is not None

    assert update.box.x == pytest.approx((60 + 45) / WIDTH, abs=0.02)
    assert update.box.y == pytest.approx((80 + 30) / HEIGHT, abs=0.02)
    assert update.box.valid is True
    assert 0.0 <= update.confidence <= 1.0


@pytest.mark.parametrize("engine_cls", follow_engines())
def test_a_follow_engine_refuses_a_degenerate_box_rather_than_raising(engine_cls):
    engine = engine_cls()

    assert engine.init(scene(), Box(0.5, 0.5, 0.0, 0.0)) is False


@pytest.mark.parametrize("engine_cls", follow_engines())
def test_update_before_init_is_none_not_an_exception(engine_cls):
    assert engine_cls().update(scene()) is None


@pytest.mark.parametrize("engine_cls", follow_engines())
def test_reset_drops_all_state(engine_cls):
    engine = engine_cls()
    engine.init(scene(), TARGET)

    engine.reset()

    assert engine.update(scene(dx=3)) is None


@pytest.mark.parametrize("engine_cls", follow_engines())
def test_engines_are_independent_instances(engine_cls):
    left, right = engine_cls(), engine_cls()
    left.init(scene(), TARGET)
    right.init(scene(), Box(0.7, 0.7, 0.1, 0.12))

    left_update = left.update(scene(dx=6))

    assert left_update is not None
    assert right.update(scene(dx=6)) is not None
    assert left_update.box.x < 0.5


def test_lk_gives_up_on_a_featureless_target():
    from cv_service.tracking.engines.lk import LkFlowEngine

    flat = np.full((HEIGHT, WIDTH, 3), 128, dtype=np.uint8)

    assert LkFlowEngine().init(flat, TARGET) is False


def test_ncc_reports_no_match_when_the_target_is_gone():
    from cv_service.tracking.engines.ncc import NccEngine

    engine = NccEngine()
    engine.init(scene(), TARGET)

    rng = np.random.default_rng(99)
    unrelated = rng.integers(0, 255, (HEIGHT, WIDTH, 3), dtype=np.uint8)

    assert engine.update(unrelated) is None


# --- the associator -----------------------------------------------------------

pytest.importorskip("ultralytics", reason="bytetrack needs the `cv` extra")
pytest.importorskip("lap", reason="BYTETracker's linear_assignment needs `lap` (pinned in [cv])")


class Det:
    def __init__(self, label, confidence, x, y, width, height):
        self.label, self.confidence = label, confidence
        self.x, self.y, self.width, self.height = x, y, width, height


def bytetrack(max_age_frames: int = 30):
    from cv_service.tracking.engines.bytetrack import ByteTrackEngine

    return ByteTrackEngine(max_age_frames=max_age_frames)


def test_bytetrack_keeps_one_key_per_object_across_frames():
    engine = bytetrack()

    keys = []
    for frame in range(6):
        observations = engine.associate(
            [
                Det("car", 0.9, 0.10 + frame * 0.01, 0.10, 0.10, 0.10),
                Det("person", 0.8, 0.60, 0.60 + frame * 0.01, 0.05, 0.12),
            ],
            frame / 15.0,
        )
        keys.append(sorted(observation.key for observation in observations))

    assert keys[-1] == keys[-2]
    assert len(set(keys[-1])) == 2


def test_bytetrack_recovers_the_same_key_after_a_short_occlusion():
    engine = bytetrack()
    for frame in range(4):
        observations = engine.associate(
            [Det("car", 0.9, 0.10 + frame * 0.01, 0.10, 0.10, 0.10)], frame / 15.0
        )
    before = observations[0].key

    for frame in range(4, 8):  # occluded
        engine.associate([], frame / 15.0)

    after = engine.associate([Det("car", 0.9, 0.14, 0.10, 0.10, 0.10)], 8 / 15.0)

    assert after[0].key == before


def test_bytetrack_reports_the_index_of_the_detection_each_observation_came_from():
    engine = bytetrack()
    detections = [
        Det("person", 0.8, 0.60, 0.60, 0.05, 0.12),
        Det("car", 0.9, 0.10, 0.10, 0.10, 0.10),
    ]
    for frame in range(4):
        observations = engine.associate(detections, frame / 15.0)

    by_index = {observation.det_index: observation.label for observation in observations}

    assert by_index == {0: "person", 1: "car"}


def test_bytetrack_emits_normalized_boxes():
    engine = bytetrack()
    for frame in range(4):
        observations = engine.associate([Det("car", 0.9, 0.10, 0.20, 0.10, 0.15)], frame / 15.0)

    box = observations[0].box

    assert box.x == pytest.approx(0.10, abs=0.02)
    assert box.y == pytest.approx(0.20, abs=0.02)
    assert box.width == pytest.approx(0.10, abs=0.02)
    assert box.height == pytest.approx(0.15, abs=0.02)


def test_two_bytetrack_engines_never_hand_out_the_same_key():
    # `BYTETracker.__init__` resets a PROCESS-GLOBAL id counter, so without
    # the engine's guard two concurrent streams would both be handed "track
    # 1" for different objects -- and one stream's next new object would
    # re-use a key it had already issued. Verified against ultralytics
    # 8.4.104; see engines/bytetrack.py.
    left = bytetrack()
    for frame in range(4):
        left_obs = left.associate([Det("car", 0.9, 0.10, 0.10, 0.10, 0.10)], frame / 15.0)

    right = bytetrack()
    for frame in range(4):
        right_obs = right.associate([Det("van", 0.9, 0.70, 0.70, 0.10, 0.10)], frame / 15.0)

    for frame in range(4, 8):
        left_after = left.associate(
            [Det("car", 0.9, 0.10, 0.10, 0.10, 0.10), Det("bus", 0.9, 0.40, 0.40, 0.08, 0.08)],
            frame / 15.0,
        )

    keys = {left_obs[0].key, right_obs[0].key} | {o.key for o in left_after}
    assert len(keys) == 3


def test_bytetrack_reset_restores_a_working_engine():
    engine = bytetrack()
    for frame in range(4):
        engine.associate([Det("car", 0.9, 0.10, 0.10, 0.10, 0.10)], frame / 15.0)

    engine.reset()

    for frame in range(4):
        observations = engine.associate([Det("car", 0.9, 0.10, 0.10, 0.10, 0.10)], frame / 15.0)
    assert len(observations) == 1


def test_bytetrack_tolerates_an_empty_frame():
    engine = bytetrack()

    assert engine.associate([], 0.0) == []

"""`cv_service.tracking.registry` -- rosters, the startup probe, and fallback.

Pure stdlib: fake factories only. The real engines are exercised in
`tests/tracking/test_engines.py`, which needs the `cv` extra.
"""

from __future__ import annotations

import dataclasses
import logging

import pytest

from cv_service.config import Settings
from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW
from cv_service.tracking.registry import (
    BUILTIN_APPEARANCES,
    BUILTIN_ASSOCIATORS,
    BUILTIN_COMPENSATORS,
    BUILTIN_FOLLOWERS,
    MOTION_ENGINE_FLOW,
    MOTION_ENGINE_POSE,
    TrackerRegistry,
    build_default_registry,
)

APPEARANCE_ENGINE_HISTOGRAM = "histogram"


class FakeEngine:
    def __init__(self, engine_id: str) -> None:
        self.engine_id = engine_id


def factory(engine_id: str):
    def create(**_kwargs):
        return FakeEngine(engine_id)

    return create


def exploding(**_kwargs):
    raise RuntimeError("this engine needs an ONNX file nobody shipped")


def registry(**overrides) -> TrackerRegistry:
    base = dict(
        associators={"bytetrack": factory("bytetrack"), "cost": factory("cost")},
        followers={"lk": factory("lk"), "ncc": factory("ncc")},
        compensators={MOTION_ENGINE_FLOW: factory(MOTION_ENGINE_FLOW), MOTION_ENGINE_POSE: factory(MOTION_ENGINE_POSE)},
        appearances={APPEARANCE_ENGINE_HISTOGRAM: factory(APPEARANCE_ENGINE_HISTOGRAM)},
        default_associate_id="bytetrack",
        default_follow_id="lk",
        default_motion_id=MOTION_ENGINE_FLOW,
        default_appearance_id=APPEARANCE_ENGINE_HISTOGRAM,
    )
    base.update(overrides)
    return TrackerRegistry(**base)


def test_the_roster_reports_which_modes_each_engine_serves():
    assert registry().roster() == {
        "bytetrack": [MODE_ASSOCIATE],
        "cost": [MODE_ASSOCIATE],
        "flow": ["MOTION_COMPENSATOR"],
        "histogram": ["APPEARANCE_EXTRACTOR"],
        "lk": [MODE_FOLLOW],
        "ncc": [MODE_FOLLOW],
        "pose": ["MOTION_COMPENSATOR"],
    }


def test_engines_are_per_stream_not_singletons():
    subject = registry()

    first = subject.follower("lk", max_age_frames=30)
    second = subject.follower("lk", max_age_frames=30)

    assert first[1] is not second[1]


def test_compensators_are_per_stream_not_singletons():
    subject = registry()

    first = subject.compensator(MOTION_ENGINE_FLOW)
    second = subject.compensator(MOTION_ENGINE_FLOW)

    assert first[1] is not second[1]


def test_an_unknown_engine_id_falls_back_to_the_mode_default():
    subject = registry()

    engine_id, engine = subject.follower("kcf", max_age_frames=30)

    assert engine_id == "lk"
    assert engine.engine_id == "lk"


def test_an_unknown_engine_id_is_logged_once_not_per_frame(caplog):
    subject = registry()

    with caplog.at_level(logging.INFO, logger="cv_service.tracking.registry"):
        for _ in range(20):
            subject.follower("kcf", max_age_frames=30)

    assert sum("kcf" in record.getMessage() for record in caplog.records) == 1


def test_an_empty_engine_id_takes_the_default_without_warning(caplog):
    subject = registry()

    with caplog.at_level(logging.INFO, logger="cv_service.tracking.registry"):
        engine_id, _engine = subject.associator("", max_age_frames=30)

    assert engine_id == "bytetrack"
    assert caplog.records == []


def test_the_probe_drops_engines_that_cannot_be_constructed_here(caplog):
    subject = registry(followers={"lk": factory("lk"), "vit": exploding})

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.registry"):
        roster = subject.probe()

    assert roster == {
        "bytetrack": [MODE_ASSOCIATE],
        "cost": [MODE_ASSOCIATE],
        "flow": ["MOTION_COMPENSATOR"],
        "histogram": ["APPEARANCE_EXTRACTOR"],
        "lk": [MODE_FOLLOW],
        "pose": ["MOTION_COMPENSATOR"],
    }
    assert any("vit" in record.getMessage() for record in caplog.records)


def test_the_probe_drops_a_compensator_that_cannot_be_constructed_here(caplog):
    subject = registry(compensators={MOTION_ENGINE_FLOW: factory(MOTION_ENGINE_FLOW), "nano-gmc": exploding})

    with caplog.at_level(logging.WARNING, logger="cv_service.tracking.registry"):
        roster = subject.probe()

    assert roster == {
        "bytetrack": [MODE_ASSOCIATE],
        "cost": [MODE_ASSOCIATE],
        "flow": ["MOTION_COMPENSATOR"],
        "histogram": ["APPEARANCE_EXTRACTOR"],
        "lk": [MODE_FOLLOW],
        "ncc": [MODE_FOLLOW],
    }
    assert any("nano-gmc" in record.getMessage() for record in caplog.records)


def test_the_probe_logs_the_roster_once_at_info(caplog):
    subject = registry()

    with caplog.at_level(logging.INFO, logger="cv_service.tracking.registry"):
        subject.probe()
        subject.probe()
        subject.probe()

    roster_lines = [r for r in caplog.records if "tracker registry roster" in r.getMessage()]
    assert len(roster_lines) == 1


def test_the_probe_never_mutates_the_shared_builtin_rosters():
    before_associators = dict(BUILTIN_ASSOCIATORS)
    before_followers = dict(BUILTIN_FOLLOWERS)
    before_compensators = dict(BUILTIN_COMPENSATORS)
    before_appearances = dict(BUILTIN_APPEARANCES)

    build_default_registry(Settings(), probe=True)

    assert BUILTIN_ASSOCIATORS == before_associators
    assert BUILTIN_FOLLOWERS == before_followers
    assert BUILTIN_COMPENSATORS == before_compensators
    assert BUILTIN_APPEARANCES == before_appearances


def test_a_default_that_itself_fails_still_serves_a_surviving_engine():
    subject = registry(followers={"lk": exploding, "ncc": factory("ncc")})

    engine_id, engine = subject.follower("lk", max_age_frames=30)

    assert engine_id == "ncc"
    assert engine.engine_id == "ncc"


def test_a_default_compensator_that_itself_fails_still_serves_a_surviving_one():
    subject = registry(compensators={MOTION_ENGINE_FLOW: exploding, MOTION_ENGINE_POSE: factory(MOTION_ENGINE_POSE)})

    engine_id, engine = subject.compensator(MOTION_ENGINE_FLOW)

    assert engine_id == MOTION_ENGINE_POSE
    assert engine.engine_id == MOTION_ENGINE_POSE


def test_an_empty_roster_returns_none_rather_than_raising():
    subject = registry(followers={})

    assert subject.follower("lk", max_age_frames=30) is None


def test_an_empty_compensator_roster_returns_none_rather_than_raising():
    subject = registry(compensators={})

    assert subject.compensator(MOTION_ENGINE_FLOW) is None


def test_an_unknown_compensator_id_falls_back_to_the_motion_default(caplog):
    subject = registry()

    with caplog.at_level(logging.INFO, logger="cv_service.tracking.registry"):
        engine_id, engine = subject.compensator("nano-gmc")

    assert engine_id == MOTION_ENGINE_FLOW
    assert engine.engine_id == MOTION_ENGINE_FLOW
    assert any("nano-gmc" in record.getMessage() for record in caplog.records)


def test_build_default_registry_takes_its_defaults_from_settings():
    settings = dataclasses.replace(
        Settings(), track_follow_engine="ncc", track_motion_engine="pose", track_appearance_engine="off"
    )

    subject = build_default_registry(settings, probe=False)

    assert subject.default_follow_id == "ncc"
    assert subject.default_associate_id == Settings().track_associate_engine
    assert subject.default_motion_id == "pose"
    assert subject.default_appearance_id == "off"
    assert set(subject.roster()) == {"bytetrack", "cost", "lk", "ncc", "flow", "pose", "histogram"}


@pytest.mark.parametrize("engine_id", ["bytetrack", "cost", "lk", "ncc", "flow", "pose", "histogram"])
def test_the_seven_shipped_engines_are_advertised(engine_id):
    assert engine_id in build_default_registry(Settings(), probe=False).roster()


@pytest.mark.parametrize(
    "module_name",
    [
        "cv_service.tracking.params",
        "cv_service.tracking.scheduler",
        "cv_service.tracking.track",
        "cv_service.tracking.lock",
        "cv_service.tracking.registry",
        "cv_service.tracking.session",
        "cv_service.tracking.sessions",
        "cv_service.tracking.engines.base",
        "cv_service.tracking.predict",
    ],
)
def test_everything_but_the_engines_stays_pure_stdlib(module_name):
    """The rule that makes the duty cycle testable with a fake clock.

    Also the rule that keeps `python -m cv_service.grpc.server` runnable
    without the `cv` extra: `grpc/servicers.py` imports the session at module
    scope, so any `cv2`/`numpy` reaching these modules would break echo mode
    on a box with no CV backend at all.
    """
    import importlib

    module = importlib.import_module(module_name)
    heavy = {"cv2", "numpy", "np", "ultralytics", "torch"}

    assert not heavy & set(vars(module))


@pytest.mark.parametrize(
    "module_name",
    ["cv_service.tracking.registry", "cv_service.tracking.session", "cv_service.tracking.sessions"],
)
def test_no_tracking_module_imports_cv_pb2(module_name):
    import importlib

    module = importlib.import_module(module_name)

    assert "cv_pb2" not in vars(module)

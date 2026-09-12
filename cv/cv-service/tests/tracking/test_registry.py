"""`cv_service.tracking.registry` -- rosters, the startup probe, and fallback.

Pure stdlib: fake factories only. The real engines are exercised in
`tests/tracking/test_engines.py`, which needs the `cv` extra.
"""

from __future__ import annotations

import dataclasses
import logging

import pytest

from cv_service.config import Settings
from cv_service.tracking import levels
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
    assert set(subject.roster()) == {"cost", "lk", "ncc", "flow", "pose", "histogram"}


@pytest.mark.parametrize("engine_id", ["cost", "lk", "ncc", "flow", "pose", "histogram"])
def test_the_six_shipped_engines_are_advertised(engine_id):
    assert engine_id in build_default_registry(Settings(), probe=False).roster()


def test_probe_no_longer_lists_bytetrack_in_the_associator_roster():
    """CV-ORCHESTRATION wave W4 (decision E16): `bytetrack` is retired from
    `BUILTIN_ASSOCIATORS`, so the real, probed registry must never advertise
    it -- `engines/bytetrack.py` stays on disk only as an unregistered
    reference (`params.py#resolve()` is what still accepts the id on the
    wire/env, aliasing it to `cost` rather than the roster serving it)."""
    roster = build_default_registry(Settings(), probe=True).roster()

    assert "bytetrack" not in roster
    assert roster["cost"] == [MODE_ASSOCIATE]


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
        "cv_service.tracking.levels",
        "cv_service.tracking.outcome",
        # CV-ORCHESTRATION W0: the per-frame chain moved here, and the
        # rule moved with it -- a contributor never touches pixels
        # directly, only the memoized `frame()` callable an engine reads.
        "cv_service.orchestration.keys",
        "cv_service.orchestration.contract",
        "cv_service.orchestration.budget",
        "cv_service.orchestration.ledger",
        "cv_service.orchestration.orchestrator",
        "cv_service.orchestration.aggregator",
        "cv_service.orchestration.detector",
        "cv_service.orchestration.state",
        "cv_service.orchestration.engines",
        "cv_service.orchestration.facts",
        "cv_service.orchestration.corrections",
        "cv_service.orchestration.contributors",
        "cv_service.orchestration.contributors.detect",
        "cv_service.orchestration.contributors.egomotion",
        "cv_service.orchestration.contributors.predict",
        "cv_service.orchestration.contributors.appearance",
        "cv_service.orchestration.contributors.associate",
        "cv_service.orchestration.contributors.roi",
        "cv_service.orchestration.contributors.memory",
        "cv_service.orchestration.contributors.follow",
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
    [
        "cv_service.tracking.registry",
        "cv_service.tracking.session",
        "cv_service.tracking.sessions",
        "cv_service.tracking.outcome",
        # The ledger is a plain dataclass tree on purpose: `grpc/
        # servicers.py` is still the SOLE translator to the wire, for
        # `Inspect` exactly as for `DetectionResponse` (plan §4.4).
        "cv_service.orchestration.keys",
        "cv_service.orchestration.contract",
        "cv_service.orchestration.budget",
        "cv_service.orchestration.ledger",
        "cv_service.orchestration.orchestrator",
        "cv_service.orchestration.aggregator",
        "cv_service.orchestration.detector",
        "cv_service.orchestration.state",
        "cv_service.orchestration.engines",
        "cv_service.orchestration.facts",
        "cv_service.orchestration.corrections",
        "cv_service.orchestration.contributors",
        "cv_service.orchestration.contributors.detect",
        "cv_service.orchestration.contributors.egomotion",
        "cv_service.orchestration.contributors.predict",
        "cv_service.orchestration.contributors.appearance",
        "cv_service.orchestration.contributors.associate",
        "cv_service.orchestration.contributors.roi",
        "cv_service.orchestration.contributors.memory",
        "cv_service.orchestration.contributors.follow",
    ],
)
def test_no_tracking_module_imports_cv_pb2(module_name):
    import importlib

    module = importlib.import_module(module_name)

    assert "cv_pb2" not in vars(module)


# -- wave V1: the capability ladder makes a roster level-aware ---------------
# (TRACKING-V3-PLAN §5, decision E11). `level=None` (every test above this
# point) means "no ceiling" -- unchanged, pre-V1 behavior. These tests are
# the OTHER half: a `level=` argument filters the roster BEFORE the fallback
# ladder or any factory ever runs.


def counting_factory(engine_id: str):
    """Like `factory()`, but records every call -- the mechanism this
    section's purity tests lean on: a level-excluded engine's factory must
    never be invoked at all, not merely have its RESULT discarded (that
    distinction is what keeps a level-1 stream from ever executing
    `import cv2` in the first place, invariant P8)."""
    calls: list[int] = []

    def create(**_kwargs):
        calls.append(1)
        return FakeEngine(engine_id)

    create.calls = calls
    return create


def test_an_l1_capped_stream_still_gets_cost_from_a_one_entry_associator_roster():
    """CV-ORCHESTRATION wave W4 (decision E16): the real `BUILTIN_ASSOCIATORS`
    now has exactly one entry (`cost`, min-level L1, since `bytetrack`'s own
    L3 floor was retired along with the engine). The level filter must not
    treat "only one candidate" as a reason to skip filtering -- `cost` is
    affordable at L1 on its own merits, not by default."""
    subject = build_default_registry(Settings(), probe=False)

    engine_id, _engine = subject.associator("cost", max_age_frames=30, level=levels.LEVEL_L1)

    assert engine_id == "cost"


def test_cost_is_selectable_at_every_level():
    subject = registry()

    for level in (levels.LEVEL_L1, levels.LEVEL_L2, levels.LEVEL_L3, levels.LEVEL_L4, levels.LEVEL_L5):
        engine_id, _engine = subject.associator("cost", max_age_frames=30, level=level)
        assert engine_id == "cost"


def test_a_level_ceiling_never_even_calls_the_excluded_factory():
    # `lk` (min-level L2, `_FOLLOWER_MIN_LEVEL`) rather than the pre-E16
    # `bytetrack` example -- CV-ORCHESTRATION wave W4 retired the only
    # associator that ever had a level floor above L1, but this is a
    # role-agnostic property of `_create` (invariant P8), so any gated role
    # proves it: a level-excluded engine's factory must never be invoked at
    # all, not merely have its RESULT discarded, which is also what proves
    # it is excluded despite being technically constructible (nothing here
    # raises) -- the level is a CONTRACT, not "whatever this box can do"
    # (decision E12/invariant P9).
    lk_factory = counting_factory("lk")
    subject = registry(followers={"lk": lk_factory, "ncc": factory("ncc")})

    subject.follower("lk", max_age_frames=30, level=levels.LEVEL_L1)

    assert lk_factory.calls == []


def test_no_level_argument_means_no_ceiling_at_all():
    subject = registry()

    # `level=None` (the default -- every pre-V1 call site, and every fake
    # registry in other test files this wave must not break) still returns
    # `bytetrack` even though its own min-level is L3 -- unfiltered is
    # unfiltered.
    engine_id, _engine = subject.associator("bytetrack", max_age_frames=30)

    assert engine_id == "bytetrack"


def test_l1_offers_no_follower_at_all():
    subject = registry()

    assert subject.follower("lk", max_age_frames=30, level=levels.LEVEL_L1) is None
    assert subject.follower("ncc", max_age_frames=30, level=levels.LEVEL_L1) is None


def test_followers_become_selectable_from_l2_up():
    subject = registry()

    engine_id, _engine = subject.follower("lk", max_age_frames=30, level=levels.LEVEL_L2)

    assert engine_id == "lk"


def test_pose_is_selectable_at_l1_flow_is_not():
    subject = registry()

    engine_id, _engine = subject.compensator(MOTION_ENGINE_POSE, level=levels.LEVEL_L1)
    assert engine_id == MOTION_ENGINE_POSE

    # `flow` needs L2 -- at L1 the only affordable compensator is `pose`,
    # so asking for `flow` explicitly still falls back to `pose`, not `None`.
    engine_id, _engine = subject.compensator(MOTION_ENGINE_FLOW, level=levels.LEVEL_L1)
    assert engine_id == MOTION_ENGINE_POSE


def test_flow_becomes_selectable_from_l2_up():
    subject = registry()

    engine_id, _engine = subject.compensator(MOTION_ENGINE_FLOW, level=levels.LEVEL_L2)

    assert engine_id == MOTION_ENGINE_FLOW


def test_l1_offers_no_appearance_extractor():
    subject = registry()

    assert subject.appearance(APPEARANCE_ENGINE_HISTOGRAM, level=levels.LEVEL_L1) is None


def test_appearance_becomes_selectable_from_l2_up():
    subject = registry()

    engine_id, _engine = subject.appearance(APPEARANCE_ENGINE_HISTOGRAM, level=levels.LEVEL_L2)

    assert engine_id == APPEARANCE_ENGINE_HISTOGRAM



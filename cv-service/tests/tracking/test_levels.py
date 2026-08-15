"""`cv_service.tracking.levels` -- the capability ladder (TRACKING-V3-PLAN §5).

Pure stdlib, and split in two halves for a reason:

* `probe()`/`resolve()`/`name()` are tested in-process, with `probe()`'s own
  importability/memory checks monkeypatched so the assertions are
  deterministic regardless of what happens to be installed on the machine
  running the suite.
* The invariant that actually matters (P8: L1 imports no `cv2`/`numpy`/
  `ultralytics`/`torch`/`lap`) CANNOT be tested in-process -- `tests/conftest.py`
  imports `numpy` at collection time for its own fixtures, so by the time any
  test function here runs, `numpy` is already in `sys.modules` and an
  in-process check would pass trivially whether or not the code under test
  ever touched it. The purity tests at the bottom launch a genuinely clean
  `python -c` subprocess instead, import nothing beforehand, run the real L1
  serving path, and inspect ITS `sys.modules`.
"""

from __future__ import annotations

import subprocess
import sys
import textwrap

import pytest

from cv_service.tracking import levels


# -- probe() ------------------------------------------------------------------


def _stub_importable(available: "set[str]"):
    def check(name: str) -> bool:
        return name in available

    return check


def test_probe_is_l1_when_cv2_is_missing(monkeypatch):
    monkeypatch.setattr(levels, "_importable", _stub_importable(set()))

    assert levels.probe() == levels.LEVEL_L1


def test_probe_is_l1_when_cv2_is_present_but_numpy_is_not(monkeypatch):
    # `cv2` itself depends on `numpy`, but `probe()` checks both explicitly
    # rather than trusting that -- a hand-assembled or broken environment
    # could still have one without the other.
    monkeypatch.setattr(levels, "_importable", _stub_importable({"cv2"}))

    assert levels.probe() == levels.LEVEL_L1


def test_probe_is_l2_when_ultralytics_is_missing(monkeypatch):
    monkeypatch.setattr(levels, "_importable", _stub_importable({"cv2", "numpy"}))

    assert levels.probe() == levels.LEVEL_L2


def test_probe_is_l2_when_torch_is_missing_even_with_ultralytics(monkeypatch):
    monkeypatch.setattr(levels, "_importable", _stub_importable({"cv2", "numpy", "ultralytics"}))

    assert levels.probe() == levels.LEVEL_L2


def test_probe_is_l2_when_memory_is_below_the_l3_floor(monkeypatch):
    monkeypatch.setattr(
        levels, "_importable", _stub_importable({"cv2", "numpy", "ultralytics", "torch"})
    )
    monkeypatch.setattr(levels, "_available_memory_mib", lambda: levels._L3_MIN_AVAILABLE_MEMORY_MIB - 1)

    assert levels.probe() == levels.LEVEL_L2


def test_probe_skips_the_memory_gate_when_it_cannot_be_determined(monkeypatch):
    # `None` means "this platform can't answer" -- treated as "don't deny L3
    # over a question this host cannot answer", not as a failure.
    monkeypatch.setattr(
        levels, "_importable", _stub_importable({"cv2", "numpy", "ultralytics", "torch"})
    )
    monkeypatch.setattr(levels, "_available_memory_mib", lambda: None)

    assert levels.probe() == levels.LEVEL_L3


def test_probe_is_l3_when_openvino_is_missing(monkeypatch):
    monkeypatch.setattr(
        levels, "_importable", _stub_importable({"cv2", "numpy", "ultralytics", "torch"})
    )
    monkeypatch.setattr(levels, "_available_memory_mib", lambda: 10_000.0)

    assert levels.probe() == levels.LEVEL_L3


def test_probe_never_auto_selects_above_l4(monkeypatch):
    # Every importable signal this module knows how to check is satisfied --
    # `probe()` still stops at L4. L5 (STUDY) is reachable only by an
    # EXPLICIT `capability_level=5` request (see `resolve()`'s own tests
    # below and `levels.py`'s module docstring for why).
    monkeypatch.setattr(
        levels,
        "_importable",
        _stub_importable({"cv2", "numpy", "ultralytics", "torch", "openvino"}),
    )
    monkeypatch.setattr(levels, "_available_memory_mib", lambda: 10_000.0)

    assert levels.probe() == levels.LEVEL_L4


# -- _importable / _available_memory_mib --------------------------------------


def test_importable_is_true_for_a_module_that_really_exists():
    assert levels._importable("os") is True


def test_importable_is_false_for_a_module_that_does_not_exist():
    assert levels._importable("this_module_does_not_exist_anywhere_xyz") is False


def test_importable_never_raises_for_a_bad_dotted_name():
    # A dotted name whose parent isn't a package raises `ModuleNotFoundError`
    # out of `find_spec` itself on some Python versions -- never propagated.
    assert levels._importable("os.path.this.is.not.a.thing") is False


def test_available_memory_reports_a_positive_number_on_this_host():
    # No monkeypatching -- this exercises the REAL path (either /proc/meminfo
    # or os.sysconf), so the only thing worth asserting is the honest
    # contract: a plausible positive number, or `None`, never a crash.
    available = levels._available_memory_mib()

    assert available is None or available > 0.0


# -- resolve() ------------------------------------------------------------------


@pytest.mark.parametrize("requested", [0, -1, -99])
def test_a_non_positive_request_auto_probes(requested):
    served, reason = levels.resolve(requested, levels.LEVEL_L3)

    assert served == levels.LEVEL_L3
    assert reason == ""


def test_a_request_at_or_below_the_probed_level_is_served_exactly_and_uncapped():
    served, reason = levels.resolve(levels.LEVEL_L2, levels.LEVEL_L4)

    assert served == levels.LEVEL_L2
    assert reason == ""


def test_a_request_above_the_probed_level_is_capped_and_explained():
    served, reason = levels.resolve(levels.LEVEL_L5, levels.LEVEL_L2)

    assert served == levels.LEVEL_L2
    assert reason != ""
    assert "L5" in reason and "L2" in reason


def test_served_never_exceeds_requested():
    for requested in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
        for probed in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
            served, _reason = levels.resolve(requested, probed)
            assert served <= requested


def test_served_never_exceeds_probed():
    for requested in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
        for probed in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
            served, _reason = levels.resolve(requested, probed)
            assert served <= probed


def test_reason_is_empty_exactly_when_nothing_was_capped():
    for requested in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
        for probed in range(levels.MIN_LEVEL, levels.MAX_LEVEL + 1):
            served, reason = levels.resolve(requested, probed)
            assert (reason == "") == (served == requested)


def test_resolve_clamps_an_out_of_range_requested_value():
    served, reason = levels.resolve(999, levels.LEVEL_L3)

    assert served == levels.LEVEL_L3
    assert "L5" in reason  # 999 clamped to MAX_LEVEL before the reason is built


def test_resolve_clamps_an_out_of_range_probed_value():
    served, reason = levels.resolve(levels.LEVEL_L2, 0)

    assert served == levels.MIN_LEVEL
    assert reason != ""


def test_resolve_never_raises():
    for requested in (-100, -1, 0, 1, 3, 5, 6, 1000):
        for probed in (-100, 0, 1, 3, 5, 6, 1000):
            served, reason = levels.resolve(requested, probed)
            assert levels.MIN_LEVEL <= served <= levels.MAX_LEVEL
            assert isinstance(reason, str)


# -- name() ---------------------------------------------------------------------


def test_name_maps_every_shipped_level():
    assert levels.name(levels.LEVEL_L1) == "RELAY"
    assert levels.name(levels.LEVEL_L2) == "FILL"
    assert levels.name(levels.LEVEL_L3) == "DETECT"
    assert levels.name(levels.LEVEL_L4) == "IDENTIFY"
    assert levels.name(levels.LEVEL_L5) == "STUDY"


def test_name_of_an_unknown_level_falls_back_rather_than_raising():
    assert levels.name(42) == "L42"


# -- P8: L1 stays pure, asserted mechanically ----------------------------------
#
# `docs/plans/active/TRACKING-V3-PLAN.md` invariant P8: "Level 1 must import no
# cv2, no numpy, no ultralytics, and load no model asset -- asserted by
# inspecting sys.modules in a test, not by reading the source." The
# subprocess below runs the REAL `StreamTrackingSession`/`TrackerRegistry`
# path -- not a hand-rolled stand-in -- capped to L1 by an EXPLICIT request
# (decision E12: a level is a ceiling), on THIS SAME venv, which genuinely
# has cv2/numpy/ultralytics/torch installed (it is running this very test
# suite). That is the strictly stronger claim: proving purity by absence
# (nothing installed) proves nothing about whether the code path would have
# reached for them if it could; proving it while they are fully available
# and one call away proves the level-filtering in `registry.py` actually
# gates the factories, not merely that a bare host degrades safely (a
# property already covered by the pre-V1 "importable without the cv extra"
# tests `MODULE.md` describes).
#
# `TrackerRegistry.probe()` is deliberately NOT called here (`build_default_
# registry(settings, probe=False)`): that startup probe is a HOST-lifecycle
# concern -- it touches every factory, including cv2-needing ones, to find
# out what a box that might ALSO serve higher-level streams can build. On
# this fully-loaded dev venv it would genuinely succeed and import cv2/numpy
# regardless of level, which would defeat the point of this check. A real L1
# host has no cv2 to probe in the first place, so `probe()` is harmless
# there (see `MODULE.md`'s "blocking cv2/.../lap at import" verification);
# this test isolates the OTHER, level-driven half of the guarantee.

_L1_ASSOCIATE_SCRIPT = textwrap.dedent(
    """
    import sys

    from cv_service.config import Settings
    from cv_service.tracking import levels
    from cv_service.tracking.params import MODE_ASSOCIATE, TrackingRequest
    from cv_service.tracking.registry import build_default_registry
    from cv_service.tracking.session import StreamTrackingSession


    class Det:
        def __init__(self, label, confidence, x, y, width, height):
            self.label, self.confidence = label, confidence
            self.x, self.y, self.width, self.height = x, y, width, height


    def detect(roi=None):
        return [Det("car", 0.9, 0.10, 0.10, 0.10, 0.10)], 0


    def frame():
        raise AssertionError("L1 must never decode a frame")


    settings = Settings(track_roi_enabled=False)
    registry = build_default_registry(settings, probe=False)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    session.apply_config(TrackingRequest(mode=MODE_ASSOCIATE, capability_level=levels.LEVEL_L1))

    outcome = None
    for step in range(5):
        outcome = session.process(now_millis=float(step) * 100.0, detect=detect, frame=frame)
        assert outcome.boxes is not None

    assert outcome.capability_level_served == levels.LEVEL_L1, outcome.capability_level_served
    assert outcome.engine_id == "cost", outcome.engine_id

    leaked = sorted({"cv2", "numpy", "ultralytics", "torch", "lap"} & set(sys.modules))
    assert not leaked, "L1 imported: %r" % (leaked,)
    print("P8_OK")
    """
)

_L1_FOLLOW_DEGRADES_SCRIPT = textwrap.dedent(
    """
    import sys

    from cv_service.config import Settings
    from cv_service.tracking import levels
    from cv_service.tracking.params import MODE_ASSOCIATE, MODE_FOLLOW, TrackingRequest
    from cv_service.tracking.registry import build_default_registry
    from cv_service.tracking.session import StreamTrackingSession


    class Det:
        def __init__(self, label, confidence, x, y, width, height):
            self.label, self.confidence = label, confidence
            self.x, self.y, self.width, self.height = x, y, width, height


    def detect(roi=None):
        return [Det("car", 0.9, 0.10, 0.10, 0.10, 0.10)], 0


    def frame():
        raise AssertionError("L1 must never decode a frame")


    settings = Settings(track_roi_enabled=False)
    registry = build_default_registry(settings, probe=False)
    session = StreamTrackingSession(settings=settings, registry_provider=lambda: registry)
    # FOLLOW at L1: no local SOT is affordable (registry._FOLLOWER_MIN_LEVEL
    # starts at L2), so this must degrade to ASSOCIATE -- the SAME ladder
    # that already handles "no FOLLOW engine constructible" -- with no new
    # code path, per this wave's own file scope.
    session.apply_config(TrackingRequest(mode=MODE_FOLLOW, capability_level=levels.LEVEL_L1))

    outcome = None
    for step in range(5):
        outcome = session.process(now_millis=float(step) * 100.0, detect=detect, frame=frame)
        assert outcome.boxes is not None

    assert session.params.mode == MODE_ASSOCIATE, session.params.mode
    assert outcome.capability_level_served == levels.LEVEL_L1, outcome.capability_level_served

    leaked = sorted({"cv2", "numpy", "ultralytics", "torch", "lap"} & set(sys.modules))
    assert not leaked, "L1 imported: %r" % (leaked,)
    print("P8_OK")
    """
)


def _run_clean_subprocess(script: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, "-c", script],
        capture_output=True,
        text=True,
        timeout=60,
    )


def test_l1_associate_imports_no_cv_stack_in_a_clean_interpreter():
    result = _run_clean_subprocess(_L1_ASSOCIATE_SCRIPT)

    assert result.returncode == 0, f"stdout={result.stdout!r} stderr={result.stderr!r}"
    assert "P8_OK" in result.stdout


def test_l1_follow_degrades_to_associate_and_still_imports_no_cv_stack():
    result = _run_clean_subprocess(_L1_FOLLOW_DEGRADES_SCRIPT)

    assert result.returncode == 0, f"stdout={result.stdout!r} stderr={result.stderr!r}"
    assert "P8_OK" in result.stdout

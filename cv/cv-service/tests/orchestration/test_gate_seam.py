"""The inference gate has exactly one door, and it is `DetectorClient`.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1 rule 5 / §4.9. This is a
SOURCE test, not a behaviour test, on purpose: the invariant it defends is
"no future contributor quietly acquires a gate of its own", and a behaviour
test can only catch that after somebody has already written the second door.

Comments and docstrings are stripped before searching (`tokenize`), so a
module is free to WRITE about `InferenceGate.acquire()` -- `ledger.py` does,
explaining what `gate_wait_ms` measures -- while still being unable to call
one.
"""

from __future__ import annotations

import io
import tokenize
from pathlib import Path

import pytest

import cv_service
from cv_service.orchestration import detector as detector_module

PACKAGE_ROOT = Path(cv_service.__file__).resolve().parent
ORCHESTRATION_ROOT = PACKAGE_ROOT / "orchestration"

#: The one module allowed to hold a gate acquisition inside this package.
#: `local` does not literally acquire one today -- it calls the servicer's
#: `detect` callable, which closes over the gate -- but it is the seam W4's
#: `pool(targets)` lands behind, so it is the only file this test will ever
#: relax for.
GATE_DOOR = "detector.py"


def _code_without_comments_or_strings(path: Path) -> str:
    """The file's CODE tokens only -- no comments, no string literals."""
    source = path.read_text(encoding="utf-8")
    pieces: "list[str]" = []
    for token in tokenize.generate_tokens(io.StringIO(source).readline):
        if token.type in (tokenize.COMMENT, tokenize.STRING):
            continue
        pieces.append(token.string)
    return " ".join(pieces)


def _python_files(root: Path) -> "list[Path]":
    return sorted(p for p in root.rglob("*.py") if "__pycache__" not in p.parts)


def test_the_orchestration_package_exists_and_is_not_empty() -> None:
    # Guards the guard: a typo'd root would make every assertion below pass
    # vacuously, which is the classic way a grep test rots into decoration.
    assert ORCHESTRATION_ROOT.is_dir()
    assert len(_python_files(ORCHESTRATION_ROOT)) >= 7


@pytest.mark.parametrize(
    "path", _python_files(ORCHESTRATION_ROOT), ids=lambda p: p.name
)
def test_only_the_detector_client_may_acquire_the_inference_gate(path: Path) -> None:
    code = _code_without_comments_or_strings(path)
    if path.name == GATE_DOOR:
        return
    assert "acquire" not in code, (
        f"{path.relative_to(PACKAGE_ROOT)} acquires something; the inference gate has "
        f"exactly one door in this package and it is orchestration/{GATE_DOOR} "
        "(CV-ORCHESTRATION §4.1 rule 5)"
    )
    # CV-ORCHESTRATION wave W4 gave `InferenceGate` a SECOND door --
    # `admit(max_queue)`, the refusing one the `Detector` servicer takes --
    # so the seam this file guards would have had a hole in it the width of
    # one method name. Named explicitly rather than widened to a substring
    # like "gate" or "Inference": a loose grep here is how a structural test
    # starts failing for reasons nobody can act on.
    assert "admit" not in code, (
        f"{path.relative_to(PACKAGE_ROOT)} admits something; `InferenceGate.admit` is "
        f"the gate's other door and orchestration/{GATE_DOOR} is still the only file "
        "in this package allowed to hold either"
    )
    assert "InferenceGate" not in code, (
        f"{path.relative_to(PACKAGE_ROOT)} names InferenceGate; only "
        f"orchestration/{GATE_DOOR} may know the gate exists"
    )


def test_the_detector_client_is_the_packages_only_route_to_a_detector() -> None:
    # The direction the plan actually cares about: a ledger with no `detect.*`
    # entry must be able to prove zero detector work happened. That holds only
    # while every call to a detect callable goes through this one class.
    assert hasattr(detector_module, "LocalDetectorClient")

    for path in _python_files(ORCHESTRATION_ROOT):
        if path.name in (GATE_DOOR, "__init__.py"):
            continue
        assert "cv_service.inference" not in path.read_text(encoding="utf-8"), (
            f"{path.relative_to(PACKAGE_ROOT)} reaches into cv_service.inference; "
            "detection enters this package through DetectorClient only"
        )


def test_the_orchestration_package_never_imports_grpc_or_generated_stubs() -> None:
    """CV-ORCHESTRATION wave W4's other structural promise, from `detector.py`'s
    own module docstring: "a module that imports neither `grpc` nor `cv_pb2`".
    The pool (`PoolDetectorClient`) holds ordering/failover/accounting only,
    never wire knowledge -- `grpc/detector_transport.py`'s `GrpcRemoteDetector`
    is the one place a `RemoteDetector` is actually implemented, and it lives
    OUTSIDE this package specifically so that split stays a plain grep, not a
    convention someone has to remember. Unlike `GATE_DOOR` above, no file in
    `orchestration/` is exempt from this one -- there is no module here that
    is supposed to know gRPC exists at all, `detector.py` included.

    Token-stripped (`_code_without_comments_or_strings`), not raw text: several
    docstrings in this package (`detector.py`'s own) name
    "`grpc/detector_transport.py`" IN PROSE, describing where the wire
    implementation lives without importing it -- exactly the distinction this
    test exists to enforce, so a naive raw-substring check would misfire on
    the very file that states the rule.
    """
    offenders: "list[str]" = []
    for path in _python_files(ORCHESTRATION_ROOT):
        code = _code_without_comments_or_strings(path)
        if "import grpc" in code or "cv_pb2" in code:
            offenders.append(path.relative_to(PACKAGE_ROOT).as_posix())
    assert offenders == [], (
        f"{offenders} import grpc or a generated cv_pb2 stub; the orchestration "
        "package must stay wire-agnostic (CV-ORCHESTRATION §4.9) -- see "
        "grpc/detector_transport.py for where that knowledge belongs instead"
    )


def test_the_tracking_package_still_cannot_see_the_gate() -> None:
    # The pre-existing half of the same invariant: `StreamTrackingSession`
    # holds no `InferenceGate` and could not acquire one by accident, because
    # the gate lives inside `grpc/servicers.py`'s `_run_detector` closure.
    offenders = [
        path.relative_to(PACKAGE_ROOT).as_posix()
        for path in _python_files(PACKAGE_ROOT / "tracking")
        if "InferenceGate" in _code_without_comments_or_strings(path)
    ]
    assert offenders == []


def test_the_local_client_passes_no_roi_argument_when_there_is_no_roi() -> None:
    """Zero-delta detail, asserted because it is invisible by reading.

    Today `session.process()` calls the servicer's bare `detect()` and only
    `_roi_rescue` calls `detect(roi)`. Every pre-ROI test double in this repo
    is written `def detect()` with no parameter at all, so passing `roi=None`
    on the plain path would break them -- which is exactly the kind of
    behavioural delta W0 is not allowed to ship.
    """
    calls: "list[tuple]" = []

    def detect_no_args():
        calls.append(())
        return [], 7

    client = detector_module.LocalDetectorClient()
    client.bind(detect_no_args)
    result = client.detect()

    assert calls == [()]
    assert result.inference_millis == 7
    assert result.roi is False
    assert result.unavailable is False
    assert client.passes == 1


def test_the_local_client_passes_the_roi_positionally() -> None:
    seen: "list[object]" = []

    def detect_with_roi(roi=None):
        seen.append(roi)
        return [], 0

    region = object()
    client = detector_module.LocalDetectorClient()
    client.bind(detect_with_roi)
    result = client.detect(region)

    assert seen == [region]
    assert result.roi is True
    assert client.passes == 1


def test_an_unresolved_model_is_reported_as_unavailable_not_as_empty() -> None:
    client = detector_module.LocalDetectorClient()
    client.bind(lambda *_: (None, 0))

    assert client.detect(object()).unavailable is True


def test_bind_resets_the_per_frame_tally() -> None:
    client = detector_module.LocalDetectorClient()
    client.bind(lambda *_: ([], 0))
    client.detect(object())
    client.detect(object())
    assert client.passes == 2

    client.bind(lambda *_: ([], 0))
    assert client.passes == 0
    assert client.wait_ms == 0.0

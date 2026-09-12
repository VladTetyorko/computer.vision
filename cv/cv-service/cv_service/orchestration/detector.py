"""`DetectorClient` -- the one door to the inference gate.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1 rule 5 and §4.9. Only
`detect.*` contributors may touch the inference gate, and only through this
seam. That replaces the old "THE ONLY GATE ACQUISITION" count invariant,
which O1 contradiction 3 showed was already false (ROI rescue ships on, so
two passes per frame is the default). What survives, and is now checkable,
is the DIRECTION: a ledger with no `detect.*` entry must show zero gate
waits, and `tests/orchestration/test_gate_seam.py` greps this package to
prove no other module can acquire one.

**Two implementations.** `local` (W0) calls the `detect` callable the
servicer supplies -- the same callable, with the same `(detections,
inference_millis)` contract and the same `roi=` optional argument -- so it is
a seam, not a new mechanism. `pool` (W4) spends the same pass on the first
target of an ORDERED list that will take it, falling through to the next on
`RESOURCE_EXHAUSTED` or unavailability.

**The pool holds no wire knowledge.** Ordering, failover and accounting live
here, in a module that imports neither `grpc` nor `cv_pb2`; each target is a
`RemoteDetector`, whose one implementation (`grpc/detector_transport.py`)
owns the channel and translates gRPC status codes into the two outcomes this
module distinguishes -- `DetectorBusy` (it was up and said no) and
`DetectorUnavailable` (it did not answer). That split is the whole reason
this package stays stdlib-only and testable with no codegen.

**Order is the affinity mechanism** (§4.9): a healthy first target serves
every frame of every stream this process holds, so a stream's identities
never move. Nothing here balances -- balancing would spread one stream's
frames across instances, which costs nothing (a detector is stateless) but
buys nothing either, while making "which instance is saturated" unanswerable.

Structurally, `StreamTrackingSession` still holds no reference to an
`InferenceGate` and could not acquire one even by accident: the gate lives
inside `grpc/servicers.py`'s `_run_detector`, which is what the callable
handed in here closes over -- and, on a `detector`-role process, inside its
own servicer's `admit()`.
"""

from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter
from typing import Any, Callable, Optional, Protocol, Sequence, runtime_checkable

from cv_service.tracking.engines.base import Box

#: `(detections, inference_millis)`; `detections is None` means "no model
#: resolved at all -- echo this frame" (`session.DetectFn`'s own contract).
DetectFn = Callable[[Optional[Box]], "tuple[Optional[list], int]"]

#: The one target id that is not a `host:port`: this process's own detector.
#: Listing it LAST is an explicit "fall back to myself"; listing it alone is
#: what an unset `CV_DETECTOR_TARGETS` means.
LOCAL_TARGET = "local"


@dataclass(frozen=True)
class DetectorPass:
    """One detector pass's result, with what it cost to obtain.

    `wait_ms` is wall time spent inside the call -- which, on the local
    client, is queueing on `InferenceGate` plus the inference itself.
    `inference_millis` is the detector's OWN measurement, the number that
    reaches the wire; the two are reported separately rather than collapsed
    so a saturated gate is distinguishable from a slow model.
    """

    detections: "Optional[list]"
    inference_millis: int
    wait_ms: float
    roi: bool = False
    #: Which target produced this pass, and how many were tried before it
    #: answered. `local`/0 on the in-process client; a pooled pass that fell
    #: through two saturated instances reports the third's id and `hops=2`.
    served_by: str = LOCAL_TARGET
    hops: int = 0

    @property
    def unavailable(self) -> bool:
        """No model resolved -- the servicer's echo degradation."""
        return self.detections is None


@runtime_checkable
class DetectorClient(Protocol):
    """Where detections come from: `local` (in-process) or `pool` (targets)."""

    id: str

    def detect(self, roi: Optional[Box] = None) -> DetectorPass:
        ...


class DetectorBusy(RuntimeError):
    """A target was up and refused the pass -- its queue is at its bound.

    Distinct from `DetectorUnavailable` on purpose: this is the fleet budget
    working (§4.9, E10), not a fault. The pool moves on without marking the
    target unhealthy, because it just proved it is alive.
    """


class DetectorUnavailable(RuntimeError):
    """A target did not answer: unreachable, timed out, or failed internally."""


class NoDetectorAvailable(RuntimeError):
    """Every target in the ordered list refused or failed this pass.

    Raised, not returned: the orchestrator turns a raising contributor into a
    `FAILED` ledger row and the frame continues with `Key.DETECTIONS` absent
    (§4.1 rule 4). That is exactly the reported degradation §4.9 promises --
    an exhausted fleet is visible per frame, never a silent queue.
    """


@dataclass(frozen=True)
class FramePayload:
    """This frame's pixels and model, as a REMOTE detector needs them.

    The `Detector` wire's request minus the ROI, which varies per pass. Plain
    Python values only -- `grpc/detector_transport.py` is what turns this into
    a `DetectRequest`, so nothing in this package imports a generated type.
    """

    stream_id: str
    sequence: int
    width: int
    height: int
    #: `ImageEncoding`'s own value name, the spelling every other plain-value
    #: boundary in this service already uses.
    encoding: str
    data: bytes
    model_id: str = ""
    model_version: str = ""
    confidence_threshold: float = 0.0


@runtime_checkable
class FrameDetect(Protocol):
    """The servicer's per-frame detect callable, plus the payload a pool needs.

    `LocalDetectorClient` only ever calls it; `PoolDetectorClient` also reads
    `.payload`. Every pre-W4 test double is a bare `def detect()` and stays
    valid, because the local path never asks for the attribute.
    """

    payload: FramePayload

    def __call__(self, roi: Optional[Box] = None) -> "tuple[Optional[list], int]":
        ...


@runtime_checkable
class RemoteDetector(Protocol):
    """One entry of the pool: a detector instance reachable over the wire."""

    target: str

    def detect(self, payload: FramePayload, roi: Optional[Box]) -> DetectorPass:
        """Raise `DetectorBusy` on refusal, `DetectorUnavailable` on anything else."""


@dataclass
class TargetHealth:
    """What THIS process observed of one target -- never a probe.

    An unused target reads all-zero rather than claiming to be up, which is
    the honest answer for a fallback that has never been needed.
    """

    target: str
    served: int = 0
    refused: int = 0
    failed: int = 0
    last_error: str = ""


class LocalDetectorClient:
    """In-process detection through the servicer's own `detect` callable.

    Rebound per frame (`bind`) rather than constructed per frame: the
    callable closes over THIS frame's request, but the client itself is
    per-session state the ledger and `Inspect` can name.
    """

    id = "local"

    __slots__ = ("_detect", "_passes", "_wait_ms")

    def __init__(self) -> None:
        self._detect: Optional[DetectFn] = None
        self._passes = 0
        self._wait_ms = 0.0

    def bind(self, detect: DetectFn) -> None:
        """Adopt this frame's detect callable and reset the per-frame tally."""
        self._detect = detect
        self._passes = 0
        self._wait_ms = 0.0

    @property
    def passes(self) -> int:
        """Detector passes this frame -- 1 for a plain frame, 2 with ROI."""
        return self._passes

    @property
    def wait_ms(self) -> float:
        """Total wall time inside the detector this frame (gate + inference)."""
        return self._wait_ms

    def detect(self, roi: Optional[Box] = None) -> DetectorPass:
        detect = self._detect
        if detect is None:  # pragma: no cover - the session always binds first
            return DetectorPass(detections=None, inference_millis=0, wait_ms=0.0, roi=roi is not None)
        started = perf_counter()
        # Positional, and only when a region is actually asked for: every
        # pre-ROI harness `detect()` in this repo is written `def detect()`
        # with no parameter at all, and passing `roi=None` would break them.
        detections, inference_millis = detect(roi) if roi is not None else detect()
        wait_ms = (perf_counter() - started) * 1000.0
        self._passes += 1
        self._wait_ms += wait_ms
        return DetectorPass(
            detections=detections,
            inference_millis=inference_millis,
            wait_ms=wait_ms,
            roi=roi is not None,
        )


class PoolDetectorClient:
    """Detection from an ORDERED list of targets, first one that will take it.

    Per session, like `LocalDetectorClient`: it holds this frame's payload and
    the per-frame tally the ledger reads. The targets themselves are
    process-wide (one channel each), handed in at construction.

    The `local` entry is served by an embedded `LocalDetectorClient`, so a
    list like `gpu-box:50051,local` needs no branch above this class: falling
    back to in-process detection is one more target, not a second mode.
    """

    id = "pool"

    __slots__ = ("_targets", "_local", "_health", "_payload", "_passes", "_wait_ms", "_served_by")

    def __init__(self, targets: "Sequence[RemoteDetector]", *, local: "LocalDetectorClient") -> None:
        if not targets:
            raise ValueError("a pool needs at least one target; an empty list is `local`")
        self._targets = tuple(targets)
        self._local = local
        self._health = {target.target: TargetHealth(target.target) for target in self._targets}
        self._payload: Optional[FramePayload] = None
        self._passes = 0
        self._wait_ms = 0.0
        self._served_by = ""

    @property
    def targets(self) -> "tuple[str, ...]":
        return tuple(target.target for target in self._targets)

    @property
    def health(self) -> "tuple[TargetHealth, ...]":
        """Per-target counters, in the configured order. Read by `Inspect`."""
        return tuple(self._health[target.target] for target in self._targets)

    @property
    def passes(self) -> int:
        return self._passes

    @property
    def wait_ms(self) -> float:
        return self._wait_ms

    @property
    def served_by(self) -> str:
        """Which target answered last this frame; `""` before the first pass."""
        return self._served_by

    def bind(self, detect: "DetectFn | FrameDetect") -> None:
        """Adopt this frame's callable and payload, and reset the tally.

        The payload is read off the callable (`FrameDetect`) rather than
        threaded through `session.process()`: the session has no wire types
        and must not grow any, while the servicer that builds the callable
        already holds the request this payload is a copy of.
        """
        self._local.bind(detect)
        self._payload = getattr(detect, "payload", None)
        self._passes = 0
        self._wait_ms = 0.0
        self._served_by = ""

    def detect(self, roi: Optional[Box] = None) -> DetectorPass:
        """Spend one pass on the first target that takes it.

        Raises `NoDetectorAvailable` once every target has refused or failed:
        the frame then carries a `FAILED` `detect.full` row and no detections,
        which is the visible form of an exhausted fleet (§4.9).
        """
        started = perf_counter()
        errors: "list[str]" = []
        for hops, target in enumerate(self._targets):
            health = self._health[target.target]
            try:
                result = self._attempt(target, roi)
            except DetectorBusy as exc:
                health.refused += 1
                health.last_error = str(exc)
                errors.append(f"{target.target}: busy")
                continue
            except DetectorUnavailable as exc:
                health.failed += 1
                health.last_error = str(exc)
                errors.append(f"{target.target}: {exc}")
                continue
            health.served += 1
            health.last_error = ""
            wait_ms = (perf_counter() - started) * 1000.0
            self._passes += 1
            self._wait_ms += wait_ms
            self._served_by = target.target
            # `wait_ms` is measured across the WHOLE attempt sequence, not just
            # the winning hop: what the frame actually paid includes every
            # refusal it walked past, and hiding that would make a saturated
            # first target look free.
            return DetectorPass(
                detections=result.detections,
                inference_millis=result.inference_millis,
                wait_ms=wait_ms,
                roi=roi is not None,
                served_by=target.target,
                hops=hops,
            )
        self._wait_ms += (perf_counter() - started) * 1000.0
        raise NoDetectorAvailable(
            f"no detector took this pass ({len(self._targets)} targets): {'; '.join(errors)}"
        )

    def _attempt(self, target: "RemoteDetector", roi: Optional[Box]) -> DetectorPass:
        if target.target == LOCAL_TARGET:
            return self._local.detect(roi)
        payload = self._payload
        if payload is None:
            # A caller bound a bare callable with no payload. Reported as an
            # unavailable target rather than raised outright, so one
            # misconfigured frame cannot be worse than one unreachable box.
            raise DetectorUnavailable("this frame carries no wire payload")
        return target.detect(payload, roi)


def served_by_summary(result: DetectorPass) -> "dict[str, str]":
    """Which target answered, recorded only when it was not this process.

    Absent on the `local` path, so a single-process deployment's ledger rows
    are byte-identical to what W0 produced -- the pooled facts appear exactly
    when there is a pool to describe (CV-ORCHESTRATION W4, §4.9).
    """
    if result.served_by == LOCAL_TARGET and result.hops == 0:
        return {}
    return {"served_by": result.served_by, "hops": str(result.hops)}


def describe(client: Any) -> str:
    """`client.id` for anything that has one -- the ledger's own spelling."""
    return getattr(client, "id", "unknown")

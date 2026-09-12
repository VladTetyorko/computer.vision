"""`DetectorClient` -- the one door to the inference gate.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1 rule 5 and §4.9. Only
`detect.*` contributors may touch the inference gate, and only through this
seam. That replaces the old "THE ONLY GATE ACQUISITION" count invariant,
which O1 contradiction 3 showed was already false (ROI rescue ships on, so
two passes per frame is the default). What survives, and is now checkable,
is the DIRECTION: a ledger with no `detect.*` entry must show zero gate
waits, and `tests/orchestration/test_gate_seam.py` greps this package to
prove no other module can acquire one.

**`local` is the only implementation in W0.** It calls the `detect`
callable the servicer already supplies -- the same callable, with the same
`(detections, inference_millis)` contract and the same `roi=` optional
argument -- so this class is a seam, not a new mechanism. `pool(targets)`,
the cross-process detector role, is W4 and lands behind this same interface
with no change above it.

Structurally, `StreamTrackingSession` still holds no reference to an
`InferenceGate` and could not acquire one even by accident: the gate lives
inside `grpc/servicers.py`'s `_run_detector`, which is what the callable
handed in here closes over.
"""

from __future__ import annotations

from dataclasses import dataclass
from time import perf_counter
from typing import Any, Callable, Optional, Protocol, runtime_checkable

from cv_service.tracking.engines.base import Box

#: `(detections, inference_millis)`; `detections is None` means "no model
#: resolved at all -- echo this frame" (`session.DetectFn`'s own contract).
DetectFn = Callable[[Optional[Box]], "tuple[Optional[list], int]"]


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

    @property
    def unavailable(self) -> bool:
        """No model resolved -- the servicer's echo degradation."""
        return self.detections is None


@runtime_checkable
class DetectorClient(Protocol):
    """Where detections come from. `local` today, `pool(targets)` in W4."""

    id: str

    def detect(self, roi: Optional[Box] = None) -> DetectorPass:
        ...


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


def describe(client: Any) -> str:
    """`client.id` for anything that has one -- the ledger's own spelling."""
    return getattr(client, "id", "unknown")

"""The capability ladder: one pipeline, five affordability tiers.

`docs/plans/active/TRACKING-V3-PLAN.md` §5 (this module *is* that section) and wave V1
(§6). L1 RELAY .. L5 STUDY (§5.2) are not five builds -- they are one package, deciding
per stream how much of itself it may actually run.

**The two rules this module exists to enforce.**

* **A level is a CEILING, not a demand** (decision E12). A stream that asks for more than
  a host affords is served `min(requested, affordable)`, never refused and never raised
  (invariant P5) -- `resolve()` below is the whole of that arithmetic.
* **A level changes who computes, never what is computed** (invariant P9). This module
  never touches a pixel, a box or a wire message; it only answers "how much of the
  pipeline may run here", so every level shares the identical wire contract and the
  identical model artefacts (TRACKING-V3-PLAN §5, "one pipeline").

**`probe()` costs nothing close to what it is protecting.** Detecting whether `numpy` is
installed by trying `import numpy` would itself cost the ~16 MiB `numpy` import costs
(§5.1) -- exactly the weight L1 exists to avoid paying on a 13 MiB companion computer.
`importlib.util.find_spec` answers "is this importable" by walking `sys.path`/package
metadata alone, without executing the module, which is why every check below uses it
and this module imports no `cv2`/`numpy`/`ultralytics`/`torch`/`lap` itself, at module
scope or inside any function (invariant P8, `docs/plans/active/TRACKING-V3-PLAN.md`
wave V1's own acceptance).

**Why `probe()` never returns L5 on its own.** L4 (IDENTIFY) has a distinct, checkable
technical signal beyond L3's `ultralytics`/`torch`/`cv2`/`numpy` plus the L3 memory
floor: an importable `openvino`. L5 (STUDY) -- `tools/trackeval`, capture, training,
promotion -- adds no NEW importable dependency (that tooling ships as part of this very
package) and its own resource profile is not measured anywhere in §5.1; inventing a
threshold for it would be exactly the kind of unmeasured guess decision E9 warns against
("reproduce direction, not magnitude ... leaderboard deltas are hypotheses"). So L5 is
reachable only by an EXPLICIT `capability_level=5` request, capped by whatever L1-L4
`probe()` actually found (typically L4) -- the same "measured floor, deliberate ceiling"
shape `vision.training.enabled` already uses on the Java side for this identical
feature. Recorded here because it is a judgment call the plan's own §5 leaves open, not
because the code needs the reasoning to run.
"""

from __future__ import annotations

import importlib.util
import os
from typing import Optional

LEVEL_L1 = 1  # RELAY    -- predict/assign/history/reupdate/memory/pose_gmc, pure stdlib
LEVEL_L2 = 2  # FILL     -- + cv2: ncc/lk SOT, flow_gmc, histogram appearance
LEVEL_L3 = 3  # DETECT   -- + ultralytics: local duty-cycled YOLO, bytetrack selectable
LEVEL_L4 = 4  # IDENTIFY -- + ROI-pool, optional OpenVINO re-ID, full identity tier
LEVEL_L5 = 5  # STUDY    -- + trackeval, capture, training, promotion (workstation only)

MIN_LEVEL = LEVEL_L1
MAX_LEVEL = LEVEL_L5

# The highest level `probe()` ever returns on its own -- see the module docstring's
# "why probe() never returns L5" for the reasoning. `resolve()` still honors an
# EXPLICIT request for L5 up to whatever this actually found.
_MAX_AUTO_PROBED_LEVEL = LEVEL_L4

LEVEL_NAMES: "dict[int, str]" = {
    LEVEL_L1: "RELAY",
    LEVEL_L2: "FILL",
    LEVEL_L3: "DETECT",
    LEVEL_L4: "IDENTIFY",
    LEVEL_L5: "STUDY",
}

# The RAM floor `probe()` requires before it will call L3 affordable. `YoloDetector`
# measures 347 MiB resident (TRACKING-V3-PLAN §5.1) -- this is deliberately higher than
# that bare number, so a host with just barely enough for the detector alone and
# nothing left for the gRPC server, the other streams' state, or the OS is correctly
# reported as NOT affording L3, rather than probing green and then failing under real
# load. Not a `CV_TRACK_*` knob (P4's exemption for "an engine's own named constants" --
# this module is deliberately dependency-free of `config.py`, see the module
# docstring): an operator who wants a specific ceiling regardless of measured headroom
# already has one, via `CV_TRACK_CAPABILITY_LEVEL`/`TrackingConfig.capability_level`
# itself, which is a hard override, not a hint to this heuristic.
_L3_MIN_AVAILABLE_MEMORY_MIB = 512.0


def name(level: int) -> str:
    """`LEVEL_NAMES[level]`, or `"L<n>"` for a level number this build does not
    know -- never raises, matching every other forgiving lookup in this
    package."""
    return LEVEL_NAMES.get(level, f"L{level}")


def _clamp(level: int) -> int:
    return max(MIN_LEVEL, min(MAX_LEVEL, level))


def _importable(module_name: str) -> bool:
    """Whether `module_name` COULD be imported, without importing it.

    `find_spec` walks `sys.path`/package metadata only -- it does not execute
    the module's own code, so it cannot trigger `numpy`/`cv2`'s own (much
    heavier) import machinery. Never raises: a malformed package on
    `sys.path`, or a dotted name whose parent isn't itself a package, both
    report "not importable" rather than crashing the probe (P5's "never
    raise" posture, applied to host discovery instead of a per-frame path).
    """
    try:
        return importlib.util.find_spec(module_name) is not None
    except (ImportError, ValueError, AttributeError):
        return False


def _available_memory_mib() -> Optional[float]:
    """Best-effort available RAM, in MiB. `None` when it cannot be determined
    on this platform -- callers then skip the memory gate rather than
    guessing, since a wrong "not enough RAM" verdict would wrongly deny a
    host L3 for no real reason.

    Tries `/proc/meminfo`'s `MemAvailable` first (Linux -- the number the
    kernel itself already computes as "usable without swapping", which is
    the right question here, not raw free bytes). Falls back to
    `os.sysconf`'s physical-page counters (POSIX, no `/proc`) for other
    Unix-likes. Neither path imports anything beyond `os`.
    """
    try:
        with open("/proc/meminfo", "r", encoding="ascii") as handle:
            for line in handle:
                if line.startswith("MemAvailable:"):
                    kib = int(line.split()[1])
                    return kib / 1024.0
    except (OSError, ValueError, IndexError):
        pass

    try:
        pages = os.sysconf("SC_AVPHYS_PAGES")
        page_size = os.sysconf("SC_PAGE_SIZE")
    except (ValueError, OSError, AttributeError):
        return None
    if pages < 0 or page_size < 0:
        # `os.sysconf` documents a platform that doesn't know the answer as
        # returning -1, not raising -- treated the same as "cannot determine".
        return None
    return (pages * page_size) / (1024.0 * 1024.0)


def probe() -> int:
    """The highest capability level (§5.2) this host can actually afford
    right now.

    Purely a READ of the host's own state (importability + available
    memory) -- no caching, no side effects, so a caller that re-probes on
    every config change (`session.py`'s "hot" requirement) sees a busy
    host's headroom shrink in real time rather than a value frozen at
    process start. The cost of asking is a handful of `find_spec` calls plus
    one `/proc/meminfo` read -- negligible next to a per-stream config
    change, let alone a per-frame one (this is never called per frame).
    """
    if not (_importable("cv2") and _importable("numpy")):
        return LEVEL_L1
    if not (_importable("ultralytics") and _importable("torch")):
        return LEVEL_L2
    available = _available_memory_mib()
    if available is not None and available < _L3_MIN_AVAILABLE_MEMORY_MIB:
        return LEVEL_L2
    if not _importable("openvino"):
        return LEVEL_L3
    return min(LEVEL_L4, _MAX_AUTO_PROBED_LEVEL)


def resolve(requested: int, probed: int) -> "tuple[int, str]":
    """`(served_level, reason)` -- decision E12's whole ceiling arithmetic.

    `requested <= 0` (the proto3 zero Java always sends, `params.py`'s same
    sentinel convention as every other `TrackingConfig` field) means
    auto-probe: served IS `probed`, by definition never capped, so `reason`
    is always `""`. A positive `requested` is a CEILING on top of whatever
    the host affords: `served = min(requested, probed)`, and `reason` is
    `""` again exactly when nothing was actually capped (`served ==
    requested`) -- the caller's own log-once bookkeeping keys off whether
    this string is non-empty, matching every other degradation in this
    package (P5).

    Never raises and never returns a level outside `[MIN_LEVEL, MAX_LEVEL]`
    -- an out-of-range `requested` (a future client's higher level, or plain
    garbage) is clamped first, so the reported reason always names a level
    this build understands.
    """
    probed = _clamp(probed)
    if requested <= 0:
        return probed, ""
    requested = _clamp(requested)
    served = min(requested, probed)
    if served == requested:
        return served, ""
    return served, (
        f"requested L{requested} ({name(requested)}) but this host affords at most "
        f"L{probed} ({name(probed)})"
    )

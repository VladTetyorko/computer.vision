"""Centralized environment configuration for cv-service.

``Settings.from_env()`` is the **one place** every ``CV_*`` environment
variable is read and parsed. Before this module existed, five different
variables were read piecemeal across ``server.py``/``inference.py``/
``trainer.py``/``concurrency.py`` -- three of them (``CV_DEVICE``,
``CV_MAX_CONCURRENT_INFERENCES``, ``CV_DATASET_DIR``) resolved once at
*import* time, so a value baked into a module-level constant could never be
changed without a process restart even though nothing else about the code
required that; the other two (``CV_MODEL``, ``CV_IMGSZ``) had their forgiving
"unset/garbage -> default" parsing logic duplicated in more than one module.

The fix: resolve a :class:`Settings` once at process startup (see
``cv_service/grpc/server.py``'s ``main()``/``serve()``) and pass it down
explicitly. Nothing downstream reads ``os.environ`` directly anymore --
``YoloDetector``/``ultralytics_train``/etc. that still support being
constructed with a value omitted call :meth:`Settings.from_env` themselves
(which re-parses fresh every call, exactly matching the old per-construction
-refresh behavior for ``CV_MODEL``/``CV_IMGSZ``), so the *parsing logic*
still lives in exactly one place even though it may be invoked from more than
one call site.

Frozen ``@dataclass``, matching this codebase's existing idiom (``Detection``,
``TrainingSpec``, ``EpochProgress`` are the other three frozen dataclasses in
cv-service).
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

LOGGER = logging.getLogger("cv_service.config")

# cv-service/ checkout directory -- this file's grandparent
# (cv_service/config.py -> cv_service/ -> cv-service/). Same directory
# `server._MODEL_SEARCH_DIR` used to compute from cv_service/server.py, and
# where the Dockerfile's WORKDIR puts things too -- unchanged by this move,
# both files sit at the same depth under cv-service/.
_BASE_DIR = Path(__file__).resolve().parent.parent

# --- defaults (byte-identical to the literals they replace) -----------------

DEFAULT_PORT = 50051
DEFAULT_MODEL = "yolo26n.pt"
DEFAULT_IMGSZ = 416
DEFAULT_MAX_UPLOAD_BYTES = 2 * 1024 * 1024 * 1024  # 2 GiB
DEFAULT_GRPC_WORKERS = 10
DEFAULT_SHUTDOWN_GRACE_SECONDS = 5
_DATASET_DIRNAME = "datasets"

# --- tracking (docs/plans/done/TRACKING-PLAN.md §4.A / TRACKING-ORCHESTRATION §4.3) ----
#
# These back the wire's `<=0 = server default` sentinels on
# `TrackingConfig` -- `cv_service/tracking/params.py`'s `resolve()` is their
# ONLY consumer, and this module stays the only place they are read from the
# environment (the one-place rule, see this module's docstring). The values
# are byte-identical to the defaults named in TRACKING-PLAN §4.A, with one
# deliberate exception recorded at the constant itself:
# DEFAULT_TRACK_ASSOCIATE_ENGINE, which TRACKING-V2 wave C3 flipped.
# `cost` rather than `bytetrack` since TRACKING-V2 wave C3, on measurement
# rather than preference (the evidence is in MODULE.md's "Association
# inversion" section): better on `crossing`, `occlusion` and `pan_step`,
# equal on `linear`, `dropout`, `pan` and `clutter`, worse on nothing. The
# one risk `cost` carries that `bytetrack` does not is a crowd -- a solver
# minimising TOTAL cost can buy a cheap overall assignment out of
# individually absurd pairs -- so `clutter` exists specifically to test that,
# and both engines score IDSW 0 with every object mostly-tracked.
# `bytetrack` remains fully supported and one env var away.
DEFAULT_TRACK_ASSOCIATE_ENGINE = "cost"
DEFAULT_TRACK_FOLLOW_ENGINE = "lk"
DEFAULT_TRACK_VERIFY_MILLIS = 2000
DEFAULT_TRACK_IOU = 0.3
DEFAULT_TRACK_MAX_AGE_FRAMES = 30
DEFAULT_TRACK_MIN_HITS = 3
# TRACKING-V2-PLAN wave C1 (review finding B7): `max_age_frames` means
# "consecutive failed verify passes", which is ~3s of real time in ASSOCIATE
# at the documented 10 fps sample rate but could be a full minute in FOLLOW,
# where a verify pass only happens on cadence. 3000ms = 30 frames / 10 fps is
# chosen specifically so this new wall-clock backstop is BEHAVIOUR-PRESERVING
# for ASSOCIATE at that rate (see `track.py`'s `_settle`) while giving FOLLOW
# the same real-world meaning instead of the frame-count's inflated one.
DEFAULT_TRACK_MAX_AGE_MILLIS = 3000
# TRACKING-V2-PLAN wave C1 (review finding C2): below this, a `SingleObject
# Tracker`'s own reported confidence (LK's surviving-corner fraction, NCC's
# match score) is treated as an early warning that the target may be
# drifting, bringing the next verify pass forward (scheduler trigger (b))
# instead of waiting for the tracker to fail outright. Conservative by
# construction: a track that has lost half its corners or dropped to a weak
# correlation is worth double-checking, not yet worth declaring lost.
DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE = 0.5
# TRACKING-V2-PLAN wave C2: which `MotionCompensator` a stream gets when its
# `TrackingConfig.motion_engine_id` is blank (proto field 8, frozen at C0).
# `flow` -- pixel-based, needs no telemetry -- is the default because it
# works on every client today; `pose` only activates once a client actually
# populates `camera_pose` (TRACKING-V2-PLAN §2.1's Java one-liner, not yet
# built). `"off"` is a legitimate value here too, disabling compensation
# fleet-wide without touching a single stream's request.
DEFAULT_TRACK_MOTION_ENGINE = "flow"

# TRACKING-V2-PLAN wave C3: which `AppearanceExtractor` a stream gets when
# its `TrackingConfig.appearance_engine_id` is blank (proto field 9, frozen
# at C0). Measured (see MODULE.md "Wave C3" for the harness table): `cost`
# is NOT the `CV_TRACK_ASSOCIATE_ENGINE` default -- `bytetrack` never
# resolves an appearance extractor at all (it has no descriptor input to
# feed, see `session.py`'s `_run_cost_associate`) -- so this default only
# matters to an operator who has already opted into `CV_TRACK_ASSOCIATE_
# ENGINE=cost`. `"off"` disables appearance evidence outright, same shape as
# `DEFAULT_TRACK_MOTION_ENGINE`'s "off".
DEFAULT_TRACK_APPEARANCE_ENGINE = "histogram"

# TRACKING-V2-PLAN wave C3: `assign.CostAssociator`'s cost weights and gates
# (`AssignWeights`/`AssignGates`, `assign.py`), resolved straight from
# `Settings` -- like `track_max_age_millis`/`min_tracker_confidence` before
# them, TRACKING-V2-PLAN §2's frozen wire diff adds no per-request field for
# any of these, so there is nothing to fall back FROM. `resolve()` builds the
# two frozen dataclasses directly (`params.py`'s "the only place a sentinel
# becomes a number", extended here to "and the only place these become an
# `AssignWeights`/`AssignGates`").
#
# `appearance` defaults NON-zero so that opting into `cost` PLUS the default
# `histogram` extractor actually does something -- `session.py`'s
# `_run_cost_associate` is what zeroes this back to 0.0 for a stream where no
# appearance extractor resolved (`"off"`, or nothing constructible), so a
# `cost` stream with no appearance evidence still behaves as pure geometry
# rather than geometry-plus-a-constant (see `assign.py`'s own
# `test_appearance_is_ignored_entirely_when_it_is_not_weighted`).
DEFAULT_TRACK_COST_WEIGHT_IOU = 1.0
DEFAULT_TRACK_COST_WEIGHT_APPEARANCE = 0.5
DEFAULT_TRACK_COST_WEIGHT_LABEL = 0.0
# `min_iou=0.0`: no geometric gate by default -- `max_cost` and, once an
# appearance engine is active, `max_appearance` are what bound a match; a
# strict `min_iou` would forbid exactly the wide-displacement case ego-motion
# compensation exists to recover (a warped candidate whose IoU with the true
# box is still imperfect right after a stall).
DEFAULT_TRACK_COST_GATE_MIN_IOU = 0.0
# Above this Hellinger distance ([0, 1], 1 = no match) an appearance-weighted
# pair is forbidden outright rather than merely penalised -- tuned so two
# genuinely different objects (e.g. `crossing`'s red/green pair) gate out
# even at moderate geometric ambiguity, while a colour shift from motion blur
# or exposure does not.
DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE = 0.6
# No cap beyond the two gates above -- `AssignGates`' own default (`assign.
# FORBIDDEN`, i.e. "never accept purely on cost, gates decide").
DEFAULT_TRACK_COST_GATE_MAX_COST = float("inf")
# The high/low confidence split for `CostAssociator`'s two-stage match
# (`assign.py`'s own docstring: "high-confidence targets first, then low").
# Matches `engines/bytetrack.py`'s own `_TRACK_HIGH_THRESH` so `cost` and
# `bytetrack` draw the same low-confidence line even though each engine
# tunes it as its own separate constant.
DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE = 0.25

_ENV_MAX_CONCURRENT_INFERENCES = "CV_MAX_CONCURRENT_INFERENCES"


def _default_max_concurrent_inferences() -> int:
    """``min(2, cpu_count // 2)``, floored at 1.

    Two, not `cpu_count`, is deliberate: a single Ultralytics/PyTorch
    `detect()` call already spreads itself across several intra-op threads
    on its own -- verified in this task's dev environment,
    `torch.get_num_threads()` reported `6` on a 12-logical-core box, entirely
    PyTorch's own default heuristic, nothing this module configures. So a
    handful of *concurrent* `detect()` calls can already contend for every
    core; `cpu_count // 2` keeps total demand (concurrent calls x each
    call's own intra-op threads) in the right ballpark relative to the
    machine instead of naively scaling the gate with core count, and it's
    capped at 2 because the realistic demo load (1-3 simultaneous streams)
    never needs more anyway (see MODULE.md for the honest OpenVINO caveat).
    Floored at 1 so a 1-2 logical-core box still runs (serially, which is the
    correct degradation) instead of constructing an invalid `Semaphore(0)`.
    """
    cpu = os.cpu_count() or 4
    return max(1, min(2, cpu // 2))


def _resolve_max_concurrent_inferences(raw: Optional[str]) -> int:
    default = _default_max_concurrent_inferences()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning(
            "%s=%r is not a valid integer; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    if value <= 0:
        LOGGER.warning(
            "%s=%r must be positive; using default %d",
            _ENV_MAX_CONCURRENT_INFERENCES,
            raw,
            default,
        )
        return default
    return value


def _parse_imgsz(raw: Optional[str], default: int = DEFAULT_IMGSZ) -> int:
    """Parse the `CV_IMGSZ` env var into a positive int, default on garbage.

    Inference size is a performance knob, not something a missing/malformed
    env var should be able to crash the service over -- unset, non-numeric,
    or non-positive values all silently fall back to `default`.
    """
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("CV_IMGSZ=%r is not a valid integer; using default %d", raw, default)
        return default
    if value <= 0:
        LOGGER.warning("CV_IMGSZ=%r must be positive; using default %d", raw, default)
        return default
    return value


def _parse_device(raw: Optional[str]) -> Optional[str]:
    """Parse the `CV_DEVICE` env var: unset/blank -> None ("ultralytics auto").

    Any non-blank value (`"cpu"`, `"cuda"`, `"cuda:0"`, `"0"`, ...) is passed
    through as-is -- ultralytics/torch own validating device strings, this
    function doesn't enumerate or second-guess them. Whitespace is stripped
    so a stray env-file trailing space doesn't turn into a bogus device
    string.
    """
    if raw is None:
        return None
    stripped = raw.strip()
    return stripped or None


def _parse_positive_int(raw: Optional[str], default: int, var_name: str) -> int:
    """Generic forgiving-parse for the newer, simpler int knobs (port,
    worker/grace/upload-cap counts) -- same idiom as `_parse_imgsz`/
    `_resolve_max_concurrent_inferences` above, just not tied to one
    specific env var name."""
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid integer; using default %d", var_name, raw, default)
        return default
    if value <= 0:
        LOGGER.warning("%s=%r must be positive; using default %d", var_name, raw, default)
        return default
    return value


def _parse_unit_fraction(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for a normalized `(0, 1]` threshold knob (`CV_TRACK_IOU`).

    Same "unset/garbage -> default, never raise" contract as the int helpers
    above. Deliberately rejects values outside `(0, 1]` with a warning rather
    than clamping: the single most likely mistake is writing the *percent*
    the REST/Java layer uses (`30`) into the *fraction* this layer wants
    (`0.3`), and silently clamping `30` to `1.0` would turn a typo into a
    tracker that never re-anchors, which is far harder to diagnose than a
    logged fallback to the documented default.
    """
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if not 0.0 < value <= 1.0:
        LOGGER.warning(
            "%s=%r must be a fraction in (0, 1] (0.3, not 30); using default %s",
            var_name,
            raw,
            default,
        )
        return default
    return value


def _parse_unit_interval(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for a `[0, 1]` gate/threshold knob where 0 is a
    legitimate value (TRACKING-V2-PLAN wave C3's cost gates).

    Deliberately NOT `_parse_unit_fraction`: that helper rejects `0.0`
    because it backs `CV_TRACK_IOU`'s FOLLOW re-anchor threshold, where a
    literal zero would silently mean "match anything" -- a functional bug.
    A cost gate's `0.0` means "this gate is off", which is a normal,
    intended configuration, not a typo to guard against.
    """
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if not 0.0 <= value <= 1.0:
        LOGGER.warning(
            "%s=%r must be a fraction in [0, 1]; using default %s", var_name, raw, default
        )
        return default
    return value


def _parse_nonnegative_float(raw: Optional[str], default: float, var_name: str) -> float:
    """Forgiving-parse for an unbounded `>= 0` knob (a cost weight, or a cost
    cap that may legitimately be very large or infinite)."""
    if not raw:
        return default
    try:
        value = float(raw)
    except ValueError:
        LOGGER.warning("%s=%r is not a valid number; using default %s", var_name, raw, default)
        return default
    if value < 0.0:
        LOGGER.warning("%s=%r must not be negative; using default %s", var_name, raw, default)
        return default
    return value


def _parse_engine_id(raw: Optional[str], default: str) -> str:
    """Unset/blank -> `default`; anything else passes through stripped.

    Deliberately does NOT validate against the engine roster: the roster is
    discovered at startup by `cv_service.tracking.registry.TrackerRegistry`
    (which probes what is actually constructible on this box), and an
    unknown id there is already a log-once-and-fall-back-to-the-default
    outcome -- exactly the posture `ModelRegistry` takes for an unknown
    `model_id`. Validating here would duplicate that in a second place and
    make a typo a startup crash instead of a degradation.
    """
    if raw is None:
        return default
    stripped = raw.strip()
    return stripped or default


@dataclass(frozen=True)
class Settings:
    """Every ``CV_*``-configurable knob cv-service has, resolved once.

    Construct via :meth:`from_env` at process startup (``grpc/server.py``'s
    ``main()``); everything downstream takes the resolved value as an
    explicit argument instead of reading the environment itself.
    """

    port: int = DEFAULT_PORT
    model: str = DEFAULT_MODEL
    imgsz: int = DEFAULT_IMGSZ
    device: Optional[str] = None
    max_concurrent_inferences: int = field(default_factory=_default_max_concurrent_inferences)
    dataset_dir: Path = field(default_factory=lambda: _BASE_DIR / _DATASET_DIRNAME)
    model_dir: Path = field(default_factory=lambda: _BASE_DIR)
    max_upload_bytes: int = DEFAULT_MAX_UPLOAD_BYTES
    grpc_workers: int = DEFAULT_GRPC_WORKERS
    shutdown_grace_seconds: int = DEFAULT_SHUTDOWN_GRACE_SECONDS
    track_associate_engine: str = DEFAULT_TRACK_ASSOCIATE_ENGINE
    track_follow_engine: str = DEFAULT_TRACK_FOLLOW_ENGINE
    track_verify_millis: int = DEFAULT_TRACK_VERIFY_MILLIS
    track_iou: float = DEFAULT_TRACK_IOU
    track_max_age_frames: int = DEFAULT_TRACK_MAX_AGE_FRAMES
    track_min_hits: int = DEFAULT_TRACK_MIN_HITS
    track_max_age_millis: int = DEFAULT_TRACK_MAX_AGE_MILLIS
    track_min_tracker_confidence: float = DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE
    track_motion_engine: str = DEFAULT_TRACK_MOTION_ENGINE
    track_appearance_engine: str = DEFAULT_TRACK_APPEARANCE_ENGINE
    track_cost_weight_iou: float = DEFAULT_TRACK_COST_WEIGHT_IOU
    track_cost_weight_appearance: float = DEFAULT_TRACK_COST_WEIGHT_APPEARANCE
    track_cost_weight_label: float = DEFAULT_TRACK_COST_WEIGHT_LABEL
    track_cost_gate_min_iou: float = DEFAULT_TRACK_COST_GATE_MIN_IOU
    track_cost_gate_max_appearance: float = DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE
    track_cost_gate_max_cost: float = DEFAULT_TRACK_COST_GATE_MAX_COST
    track_cost_gate_high_confidence: float = DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE

    @classmethod
    def from_env(cls) -> "Settings":
        """Read every ``CV_*`` var from ``os.environ`` fresh and resolve one
        :class:`Settings`. This is the ONLY function in cv-service that reads
        ``os.environ`` -- everything else takes a `Settings` (or one of its
        already-resolved fields) as a plain argument.
        """
        return cls(
            port=_parse_positive_int(os.environ.get("CV_PORT"), DEFAULT_PORT, "CV_PORT"),
            model=os.environ.get("CV_MODEL", DEFAULT_MODEL),
            imgsz=_parse_imgsz(os.environ.get("CV_IMGSZ")),
            device=_parse_device(os.environ.get("CV_DEVICE")),
            max_concurrent_inferences=_resolve_max_concurrent_inferences(
                os.environ.get(_ENV_MAX_CONCURRENT_INFERENCES)
            ),
            dataset_dir=Path(
                os.environ.get("CV_DATASET_DIR", str(_BASE_DIR / _DATASET_DIRNAME))
            ),
            model_dir=Path(os.environ.get("CV_MODEL_DIR", str(_BASE_DIR))),
            max_upload_bytes=_parse_positive_int(
                os.environ.get("CV_MAX_UPLOAD_BYTES"), DEFAULT_MAX_UPLOAD_BYTES, "CV_MAX_UPLOAD_BYTES"
            ),
            grpc_workers=_parse_positive_int(
                os.environ.get("CV_GRPC_WORKERS"), DEFAULT_GRPC_WORKERS, "CV_GRPC_WORKERS"
            ),
            shutdown_grace_seconds=_parse_positive_int(
                os.environ.get("CV_SHUTDOWN_GRACE"),
                DEFAULT_SHUTDOWN_GRACE_SECONDS,
                "CV_SHUTDOWN_GRACE",
            ),
            track_associate_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_ASSOCIATE_ENGINE"), DEFAULT_TRACK_ASSOCIATE_ENGINE
            ),
            track_follow_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_FOLLOW_ENGINE"), DEFAULT_TRACK_FOLLOW_ENGINE
            ),
            track_verify_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_VERIFY_MS"),
                DEFAULT_TRACK_VERIFY_MILLIS,
                "CV_TRACK_VERIFY_MS",
            ),
            track_iou=_parse_unit_fraction(
                os.environ.get("CV_TRACK_IOU"), DEFAULT_TRACK_IOU, "CV_TRACK_IOU"
            ),
            track_max_age_frames=_parse_positive_int(
                os.environ.get("CV_TRACK_MAX_AGE"),
                DEFAULT_TRACK_MAX_AGE_FRAMES,
                "CV_TRACK_MAX_AGE",
            ),
            track_min_hits=_parse_positive_int(
                os.environ.get("CV_TRACK_MIN_HITS"), DEFAULT_TRACK_MIN_HITS, "CV_TRACK_MIN_HITS"
            ),
            track_max_age_millis=_parse_positive_int(
                os.environ.get("CV_TRACK_MAX_AGE_MILLIS"),
                DEFAULT_TRACK_MAX_AGE_MILLIS,
                "CV_TRACK_MAX_AGE_MILLIS",
            ),
            track_min_tracker_confidence=_parse_unit_fraction(
                os.environ.get("CV_TRACK_MIN_TRACKER_CONFIDENCE"),
                DEFAULT_TRACK_MIN_TRACKER_CONFIDENCE,
                "CV_TRACK_MIN_TRACKER_CONFIDENCE",
            ),
            track_motion_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_MOTION_ENGINE"), DEFAULT_TRACK_MOTION_ENGINE
            ),
            track_appearance_engine=_parse_engine_id(
                os.environ.get("CV_TRACK_APPEARANCE_ENGINE"), DEFAULT_TRACK_APPEARANCE_ENGINE
            ),
            track_cost_weight_iou=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_IOU"),
                DEFAULT_TRACK_COST_WEIGHT_IOU,
                "CV_TRACK_COST_WEIGHT_IOU",
            ),
            track_cost_weight_appearance=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_APPEARANCE"),
                DEFAULT_TRACK_COST_WEIGHT_APPEARANCE,
                "CV_TRACK_COST_WEIGHT_APPEARANCE",
            ),
            track_cost_weight_label=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_WEIGHT_LABEL"),
                DEFAULT_TRACK_COST_WEIGHT_LABEL,
                "CV_TRACK_COST_WEIGHT_LABEL",
            ),
            track_cost_gate_min_iou=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_MIN_IOU"),
                DEFAULT_TRACK_COST_GATE_MIN_IOU,
                "CV_TRACK_COST_GATE_MIN_IOU",
            ),
            track_cost_gate_max_appearance=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_MAX_APPEARANCE"),
                DEFAULT_TRACK_COST_GATE_MAX_APPEARANCE,
                "CV_TRACK_COST_GATE_MAX_APPEARANCE",
            ),
            track_cost_gate_max_cost=_parse_nonnegative_float(
                os.environ.get("CV_TRACK_COST_GATE_MAX_COST"),
                DEFAULT_TRACK_COST_GATE_MAX_COST,
                "CV_TRACK_COST_GATE_MAX_COST",
            ),
            track_cost_gate_high_confidence=_parse_unit_interval(
                os.environ.get("CV_TRACK_COST_GATE_HIGH_CONFIDENCE"),
                DEFAULT_TRACK_COST_GATE_HIGH_CONFIDENCE,
                "CV_TRACK_COST_GATE_HIGH_CONFIDENCE",
            ),
        )

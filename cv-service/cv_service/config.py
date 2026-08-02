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
        )

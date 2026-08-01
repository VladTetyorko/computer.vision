"""Dataset fine-tuning for the ``Training.StartTraining`` RPC.

Runs a real Ultralytics YOLO fine-tune on an exported YOLO dataset and
produces a new ``.pt`` artifact the ``Training.ListModels``/``PromoteModel``
loop can then surface and promote.

**Import discipline.** This module is stdlib-only at import time -- it never
imports ``ultralytics``/``torch``/``cv2`` at module scope, exactly like
``server.py``'s top-level imports. The heavyweight ``ultralytics`` import is
lazy, inside :func:`ultralytics_train` (the real training path), so
``cv_service.server`` -- and hence ``python -m cv_service.server`` and the
whole test suite's servicer tests -- stays importable without the ``cv``
extra installed. ``server.py`` injects :func:`ultralytics_train` as the
default trainer; tests inject a fake ``train_fn`` and never touch ultralytics.

**Dataset delivery (rsync-consistent with models).** Training consumes a
dataset that was exported by the platform (Phase 1's ``FilesystemDatasetExport``
layout) and copied onto the training host -- the same offline rsync operating
model the model artifacts use (MEMORY: gb4005-inference-box). A dataset lives
at ``<datasets-root>/<dataset_id>/`` and must contain ``data.yaml`` +
``images/`` + ``labels/``. The datasets root is configured by ``CV_DATASET_DIR``
(default ``<cv-service>/datasets``); see :data:`DATASET_DIRNAME` /
``server._DATASET_SEARCH_DIR``.

**Device-agnostic.** Training never hard-pins the device: it passes
``device=CV_DEVICE`` when set, else ``None`` so Ultralytics auto-selects CUDA
if present and CPU otherwise. The production deploy host is Intel-only / no
CUDA, so a one-time "training on CPU -- this is slow" warning is logged when
no GPU is available (see :func:`_warn_cpu_once`).
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

LOGGER = logging.getLogger("cv_service.trainer")

# Default datasets-root directory name (under the cv-service checkout dir),
# used when CV_DATASET_DIR is unset -- see server._DATASET_SEARCH_DIR.
DATASET_DIRNAME = "datasets"

# The YOLO dataset manifest + subdirectories a dataset dir must contain to be
# trainable (exactly Phase 1's FilesystemDatasetExport layout).
DATA_YAML_NAME = "data.yaml"
IMAGES_DIRNAME = "images"
LABELS_DIRNAME = "labels"

# Fallback when TrainingJobSpec.epochs is unset/<=0 (proto3 int32 default 0).
DEFAULT_EPOCHS = 50

# Fallback base model when TrainingJobSpec.base_model is empty. Kept as a bare
# string (not imported from inference.py, which needs the `cv` extra) so this
# module stays importable without it; the same yolo26n default the inference
# path uses.
DEFAULT_BASE_MODEL = "yolo26n.pt"

# Ultralytics writes each run under <output_dir>/<run_name>/weights/best.pt.
_RUNS_PROJECT_DIRNAME = "runs"

# Has the one-time CPU-slow warning already been logged this process?
_cpu_warned = False


@dataclass(frozen=True)
class TrainingSpec:
    """Everything :func:`ultralytics_train` needs for one fine-tune run."""

    job_id: str
    base_model: str
    dataset_id: str
    epochs: int
    dataset_dir: Path
    data_yaml: Path
    output_dir: Path


@dataclass(frozen=True)
class EpochProgress:
    """One epoch's reported metrics (mapped to ``TrainingProgress`` fields)."""

    epoch: int
    total_epochs: int
    loss: float
    map50: float


class DatasetNotFoundError(Exception):
    """The requested dataset is missing/malformed on the training host.

    A *normal, reported* outcome (``StartTraining`` turns it into a terminal
    ``TrainingProgress{state:FAILED}`` and ends the stream), never a gRPC
    abort -- a dataset that hasn't been rsync'd in yet is expected, not an
    error condition of the service.
    """


class TrainingCancelled(Exception):
    """Raised out of a ``train_fn`` when the caller cancelled mid-run."""


# Signature of the injectable trainer. Default: `ultralytics_train`; tests
# inject a fake that reports epochs + honours `is_cancelled` without touching
# ultralytics. Returns the path to the produced `best.pt`; raises
# TrainingCancelled if cancelled, or any Exception on failure.
TrainFn = Callable[..., Path]


def resolve_dataset_dir(datasets_root: Path, dataset_id: str) -> Path:
    """Resolve + validate ``<datasets_root>/<dataset_id>/``.

    Returns the dataset directory if it exists and carries the YOLO layout
    (``data.yaml`` + ``images/`` + ``labels/``). Raises
    :class:`DatasetNotFoundError` otherwise -- the caller reports it as a
    terminal ``FAILED`` progress, not a gRPC abort.
    """
    if not dataset_id:
        raise DatasetNotFoundError("dataset id must not be empty")
    dataset_dir = Path(datasets_root) / dataset_id
    if not dataset_dir.is_dir():
        raise DatasetNotFoundError(
            f"dataset {dataset_id!r} isn't on the training host -- export it and copy it in first"
        )
    data_yaml = dataset_dir / DATA_YAML_NAME
    images_dir = dataset_dir / IMAGES_DIRNAME
    labels_dir = dataset_dir / LABELS_DIRNAME
    missing = [
        name
        for name, present in (
            (DATA_YAML_NAME, data_yaml.is_file()),
            (f"{IMAGES_DIRNAME}/", images_dir.is_dir()),
            (f"{LABELS_DIRNAME}/", labels_dir.is_dir()),
        )
        if not present
    ]
    if missing:
        raise DatasetNotFoundError(
            f"dataset {dataset_id!r} on the training host is malformed (missing {', '.join(missing)}) "
            "-- re-export it and copy it in first"
        )
    return dataset_dir


def build_spec(
    *,
    job_id: str,
    base_model: str,
    dataset_id: str,
    epochs: int,
    datasets_root: Path,
    output_dir: Path,
) -> TrainingSpec:
    """Validate the dataset and assemble a :class:`TrainingSpec`.

    Raises :class:`DatasetNotFoundError` (reported as terminal ``FAILED``) if
    the dataset dir is missing/malformed.
    """
    dataset_dir = resolve_dataset_dir(datasets_root, dataset_id)
    effective_epochs = epochs if epochs and epochs > 0 else DEFAULT_EPOCHS
    return TrainingSpec(
        job_id=job_id,
        base_model=base_model or DEFAULT_BASE_MODEL,
        dataset_id=dataset_id,
        epochs=effective_epochs,
        dataset_dir=dataset_dir,
        data_yaml=dataset_dir / DATA_YAML_NAME,
        output_dir=output_dir,
    )


def output_model_id(dataset_id: str, epochs: int) -> str:
    """Filename/id for the produced artifact: ``<dataset_id>-<epochs>e.pt``.

    A clear, deterministic scheme: re-training the same dataset for the same
    epoch count reproduces the same id (overwriting the prior artifact). The
    dataset_id is sanitized to a filesystem-safe basename so a stray path
    separator can never escape the model directory.
    """
    safe = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in dataset_id).strip("._") or "dataset"
    return f"{safe}-{epochs}e.pt"


def _warn_cpu_once(device: Optional[str]) -> None:
    """Log the one-time "training on CPU -- this is slow" note, if applicable.

    Only warns when no CUDA device is actually available (the production
    deploy host is Intel-only / no CUDA). ``torch`` is imported lazily here so
    this module stays importable without the ``cv`` extra.
    """
    global _cpu_warned
    if _cpu_warned:
        return
    on_cpu: bool
    if device and device.lower() not in ("cpu",):
        # An explicit non-CPU device was requested; assume the operator knows
        # a GPU is present, don't emit the CPU warning.
        on_cpu = False
    else:
        try:
            import torch

            on_cpu = not torch.cuda.is_available()
        except Exception:  # noqa: BLE001 - torch missing/broken -> assume CPU
            on_cpu = True
    if on_cpu:
        _cpu_warned = True
        LOGGER.warning(
            "training on CPU -- this is SLOW. This host has no CUDA GPU (it is an "
            "inference appliance). A real multi-epoch fine-tune here can take a very "
            "long time; prefer training on a GPU box and rsync'ing the artifact in."
        )


def ultralytics_train(
    spec: TrainingSpec,
    *,
    on_epoch: Callable[[EpochProgress], None],
    is_cancelled: Callable[[], bool],
) -> Path:
    """Run one real Ultralytics YOLO fine-tune; return the produced ``best.pt``.

    - **Lazy import**: ``ultralytics`` is imported here, not at module scope,
      so the server stays importable without the ``cv`` extra.
    - **Device-agnostic**: ``device`` comes from ``CV_DEVICE`` if set, else
      ``None`` (Ultralytics auto-selects CUDA if present, CPU otherwise) --
      never hard-pinned to CPU. A one-time CPU-slow warning is logged when no
      GPU is available.
    - **Per-epoch progress**: hooks Ultralytics' ``on_fit_epoch_end`` callback
      (fired after each epoch's validation, so ``mAP50`` is populated) and
      calls ``on_epoch`` with the epoch/total/loss/map50.
    - **Cancellation**: the same callback checks ``is_cancelled()`` and sets
      ``trainer.stop = True`` to end training early; if cancellation was
      observed, :class:`TrainingCancelled` is raised after ``.train()``
      returns so the caller can end the stream quietly.
    """
    from ultralytics import YOLO

    device = os.environ.get("CV_DEVICE") or None
    if device is not None:
        device = device.strip() or None
    _warn_cpu_once(device)

    imgsz = _parse_imgsz(os.environ.get("CV_IMGSZ"))

    model = YOLO(spec.base_model)
    cancelled = False

    def _on_fit_epoch_end(trainer: object) -> None:
        nonlocal cancelled
        # Ultralytics fires `on_fit_epoch_end` once per training epoch AND one
        # extra time for the post-training final validation, where the trainer's
        # 0-indexed `epoch` has reached `epochs`. That trailing call re-reports
        # the last epoch's metrics; skip it so callers see exactly one RUNNING
        # update per epoch (never an epoch > total_epochs).
        raw_epoch = _as_int(getattr(trainer, "epoch", 0))
        total = _as_int(getattr(trainer, "epochs", 0))
        if not (total and raw_epoch >= total):
            try:
                on_epoch(_extract_epoch(trainer))
            except Exception:  # noqa: BLE001 - a reporting hiccup must not kill training
                LOGGER.exception("progress reporting failed for job %s (continuing)", spec.job_id)
        if is_cancelled():
            cancelled = True
            # Ultralytics' documented early-stop signal: the trainer loop
            # checks `self.stop` at each epoch boundary and returns.
            try:
                setattr(trainer, "stop", True)
            except Exception:  # noqa: BLE001 - best-effort
                LOGGER.warning("could not signal early-stop to the trainer for job %s", spec.job_id)

    model.add_callback("on_fit_epoch_end", _on_fit_epoch_end)

    LOGGER.info(
        "starting fine-tune job=%s base=%r dataset=%s epochs=%d imgsz=%d device=%s",
        spec.job_id,
        spec.base_model,
        spec.dataset_id,
        spec.epochs,
        imgsz,
        device if device is not None else "auto",
    )

    train_kwargs = {
        "data": str(spec.data_yaml),
        "epochs": spec.epochs,
        "imgsz": imgsz,
        "project": str(spec.output_dir / _RUNS_PROJECT_DIRNAME),
        "name": spec.job_id,
        "exist_ok": True,
        "verbose": False,
    }
    if device is not None:
        train_kwargs["device"] = device

    model.train(**train_kwargs)

    if cancelled or is_cancelled():
        raise TrainingCancelled(f"training job {spec.job_id} cancelled")

    best = _locate_best_weights(model, spec)
    if best is None or not best.is_file():
        raise RuntimeError(
            f"training job {spec.job_id} finished but produced no best.pt weights"
        )
    return best


def _extract_epoch(trainer: object) -> EpochProgress:
    """Best-effort map an Ultralytics trainer's state to :class:`EpochProgress`.

    Every field is read defensively: Ultralytics' internal attribute shapes
    vary across versions, and a metrics-extraction slip must never crash a
    real training run -- a missing value degrades to ``0.0``/``0`` rather than
    raising.
    """
    epoch = _as_int(getattr(trainer, "epoch", 0)) + 1  # trainer.epoch is 0-indexed
    total = _as_int(getattr(trainer, "epochs", 0))
    # `trainer.loss` is the scalar summed training loss (a tensor); prefer it.
    # `trainer.tloss` is a dict of per-component losses in this ultralytics
    # version -- `_as_float` falls back to summing its values if `loss` is absent.
    loss = _as_float(getattr(trainer, "loss", None))
    if loss == 0.0:
        loss = _as_float(getattr(trainer, "tloss", None))
    metrics = getattr(trainer, "metrics", None)
    map50 = 0.0
    if isinstance(metrics, dict):
        for key in ("metrics/mAP50(B)", "metrics/mAP50", "mAP50"):
            if key in metrics:
                map50 = _as_float(metrics[key])
                break
    return EpochProgress(epoch=epoch, total_epochs=total, loss=loss, map50=map50)


def _locate_best_weights(model: object, spec: TrainingSpec) -> Optional[Path]:
    """Find the run's ``best.pt`` -- from the trainer if exposed, else by path."""
    trainer = getattr(model, "trainer", None)
    best = getattr(trainer, "best", None) if trainer is not None else None
    if best:
        return Path(best)
    # Fall back to the conventional run layout.
    return spec.output_dir / _RUNS_PROJECT_DIRNAME / spec.job_id / "weights" / "best.pt"


def _as_int(value: object) -> int:
    try:
        return int(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return 0


def _as_float(value: object) -> float:
    """Coerce a scalar / tensor / loss-dict to a plain float.

    Handles the three shapes an ultralytics loss can take across versions:
    a scalar tensor (``trainer.loss``), a dict of per-component loss tensors
    (``trainer.tloss`` in this version), or a 1-d tensor/array -- summing the
    components in the latter two cases. Any unrecognized shape degrades to
    ``0.0`` rather than raising (a metrics slip must not crash training).
    """
    if value is None:
        return 0.0
    if isinstance(value, dict):
        try:
            return float(sum(float(v) for v in value.values()))
        except Exception:  # noqa: BLE001
            return 0.0
    try:
        return float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        try:
            return float(sum(float(v) for v in value))  # type: ignore[union-attr]
        except Exception:  # noqa: BLE001
            return 0.0


def _parse_imgsz(raw: Optional[str], default: int = 416) -> int:
    """Forgiving CV_IMGSZ parse (mirrors inference._parse_imgsz), local to
    avoid importing inference.py (which needs the ``cv`` extra)."""
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return value if value > 0 else default

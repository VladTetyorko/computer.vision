"""Job-lifecycle state machine for `Geolocation.BuildReferenceIndex` (docs/VISUAL-GEO-PLAN.md
§3.1/§6 Wave 2a). Mirrors `cv_service/training/orchestrator.py`'s queue + daemon-thread +
cancel-event + `is_context_active()` poll shape almost verbatim -- see that module's docstring
for the full "why a background thread" rationale (a multi-thousand-tile encode+calibrate pass can
run for minutes, same as a multi-epoch train, and must stay responsive to client cancellation).

Ties together `cv_service.geo.{pack,index,calibrate,encoder}` into one job: scan a landed
region's tiles, hold out a fraction for self-calibration, encode the rest into a
`cv_service.geo.index.ReferenceIndex`, persist it, then calibrate `accept_similarity`/
`accept_margin` against the (perturbed) holdout. Never imports `cv_pb2`/`grpc` -- yields plain
`BuildEvent`s; `cv_service/grpc/servicers.py` is the sole place that turns those into
`cv_pb2.ReferenceIndexProgress` messages.

Needs `cv2` (tile image decode) + `numpy` -- the `geo` extra, same "extra-gated" discipline as
`cv_service/inference/detector.py` for the `cv` extra. `cv_service/grpc/servicers.py` only
imports this module lazily, inside `BuildReferenceIndex`, never at module scope.
"""

from __future__ import annotations

import dataclasses
import logging
import queue
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, Optional

import cv2
import numpy as np

from spikes.geo.harvested import calibrate, index, pack
from spikes.geo.harvested.encoder import Encoder

LOGGER = logging.getLogger("cv_service.geo.orchestrator")

# `BuildEvent.phase` values this module reports -- must stay a subset of
# `ReferenceIndexProgress.phase`'s doc comment (§3.1):
# "receiving"|"extracting"|"encoding"|"indexing"|"calibrating"|"done". "receiving"/"extracting"
# are reported directly by `GeolocationServicer.BuildReferenceIndex` before this module's job
# even starts -- this module only ever reports the last three.
PHASE_ENCODING = "encoding"
PHASE_INDEXING = "indexing"
PHASE_CALIBRATING = "calibrating"
PHASE_DONE = "done"

# Wave 0's own spike seed (`spikes/geo/run_spike.py`) -- deterministic holdout split +
# perturbation, kept for continuity with the spike's own results, not for any statistical
# significance of the specific value.
_CALIBRATION_SEED = 20260806

# Slice A (§13.3 item 6): descriptors are STORED and SERVED as fp16. Measured before shipping
# (the plan's own gate) across all six real regions on disk (kyiv-maidan, kyiv-pozniaky,
# btn-road-x, vas-road, boryspil-fields, kyiv-nw-forest; 452 leave-one-out queries): max
# |similarity shift| 1.2e-4, max |margin shift| 6.1e-5 -- both ~100x below the calibration
# sweep's 0.01 grain -- and zero top-1 changes. The cast happens BEFORE holdout calibration so
# the persisted thresholds are measured at exactly the precision live queries are served at.
# Pre-Slice-A fp32 regions load and search unchanged (`ReferenceIndex` is dtype-agnostic).
_DESCRIPTOR_STORE_DTYPE = "float16"


class BuildCancelled(Exception):
    """Raised out of `build_region_index` on caller cancel (mirrors
    `cv_service.training.trainer.TrainingCancelled`)."""


@dataclass(frozen=True)
class BuildEvent:
    """One reported step of a reference-index build -- the plain, wire-agnostic counterpart of
    `cv_pb2.ReferenceIndexProgress`. `kind` is `"phase"` (any number, one per progress tick), or
    a terminal `"succeeded"`/`"failed"`. A cancelled job yields no terminal event at all -- same
    contract as `cv_service.training.orchestrator.JobEvent`."""

    kind: str
    phase: str = ""
    done: int = 0
    total: int = 0
    message: str = ""
    stats: Optional["index.ReferenceIndexStats"] = None  # only set for "succeeded"


OnPhase = Callable[[str, int, int, str], None]
IsCancelled = Callable[[], bool]
BuildFn = Callable[..., "index.ReferenceIndexStats"]


def build_region_index(
    region_dir: Path,
    encoder: Encoder,
    *,
    on_phase: OnPhase,
    is_cancelled: IsCancelled,
    holdout_fraction: float = calibrate.DEFAULT_HOLDOUT_FRACTION,
    max_false_fix_rate: float = calibrate.DEFAULT_MAX_FALSE_FIX_RATE,
    seed: int = _CALIBRATION_SEED,
) -> "index.ReferenceIndexStats":
    """The actual (potentially slow) work `run_build_job` runs on a background thread: scan
    `region_dir/tiles/*.jpg`, hold out `holdout_fraction` of them, encode the rest into a
    `ReferenceIndex`, persist it, then self-calibrate against the (perturbed) holdout and persist
    `index.json`. Raises `BuildCancelled` if `is_cancelled()` flips true mid-run; any other
    exception is a normal reported build failure (the caller, `run_build_job`, wraps it).
    """
    tiles_dir = region_dir / pack.TILES_DIRNAME
    parsed: list[tuple[Path, str, float, float]] = []
    for tile_path in sorted(tiles_dir.glob("*.jpg")):
        zxy = index.parse_tile_filename(tile_path.name)
        if zxy is None:
            continue
        zoom, x, y = zxy
        lat, lon = index.tile_center(x, y, zoom)
        parsed.append((tile_path, f"{zoom}/{x}/{y}", lat, lon))

    if not parsed:
        raise ValueError(f"region {region_dir.name!r} has no recognizable tiles to index")

    rng = np.random.default_rng(seed)
    order = rng.permutation(len(parsed))
    if len(parsed) > 1:
        holdout_count = max(1, min(int(len(parsed) * holdout_fraction), len(parsed) - 1))
    else:
        holdout_count = 0
    holdout_positions = set(order[:holdout_count].tolist())

    reference_items = [parsed[i] for i in range(len(parsed)) if i not in holdout_positions]
    holdout_items = [parsed[i] for i in range(len(parsed)) if i in holdout_positions]
    if not reference_items:
        # Degenerate tiny region (e.g. exactly 1 tile) -- index everything, calibrate against the
        # same set rather than crash; an honestly-small holdout signal beats no index at all.
        reference_items = parsed
        holdout_items = parsed

    tiles_meta: list["index.ReferenceTileMeta"] = []
    descriptors = np.zeros((len(reference_items), encoder.dim), dtype=np.float32)
    verify_grays: list[np.ndarray] = []
    verify_scales: list[float] = []
    encoded_count = 0
    for position, (tile_path, tile_id, lat, lon) in enumerate(reference_items):
        if is_cancelled():
            raise BuildCancelled()
        image = cv2.imread(str(tile_path), cv2.IMREAD_COLOR)
        if image is None:
            LOGGER.warning("skipping unreadable reference tile %s", tile_path)
            continue
        descriptors[encoded_count] = encoder.encode(image)
        tiles_meta.append(index.ReferenceTileMeta(tile_id=tile_id, lat=lat, lon=lon))
        # Slice A (§13.3 item 2): the tile is decoded right here anyway -- cache its
        # verify-ready gray form so query-time LoFTR verification never re-decodes it.
        gray, gray_scale = index.prep_verify_gray(image)
        verify_grays.append(gray)
        verify_scales.append(gray_scale)
        encoded_count += 1
        if position == 0 or (position + 1) % 50 == 0 or position == len(reference_items) - 1:
            on_phase(PHASE_ENCODING, position + 1, len(reference_items), "")

    # Slice A (§13.3 item 1): per-tile leave-one-out distinctiveness, computed on the fp32
    # descriptors (full precision in, fp16 only for storage/serving below), persisted per tile
    # row -- see `calibrate.leave_one_out_distinctiveness`'s docstring for the definition.
    distinctiveness = calibrate.leave_one_out_distinctiveness(descriptors[:encoded_count])
    tiles_meta = [
        dataclasses.replace(meta, distinctiveness=float(score))
        for meta, score in zip(tiles_meta, distinctiveness)
    ]

    reference_index = index.ReferenceIndex(
        tiles_meta, descriptors[:encoded_count].astype(_DESCRIPTOR_STORE_DTYPE)
    )
    on_phase(PHASE_INDEXING, len(tiles_meta), len(tiles_meta), "")

    holdout_results: list["calibrate.HoldoutResult"] = []
    for position, (tile_path, tile_id, lat, lon) in enumerate(holdout_items):
        if is_cancelled():
            raise BuildCancelled()
        image = cv2.imread(str(tile_path), cv2.IMREAD_COLOR)
        if image is None:
            continue
        perturbed = calibrate.simulate_view_perturbation(image, rng)
        query_descriptor = encoder.encode(perturbed)
        matches, _search_ms = reference_index.search(query_descriptor, top_k=2)
        if not matches:
            continue
        top = matches[0]
        margin = top.similarity - matches[1].similarity if len(matches) > 1 else 0.0
        error_meters = index.haversine_m(lat, lon, top.tile.lat, top.tile.lon)
        holdout_results.append(
            calibrate.HoldoutResult(
                tile_id=tile_id, top_similarity=top.similarity, margin=margin, error_meters=error_meters
            )
        )
        if position == 0 or (position + 1) % 20 == 0 or position == len(holdout_items) - 1:
            on_phase(PHASE_CALIBRATING, position + 1, len(holdout_items), "")

    calibration = calibrate.calibrate(holdout_results, max_false_fix_rate=max_false_fix_rate)

    reference_index.save(region_dir)
    cache_bytes = index.write_verify_tiles(
        region_dir, [m.tile_id for m in tiles_meta], verify_grays, verify_scales
    )
    LOGGER.info(
        "region %s: verify-tile cache written (%d tiles, %.1f KiB)",
        region_dir.name,
        len(tiles_meta),
        cache_bytes / 1024.0,
    )
    descriptor_bytes = (region_dir / index.DESCRIPTORS_FILENAME).stat().st_size
    tiles_json_bytes = (region_dir / index.TILES_JSON_FILENAME).stat().st_size

    stats = index.ReferenceIndexStats(
        tile_count=len(parsed),
        descriptor_count=len(tiles_meta),
        descriptor_dim=encoder.dim,
        encoder_id=encoder.name,
        accept_similarity=calibration.accept_similarity,
        accept_margin=calibration.accept_margin,
        holdout_recall_at_1=calibration.holdout_recall_at_1,
        holdout_median_error_meters=calibration.holdout_median_error_meters,
        index_bytes=descriptor_bytes + tiles_json_bytes,
    )
    index.write_index_json(region_dir, stats)
    on_phase(PHASE_DONE, 1, 1, "")
    return stats


def run_build_job(
    region_dir: Path,
    encoder: Encoder,
    *,
    region_id: str,
    build_fn: BuildFn = build_region_index,
    is_context_active: IsCancelled,
    register_cancel_callback: Callable[[Callable[[], None]], None],
    holdout_fraction: float = calibrate.DEFAULT_HOLDOUT_FRACTION,
    max_false_fix_rate: float = calibrate.DEFAULT_MAX_FALSE_FIX_RATE,
) -> Iterator[BuildEvent]:
    """Run `build_fn` on a background worker thread; yield one `BuildEvent(kind="phase")` per
    progress tick then exactly one terminal event (`"succeeded"`/`"failed"`), or none at all if
    cancelled -- mirrors `cv_service.training.orchestrator.run_training_job` almost verbatim.

    - The blocking encode/index/calibrate work runs entirely on a background thread (`worker`,
      below) -- this generator only polls a `queue.Queue` for progress + yields, so it stays
      responsive to caller cancellation.
    - `is_context_active()` is polled every 0.5s; `register_cancel_callback` is invoked once up
      front so an immediate/async cancellation signal (e.g. a gRPC client disconnect) also flips
      the same cancel flag, belt-and-braces with the polling.
    """
    events: "queue.Queue[tuple[str, object]]" = queue.Queue()
    cancel_event = threading.Event()

    def on_phase(phase: str, done: int, total: int, message: str = "") -> None:
        events.put(("phase", BuildEvent(kind="phase", phase=phase, done=done, total=total, message=message)))

    def worker() -> None:
        try:
            stats = build_fn(
                region_dir,
                encoder,
                on_phase=on_phase,
                is_cancelled=cancel_event.is_set,
                holdout_fraction=holdout_fraction,
                max_false_fix_rate=max_false_fix_rate,
            )
            events.put(("done", stats))
        except BuildCancelled:
            events.put(("cancelled", None))
        except Exception as exc:  # noqa: BLE001 - reported to the caller as "failed"
            LOGGER.exception("reference index build region_id=%s failed", region_id)
            events.put(("error", exc))

    thread = threading.Thread(target=worker, name=f"cv-geo-build-{region_id[:8]}", daemon=True)
    thread.start()

    register_cancel_callback(cancel_event.set)

    try:
        while True:
            if not is_context_active():
                cancel_event.set()
                return
            try:
                kind, payload = events.get(timeout=0.5)
            except queue.Empty:
                continue

            if kind == "phase":
                yield payload
            elif kind == "done":
                yield BuildEvent(kind="succeeded", phase=PHASE_DONE, stats=payload)
                return
            elif kind == "cancelled":
                LOGGER.info("reference index build region_id=%s cancelled", region_id)
                return
            elif kind == "error":
                yield BuildEvent(kind="failed", message=f"reference index build failed: {payload}")
                return
    finally:
        cancel_event.set()
        thread.join(timeout=5)

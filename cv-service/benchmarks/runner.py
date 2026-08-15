"""CLI: `python -m benchmarks.runner --sequence 09 --detector FRCNN --engine cost --level 1 --oru off`.

`docs/conclusions/TRACKING-BENCHMARKS.md` §5 (the survey this package
implements), `tools/trackeval/__main__.py` (the pattern this CLI mirrors:
thin argument parsing and output only -- `mot17.py` loads the data,
`driver.py` runs the real session, `metrics.py` scores it; nothing here
reimplements any of the three).

One invocation is one composition: one (scene, detector) sequence, one
engine, one capability level, one ORU setting -- one output row. Building a
whole matrix means invoking this once per cell and appending
(`docs/conclusions/TRACKING-BENCHMARKS.md`'s brief scoped exactly this: "do
not run the full composition matrix -- I will do that").

Run from `cv-service/` with the source tree on `PYTHONPATH`::

    PYTHONPATH="$PWD" .venv/bin/python -m benchmarks.runner \\
        --sequence 09 --detector FRCNN --engine cost --level 1 --oru off \\
        --output benchmarks/results.csv
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from benchmarks import mot17, thresholds
from benchmarks.driver import CompositionConfig, run_mot17_replay
from tools.trackeval import metrics as metrics_module

_DEFAULT_DATA_DIR = Path(__file__).resolve().parent / "data" / "mot17"

# One explicit, ordered column list -- same "documented, not incidental"
# convention `tools/trackeval/metrics.py`'s own `_COLUMN_HEADERS` uses.
# Every axis TRACKING-BENCHMARKS.md §5 asked this runner to report, then
# every `Metrics` field (imported, never re-derived), then this run's own
# throughput/retention numbers.
_ROW_COLUMNS: tuple[str, ...] = (
    "sequence",
    "scene",
    "detector",
    "gt_source",
    "engine_requested",
    "engine_served",
    "capability_level_requested",
    "capability_level_served",
    "capability_level_reason",
    "oru_enabled",
    "conf_threshold",
    "frames",
    "gt_object_count",
    "dets_retained_total",
    "dets_per_frame",
    "below_threshold_dropped",
    "ignore_region_dropped",
    "idsw",
    "fragmentations",
    "mostly_tracked",
    "partially_tracked",
    "mostly_lost",
    "gap_count",
    "recovered_count",
    "recovery_rate",
    "mean_track_lifetime_frames",
    "median_track_lifetime_frames",
    "detector_passes",
    "detector_passes_per_sec",
    "mean_tracker_millis",
    "p95_tracker_millis",
    "mean_process_wall_millis",
    "p95_process_wall_millis",
    "association_fps",
    "coast_sample_count",
    "coast_ade_norm",
    "coast_fde_norm",
    "coast_ade_px",
    "coast_fde_px",
    "implausible_velocity_count",
)


def _parse_args(argv: "list[str] | None" = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Real-footage (MOT17) benchmark for cv-service's ASSOCIATE tracking engines "
            "(cost/bytetrack). ASSOCIATE only -- FOLLOW, flow GMC, appearance evidence and "
            "detector throughput are all pixel-dependent and this dataset has no images; "
            "see benchmarks/__init__.py."
        )
    )
    parser.add_argument("--sequence", required=True, choices=mot17.SCENES, help="MOT17 scene number, e.g. 09")
    parser.add_argument("--detector", required=True, choices=mot17.DETECTORS, help="det.txt variant")
    parser.add_argument("--engine", required=True, choices=("cost", "bytetrack"), help="ASSOCIATE engine")
    parser.add_argument("--level", required=True, type=int, choices=(1, 2, 3, 4, 5), help="capability ceiling L1-L5")
    parser.add_argument(
        "--oru",
        required=True,
        choices=("on", "off"),
        help="post-occlusion re-update; off == CV_TRACK_REUPDATE_MAX_GAP_MILLIS=0",
    )
    parser.add_argument(
        "--conf-threshold",
        type=float,
        default=None,
        help="override this detector's documented default confidence threshold (see thresholds.py)",
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=_DEFAULT_DATA_DIR,
        help=f"MOT17 data root (default: {_DEFAULT_DATA_DIR})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="append this composition's row here (CSV or JSON Lines, see --format); "
        "omit to print the row to stdout instead",
    )
    parser.add_argument("--format", choices=("csv", "json"), default="csv", help="output row format (default csv)")
    return parser.parse_args(argv)


def _build_row(args: argparse.Namespace) -> "dict[str, Any]":
    sequence = mot17.load_sequence(args.data_dir, args.sequence, args.detector)
    threshold = args.conf_threshold if args.conf_threshold is not None else thresholds.default_threshold(args.detector)
    config = CompositionConfig(
        engine_id=args.engine,
        capability_level=args.level,
        reupdate_enabled=(args.oru == "on"),
        detection_threshold=threshold,
    )
    result, stats = run_mot17_replay(sequence, config)
    scored = metrics_module.compute(result)

    if result.engine_id != config.engine_id:
        print(
            f"warning: requested engine {config.engine_id!r} but this composition served "
            f"{result.engine_id!r} -- capability level {args.level} may not afford it "
            f"(bytetrack needs L3+; see cv_service/tracking/registry.py's "
            f"_ASSOCIATOR_MIN_LEVEL). Row reports the SERVED engine as ground truth.",
            file=sys.stderr,
        )

    metrics_fields = asdict(scored)
    row: "dict[str, Any]" = {
        "sequence": sequence.name,
        "scene": sequence.scene,
        "detector": sequence.detector,
        "gt_source": sequence.gt_source,
        "engine_requested": config.engine_id,
        "engine_served": result.engine_id,
        "capability_level_requested": stats.capability_level_requested,
        "capability_level_served": stats.capability_level_served,
        "capability_level_reason": stats.capability_level_reason,
        "oru_enabled": config.reupdate_enabled,
        "conf_threshold": threshold,
        "frames": metrics_fields["total_frames"],
        "gt_object_count": metrics_fields["gt_object_count"],
        "dets_retained_total": stats.dets_retained_total,
        "dets_per_frame": stats.dets_per_frame,
        "below_threshold_dropped": stats.below_threshold_dropped,
        "ignore_region_dropped": stats.ignore_region_dropped,
        "idsw": metrics_fields["idsw"],
        "fragmentations": metrics_fields["fragmentations"],
        "mostly_tracked": metrics_fields["mostly_tracked"],
        "partially_tracked": metrics_fields["partially_tracked"],
        "mostly_lost": metrics_fields["mostly_lost"],
        "gap_count": metrics_fields["gap_count"],
        "recovered_count": metrics_fields["recovered_count"],
        "recovery_rate": metrics_fields["recovery_rate"],
        "mean_track_lifetime_frames": metrics_fields["mean_track_lifetime_frames"],
        "median_track_lifetime_frames": metrics_fields["median_track_lifetime_frames"],
        "detector_passes": metrics_fields["detector_passes"],
        "detector_passes_per_sec": metrics_fields["detector_passes_per_sec"],
        "mean_tracker_millis": metrics_fields["mean_tracker_millis"],
        "p95_tracker_millis": metrics_fields["p95_tracker_millis"],
        "mean_process_wall_millis": stats.mean_process_wall_millis,
        "p95_process_wall_millis": stats.p95_process_wall_millis,
        "association_fps": stats.association_fps,
        "coast_sample_count": metrics_fields["coast_sample_count"],
        "coast_ade_norm": metrics_fields["coast_ade_norm"],
        "coast_fde_norm": metrics_fields["coast_fde_norm"],
        "coast_ade_px": metrics_fields["coast_ade_px"],
        "coast_fde_px": metrics_fields["coast_fde_px"],
        "implausible_velocity_count": metrics_fields["implausible_velocity_count"],
    }
    assert set(row) == set(_ROW_COLUMNS), (
        f"_build_row's keys drifted from _ROW_COLUMNS: "
        f"missing={set(_ROW_COLUMNS) - set(row)} extra={set(row) - set(_ROW_COLUMNS)}"
    )
    return row


def _write_csv(row: "dict[str, Any]", path: "Path | None") -> None:
    if path is None:
        writer = csv.DictWriter(sys.stdout, fieldnames=_ROW_COLUMNS)
        writer.writeheader()
        writer.writerow(row)
        return
    write_header = not path.exists() or path.stat().st_size == 0
    with path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=_ROW_COLUMNS)
        if write_header:
            writer.writeheader()
        writer.writerow(row)


def _write_json(row: "dict[str, Any]", path: "Path | None") -> None:
    # JSON Lines (one compact object per line): the JSON counterpart of
    # CSV's "append a row" semantics above -- a single JSON array would
    # require a read-modify-write on every invocation, silently unsafe if
    # two matrix cells ever run concurrently, which JSON Lines' append-only
    # write never is.
    line = json.dumps(row, sort_keys=False)
    if path is None:
        print(line)
        return
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def main(argv: "list[str] | None" = None) -> int:
    args = _parse_args(argv)
    row = _build_row(args)
    if args.format == "csv":
        _write_csv(row, args.output)
    else:
        _write_json(row, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""M9 spike -- OpenVINO IR inference cost on a target box, same YoloDetector class and same
percentile methodology as cv-service/spikes/pull/decode_bench.py (M0), so the numbers are directly
comparable to M0's already-published PyTorch-CPU figures for the same box.

M0 measured GB4005 PyTorch CPU inference (yolo26n.pt) but only CITED DEPLOY-GPU.md's OpenVINO IR
figure (135-150 ms/frame) rather than reproducing it. This script reproduces it: exports
yolo26n.pt -> OpenVINO IR at the same imgsz DEPLOY-GPU.md documents (416, matching
cv_service.config.DEFAULT_IMGSZ), then times YoloDetector.detect() over synthetic frames -- no RTSP
source needed, since decode cost is already measured separately (M0 §2) and is not what this script
is for.

Usage (run from an isolated tree with the current cv_service/ on PYTHONPATH, exactly as M0 did):
  python openvino_bench.py --model yolo26n_openvino_model --width 1280 --height 720 --label 720p --out out.json
"""
from __future__ import annotations

import argparse
import json
import statistics
import time

import numpy as np

try:
    import psutil
except ImportError:
    psutil = None


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    return float(np.percentile(values, pct))


def synthetic_frame(width: int, height: int) -> np.ndarray:
    """A colour-bar-like static pattern (not blank, not noise) -- cheap to build, and unlikely to
    spuriously trigger a pathological number of detections/NMS candidates the way random noise
    could, matching the spirit of M0's moving-testsrc choice (measure a realistic decode target,
    not an artificially easy/hard one) without needing ffmpeg on this box for a decode-only run."""
    frame = np.zeros((height, width, 3), dtype=np.uint8)
    bands = 8
    band_w = width // bands
    colors = [
        (255, 255, 255), (0, 255, 255), (255, 255, 0), (0, 255, 0),
        (255, 0, 255), (0, 0, 255), (255, 0, 0), (0, 0, 0),
    ]
    for i, color in enumerate(colors):
        frame[:, i * band_w:(i + 1) * band_w] = color
    return frame


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="e.g. yolo26n_openvino_model or yolo26n.pt")
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--label", required=True)
    parser.add_argument("--frames", type=int, default=120)
    parser.add_argument("--warmup-frames", type=int, default=10)
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    from cv_service.inference.detector import ENCODING_BGR24, YoloDetector

    detector = YoloDetector(model_name=args.model)
    frame = synthetic_frame(args.width, args.height)
    data = frame.tobytes()

    proc = psutil.Process() if psutil else None
    if proc:
        proc.cpu_percent(interval=None)

    for _ in range(args.warmup_frames):
        detector.detect(width=args.width, height=args.height, encoding=ENCODING_BGR24, data=data)

    inference_ms: list[float] = []
    t_start = time.perf_counter()
    for _ in range(args.frames):
        _detections, inf_ms = detector.detect(
            width=args.width, height=args.height, encoding=ENCODING_BGR24, data=data
        )
        inference_ms.append(float(inf_ms))
    t_end = time.perf_counter()

    cpu_percent = proc.cpu_percent(interval=None) if proc else None
    rss_mb = round(proc.memory_info().rss / (1024 * 1024), 1) if proc else None

    result = {
        "model": args.model,
        "label": args.label,
        "resolution": f"{args.width}x{args.height}",
        "frames": args.frames,
        "wall_seconds": round(t_end - t_start, 2),
        "inference_ms": {
            "p50": round(percentile(inference_ms, 50), 3),
            "p95": round(percentile(inference_ms, 95), 3),
            "max": round(max(inference_ms), 3),
            "mean": round(statistics.mean(inference_ms), 3),
            "n": len(inference_ms),
        },
        "implied_fps_ceiling": round(1000.0 / percentile(inference_ms, 50), 2),
        "cpu_percent_avg_over_run": cpu_percent,
        "rss_mb_final": rss_mb,
    }
    print(json.dumps(result, indent=2))
    if args.out:
        with open(args.out, "w") as f:
            json.dump(result, f, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

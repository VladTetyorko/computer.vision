#!/usr/bin/env python3
"""M0 spike -- achieved fps against a requested target, using the ported DeadlineSampler
(sampler.py) directly against a live pulled RTSP source. No inference in this script --
isolates the sampler's own behaviour from detector cost (rate_stall_bench.py covers the
interaction with a slow/stalled detector separately).

Usage:
  cv-service/.venv/bin/python rate_achieved_bench.py --url rtsp://localhost:18554/push720 \
      --target-fps 10.0 --duration 30 --out results/rate_achieved.json
"""
from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import cv2

from sampler import DeadlineSampler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--target-fps", type=float, default=10.0)
    parser.add_argument("--duration", type=float, default=30.0, help="seconds")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    cap = cv2.VideoCapture(args.url, cv2.CAP_FFMPEG)
    if not cap.isOpened():
        raise SystemExit(f"could not open {args.url!r}")

    sampler = DeadlineSampler(args.target_fps)
    frames_seen = 0
    frames_sampled = 0
    sample_wall_times: list[float] = []

    t_start_wall = time.time()
    t_start = time.perf_counter()
    while time.perf_counter() - t_start < args.duration:
        ok, _frame = cap.read()
        if not ok:
            break
        frames_seen += 1
        # ONE clock read per frame, threaded through -- see sampler.py's docstring.
        now_ns = time.monotonic_ns()
        if sampler.sample_due(now_ns):
            frames_sampled += 1
            sample_wall_times.append(time.time())
    t_end = time.perf_counter()
    cap.release()

    elapsed = t_end - t_start
    achieved_fps = frames_sampled / elapsed if elapsed > 0 else 0.0
    source_fps = frames_seen / elapsed if elapsed > 0 else 0.0

    # Inter-sample interval stats -- how close to the requested 100ms cadence (@10fps).
    intervals_ms = [
        (sample_wall_times[i] - sample_wall_times[i - 1]) * 1000.0
        for i in range(1, len(sample_wall_times))
    ]

    result = {
        "url": args.url,
        "target_fps": args.target_fps,
        "duration_s": round(elapsed, 2),
        "source_frames_seen": frames_seen,
        "source_fps_measured": round(source_fps, 2),
        "frames_sampled": frames_sampled,
        "achieved_fps": round(achieved_fps, 3),
        "missed_deadlines": sampler.missed_deadlines,
        "sample_interval_ms": {
            "p50": round(sorted(intervals_ms)[len(intervals_ms) // 2], 2) if intervals_ms else None,
            "min": round(min(intervals_ms), 2) if intervals_ms else None,
            "max": round(max(intervals_ms), 2) if intervals_ms else None,
        },
    }
    text = json.dumps(result, indent=2)
    print(text)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
